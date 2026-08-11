package com.mira.sink.codec

data class VideoDimensions(val width: Int, val height: Int)

object SpsParser {

    fun parseH264Sps(sps: ByteArray): VideoDimensions? {
        var pos = 0

        fun readBit(): Int {
            val byte = sps[pos / 8].toInt() and 0xFF
            val bit = (byte shr (7 - (pos % 8))) and 1
            pos++
            return bit
        }

        fun readBits(n: Int): Long {
            var v = 0L
            for (i in 0 until n) {
                v = (v shl 1) or (readBit().toLong())
            }
            return v
        }

        fun readUe(): Long {
            var zeros = 0
            while (readBit() == 0) zeros++
            if (zeros == 0) return 0L
            return (1L shl zeros) - 1L + readBits(zeros)
        }

        try {
            readBits(8) // nal header
            readBits(8) // profile
            readBits(8) // constraints
            readBits(8) // level idc
            readUe() // sps id

            val profileIdc = sps[1].toInt() and 0xFF
            var chromaSubWidthC = 2
            var chromaSubHeightC = 2
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormat = readUe().toInt()
                if (chromaFormat == 3) readBit() // separate colour plane
                chromaSubWidthC = when (chromaFormat) {
                    0 -> 1
                    1 -> 2
                    2 -> 2
                    else -> 1
                }
                chromaSubHeightC = when (chromaFormat) {
                    0 -> 1
                    1 -> 2
                    2 -> 1
                    else -> 1
                }
            }

            readUe() // log2_max_frame_num_minus4
            when (readUe().toInt()) {
                0 -> readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    readBit()
                    readUe()
                    readUe()
                    val count = readUe().toInt()
                    for (i in 0 until count) readUe()
                }
            }
            readUe() // max_num_ref_frames
            readBit() // gaps_in_frame_num_value_allowed_flag
            val widthMbs = readUe().toInt() + 1
            val heightMapUnits = readUe().toInt() + 1
            val frameMbsOnly = readBit()
            if (frameMbsOnly == 0) readBit() // mb_adaptive_frame_field_flag
            readBit() // direct_8x8_inference

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (readBit() == 1) { // frame_cropping_flag
                cropLeft = readUe().toInt()
                cropRight = readUe().toInt()
                cropTop = readUe().toInt()
                cropBottom = readUe().toInt()
            }

            val width = widthMbs * 16 - (cropLeft + cropRight) * chromaSubWidthC
            var height = heightMapUnits * 16 * (if (frameMbsOnly == 1) 1 else 2)
            height -= (cropTop + cropBottom) * chromaSubHeightC
            if (height < 0) height = 0
            if (width <= 0) return null
            return VideoDimensions(width, height)
        } catch (t: Throwable) {
            return null
        }
    }

    fun parseHevcSps(sps: ByteArray): VideoDimensions? {
        var pos = 0

        fun readBit(): Int {
            val byte = sps[pos / 8].toInt() and 0xFF
            val bit = (byte shr (7 - (pos % 8))) and 1
            pos++
            return bit
        }

        fun readBits(n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = (v shl 1) or readBit().toLong()
            return v
        }

        fun readUe(): Long {
            var zeros = 0
            while (readBit() == 0) zeros++
            if (zeros == 0) return 0L
            return (1L shl zeros) - 1L + readBits(zeros)
        }

        try {
            readBits(16) // NAL header (2 bytes: forbidden+type+layer+id)
            readBits(4) // sps_video_parameter_set_id
            val maxSubLayers = readBits(3).toInt() + 1
            readBits(1) // sps_temporal_id_nesting_flag

            readBits(2) // profile_space
            readBits(1) // tier
            readBits(5) // profile_idc
            readBits(32) // profile_compatibility_flags
            readBits(48) // constraint flags + reserved
            readBits(8) // level_idc
            if (maxSubLayers > 1) {
                val flags = IntArray(maxSubLayers - 1) { readBit() }
                readBits(2) // reserved
                for (i in 0 until maxSubLayers - 1) {
                    if (flags[i] == 1) readBits(88)
                }
            } else {
                readBits(2) // reserved_zero_2bits
            }

            readUe() // sps_seq_parameter_set_id
            val chromaFormat = readUe().toInt()
            if (chromaFormat == 3) readBit() // separate_colour_plane

            val width = readUe().toInt()
            var height = readUe().toInt()

            val subWidthC = when (chromaFormat) {
                0 -> 1; 1 -> 2; 2 -> 2; else -> 1
            }
            val subHeightC = when (chromaFormat) {
                0 -> 1; 1 -> 2; else -> 1
            }

            if (readBit() == 1) { // conformance_window_flag
                val left = readUe().toInt()
                val right = readUe().toInt()
                val top = readUe().toInt()
                val bottom = readUe().toInt()
                if (width - (left + right) * subWidthC <= 0 || height - (top + bottom) * subHeightC <= 0) {
                    return VideoDimensions(width, height)
                }
                return VideoDimensions(
                    width - (left + right) * subWidthC,
                    height - (top + bottom) * subHeightC
                )
            }
            if (width <= 0 || height <= 0) return null
            return VideoDimensions(width, height)
        } catch (t: Throwable) {
            return null
        }
    }
}