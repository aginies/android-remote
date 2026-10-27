package com.example.remoteandroid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.example.remoteandroid.capture.MjpegFrameSource
import com.example.remoteandroid.capture.ScreenCapturer
import com.example.remoteandroid.server.Tls
import com.example.remoteandroid.server.WebServer
import com.example.remoteandroid.settings.StreamSettings
import com.example.remoteandroid.util.Network

/**
 * Foreground service (type mediaProjection) that owns the capture pipeline and
 * the embedded HTTPS server for the lifetime of a screen-sharing session.
 *
 * Teardown is centralized in [teardown]; the projection reference is cleared
 * *before* calling [MediaProjection.stop] so the projection's onStop callback
 * (which may fire synchronously or asynchronously) can never trigger a
 * re-entrant stopSelf() and kill a freshly restarted session.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.remoteandroid.action.START"
        const val ACTION_STOP = "com.example.remoteandroid.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "RemoteAndroid"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1
    }

    private val frameSource = MjpegFrameSource()
    private var capturer: ScreenCapturer? = null
    private var server: WebServer? = null
    private var projection: MediaProjection? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var settings = StreamSettings()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settings = StreamSettings.fromPrefs(getSharedPreferences("settings", MODE_PRIVATE))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val ctx = applicationContext

        // If a previous session is still alive (e.g. a quick Stop → Start),
        // tear it down first so the new consent token can be consumed cleanly.
        if (server != null || projection != null) teardown()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "Missing or canceled projection consent, stopping")
            stopSelf()
            return
        }

        val preferredToken =
            getSharedPreferences("settings", MODE_PRIVATE)
                .getString(ACCESS_TOKEN_KEY, "")
                ?.trim()
                .orEmpty()
        val token = if (preferredToken.isNotEmpty()) preferredToken else SessionStatus.newToken()
        SessionStatus.token = token

        // On Android 14+ the mediaProjection-type foreground service must be
        // running before getMediaProjection() is called.
        startForegroundCompat()

        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = pm.getMediaProjection(resultCode, resultData)
            projection = proj
            proj.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        // Only react if this is still the live projection.
                        if (projection === proj) stopSelf()
                    }
                },
                mainHandler,
            )

            val ssl = Tls.sslContext(ctx)
            server =
                WebServer(
                    sslContext = ssl,
                    frameSource = frameSource,
                    token = token,
                    onWake = { wakeDevice() },
                    settingsProvider = { settings },
                    onSettingsUpdate = { s ->
                        settings = s
                        s.save(getSharedPreferences("settings", MODE_PRIVATE))
                        capturer?.updateSettings(s)
                        SessionStatus.settings = s
                        SessionStatus.log("settings: ${s.width}px ${s.fps}fps q${s.quality}")
                    },
                )
            server?.start()
            capturer =
                ScreenCapturer(ctx, proj, frameSource).apply {
                    updateSettings(settings)
                    start()
                }
            updateNotification("Sharing active · token $token")
            SessionStatus.settings = settings
            SessionStatus.url = "https://${Network.localIPv4s().firstOrNull() ?: "127.0.0.1"}:8443/"
            SessionStatus.running = true
            SessionStatus.log("sharing started at ${SessionStatus.url} · token $token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen sharing", e)
            teardown()
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeDevice() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        val wakeLock =
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "RemoteAndroid:wake",
            )
        wakeLock.acquire(2000)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Starting screen sharing…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun teardown() {
        SessionStatus.running = false
        frameSource.shutdown()
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Server stop failed", e)
        }
        server = null
        capturer?.stop()
        capturer = null
        val old = projection
        projection = null
        @Suppress("DEPRECATION")
        try {
            old?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Projection stop failed", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen sharing",
                    NotificationManager.IMPORTANCE_LOW,
                )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        SessionStatus.log("sharing stopped")
        teardown()
        super.onDestroy()
    }
}
