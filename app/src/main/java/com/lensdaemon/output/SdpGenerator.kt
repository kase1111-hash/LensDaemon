package com.lensdaemon.output

import android.util.Base64
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import timber.log.Timber
import java.net.InetAddress

/**
 * SDP (Session Description Protocol) generator for RTSP streaming
 * RFC 4566 - SDP: Session Description Protocol
 */
class SdpGenerator {

    companion object {
        private const val TAG = "SdpGenerator"

        // SDP version
        private const val SDP_VERSION = "0"

        // Media type
        private const val MEDIA_VIDEO = "video"

        // RTP payload types for dynamic payloads (96-127)
        const val PAYLOAD_TYPE_H264 = 96
        const val PAYLOAD_TYPE_H265 = 97

        // Clock rate for video (90kHz as per RTP spec)
        const val VIDEO_CLOCK_RATE = 90000
    }

    /**
     * Generate SDP for H.264 video stream
     *
     * @param serverAddress Server IP address
     * @param serverPort RTP port
     * @param sessionName Session name
     * @param config Encoder configuration
     * @param sps SPS NAL unit (without start code)
     * @param pps PPS NAL unit (without start code)
     * @param trackId Track identifier
     */
    fun generateH264Sdp(
        serverAddress: String,
        serverPort: Int,
        sessionName: String,
        config: EncoderConfig,
        sps: ByteArray?,
        pps: ByteArray?,
        trackId: String = "video"
    ): String {
        val sb = StringBuilder()

        // Protocol version
        sb.appendLine("v=$SDP_VERSION")

        // Origin: username session-id version network-type address-type address
        val sessionId = System.currentTimeMillis()
        sb.appendLine("o=- $sessionId $sessionId IN IP4 $serverAddress")

        // Session name
        sb.appendLine("s=$sessionName")

        // Connection information
        sb.appendLine("c=IN IP4 $serverAddress")

        // Time (0 0 means session is permanent)
        sb.appendLine("t=0 0")

        // Attributes
        sb.appendLine("a=tool:${RtspConstants.SERVER_NAME}")
        sb.appendLine("a=type:broadcast")
        sb.appendLine("a=control:*")
        sb.appendLine("a=range:npt=0-")

        // Media description: media port protocol format
        sb.appendLine("m=$MEDIA_VIDEO $serverPort RTP/AVP $PAYLOAD_TYPE_H264")

        // Bandwidth (in kbps)
        sb.appendLine("b=AS:${config.bitrateBps / 1000}")

        // RTP map: payload-type encoding-name/clock-rate
        sb.appendLine("a=rtpmap:$PAYLOAD_TYPE_H264 H264/$VIDEO_CLOCK_RATE")

        // Format parameters (fmtp)
        val fmtp = buildH264Fmtp(config, sps, pps)
        sb.appendLine("a=fmtp:$PAYLOAD_TYPE_H264 $fmtp")

        // Frame rate
        sb.appendLine("a=framerate:${config.frameRate}")

        // Control URL for this track
        sb.appendLine("a=control:$trackId")

        return sb.toString()
    }

    /**
     * Generate SDP for H.265/HEVC video stream
     *
     * @param serverAddress Server IP address
     * @param serverPort RTP port
     * @param sessionName Session name
     * @param config Encoder configuration
     * @param vps VPS NAL unit (without start code)
     * @param sps SPS NAL unit (without start code)
     * @param pps PPS NAL unit (without start code)
     * @param trackId Track identifier
     */
    fun generateH265Sdp(
        serverAddress: String,
        serverPort: Int,
        sessionName: String,
        config: EncoderConfig,
        vps: ByteArray?,
        sps: ByteArray?,
        pps: ByteArray?,
        trackId: String = "video"
    ): String {
        val sb = StringBuilder()

        // Protocol version
        sb.appendLine("v=$SDP_VERSION")

        // Origin
        val sessionId = System.currentTimeMillis()
        sb.appendLine("o=- $sessionId $sessionId IN IP4 $serverAddress")

        // Session name
        sb.appendLine("s=$sessionName")

        // Connection information
        sb.appendLine("c=IN IP4 $serverAddress")

        // Time
        sb.appendLine("t=0 0")

        // Attributes
        sb.appendLine("a=tool:${RtspConstants.SERVER_NAME}")
        sb.appendLine("a=type:broadcast")
        sb.appendLine("a=control:*")
        sb.appendLine("a=range:npt=0-")

        // Media description
        sb.appendLine("m=$MEDIA_VIDEO $serverPort RTP/AVP $PAYLOAD_TYPE_H265")

        // Bandwidth
        sb.appendLine("b=AS:${config.bitrateBps / 1000}")

        // RTP map for H.265
        sb.appendLine("a=rtpmap:$PAYLOAD_TYPE_H265 H265/$VIDEO_CLOCK_RATE")

        // Format parameters for H.265
        val fmtp = buildH265Fmtp(config, vps, sps, pps)
        sb.appendLine("a=fmtp:$PAYLOAD_TYPE_H265 $fmtp")

        // Frame rate
        sb.appendLine("a=framerate:${config.frameRate}")

        // Control URL
        sb.appendLine("a=control:$trackId")

        return sb.toString()
    }

