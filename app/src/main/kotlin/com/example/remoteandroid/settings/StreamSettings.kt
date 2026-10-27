package com.example.remoteandroid.settings

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Sentinel value meaning "do not downscale" — stream at the source resolution. */
const val WIDTH_NATIVE = 9999

data class StreamSettings(
    val width: Int = 960,
    val fps: Int = 15,
    val quality: Int = 75,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("width", width)
            put("fps", fps)
            put("quality", quality)
            put("widths", JSONArray(WIDTHS))
            put("fpsRange", JSONArray(listOf(MIN_FPS, MAX_FPS)))
            put("qualityRange", JSONArray(listOf(MIN_QUALITY, MAX_QUALITY)))
        }

    fun save(p: SharedPreferences) {
        p
            .edit()
            .putInt("width", width)
            .putInt("fps", fps)
            .putInt("quality", quality)
            .apply()
    }

    companion object {
        const val MIN_FPS = 5
        const val MAX_FPS = 30
        const val MIN_QUALITY = 30
        const val MAX_QUALITY = 95

        val WIDTHS = listOf(640, 960, 1280, WIDTH_NATIVE)

        val PRESET_LOW = StreamSettings(width = 640, fps = 8, quality = 55)
        val PRESET_MEDIUM = StreamSettings(width = 960, fps = 15, quality = 75)
        val PRESET_HIGH = StreamSettings(width = 1280, fps = 20, quality = 85)

        fun sanitize(s: StreamSettings): StreamSettings {
            val w = if (s.width == WIDTH_NATIVE) WIDTH_NATIVE else s.width.coerceIn(320, 2880)
            return StreamSettings(
                width = w,
                fps = s.fps.coerceIn(MIN_FPS, MAX_FPS),
                quality = s.quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
            )
        }

        fun fromJson(raw: String): StreamSettings =
            sanitize(
                (
                    try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                ).let { o ->
                    StreamSettings(
                        width = o.optInt("width", PRESET_MEDIUM.width),
                        fps = o.optInt("fps", PRESET_MEDIUM.fps),
                        quality = o.optInt("quality", PRESET_MEDIUM.quality),
                    )
                },
            )

        fun fromPrefs(p: SharedPreferences): StreamSettings =
            sanitize(
                StreamSettings(
                    width = p.getInt("width", PRESET_MEDIUM.width),
                    fps = p.getInt("fps", PRESET_MEDIUM.fps),
                    quality = p.getInt("quality", PRESET_MEDIUM.quality),
                ),
            )
    }
}
