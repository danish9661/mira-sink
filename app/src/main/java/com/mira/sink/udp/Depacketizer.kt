package com.mira.sink.udp

import android.util.Log

class Depacketizer(private val sink: Sink) {

    interface Sink {
        fun onAccessUnit(au: ByteArray, offset: Int, length: Int, isH265: Boolean)
    }

    companion object {
        private const val TAG = "MiraRtp"
        private const val START_CODE_A = 0x00.toByte()
        private const val START_CODE_B = 0x00.toByte()
        private const val MAX_UNIT = 2_000_000
    }

    private val h264Fu = ByteArray(MAX_UNIT)
    private var h264FuLen = 0
    private var h264FuType = 0
    private var h264FuHeader = 0.toByte()

    private val hevcFu = ByteArray(MAX_UNIT)
    private var hevcFuLen = 0
    private var hevcFuType = 0

    private val auBuf = ByteArray(2 * MAX_UNIT)
    private var auLen = 0
    private var auTs = Int.MIN_VALUE
    private var auH265 = false

    private fun flushAu() {
        if (auLen <= 0) return
        sink.onAccessUnit(auBuf, 0, auLen, auH265)
        auLen = 0
    }

    private fun appendToAu(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (auLen + length > auBuf.size) {
            flushAu()
            auLen = 0
            if (length > auBuf.size) return
        }
        System.arraycopy(data, offset, auBuf, auLen, length)
        auLen += length
    }

    private var debugCount = 0
    private var emitCount = 0

    @Volatile
    private var forcedH265: Boolean? = null

    fun setCodecH265(h265: Boolean?) {
        forcedH265 = h265
        h264FuLen = 0
        hevcFuLen = 0
        Log.i(TAG, "codec hint: ${if (h265 == null) "auto" else if (h265) "h265" else "h264"}")
    }

    private val tsPes = ByteArray(2 * MAX_UNIT)
    private var tsPesLen = 0
    private var tsVideoPid = -1
    private var tsStreamType = -1

    fun reset() {
        h264FuLen = 0
        hevcFuLen = 0
        tsPesLen = 0
        tsVideoPid = -1
        tsStreamType = -1
    }

    fun handlePacket(data: ByteArray, offset: Int, length: Int) {
        if (length < 12) return
        val version = (data[offset].toInt() ushr 6) and 0x03
        if (version != 2) return
        val pt = data[offset + 1].toInt() and 0x7F
        val marker = (data[offset + 1].toInt() and 0x80) != 0
        val ts = ((data[offset + 4].toInt() and 0xFF) shl 24) or
            ((data[offset + 5].toInt() and 0xFF) shl 16) or
            ((data[offset + 6].toInt() and 0xFF) shl 8) or
            (data[offset + 7].toInt() and 0xFF)
        val payloadOffset = offset + 12
        val payloadLen = length - 12
        if (payloadLen <= 0) return

        if (pt == 33 || ((data[payloadOffset].toInt() and 0xFF) == 0x47)) {
            handleTs(data, payloadOffset, payloadLen)
            return
        }

        val first = data[payloadOffset].toInt() and 0xFF
        val hevcType = (first shr 1) and 0x3F
        val h264Type = first and 0x1F

        val isH265 = when {
            forcedH265 != null -> forcedH265!!
            hevcType in 32..35 || hevcType in 16..23 || hevcType in 48..50 -> true
            h264Type in 1..23 || h264Type == 28 -> false
            else -> false
        }
        if ((Log.isLoggable(TAG, Log.DEBUG) || debugCount < 5)) {
            Log.i(
                TAG,
                "pkt#$debugCount pt=$pt first=${String.format("%02X", first)} h265=$isH265 len=$length version=$version"
            )
            debugCount++
        }

        if (auLen > 0 && (ts != auTs || isH265 != auH265)) flushAu()
        auTs = ts
        auH265 = isH265

        if (isH265) handleHevc(data, payloadOffset, payloadLen)
        else handleH264(data, payloadOffset, payloadLen)

        if (marker) flushAu()
    }

