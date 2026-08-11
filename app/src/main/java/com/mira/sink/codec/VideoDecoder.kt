package com.mira.sink.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.mira.sink.Phase
import com.mira.sink.SinkState
import com.mira.sink.StatusBus

class VideoDecoder {

    companion object {
        private const val TAG = "MiraCodec"
        const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val MAX_INPUT_SIZE = 4 * 1024 * 1024
    }

    private val lock = Object()

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var configured: Boolean = false

    @Volatile
    private var bufferMode: Boolean = false

    private var imageReader: ImageReader? = null

    fun setBufferMode(enabled: Boolean) {
        bufferMode = enabled
        Log.i(TAG, "bufferMode=$enabled")
    }

    fun setImageReaderMode(enabled: Boolean) {
        if (enabled) {
            val ir = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 6)
            ir.setOnImageAvailableListener({ reader ->
                val img = try {
                    reader.acquireLatestImage()
                } catch (t: Throwable) {
                    null
                }
                if (img != null) {
                    imageCount++
                    if (imageCount % 60 == 0L) Log.i(TAG, "imageReader: frames=$imageCount")
                    img.close()
                }
            }, null)
            imageReader = ir
            Log.i(TAG, "imageReaderMode=true")
        } else {
            imageReader = null
        }
    }

    private var imageCount = 0L

    @Volatile
    private var currentMime: String? = null

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    private var heldInputIndex = -1
    private val paramSetNals = ArrayList<ByteArray>()
    private var paramSetBytes = 0

    @Volatile
    private var captureEnabled = false

    private var captureFile: java.io.FileOutputStream? = null

    @Volatile
    private var localMode = false

    @Volatile
    private var wholeFileMode = false

    fun setWholeFileMode(enabled: Boolean) {
        wholeFileMode = enabled
        Log.i(TAG, "wholeFileMode=$enabled")
        if (enabled) {
            Thread({
                runWholeFile()
            }, "whole-decode").start()
        }
    }

    private fun runWholeFile() {
        val f = java.io.File("/data/local/tmp/captured.h265")
        val bytes = try {
            f.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "whole: read failed", t)
            return
        }
        Log.i(TAG, "whole: ${bytes.size} bytes")
        // configure decoder directly with default dims
        configure(MIME_HEVC)
        val c = codec ?: return
        var sent = false
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!sent) {
                val inIndex = c.dequeueInputBuffer(1000)
                if (inIndex >= 0) {
                    val buf = c.getInputBuffer(inIndex) ?: continue
                    buf.clear()
                    val write = minOf(bytes.size, buf.capacity())
                    buf.put(bytes, 0, write)
                    c.queueInputBuffer(inIndex, 0, write, 1_000_000L, 0)
                    sent = true
                    Log.i(TAG, "whole: queued $write bytes")
                }
            }
            drainOutputs(c)
            Thread.sleep(50)
        }
        Log.i(TAG, "whole: done rendered=$framesRendered")
    }

    @Volatile
    private var minimalConfig = false

    fun setMinimalConfig(enabled: Boolean) {
        minimalConfig = enabled
        Log.i(TAG, "minimalConfig=$enabled")
    }

    fun setLocalMode(enabled: Boolean) {
        localMode = enabled
        Log.i(TAG, "localMode=$enabled")
        if (enabled) {
            Thread({
                runLocalFile()
            }, "local-decode").start()
        }
    }

    private fun runLocalFile() {
        val f = java.io.File("/data/local/tmp/captured.h265")
        Log.i(TAG, "local: reading ${f.absolutePath} exists=${f.exists()}")
        val bytes = try {
            f.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "local: read failed", t)
            return
        }
        // split into AUs by start codes; feed each as a unit
        val aus = ArrayList<ByteArray>()
        var p = 0
        var lastSc = -1
        val scAt = { pos: Int -> if (pos + 4 <= bytes.size && bytes[pos].toInt()==0 && bytes[pos+1].toInt()==0 && bytes[pos+2].toInt()==0 && bytes[pos+3].toInt()==1) 4 else if (pos + 3 <= bytes.size && bytes[pos].toInt()==0 && bytes[pos+1].toInt()==0 && bytes[pos+2].toInt()==1) 3 else 0 }
        while (p < bytes.size) {
            val sc = scAt(p)
            if (sc > 0) {
                if (lastSc >= 0) aus.add(bytes.copyOfRange(lastSc, p))
                lastSc = p
                p += sc
            } else {
                p++
            }
        }
        if (lastSc >= 0) aus.add(bytes.copyOfRange(lastSc, bytes.size))
        Log.i(TAG, "local: ${aus.size} AUs, first=${aus.firstOrNull()?.size}, last=${aus.lastOrNull()?.size}")
        var n = 0
        for (au in aus) {
            feed(au, 0, au.size, true)
            n++
            if (n % 30 == 0) Log.i(TAG, "local: fed $n AUs rendered=$framesRendered")
            try {
                Thread.sleep(33)
            } catch (t: Throwable) {
            }
        }
        Log.i(TAG, "local: done feeding $n AUs rendered=$framesRendered")
        Log.i(TAG, "local: signaling EOS to flush decoder")
        try {
            val c = codec
            if (c != null) {
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    c.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drainOutputs(c)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local: EOS failed", t)
        }
        try {
            Thread.sleep(3000)
        } catch (t: Throwable) {
        }
        Log.i(TAG, "local: after EOS rendered=$framesRendered")
    }

    fun setCaptureEnabled(enabled: Boolean) {
        synchronized(lock) {
            captureEnabled = enabled
            if (enabled && captureFile == null) {
                try {
                    val f = java.io.File("/data/user/0/com.mira.sink/files/captured.h265")
                    f.parentFile?.mkdirs()
                    captureFile = java.io.FileOutputStream(f)
                    Log.i(TAG, "capture enabled -> ${f.absolutePath}")
                } catch (t: Throwable) {
                    Log.e(TAG, "capture open failed", t)
                }
            }
        }
    }

    fun flushCapture() {
        synchronized(lock) {
            try {
                captureFile?.flush()
            } catch (t: Throwable) {
            }
        }
    }

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
            Log.i(TAG, "setSurface: ${if (s != null) "surface" else "null"} configured=$configured")
        if (configured && s != null && !bufferMode) {
            Log.i(TAG, "Surface recreated, rebuilding decoder")
            releaseCodecLocked()
        }
        }
    }

    fun getSurface(): Surface? = surface

    fun framesQueuedTotal(): Long = framesQueuedTotal

    fun framesRenderedTotal(): Long = framesRendered

    fun feed(nal: ByteArray, offset: Int, length: Int, isH265: Boolean, arrivalNanos: Long = 0L) {
        if (length <= 0) return
        if (captureEnabled) {
            try {
                val fo = captureFile
                if (fo != null) {
                    fo.write(nal, offset, length)
                    fo.flush()
                }
            } catch (t: Throwable) {
            }
        }
        if (feedCount < 8) {
            Log.i(TAG, "feed: au len=$length h265=$isH265 surface=${surface != null} codec=${codec != null}")
            feedCount++
        }

        val mime = if (isH265) MIME_HEVC else MIME_H264

        if (surface == null && !bufferMode) {
            Log.w(TAG, "feed: no surface (h265=$isH265) - dropping AU")
            return
        }

        var p = offset
        val end = offset + length
        var isSyncFrame = false
        while (p < end) {
            val startCodeLen = startCode(nal, p, end)
            if (startCodeLen == 0) {
                p++
                continue
            }
            val typePos = p + startCodeLen
            if (typePos >= end) break
            val t = if (isH265) ((nal[typePos].toInt() and 0x7E) shr 1) else (nal[typePos].toInt() and 0x1F)
            if (isH265) {
                if (t in 16..23) isSyncFrame = true
            } else if (t == 5) {
                isSyncFrame = true
            }
            val isPs = if (isH265) t in 32..34 else t in 7..8
            val nEnd = nextStartCode(nal, typePos + 2, end)
            if (isPs) {
                if (paramSetNals.size < 16 && paramSetBytes + nEnd <= 256 * 1024) {
                    val n = ByteArray(nEnd)
                    System.arraycopy(nal, typePos, n, 0, nEnd)
                    paramSetNals.add(n)
                    paramSetBytes += nEnd
                }
                if ((isH265 && t == 33) || (!isH265 && t == 7)) {
                    val sps = ByteArray(nEnd)
                    System.arraycopy(nal, typePos, sps, 0, nEnd)
                    val dims = if (isH265) SpsParser.parseHevcSps(sps) else SpsParser.parseH264Sps(sps)
                    dims?.let { updateDimensions(it.width, it.height) }
                }
            }
            p = typePos + nEnd
        }

        if (codec == null || !configured || currentMime != mime) {
            synchronized(lock) {
                if (codec == null || currentMime != mime) {
                    releaseCodecLocked()
                    configure(mime)
                }
            }
        }

        queueToCodec(nal, offset, length, 0L, isSyncFrame)
    }

    private fun startCode(nal: ByteArray, from: Int, end: Int): Int {
        if (from + 4 <= end &&
            nal[from] == 0.toByte() && nal[from + 1] == 0.toByte() &&
            nal[from + 2] == 0.toByte() && nal[from + 3] == 1.toByte()
        ) return 4
        if (from + 3 <= end &&
            nal[from] == 0.toByte() && nal[from + 1] == 0.toByte() && nal[from + 2] == 1.toByte()
        ) return 3
        return 0
    }

    private fun nextStartCode(nal: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (startCode(nal, i, end) > 0) return i - from
            i++
        }
        return end - from
    }

    private fun updateDimensions(w: Int, h: Int) {
        if (videoWidth != w || videoHeight != h) {
            videoWidth = w
            videoHeight = h
            Log.i(TAG, "Stream dimensions: ${w}x$h")
            StatusBus.update(
                StatusBus.state.copy(
                    phase = Phase.STREAMING,
                    message = "Streaming",
                    detail = "${mimeLabel(currentMime)} ${w}x$h"
                )
            )
        }
    }

    private fun configure(mime: String) {
        Log.i(TAG, "configure($mime) start, surface=${surface != null}")
        val targetSurface = if (bufferMode) null else (imageReader?.surface ?: surface)
        if (targetSurface == null && !bufferMode) {
            Log.w(TAG, "configure: no target surface")
            return
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            videoWidth = 1280
            videoHeight = 720
        }
        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        if (bufferMode) {
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            } catch (t: Throwable) {
            }
        }
        if (!minimalConfig) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } catch (t: Throwable) {
                }
            }
            try {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
            } catch (t: Throwable) {
            }
            try {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            } catch (t: Throwable) {
            }
            try {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            } catch (t: Throwable) {
            }
        }

        try {
            val name = if (mime == MIME_HEVC) {
                "c2.android.hevc.decoder"
            } else {
                "c2.android.avc.decoder"
            }
            val decoder = try {
                MediaCodec.createByCodecName(name)
            } catch (t: Throwable) {
                Log.w(TAG, "Preferred decoder $name unavailable, using default for $mime")
                MediaCodec.createDecoderByType(mime)
            }
            decoder.configure(format, targetSurface, null, 0)
            decoder.start()
            codec = decoder
            configured = true
            currentMime = mime
            Log.i(TAG, "Decoder configured: ${decoder.name} $mime (${videoWidth}x$videoHeight) low-latency surface output")
        } catch (t: Throwable) {
            Log.e(TAG, "MediaCodec configure failed: $mime ${videoWidth}x$videoHeight", t)
            configured = false
        }
    }

    private var latencySamples = 0L
    private var latencySumNanos = 0L
    private var latencyMaxNanos = 0L
    private var framesQueuedTotal = 0L
    private var framesRendered = 0L
    private var feedCount = 0L
    private var lockProbe = 0L

    private fun drainOutputs(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var drained = 0
        var whatever = 0
        while (drained < 8) {
            val out = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (t: Throwable) {
                Log.e(TAG, "dequeueOutputBuffer threw", t)
                return
            }
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                whatever++
                if (debugDrain < 6) {
                    debugDrain++
                    Log.i(TAG, "drain: try-again (queued=$framesQueuedTotal rendered=$framesRendered)")
                }
                if (drained >= 4) Log.i(TAG, "drain: stalled mid-loop (queued=$framesQueuedTotal rendered=$framesRendered)")
                return
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else videoWidth
                val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else videoHeight
                if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                    videoWidth = w
                    videoHeight = h
                    Log.i(TAG, "Decoder output: ${w}x$h")
                }
                continue
            }
            if (out < 0) {
                if (out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                return
            }
            try {
                if (bufferMode) {
                    val ob = c.getOutputBuffer(out) ?: continue
                    Log.i(TAG, "bufout #$framesRendered pts=${info.presentationTimeUs} size=${info.size} cap=${ob.capacity()} flags=${info.flags}")
                    c.releaseOutputBuffer(out, false)
                } else {
                    c.releaseOutputBuffer(out, true)
                }
            } catch (t: Throwable) {
                return
            }
            drained++
            framesRendered++
            if (debugRender < 12) {
                debugRender++
                Log.i(TAG, "render #$framesRendered pts=${info.presentationTimeUs} size=${info.size} flags=${info.flags}")
            }
        }
    }

    private var debugRender = 0L

    private var debugDrain = 0L

    private fun queueToCodec(nal: ByteArray, offset: Int, length: Int, arrivalNanos: Long = 0L, isSyncFrame: Boolean = false) {
        val c = codec ?: return
        try {
            val inIndex = if (heldInputIndex >= 0) heldInputIndex else c.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            heldInputIndex = inIndex
            val buf = c.getInputBuffer(inIndex) ?: return
            buf.clear()
            val write = minOf(length, buf.capacity())
            buf.put(nal, offset, write)
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
            val flags = if (isSyncFrame) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
            c.queueInputBuffer(inIndex, 0, write, ptsUs, flags)
            heldInputIndex = -1
            framesQueuedTotal++
            drainOutputs(c)
            if (framesQueuedTotal % 60 == 0L) {
                Log.i(TAG, "decoded frames queued: $framesQueuedTotal")
            }
            if (arrivalNanos > 0) {
                val delay = SystemClock.elapsedRealtimeNanos() - arrivalNanos
                latencySamples++
                latencySumNanos += delay
                if (delay > latencyMaxNanos) latencyMaxNanos = delay
                if (latencySamples % 90 == 0L) {
                    Log.i(
                        TAG,
                        "pipeline: frames=$framesQueuedTotal queueDelayAvg=${latencySumNanos / latencySamples / 1_000_000}ms max=${latencyMaxNanos / 1_000_000}ms"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queueInputBuffer failed", t)
        }
    }

    fun feedTs(pes: ByteArray, offset: Int, length: Int) {
        feed(pes, offset, length, false)
    }

    fun flush() {
        synchronized(lock) {
            try {
                codec?.flush()
            } catch (t: Throwable) {
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                captureFile?.close()
            } catch (t: Throwable) {
            }
            captureFile = null
            releaseCodecLocked()
        }
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        try {
            c.stop()
        } catch (t: Throwable) {
        }
        try {
            c.release()
        } catch (t: Throwable) {
        }
        codec = null
        configured = false
        currentMime = null
        heldInputIndex = -1
    }

    private fun mimeLabel(mime: String?): String = when (mime) {
        MIME_HEVC -> "H.265"
        MIME_H264 -> "H.264"
        else -> "?"
    }
}