    /**
     * Generate SDP based on encoder configuration
     */
    fun generateSdp(
        serverAddress: String,
        serverPort: Int,
        sessionName: String,
        config: EncoderConfig,
        vps: ByteArray? = null,
        sps: ByteArray? = null,
        pps: ByteArray? = null,
        trackId: String = "video"
    ): String {
        return when (config.codec) {
            VideoCodec.H264 -> generateH264Sdp(
                serverAddress, serverPort, sessionName, config, sps, pps, trackId
            )
            VideoCodec.H265 -> generateH265Sdp(
                serverAddress, serverPort, sessionName, config, vps, sps, pps, trackId
            )
        }
    }

    /**
     * Build H.264 fmtp (format parameters) string
     * RFC 6184 - RTP Payload Format for H.264 Video
     */
    private fun buildH264Fmtp(
        config: EncoderConfig,
        sps: ByteArray?,
        pps: ByteArray?
    ): String {
        val params = mutableListOf<String>()

        // Packetization mode (1 = non-interleaved mode, required for FU-A)
        params.add("packetization-mode=1")

        // Profile-level-id from SPS
        if (sps != null && sps.isNotEmpty()) {
            // profile-level-id is first 3 bytes of SPS after NAL header
            if (sps.size >= 4) {
                val profileLevelId = String.format(
                    "%02X%02X%02X",
                    sps[1].toInt() and 0xFF,
                    sps[2].toInt() and 0xFF,
                    sps[3].toInt() and 0xFF
                )
                params.add("profile-level-id=$profileLevelId")
            }
        } else {
            // Default profile-level-id based on config
            val profileLevelId = getDefaultH264ProfileLevelId(config)
            params.add("profile-level-id=$profileLevelId")
        }

        // SPS and PPS in base64
        if (sps != null && pps != null) {
            val spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP)
            val ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP)
            params.add("sprop-parameter-sets=$spsBase64,$ppsBase64")
        }

        return params.joinToString("; ")
    }

    /**
     * Build H.265 fmtp (format parameters) string
     * RFC 7798 - RTP Payload Format for HEVC
     */
    private fun buildH265Fmtp(
        config: EncoderConfig,
        vps: ByteArray?,
        sps: ByteArray?,
        pps: ByteArray?
    ): String {
        val params = mutableListOf<String>()

        // VPS, SPS, PPS in base64
        if (vps != null) {
            val vpsBase64 = Base64.encodeToString(vps, Base64.NO_WRAP)
            params.add("sprop-vps=$vpsBase64")
        }

        if (sps != null) {
            val spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP)
            params.add("sprop-sps=$spsBase64")
        }

        if (pps != null) {
            val ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP)
            params.add("sprop-pps=$ppsBase64")
        }

        return params.joinToString("; ")
    }

    /**
     * Get default H.264 profile-level-id based on encoder config
     */
    private fun getDefaultH264ProfileLevelId(config: EncoderConfig): String {
        // Profile byte based on H264Profile
        val profile = when (config.h264Profile) {
            com.lensdaemon.encoder.H264Profile.BASELINE -> 0x42 // Baseline
            com.lensdaemon.encoder.H264Profile.MAIN -> 0x4D     // Main
            com.lensdaemon.encoder.H264Profile.HIGH -> 0x64     // High
        }

        // Constraint set flags (typically 0x00 or E0 for baseline)
        val constraints = when (config.h264Profile) {
            com.lensdaemon.encoder.H264Profile.BASELINE -> 0xE0
            else -> 0x00
        }

        // Level based on resolution and frame rate
        val level = calculateH264Level(config.width, config.height, config.frameRate)

        return String.format("%02X%02X%02X", profile, constraints, level)
    }

    /**
     * Calculate H.264 level based on resolution and frame rate
     */
    private fun calculateH264Level(width: Int, height: Int, frameRate: Int): Int {
        val macroblocks = (width / 16) * (height / 16)
        val mbPerSec = macroblocks * frameRate

        return when {
            // Level 5.1 (4K)
            width > 1920 || mbPerSec > 245760 -> 0x33
            // Level 4.2 (1080p60)
            frameRate > 30 && width >= 1920 -> 0x2A
            // Level 4.1 (1080p30)
            width >= 1920 -> 0x29
            // Level 4.0 (1080p)
            width >= 1280 -> 0x28
            // Level 3.1 (720p)
            width >= 720 -> 0x1F
            // Level 3.0
            else -> 0x1E
        }
    }

    /**
     * Get local IP address
     */
    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                val addresses = netInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get local IP")
            "0.0.0.0"
        }
    }
}
