package com.chesslens.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Transparent overlay renderer. It is intentionally not launched with suggestions
 * until board geometry and a training-validated legal move are supplied. */
class OverlayService : Service() {
    private var view: MoveOverlayView? = null
    private var panel: MovePanelView? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) return START_NOT_STICKY
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenSize(wm)
        if (view == null) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            view = MoveOverlayView(this)
            wm.addView(view, params)

            val minWidth = dp(MIN_PANEL_WIDTH_DP)
            val minHeight = dp(MIN_PANEL_HEIGHT_DP)
            val maxWidth = (screenWidth - dp(8)).coerceAtLeast(minWidth)
            val maxHeight = (screenHeight - dp(8)).coerceAtLeast(minHeight)
            val width = prefs.getInt(KEY_WIDTH, dp(DEFAULT_PANEL_WIDTH_DP)).coerceIn(minWidth, maxWidth)
            val height = prefs.getInt(KEY_HEIGHT, dp(DEFAULT_PANEL_HEIGHT_DP)).coerceIn(minHeight, maxHeight)
            val x = prefs.getInt(KEY_X, dp(12)).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            val y = prefs.getInt(KEY_Y, dp(96)).coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            panelParams = WindowManager.LayoutParams(
                width, height, overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            panel = MovePanelView(
                this,
                onMove = { newX, newY -> movePanel(wm, newX, newY) },
                onResize = { newWidth, newHeight -> resizePanel(wm, newWidth, newHeight) },
                onGestureEnd = { persistPanelGeometry() }
            )
            wm.addView(panel, panelParams)
        }
        when (intent?.action) {
            ACTION_SUGGESTION -> {
                val from = intent.getStringExtra(EXTRA_FROM)
                val to = intent.getStringExtra(EXTRA_TO)
                val left = intent.getFloatExtra(EXTRA_LEFT, Float.NaN)
                val top = intent.getFloatExtra(EXTRA_TOP, Float.NaN)
                val right = intent.getFloatExtra(EXTRA_RIGHT, Float.NaN)
                val bottom = intent.getFloatExtra(EXTRA_BOTTOM, Float.NaN)
                if (from != null && to != null && left.isFinite() && top.isFinite() && right > left && bottom > top) {
                    view?.setMove(
                        from, to, BoardRect(left, top, right, bottom),
                        intent.getBooleanExtra(EXTRA_WHITE_AT_BOTTOM, true)
                    )
                    panel?.setSuggestion(intent.getStringExtra(EXTRA_TEXT) ?: "$from → $to")
                    panel?.setStatus(
                        intent.getStringExtra(EXTRA_STATUS)
                            ?: "Drag the panel to move it. Drag the bottom-right corner to resize."
                    )
                }
            }
            ACTION_HIDE -> stopSelf()
            ACTION_CLEAR -> {
                view?.clear()
                panel?.setSuggestion(intent.getStringExtra(EXTRA_TEXT) ?: "Waiting for a stable position")
                panel?.setStatus(
                    intent.getStringExtra(EXTRA_STATUS)
                        ?: "Drag the panel to move it. Drag the bottom-right corner to resize."
                )
            }
            ACTION_STATUS -> panel?.setStatus(intent.getStringExtra(EXTRA_TEXT) ?: "")
        }
        return START_NOT_STICKY
    }

    private fun updateScreenSize(wm: WindowManager) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun movePanel(wm: WindowManager, x: Int, y: Int) {
        if (!::panelParams.isInitialized) return
        panelParams.x = x.coerceIn(0, (screenWidth - panelParams.width).coerceAtLeast(0))
        panelParams.y = y.coerceIn(0, (screenHeight - panelParams.height).coerceAtLeast(0))
        panel?.let { runCatching { wm.updateViewLayout(it, panelParams) } }
    }

    private fun resizePanel(wm: WindowManager, width: Int, height: Int) {
        if (!::panelParams.isInitialized) return
        val minWidth = dp(MIN_PANEL_WIDTH_DP)
        val minHeight = dp(MIN_PANEL_HEIGHT_DP)
        val maxWidth = (screenWidth - dp(8)).coerceAtLeast(minWidth)
        val maxHeight = (screenHeight - dp(8)).coerceAtLeast(minHeight)
        panelParams.width = width.coerceIn(minWidth, maxWidth)
        panelParams.height = height.coerceIn(minHeight, maxHeight)
        panelParams.x = panelParams.x.coerceIn(0, (screenWidth - panelParams.width).coerceAtLeast(0))
        panelParams.y = panelParams.y.coerceIn(0, (screenHeight - panelParams.height).coerceAtLeast(0))
        panel?.let { runCatching { wm.updateViewLayout(it, panelParams) } }
    }

    /** Persist once per gesture instead of writing preferences on every touch event. */
    private fun persistPanelGeometry() {
        if (!::panelParams.isInitialized) return
        prefs.edit()
            .putInt(KEY_X, panelParams.x)
            .putInt(KEY_Y, panelParams.y)
            .putInt(KEY_WIDTH, panelParams.width)
            .putInt(KEY_HEIGHT, panelParams.height)
            .apply()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenSize(wm)
        if (::panelParams.isInitialized) {
            resizePanel(wm, panelParams.width, panelParams.height)
            persistPanelGeometry()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    override fun onDestroy() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        view = null
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SUGGESTION = "com.chesslens.SUGGESTION"
        const val ACTION_HIDE = "com.chesslens.HIDE_OVERLAY"
        const val ACTION_CLEAR = "com.chesslens.CLEAR_OVERLAY"
        const val ACTION_STATUS = "com.chesslens.STATUS_OVERLAY"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_LEFT = "board_left"
        const val EXTRA_TOP = "board_top"
        const val EXTRA_RIGHT = "board_right"
        const val EXTRA_BOTTOM = "board_bottom"
        const val EXTRA_WHITE_AT_BOTTOM = "white_bottom"
        const val EXTRA_TEXT = "suggestion_text"
        const val EXTRA_STATUS = "suggestion_status"

        private const val PREFS = "chesslens_overlay"
        private const val KEY_WIDTH = "panel_width"
        private const val KEY_HEIGHT = "panel_height"
        private const val KEY_X = "panel_x"
        private const val KEY_Y = "panel_y"
        private const val DEFAULT_PANEL_WIDTH_DP = 300
        private const val DEFAULT_PANEL_HEIGHT_DP = 164
        private const val MIN_PANEL_WIDTH_DP = 220
        private const val MIN_PANEL_HEIGHT_DP = 140
    }
}

class MoveOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 74, 222, 128)
        style = Paint.Style.FILL
    }
    private val lineOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 7, 17, 31)
        style = Paint.Style.STROKE
        strokeWidth = 15f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 222, 128)
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val headOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 7, 17, 31)
        style = Paint.Style.FILL
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 222, 128)
        style = Paint.Style.FILL
    }
    private var move: Pair<String, String>? = null
    private var board: BoardRect? = null
    private var whiteAtBottom: Boolean = true
    private val screenLocation = IntArray(2)

    fun setMove(from: String, to: String, bounds: BoardRect, whiteAtBottom: Boolean) {
        move = from to to
        board = bounds
        this.whiteAtBottom = whiteAtBottom
        invalidate()
    }

    fun clear() {
        move = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val screenBoard = board ?: return
        val (from, to) = move ?: return
        getLocationOnScreen(screenLocation)
        val rect = BoardRect(
            screenBoard.left - screenLocation[0],
            screenBoard.top - screenLocation[1],
            screenBoard.right - screenLocation[0],
            screenBoard.bottom - screenLocation[1]
        )
        val cellWidth = (rect.right - rect.left) / 8f
        val cellHeight = (rect.bottom - rect.top) / 8f
        val highlightInset = 2f * density
        listOf(from, to).forEach { square ->
            val (cx, cy) = BoardCoordinates.center(square, rect, whiteAtBottom)
            val left = cx - cellWidth / 2 + highlightInset
            val top = cy - cellHeight / 2 + highlightInset
            val right = cx + cellWidth / 2 - highlightInset
            val bottom = cy + cellHeight / 2 - highlightInset
            canvas.drawRoundRect(left, top, right, bottom, 8f * density, 8f * density, fillPaint)
        }
        val start = BoardCoordinates.center(from, rect, whiteAtBottom)
        val end = BoardCoordinates.center(to, rect, whiteAtBottom)
        canvas.drawLine(start.first, start.second, end.first, end.second, lineOutlinePaint)
        canvas.drawLine(start.first, start.second, end.first, end.second, linePaint)
        val angle = atan2(end.second - start.second, end.first - start.first)
        val head = 30f * density
        val path = Path().apply {
            moveTo(end.first, end.second)
            lineTo(end.first - head * cos(angle - .5f), end.second - head * sin(angle - .5f))
            lineTo(end.first - head * cos(angle + .5f), end.second - head * sin(angle + .5f))
            close()
        }
        canvas.drawPath(path, headOutlinePaint)
        canvas.drawPath(path, headPaint)
        canvas.drawCircle(end.first, end.second, 10f * density, headPaint)
    }
}

