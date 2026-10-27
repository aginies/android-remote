package com.example.remoteandroid.capture

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the most recent JPEG frame and fans it out to any number of concurrent
 * MJPEG viewers (multi-client broadcast). Each viewer gets its own stream; slow
 * clients simply skip frames.
 */
class MjpegFrameSource {
    private val frameLock = Object()
    private var frame: ByteArray = byteArrayOf()
    private var seq = 0L
    private var shutDown = false

    private val statsLock = Object()
    private val frameTimes = mutableListOf<Long>()
    private val frameByteCounts = mutableListOf<Pair<Long, Int>>()
    private val servedBytes = AtomicLong(0)

    /** Publish the latest encoded JPEG; wakes up every waiting viewer. */
    fun publish(jpeg: ByteArray) {
        synchronized(frameLock) {
            frame = jpeg
            seq++
            frameLock.notifyAll()
        }
        synchronized(statsLock) {
            val now = System.currentTimeMillis()
            frameTimes.add(now)
            frameByteCounts.add(now to jpeg.size)
        }
    }

    /** Frames actually produced in the last second (a cheap FPS meter). */
    fun framesPerSecond(): Int =
        synchronized(statsLock) {
            val cutoff = System.currentTimeMillis() - 1000
            frameTimes.removeAll { it < cutoff }
            frameTimes.size
        }

    /** JPEG bytes produced in the last second (encoder throughput). */
    fun encodedBytesPerSecond(): Long =
        synchronized(statsLock) {
            val cutoff = System.currentTimeMillis() - 1000
            frameByteCounts.removeAll { it.first < cutoff }
            frameByteCounts.sumOf { it.second.toLong() }
        }

    /** Total bytes handed out to viewers (actual network upload volume). */
    fun servedBytes(): Long = servedBytes.get()

    fun newStream(
        boundary: String,
        onClose: () -> Unit = {},
    ): InputStream = FrameInputStream(boundary, onClose)

    /** Wake every waiting viewer and force their streams to EOF. */
    fun shutdown() {
        synchronized(frameLock) {
            shutDown = true
            frameLock.notifyAll()
        }
    }

    private inner class FrameInputStream(
        boundary: String,
        private val onClose: () -> Unit,
    ) : InputStream() {
        // Precomputed, immutable multipart header fragments for this boundary.
        private val headBytes =
            "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: "
                .toByteArray(StandardCharsets.US_ASCII)
        private val midBytes = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val tailBytes = "\r\n".toByteArray(StandardCharsets.US_ASCII)

        // Reusable chunk buffer, built in place (no per-frame allocation).
        private var chunk: ByteArray = ByteArray(8192)
        private var chunkSize = 0
        private var chunkPos = 0
        private var seenSeq = 0L
        private var closed = false

        // NanoHTTPD reads the stream only via the bulk overload, so the
        // single-byte form is unsupported (and would have allocated a
        // ByteArray(1) per byte). Callers must use read(byte[], Int, Int).
        override fun read(): Int = throw UnsupportedOperationException("Use the bulk read(byte[], Int, Int) overload.")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (closed) return -1
            if (chunkPos >= chunkSize && !loadNext()) return -1
            val n = minOf(len, chunkSize - chunkPos)
            System.arraycopy(chunk, chunkPos, b, off, n)
            chunkPos += n
            servedBytes.addAndGet(n.toLong())
            return n
        }

        /**
         * Wait for a frame newer than the last one we served, then build the
         * multipart chunk for it. Returns false if the stream was closed while
         * waiting.
         */
        private fun loadNext(): Boolean {
            synchronized(frameLock) {
                while (!closed && !shutDown && seq == seenSeq) {
                    frameLock.wait()
                }
                if (closed || shutDown) return false
                val jpeg = frame
                seenSeq = seq
                buildChunk(jpeg)
                chunkPos = 0
                return chunkSize > 0
            }
        }

        private fun buildChunk(jpeg: ByteArray) {
            val lenBytes = jpeg.size.toString().toByteArray(StandardCharsets.US_ASCII)
            val total = headBytes.size + lenBytes.size + midBytes.size + jpeg.size + tailBytes.size
            if (chunk.size < total) {
                var cap = chunk.size
                while (cap < total) cap = cap + (cap shr 1) + 16
                chunk = ByteArray(cap)
            }
            var p = 0
            System.arraycopy(headBytes, 0, chunk, p, headBytes.size)
            p += headBytes.size
            System.arraycopy(lenBytes, 0, chunk, p, lenBytes.size)
            p += lenBytes.size
            System.arraycopy(midBytes, 0, chunk, p, midBytes.size)
            p += midBytes.size
            System.arraycopy(jpeg, 0, chunk, p, jpeg.size)
            p += jpeg.size
            System.arraycopy(tailBytes, 0, chunk, p, tailBytes.size)
            p += tailBytes.size
            chunkSize = total
        }

        override fun close() {
            var notify = false
            synchronized(frameLock) {
                if (!closed) {
                    closed = true
                    notify = true
                    frameLock.notifyAll()
                }
            }
            if (notify) onClose()
            super.close()
        }
    }
}
