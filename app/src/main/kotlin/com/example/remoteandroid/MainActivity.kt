package com.example.remoteandroid

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.remoteandroid.control.RemoteControl
import com.example.remoteandroid.control.RemoteControlService
import com.example.remoteandroid.util.Network

class MainActivity : Activity() {
    private val requestProjection = 1001
    private val requestNotifications = 1002

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask =
        object : Runnable {
            override fun run() {
                refreshStatus()
                refreshHandler.postDelayed(this, 1000)
            }
        }

    private lateinit var status: TextView
    private lateinit var urls: TextView
    private lateinit var logText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var remoteButton: Button
    private lateinit var tokenInput: EditText
    private lateinit var setTokenButton: Button

    // When false the access token is masked in the log (shown as dots).
    private var tokenVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        urls = findViewById(R.id.urls)
        logText = findViewById(R.id.logText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        remoteButton = findViewById(R.id.remoteButton)
        tokenInput = findViewById(R.id.tokenInput)
        setTokenButton = findViewById(R.id.setTokenButton)

        startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.setText(R.string.status_consent)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), requestProjection)
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            refreshStatus()
        }

        remoteButton.setOnClickListener {
            openAccessibilitySettings()
        }

        val toggleTokenButton = findViewById<Button>(R.id.toggleTokenButton)
        toggleTokenButton.setOnClickListener {
            tokenVisible = !tokenVisible
            toggleTokenButton.setText(if (tokenVisible) R.string.hide_token else R.string.show_token)
            refreshStatus()
        }

        tokenInput.setText(
            getSharedPreferences("settings", MODE_PRIVATE)
                .getString(ACCESS_TOKEN_KEY, "") ?: "",
        )
        setTokenButton.setOnClickListener {
            val value = tokenInput.text.toString().trim()
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putString(ACCESS_TOKEN_KEY, value)
                .apply()
            val message =
                if (value.isEmpty()) {
                    "Token cleared — a random token will be used next Start"
                } else {
                    "Token saved — applies on next Start"
                }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode == requestProjection && resultCode == RESULT_OK && data != null) {
            val intent =
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            startForegroundService(intent)
            status.setText(R.string.status_starting)
            // The service sets SessionStatus.running only once capture is truly
            // up; poll briefly instead of optimistically enabling Stop.
            refreshHandler.postDelayed({ refreshStatus() }, 600)
            refreshHandler.postDelayed({ refreshStatus() }, 2000)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun openAccessibilitySettings() {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ACTION_ACCESSIBILITY_DETAILS_SETTINGS is a hidden @SystemApi,
                // so it is referenced by its literal action string.
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(this@MainActivity, RemoteControlService::class.java),
                    )
                }
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun refreshStatus() {
        val running = SessionStatus.running
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        status.setText(if (running) R.string.status_running else R.string.status_idle)
        remoteButton.setText(
            if (RemoteControl.isConnected) R.string.remote_enabled else R.string.remote_enable,
        )
        urls.text =
            if (running) {
                SessionStatus.url.ifEmpty {
                    Network.localIPv4s().joinToString("\n") { ip -> "https://$ip:8443/" }
                }
            } else {
                ""
            }
        logText.text = buildLog()
    }

    private fun buildLog(): String {
        val sb = StringBuilder()
        if (SessionStatus.token.isNotEmpty()) {
            val display = if (tokenVisible) SessionStatus.token else "\u2022".repeat(SessionStatus.token.length)
            sb.append("Token: ").append(display).append('\n')
        }
        sb
            .append("Remote control: ")
            .append(if (RemoteControl.isConnected) "enabled" else "disabled")
            .append('\n')
        val clients = SessionStatus.clients()
        sb.append("Viewers: ").append(clients.size)
        if (clients.isNotEmpty()) {
            sb.append(" — ").append(clients.joinToString(", "))
        }
        sb.append('\n')
        sb.append("Author: ").append(R.string.author).append('\n')
        val s = SessionStatus.settings
        sb
            .append("Settings: ")
            .append(s.width)
            .append("px · ")
            .append(s.fps)
            .append("fps · q")
            .append(s.quality)
        sb.append("\n\n")
        for (line in SessionStatus.recent(30)) {
            sb.append(line).append('\n')
        }
        return sb.toString()
    }
}