    private fun handleH264(data: ByteArray, payloadOffset: Int, payloadLen: Int) {
        var i = payloadOffset
        val end = payloadOffset + payloadLen
        while (i < end) {
            val type = data[i].toInt() and 0x1F
            when (type) {
                24 -> {
                    i += 1
                    while (i + 2 <= end) {
                        val naluLen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        i += 2
                        if (i + naluLen > end) return
                        emitH264(data, i, naluLen)
                        i += naluLen
                    }
                }
                28 -> {
                    if (i + 2 > end) return
                    val fuHeader = data[i + 1].toInt() and 0xFF
                    val start = (fuHeader and 0x80) != 0
                    val stop = (fuHeader and 0x40) != 0
                    val fuType = fuHeader and 0x1F
                    val nri = data[i].toInt() and 0x60
                    i += 2
                    if (start) {
                        h264FuLen = 0
                        h264FuType = fuType
                        h264FuHeader = (nri or fuType).toByte()
                    }
                    if (h264FuType == 0) return
                    val chunk = end - i
                    if (h264FuLen + chunk > MAX_UNIT) {
                        h264FuLen = 0
                        return
                    }
                    System.arraycopy(data, i, h264Fu, h264FuLen, chunk)
                    h264FuLen += chunk
                    i = end
                    if (stop && h264FuLen > 0) {
                        val out = ByteArray(h264FuLen + 5)
                        out[0] = START_CODE_A
                        out[1] = 0
                        out[2] = 0
                        out[3] = 1
                        out[4] = h264FuHeader.toByte()
                        System.arraycopy(h264Fu, 0, out, 5, h264FuLen)
                        h264FuLen = 0
                        appendToAu(out, 0, out.size)
                    }
                }
                in 1..23 -> {
                    emitH264(data, i, end - i)
                    i = end
                }
                else -> return
            }
        }
    }

    private fun emitH264(data: ByteArray, offset: Int, length: Int) {
        val out = ByteArray(length + 4)
        out[0] = START_CODE_A
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(data, offset, out, 4, length)
        appendToAu(out, 0, out.size)
    }

    private fun handleHevc(data: ByteArray, payloadOffset: Int, payloadLen: Int) {
        var i = payloadOffset
        val end = payloadOffset + payloadLen
        if (end - i < 2) return
        val type = (data[i].toInt() and 0x7E) shr 1
        when (type) {
            49 -> {
                if (end - i < 3) return
                val fuHeader = data[i + 2].toInt() and 0xFF
                val start = (fuHeader and 0x80) != 0
                val stop = (fuHeader and 0x40) != 0
                val fuType = fuHeader and 0x3F
                val layerIdBit = data[i].toInt() and 0x01
                val tidByte = data[i + 1].toInt() and 0xFF
                i += 3
                if (start) {
                    hevcFuLen = 0
                    hevcFuType = fuType
                }
                if (hevcFuType == 0) return
                val chunk = end - i
                if (hevcFuLen + chunk > MAX_UNIT) {
                    hevcFuLen = 0
                    return
                }
                System.arraycopy(data, i, hevcFu, hevcFuLen, chunk)
                hevcFuLen += chunk
                i = end
                if (stop && hevcFuLen > 0) {
                    val out = ByteArray(hevcFuLen + 6)
                    out[0] = START_CODE_A
                    out[1] = 0
                    out[2] = 0
                    out[3] = 1
                    out[4] = ((hevcFuType shl 1) or layerIdBit).toByte()
                    out[5] = tidByte.toByte()
                    System.arraycopy(hevcFu, 0, out, 6, hevcFuLen)
                    hevcFuLen = 0
                    appendToAu(out, 0, out.size)
                }
            }
            48 -> {
                i += 2
                while (i + 3 <= end) {
                    val len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                    i += 2
                    if (i + len > end) return
                    emitHevc(data, i, len)
                    i += len
                }
            }
            else -> emitHevc(data, i, end - i)
        }
    }

