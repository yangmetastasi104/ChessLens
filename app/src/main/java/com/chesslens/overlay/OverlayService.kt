package com.chesslens.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.MotionEvent
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) return START_NOT_STICKY
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            panelParams = WindowManager.LayoutParams(
                dp(228), dp(82), overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(96) }
            panel = MovePanelView(this) { x, y ->
                panelParams.x = x; panelParams.y = y
                panel?.let { panelView -> runCatching { wm.updateViewLayout(panelView, panelParams) } }
            }
            wm.addView(panel, panelParams)
        }
        if (intent?.action == ACTION_SUGGESTION) {
            val from = intent.getStringExtra(EXTRA_FROM)
            val to = intent.getStringExtra(EXTRA_TO)
            val left = intent.getFloatExtra(EXTRA_LEFT, Float.NaN)
            val top = intent.getFloatExtra(EXTRA_TOP, Float.NaN)
            val right = intent.getFloatExtra(EXTRA_RIGHT, Float.NaN)
            val bottom = intent.getFloatExtra(EXTRA_BOTTOM, Float.NaN)
            if (from != null && to != null && left.isFinite() && top.isFinite() && right > left && bottom > top) {
                view?.setMove(from, to, BoardRect(left, top, right, bottom),
                    intent.getBooleanExtra(EXTRA_WHITE_AT_BOTTOM, true))
                panel?.setSuggestion(intent.getStringExtra(EXTRA_TEXT) ?: "$from → $to")
            }
        } else if (intent?.action == ACTION_HIDE) {
            stopSelf()
        } else if (intent?.action == ACTION_CLEAR) {
            view?.clear()
            panel?.setSuggestion(intent.getStringExtra(EXTRA_TEXT) ?: "Waiting for stable position")
        }
        return START_NOT_STICKY
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
        view = null; panel = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SUGGESTION = "com.chesslens.SUGGESTION"
        const val ACTION_HIDE = "com.chesslens.HIDE_OVERLAY"
        const val ACTION_CLEAR = "com.chesslens.CLEAR_OVERLAY"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_LEFT = "board_left"
        const val EXTRA_TOP = "board_top"
        const val EXTRA_RIGHT = "board_right"
        const val EXTRA_BOTTOM = "board_bottom"
        const val EXTRA_WHITE_AT_BOTTOM = "white_bottom"
        const val EXTRA_TEXT = "suggestion_text"
    }
}

class MoveOverlayView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 74, 222, 128); style = Paint.Style.FILL
    }
    private var move: Pair<String, String>? = null
    private var board: BoardRect? = null
    private var whiteAtBottom: Boolean = true

    fun setMove(from: String, to: String, bounds: BoardRect, whiteAtBottom: Boolean) {
        move = from to to
        board = bounds
        this.whiteAtBottom = whiteAtBottom
        invalidate()
    }
    fun clear() { move = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = board ?: return
        val (from, to) = move ?: return
        listOf(from, to).forEach { square ->
            val (cx, cy) = BoardCoordinates.center(square, rect, whiteAtBottom)
            val w = (rect.right - rect.left) / 8f
            val h = (rect.bottom - rect.top) / 8f
            canvas.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, paint)
        }
        val start = BoardCoordinates.center(from, rect, whiteAtBottom)
        val end = BoardCoordinates.center(to, rect, whiteAtBottom)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
        canvas.drawLine(start.first, start.second, end.first, end.second, paint)
        val angle = atan2(end.second - start.second, end.first - start.first)
        val head = 22f
        val path = Path().apply {
            moveTo(end.first, end.second)
            lineTo(end.first - head * cos(angle - .5f), end.second - head * sin(angle - .5f))
            lineTo(end.first - head * cos(angle + .5f), end.second - head * sin(angle + .5f))
            close()
        }
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}

private class MovePanelView(context: android.content.Context, private val move: (Int, Int) -> Unit) : View(context) {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 24, 29, 35) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(74, 222, 128); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private var suggestion = "Waiting for a stable position"
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0

    fun setSuggestion(value: String) { suggestion = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 14f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, background)
        canvas.drawText("ChessLens ♛", 14f * density, 24f * density, accent)
        canvas.drawText("Suggested move: $suggestion", 14f * density, 47f * density, text)
        canvas.drawText("Drag panel to reposition", 14f * density, 67f * density, text.apply { alpha = 170 })
        text.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                val p = layoutParams as? WindowManager.LayoutParams
                originX = p?.x ?: 0; originY = p?.y ?: 0
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                move(originX + (event.rawX - downX).toInt(), originY + (event.rawY - downY).toInt())
                return true
            }
            MotionEvent.ACTION_UP -> return true
        }
        return super.onTouchEvent(event)
    }
}
