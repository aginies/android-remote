package com.example.remoteandroid.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import com.example.remoteandroid.settings.StreamSettings
import com.example.remoteandroid.settings.WIDTH_NATIVE
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the MediaProjection → VirtualDisplay → ImageReader pipeline, converts
 * frames to JPEG and publishes them to the [source].
 *
 * Performance notes:
 * - The (large) native-resolution bitmap and the crop bitmap are reused across
 *   frames and only reallocated when dimensions change.
 * - JPEG encode runs on a dedicated single worker thread with a two-slot buffer
 *   pool, so the capture thread keeps draining the ImageReader (and grabbing the
 *   latest frame) without blocking on compress(); frames are dropped when both
 *   slots are busy.
 * - One ByteArrayOutputStream is reused for the encode output.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val source: MjpegFrameSource,
) {
    private val handlerThread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val encodeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "remoteandroid-encode").apply { isDaemon = true } }
    private val encodeBaos = ByteArrayOutputStream()

    // Reused capture-side bitmaps (owned by the capture thread only).
    private var sourceBitmap: Bitmap? = null
    private var cropBitmap: Bitmap? = null
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Reused encode slots (producer = capture thread, consumer = encode worker).
    private val slotLock = Object()
    private val slots = arrayOfNulls<Bitmap>(2)
    private val slotBusy = booleanArrayOf(false, false)

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var settings = StreamSettings()

    @Volatile
    private var running = false

    private var lastFrameAt = 0L
    private var nativeWidth = 0
    private var nativeHeight = 0

    fun start() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        nativeWidth = point.x
        nativeHeight = point.y

        val density = context.resources.displayMetrics.densityDpi
        imageReader =
            ImageReader.newInstance(nativeWidth, nativeHeight, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r -> onFrame(r) }, handler)
            }
        virtualDisplay =
            projection.createVirtualDisplay(
                "RemoteAndroid",
                nativeWidth,
                nativeHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler,
            )
        running = true
    }

    fun updateSettings(s: StreamSettings) {
        settings = s
    }

    fun stop() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        // Wait for the capture thread to finish any in-flight frame before
        // recycling the bitmaps it owns.
        handlerThread.quitSafely()
        try {
            handlerThread.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Wait for the single encode worker to finish the current compress().
        encodeExecutor.shutdown()
        try {
            encodeExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        releaseBitmaps()
    }

    private fun onFrame(reader: ImageReader) {
        if (!running) return
        // Always acquire and close the image to keep the ImageReader buffer
        // queue drained; otherwise the reader stalls after maxImages frames and
        // stops notifying. Only the expensive work below is throttled.
        val image = reader.acquireLatestImage() ?: return
        try {
            val s = settings
            val now = SystemClock.elapsedRealtime()
            val interval = 1000L / s.fps.coerceAtLeast(1)
            if (now - lastFrameAt < interval) return

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * nativeWidth
            val paddedWidth = nativeWidth + rowPadding / pixelStride

            // 1. Fill the reused native-resolution buffer.
            if (
                sourceBitmap == null ||
                sourceBitmap!!.width != paddedWidth ||
                sourceBitmap!!.height != nativeHeight
            ) {
                sourceBitmap?.recycle()
                sourceBitmap = Bitmap.createBitmap(paddedWidth, nativeHeight, Bitmap.Config.ARGB_8888)
            }
            sourceBitmap!!.copyPixelsFromBuffer(buffer)

            // 2. Crop the stride padding away (rare; buffer reused).
            val native: Bitmap =
                if (rowPadding == 0) {
                    sourceBitmap!!
                } else {
                    if (
                        cropBitmap == null ||
                        cropBitmap!!.width != nativeWidth ||
                        cropBitmap!!.height != nativeHeight
                    ) {
                        cropBitmap?.recycle()
                        cropBitmap = Bitmap.createBitmap(nativeWidth, nativeHeight, Bitmap.Config.ARGB_8888)
                    }
                    Canvas(cropBitmap!!).drawBitmap(
                        sourceBitmap!!,
                        Rect(0, 0, nativeWidth, nativeHeight),
                        Rect(0, 0, nativeWidth, nativeHeight),
                        null,
                    )
                    cropBitmap!!
                }

            val targetW = if (s.width == WIDTH_NATIVE) nativeWidth else minOf(s.width, nativeWidth)
            val targetH = (nativeHeight.toLong() * targetW / nativeWidth).toInt().coerceAtLeast(1)

            // 3. Get a free encode slot or drop this frame under backpressure.
            val slot = acquireSlot(targetW, targetH) ?: return
            lastFrameAt = now

            // 4. Copy the frame into the slot (no new bitmap per frame).
            Canvas(slot).drawBitmap(native, null, Rect(0, 0, targetW, targetH), scalePaint)

            // 5. Move the expensive JPEG encode off the capture thread.
            encodeExecutor.execute {
                try {
                    encodeBaos.reset()
                    slot.compress(Bitmap.CompressFormat.JPEG, s.quality, encodeBaos)
                    source.publish(encodeBaos.toByteArray())
                } catch (_: Exception) {
                    // Swallow encode errors so the worker keeps running.
                } finally {
                    releaseSlot(slot)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun acquireSlot(
        w: Int,
        h: Int,
    ): Bitmap? =
        synchronized(slotLock) {
            val idx =
                if (!slotBusy[0]) {
                    0
                } else if (!slotBusy[1]) {
                    1
                } else {
                    -1
                }
            if (idx < 0) {
                null
            } else {
                if (slots[idx] == null || slots[idx]!!.width != w || slots[idx]!!.height != h) {
                    slots[idx]?.recycle()
                    slots[idx] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                slotBusy[idx] = true
                slots[idx]
            }
        }

    private fun releaseSlot(slot: Bitmap) =
        synchronized(slotLock) {
            if (slot === slots[0]) {
                slotBusy[0] = false
            } else if (slot === slots[1]) {
                slotBusy[1] = false
            }
        }

    private fun releaseBitmaps() {
        synchronized(slotLock) {
            slots[0]?.recycle()
            slots[1]?.recycle()
            slots[0] = null
            slots[1] = null
            slotBusy[0] = false
            slotBusy[1] = false
        }
        sourceBitmap?.recycle()
        sourceBitmap = null
        cropBitmap?.recycle()
        cropBitmap = null
    }
}
