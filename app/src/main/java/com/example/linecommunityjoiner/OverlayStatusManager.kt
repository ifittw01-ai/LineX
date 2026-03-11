package com.example.linecommunityjoiner

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object OverlayStatusManager {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var isAttached = false

    fun hasPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun update(context: Context, message: String) {
        handler.post {
            if (!hasPermission(context)) return@post
            val appContext = context.applicationContext
            val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
                windowManager = it
            }
            val tv = textView ?: TextView(appContext).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(24, 14, 24, 14)
                maxLines = 3
            }.also { textView = it }
            tv.text = message
            if (!isAttached) {
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    -3
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = 90
                wm.addView(tv, lp)
                isAttached = true
            }
        }
    }

    fun hide() {
        handler.post {
            try {
                if (isAttached && windowManager != null && textView != null) {
                    windowManager?.removeView(textView)
                }
            } catch (_: Exception) {
            } finally {
                isAttached = false
                textView = null
            }
        }
    }
}