    private fun emitHevc(data: ByteArray, offset: Int, length: Int) {
        if (emitCount < 8) {
            val t = (data[offset].toInt() and 0x7E) shr 1
            val hex = data.copyOfRange(offset, minOf(offset + 16, offset + length))
                .joinToString("") { String.format("%02X", it) }
            Log.i(TAG, "emit-hevc type=$t len=$length head=$hex")
            emitCount++
        }
        val out = ByteArray(length + 4)
        out[0] = START_CODE_A
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(data, offset, out, 4, length)
        appendToAu(out, 0, out.size)
    }

    fun handleTs(data: ByteArray, payloadOffset: Int, payloadLen: Int) {
        var i = payloadOffset
        val end = payloadOffset + payloadLen
        while (i + 188 <= end) {
            if ((data[i].toInt() and 0xFF) != 0x47) {
                i++
                continue
            }
            val pid = (((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF))
            val pusi = (data[i + 1].toInt() and 0x40) != 0
            var head = 4
            if ((data[i + 3].toInt() and 0x20) != 0) {
                head += (data[i + 4].toInt() and 0xFF) + 1
            }
            val payload = i + head
            val payloadEnd = i + 188

            when (pid) {
                0 -> if (pusi && payload + 1 < payloadEnd) {
                    var p = payload + (data[payload].toInt() and 0xFF)
                    if (p + 12 <= payloadEnd) {
                        val sectionLen = (((data[p + 1].toInt() and 0x0F) shl 8) or (data[p + 2].toInt() and 0xFF))
                        val maxP = minOf(payloadEnd, p + 3 + sectionLen)
                        p += 5
                        if (p + 2 <= maxP) {
                            var count = (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)) / 4
                            p += 2
                            while (count-- > 0 && p + 5 <= maxP) {
                                if ((data[p + 1].toInt() and 0x1F) == 0x01) {
                                    tsVideoPid = ((data[p + 3].toInt() and 0x1F) shl 8) or (data[p + 4].toInt() and 0xFF)
                                    tsStreamType = data[p + 2].toInt() and 0xFF
                                }
                                p += 4
                            }
                        }
                    }
                }
                tsVideoPid -> if (payload < payloadEnd) {
                    if (pusi) {
                        if (tsPesLen > 0) flushPes()
                        if (payload + 1 < payloadEnd) {
                            val pesStart = payload + 1
                            if (pesStart + 6 <= payloadEnd) {
                                var p = pesStart
                                val prefixOk =
                                    data[p].toInt() == 0 && (data[p + 1].toInt() and 0xFF) == 0 &&
                                        (data[p + 2].toInt() and 0xFF) == 1
                                if (prefixOk) {
                                    p += 3
                                    val streamId = data[p].toInt() and 0xFF
                                    if (streamId != 0xBC) {
                                        p += 2
                                        val pesLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                                        p += 2
                                        val pesHdrLen = data[p].toInt() and 0xFF
                                        p += 1 + pesHdrLen
                                        tsPesLen = 0
                                        if (payloadEnd - p > 0) {
                                            System.arraycopy(data, p, tsPes, 0, payloadEnd - p)
                                            tsPesLen = payloadEnd - p
                                        }
                                    }
                                }
                            }
                        }
                    } else if (tsPesLen > 0) {
                        if (tsPesLen + (payloadEnd - payload) <= tsPes.size) {
                            System.arraycopy(data, payload, tsPes, tsPesLen, payloadEnd - payload)
                            tsPesLen += payloadEnd - payload
                        } else {
                            tsPesLen = 0
                        }
                    }
                }
            }
            i += 188
        }
    }

    private fun flushPes() {
        if (tsPesLen < 4) {
            tsPesLen = 0
            return
        }
        val isH265 = tsStreamType == 0x24
        appendToAu(tsPes, 0, tsPesLen)
        flushAu()
        tsPesLen = 0
    }
}