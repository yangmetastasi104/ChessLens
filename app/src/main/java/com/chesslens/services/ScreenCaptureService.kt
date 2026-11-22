package com.chesslens.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chesslens.chess.ChessPosition
import com.chesslens.chess.ChessMove
import com.chesslens.chess.ChessRules
import com.chesslens.engine.EngineUnavailableException
import com.chesslens.engine.SearchLimit
import com.chesslens.engine.StockfishManager
import com.chesslens.overlay.OverlayService
import com.chesslens.tracking.TrackingResult
import com.chesslens.vision.FrameAnalysisPipeline
import com.chesslens.vision.RgbFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Consent-gated MediaProjection capture. Frames are downsampled in memory and
 * processed on a HandlerThread; no image is written to storage or uploaded.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var pipeline: FrameAnalysisPipeline? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: StockfishManager? = null
    private var analysisJob: Job? = null
    private var lastAnalyzedFen: String? = null
    private var latestBoard: com.chesslens.vision.BoardGeometry? = null
    private var latestWhiteAtBottom = true
    private var lastFrameAt = 0L
    private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(
            "Screen capture is running for your training session", ongoing = true
        ))
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        if (!intent.getBooleanExtra(EXTRA_TRAINING_CONFIRMED, false) ||
            !intent.getBooleanExtra(EXTRA_POSITION_CONFIRMED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        releaseCapture()
        val fen = intent.getStringExtra(EXTRA_INITIAL_FEN) ?: run { stopSelf(); return START_NOT_STICKY }
        val whiteAtBottom = intent.getBooleanExtra(EXTRA_WHITE_AT_BOTTOM, true)
        latestWhiteAtBottom = whiteAtBottom
        pipeline = try {
            FrameAnalysisPipeline(ChessPosition.fromFen(fen), whiteAtBottom)
        } catch (_: IllegalArgumentException) {
            stopSelf()
            return START_NOT_STICKY
        }
        engine?.close()
        engine = StockfishManager.bundled(this)
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, workerHandler)
        startDisplay()
        return START_NOT_STICKY
    }

    private fun startDisplay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val thread = HandlerThread("ChessLens-frame-analysis").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            image.use { analyzeIfDue(it) }
        }, handler)
        display = projection?.createVirtualDisplay(
            "ChessLens training capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )
    }

    private fun analyzeIfDue(image: Image) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) return
        lastFrameAt = now
        val frame = image.toRgbFrame() ?: return
        val activePipeline = pipeline ?: return
        val result = activePipeline.process(frame)
        latestBoard = result.board ?: latestBoard
        if (result.tracking is TrackingResult.MoveAccepted || result.tracking == TrackingResult.Unchanged) {
            activePipeline.currentPosition.toFen()?.let { fen ->
                if (fen != lastAnalyzedFen) startAnalysis(fen, result.message)
            }
        }
        if (result.tracking == TrackingResult.RecognitionMismatch || result.board == null) {
            analysisJob?.cancel()
            lastAnalyzedFen = null
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CLEAR
                putExtra(OverlayService.EXTRA_TEXT, result.message)
            })
        }
        if (result.message.startsWith("Move detected:") || result.message.startsWith("Chessboard") ||
            result.message.startsWith("Uncertain"))
            updateNotification(result.message)
    }

    private fun startAnalysis(fen: String, reason: String) {
        lastAnalyzedFen = fen
        analysisJob?.cancel()
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_CLEAR
            putExtra(OverlayService.EXTRA_TEXT, "Analyzing current position…")
        })
        val manager = engine ?: return
        analysisJob = serviceScope.launch {
            try {
                val result = manager.analyze(fen, SearchLimit(depth = 18)) { info ->
                    val eval = info.mateIn?.let { "M$it" }
                        ?: info.evaluationPawns?.let { "%+.2f".format(java.util.Locale.US, it) }
                        ?: "—"
                    updateNotification("Depth ${info.depth} • $eval")
                }
                val uci = result.bestMove ?: run {
                    updateNotification("No legal engine move")
                    return@launch
                }
                val currentPosition = pipeline?.currentPosition ?: return@launch
                if (currentPosition.toFen() != fen || lastAnalyzedFen != fen) return@launch
                if (uci.length !in 4..5) {
                    updateNotification("Engine returned an invalid move")
                    return@launch
                }
                val move = runCatching {
                    ChessMove(
                        ChessRules.parseSquare(uci.substring(0, 2)),
                        ChessRules.parseSquare(uci.substring(2, 4)),
                        uci.getOrNull(4)?.let {
                            if (currentPosition.whiteToMove == true) it.uppercaseChar() else it.lowercaseChar()
                        }
                    )
                }.getOrNull()
                if (move == null || move !in ChessRules.legalMoves(currentPosition)) {
                    updateNotification("Engine move failed legal-move validation")
                    return@launch
                }
                val board = latestBoard ?: return@launch
                startService(Intent(this@ScreenCaptureService, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SUGGESTION
                    putExtra(OverlayService.EXTRA_FROM, ChessRules.squareName(move.from))
                    putExtra(OverlayService.EXTRA_TO, ChessRules.squareName(move.to))
                    putExtra(OverlayService.EXTRA_LEFT, board.left)
                    putExtra(OverlayService.EXTRA_TOP, board.top)
                    putExtra(OverlayService.EXTRA_RIGHT, board.right)
                    putExtra(OverlayService.EXTRA_BOTTOM, board.bottom)
                    putExtra(OverlayService.EXTRA_WHITE_AT_BOTTOM, latestWhiteAtBottom)
                    putExtra(OverlayService.EXTRA_TEXT,
                        "${ChessRules.squareName(move.from)} → ${ChessRules.squareName(move.to)}")
                })
                updateNotification("$reason • best move ${uci.take(4)}")
            } catch (_: CancellationException) {
                // Position changed; canceled search must not publish stale advice.
            } catch (e: EngineUnavailableException) {
                updateNotification("Stockfish unavailable; no move suggested")
            } catch (e: Exception) {
                updateNotification("Engine analysis failed")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL, "ChessLens capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(message: String, ongoing: Boolean = false): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ChessLens • training")
            .setContentText(message.take(100))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .build()

    private fun updateNotification(message: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun Image.toRgbFrame(): RgbFrame? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val scale = minOf(1.0, 320.0 / width, 640.0 / height)
        val outWidth = (width * scale).toInt().coerceAtLeast(1)
        val outHeight = (height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(outWidth * outHeight)
        val base = buffer.position()
        for (y in 0 until outHeight) for (x in 0 until outWidth) {
            val sourceX = x * width / outWidth
            val sourceY = y * height / outHeight
            val offset = base + sourceY * rowStride + sourceX * pixelStride
            if (offset + 3 >= buffer.limit()) continue
            val r = buffer.get(offset).toInt() and 255
            val g = buffer.get(offset + 1).toInt() and 255
            val b = buffer.get(offset + 2).toInt() and 255
            val a = buffer.get(offset + 3).toInt() and 255
            pixels[y * outWidth + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RgbFrame(outWidth, outHeight, pixels)
    }

    private fun releaseCapture() {
        display?.release(); display = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close(); imageReader = null
        worker?.quitSafely(); worker = null; workerHandler = null
        if (!shuttingDown) { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        shuttingDown = true
        analysisJob?.cancel()
        serviceScope.cancel()
        engine?.close()
        engine = null
        releaseCapture()
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "permission_data"
        const val EXTRA_INITIAL_FEN = "initial_fen"
        const val EXTRA_WHITE_AT_BOTTOM = "white_at_bottom"
        const val EXTRA_TRAINING_CONFIRMED = "training_confirmed"
        const val EXTRA_POSITION_CONFIRMED = "position_confirmed"
        const val ACTION_FRAME_ANALYSIS = "com.chesslens.FRAME_ANALYSIS"
        const val EXTRA_MOVE = "move"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LEFT = "left"
        const val EXTRA_TOP = "top"
        const val EXTRA_RIGHT = "right"
        const val EXTRA_BOTTOM = "bottom"
        private const val FRAME_INTERVAL_MS = 400L
        private const val CHANNEL = "chesslens_capture"
        private const val NOTIFICATION_ID = 1
    }
}
