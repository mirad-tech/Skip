package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.skip.data.IconManager
import com.example.skip.data.SettingsRepository

internal class ServiceFeedbackController(
    private val service: AccessibilityService,
    private val mainHandler: Handler
) {
    private val lastToastAt = mutableMapOf<String, Long>()
    private var popupView: View? = null

    fun showDebugToast(message: String, key: String) {
        if (!SettingsRepository.isDebugToastEnabled(service)) return
        showToast(message, key)
    }

    fun showSuccessToast(message: String, key: String) {
        if (!SettingsRepository.isSuccessToastEnabled(service)) return
        showToast(message, key)
    }

    private fun showToast(message: String, key: String) {
        val now = System.currentTimeMillis()
        val last = lastToastAt[key] ?: 0L
        if (now - last < TOAST_COOLDOWN_MS) return
        lastToastAt[key] = now
        mainHandler.post { showOverlayPopup(message) }
    }

    private fun showOverlayPopup(message: String) {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hidePopup()
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xEE20242A.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val icon = ImageView(service).apply {
            setImageResource(IconManager.displayIconRes(IconManager.currentScheme(service)))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(8)
            }
        }
        val text = TextView(service).apply {
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
        }
        view.addView(icon)
        view.addView(text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        runCatching {
            windowManager.addView(view, params)
            popupView = view
            mainHandler.postDelayed({ hidePopup() }, POPUP_DURATION_MS)
        }
    }

    fun hidePopup() {
        val view = popupView ?: return
        popupView = null
        runCatching {
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeViewImmediate(view)
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val TOAST_COOLDOWN_MS = 60_000L
        const val POPUP_DURATION_MS = 1600L
    }
}
