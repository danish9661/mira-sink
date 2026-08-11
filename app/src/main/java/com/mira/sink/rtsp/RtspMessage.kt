package com.mira.sink.rtsp

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class RtspMessage(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String
) {
    val cseq: String get() = headers["CSeq"] ?: "0"
    val session: String? get() = headers["Session"]

    val bodyLines: List<String>
        get() = body.split("\r\n", "\n").filter { it.isNotBlank() }

    fun hasParameter(name: String): Boolean =
        bodyLines.any { it.trim().startsWith(name, ignoreCase = true) }

    companion object {
        private const val MAX_HEADER_SIZE = 64 * 1024
        private const val MAX_BODY_SIZE = 256 * 1024

        fun read(input: InputStream): RtspMessage? {
            val raw = ByteArrayOutputStream(2048)
            while (raw.size() < MAX_HEADER_SIZE) {
                val b = input.read()
                if (b < 0) return null
                raw.write(b)
                if (raw.size() >= 4) {
                    val buf = raw.toByteArray()
                    if (buf[raw.size() - 4] == '\r'.code.toByte() &&
                        buf[raw.size() - 3] == '\n'.code.toByte() &&
                        buf[raw.size() - 2] == '\r'.code.toByte() &&
                        buf[raw.size() - 1] == '\n'.code.toByte()
                    ) {
                        break
                    }
                }
            }
            val full = raw.toByteArray()
            val headerEnd = indexOfTerminator(full)
            if (headerEnd < 0) throw IOException("RTSP headers too large or malformed")

            val headerText = String(full, 0, headerEnd, StandardCharsets.ISO_8859_1)
            val consumed = headerEnd + terminatorLen(full, headerEnd)
            val leftover = full.size - consumed

            val lines = headerText.split("\r\n", "\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) throw IOException("Empty RTSP request")
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 3) throw IOException("Bad request line: ${lines[0]}")

            val headers = linkedMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim()] =
                        lines[i].substring(idx + 1).trim()
                }
            }

            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > MAX_BODY_SIZE) throw IOException("Bad Content-Length")

            val body = ByteArray(contentLength)
            val fromLeftover = minOf(leftover, contentLength)
            System.arraycopy(full, consumed, body, 0, fromLeftover)
            var read = fromLeftover
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) throw IOException("Socket closed mid-body")
                read += n
            }

            return RtspMessage(
                requestLine[0],
                requestLine[1],
                headers,
                String(body, StandardCharsets.UTF_8)
            )
        }

        private fun indexOfTerminator(buf: ByteArray): Int {
            for (i in 0..buf.size - 4) {
                if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() &&
                    buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()
                ) return i
            }
            for (i in 0..buf.size - 2) {
                if (buf[i] == '\n'.code.toByte() && buf[i + 1] == '\n'.code.toByte()) return i
            }
            return -1
        }

        private fun terminatorLen(buf: ByteArray, start: Int): Int {
            if (start + 4 <= buf.size &&
                buf[start] == '\r'.code.toByte() && buf[start + 1] == '\n'.code.toByte() &&
                buf[start + 2] == '\r'.code.toByte() && buf[start + 3] == '\n'.code.toByte()
            ) return 4
            return 2
        }
    }
}

private class ByteArrayOutputStream(initialSize: Int) {
    private var buf = ByteArray(initialSize)
    private var count = 0

    fun write(b: Int) {
        if (count == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[count++] = b.toByte()
    }

    fun size(): Int = count

    fun toByteArray(): ByteArray = buf.copyOf(count)
}