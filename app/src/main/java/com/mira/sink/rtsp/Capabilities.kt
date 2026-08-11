package com.mira.sink.rtsp

object Capabilities {

    const val RTSP_PORT = 7236

    const val SINK_IP_PLACEHOLDER = "192.168.49.1"
    const val SESSION_ID = "mira_session_001"

    const val WFD_VIDEO_FORMATS_H264 =
        "1 1 0 0 0 00000001 00000000 00000000 00000000 00000000 00000000 0 0 00 0000 0 1 1 0 0000 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

    const val WFD_VIDEO_FORMATS_H265 =
        "0 1 0 0 0 00000001 00000000 00000000 00000000 00000000 00000000 0 0 00 0000 0 1 1 0 0000 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

    const val WFD_VIDEO_FORMATS_BOTH =
        "$WFD_VIDEO_FORMATS_H264, $WFD_VIDEO_FORMATS_H265"

    const val WFD_AUDIO_CODECS = "LPCM 00000003 00, AAC 00000003 00"

    const val WFD_UIBC_CAPABILITY_NONE = "none"

    const val WFD_3D_VIDEO_FORMATS = "none"
    const val WFD_CONTENT_PROTECTION = "none"
    const val WFD_CONNECTOR_TYPE = "05"
    const val WFD_COUPLED_SINK_INFO = "none"
    const val WFD_STANDBY_RESUME = "none"
    const val WFD_IDR_REQUEST = "none"

    const val UIBC_PORT = 7237

    const val DEFAULT_VIDEO_FORMATS = WFD_VIDEO_FORMATS_BOTH

    fun uibcCapability(port: Int): String =
        "port=$port method=2 event=00000018"

    fun videoFormatsPrefixed(): String = "wfd_video_formats: $WFD_VIDEO_FORMATS_BOTH"

    fun audioCodecsPrefixed(): String = "wfd_audio_codecs: $WFD_AUDIO_CODECS"

    fun uibcPrefixed(port: Int): String = "wfd_uibc_capability: ${uibcCapability(port)}"
}