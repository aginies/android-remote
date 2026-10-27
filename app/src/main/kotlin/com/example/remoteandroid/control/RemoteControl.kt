package com.example.remoteandroid.control

/**
 * Thin bridge between the web server and the [RemoteControlService]. The web
 * server sends normalized (0..1) coordinates; this object scales them to screen
 * pixels and forwards to the accessibility service.
 */
object RemoteControl {
    @Volatile
    private var service: RemoteControlService? = null

    val isConnected: Boolean
        get() = service != null

    fun attach(s: RemoteControlService) {
        service = s
    }

    fun detach(s: RemoteControlService) {
        if (service === s) service = null
    }

    fun tap(
        nx: Float,
        ny: Float,
    ) {
        val s = service ?: return
        s.tap(s.scaleX(nx), s.scaleY(ny))
    }

    fun swipe(
        nx1: Float,
        ny1: Float,
        nx2: Float,
        ny2: Float,
        durationMs: Long,
    ) {
        val s = service ?: return
        s.swipe(s.scaleX(nx1), s.scaleY(ny1), s.scaleX(nx2), s.scaleY(ny2), durationMs)
    }

    fun key(key: String) {
        service?.key(key)
    }

    fun type(text: String) {
        service?.type(text)
    }
}
