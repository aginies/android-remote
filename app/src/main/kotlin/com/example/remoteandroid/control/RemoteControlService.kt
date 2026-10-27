package com.example.remoteandroid.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used to inject gestures (tap/swipe), global actions
 * (back/home/recents) and best-effort text into other apps. The user must
 * enable it once in Settings → Accessibility → RemoteAndroid.
 */
class RemoteControlService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 0
    private var screenHeight = 0

    fun scaleX(nx: Float): Float = nx.coerceIn(0f, 1f) * screenWidth

    fun scaleY(ny: Float): Float = ny.coerceIn(0f, 1f) * screenHeight

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        RemoteControl.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        RemoteControl.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        RemoteControl.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(
        x: Float,
        y: Float,
    ) {
        handler.post {
            val path = Path().apply { moveTo(x, y) }
            dispatchGesture(stroke(path, TAP_MS), null, handler)
        }
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ) {
        handler.post {
            val path =
                Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            dispatchGesture(stroke(path, durationMs), null, handler)
        }
    }

    fun key(key: String) {
        handler.post {
            when (key) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }

    fun type(text: String) {
        handler.post {
            // Best effort: put it in the clipboard, then try to set the focused
            // editable field directly (the most reliable route on Android 13+).
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RemoteAndroid", text))
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
    }

    private fun stroke(
        path: Path,
        durationMs: Long,
    ): GestureDescription =
        GestureDescription
            .Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

    companion object {
        private const val TAP_MS = 80L
    }
}