private class MovePanelView(
    context: Context,
    private val onMove: (Int, Int) -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onGestureEnd: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 15, 23, 32) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 74, 222, 128)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(74, 222, 128) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 222, 128)
        textSize = 15f * scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val suggestionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 226, 232, 240)
        textSize = 12f * scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 148, 163, 184)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var suggestion = "Waiting for a stable position"
    private var status = "Drag the panel to move it. Drag the bottom-right corner to resize."
    private var suggestionLayout: StaticLayout? = null
    private var statusLayout: StaticLayout? = null
    private var resizing = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startWidth = 0
    private var startHeight = 0
    private var originX = 0
    private var originY = 0

    init {
        isClickable = true
        contentDescription = "ChessLens suggestion panel"
    }

    fun setSuggestion(value: String) {
        suggestion = value
        rebuildLayouts()
        invalidate()
    }

    fun setStatus(value: String) {
        status = value
        rebuildLayouts()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts()
    }

    private fun rebuildLayouts() {
        if (width <= 0) return
        val padding = 14f * density
        val textWidth = (width - padding * 2).toInt().coerceAtLeast(1)
        val statusWidth = (textWidth - 24f * density).toInt().coerceAtLeast(1)
        suggestionLayout = buildLayout(suggestion, suggestionPaint, textWidth, 3)
        statusLayout = buildLayout(status, statusPaint, statusWidth, 2)
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 14f * density
        val radius = 16f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, borderPaint)

        val titleBaseline = padding + titlePaint.textSize
        canvas.drawCircle(padding + 3f * density, titleBaseline - titlePaint.textSize * .35f, 3f * density, accentPaint)
        canvas.drawText("ChessLens", padding + 12f * density, titleBaseline, titlePaint)

        val suggestionTop = titleBaseline + 6f * density
        suggestionLayout?.let {
            canvas.save()
            canvas.translate(padding, suggestionTop)
            it.draw(canvas)
            canvas.restore()
        }

        val statusHeight = statusLayout?.height ?: 0
        val statusTop = height - padding - statusHeight
        statusLayout?.let {
            canvas.save()
            canvas.translate(padding, statusTop)
            it.draw(canvas)
            canvas.restore()
        }

        val hx = width - padding
        val hy = height - padding
        for (i in 0..2) {
            val offset = (i + 1) * 5f * density
            canvas.drawLine(hx - offset, hy, hx, hy - offset, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handleSize = 48f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                resizing = event.x >= width - handleSize && event.y >= height - handleSize
                dragging = !resizing
                startWidth = width
                startHeight = height
                val p = layoutParams as? WindowManager.LayoutParams
                originX = p?.x ?: 0
                originY = p?.y ?: 0
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    onResize(
                        startWidth + (event.rawX - downRawX).toInt(),
                        startHeight + (event.rawY - downRawY).toInt()
                    )
                } else if (dragging) {
                    onMove(
                        originX + (event.rawX - downRawX).toInt(),
                        originY + (event.rawY - downRawY).toInt()
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (resizing || dragging) onGestureEnd()
                resizing = false
                dragging = false
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (resizing || dragging) onGestureEnd()
                resizing = false
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
