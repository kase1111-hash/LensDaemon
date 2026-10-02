package com.lensdaemon.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * One media track of an RTSP session, video or audio: the transport its
 * client set up for it (UDP ports, or channels interleaved on the control
 * connection), its RTP and RTCP send paths, and the counts its RTCP sender
 * reports carry.
 */
internal class RtspTrack(
    val kind: Kind,
    val params: TransportParams,
    private val clientAddress: InetAddress,
    /** The RTSP control connection; interleaved data is written to it under its own lock. */
    private val controlOutput: OutputStream?,
    private val onReceiverReports: (List<RtcpParser.ReceiverReport>) -> Unit
) {
    enum class Kind(val trackId: Int) {
        VIDEO(0),
        AUDIO(1)
    }

    private companion object {
        const val TAG = "RtspTrack"
        const val RTCP_RECEIVE_TIMEOUT_MS = 1000
        const val RTCP_BUFFER_BYTES = 512
    }

    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null

    var serverRtpPort = 0
        private set
    var serverRtcpPort = 0
        private set

    /** RTP packets and payload octets sent, for sender reports. */
    var packetsSent = 0L
        private set
    var octetsSent = 0L
        private set

    /** When the last sender report went out (0 = never). */
    var lastSenderReportMs = 0L

    val isInterleaved: Boolean get() = params.mode == RtspTransportMode.TCP_INTERLEAVED

    /**
     * Bind UDP sockets for a UDP client and listen for its RTCP receiver
     * reports. Nothing to do for an interleaved client.
     */
    fun open(scope: CoroutineScope): Boolean {
        if (isInterleaved) return true
        return try {
            val rtp = DatagramSocket()
            rtpSocket = rtp
            serverRtpPort = rtp.localPort
            serverRtcpPort = serverRtpPort + 1
            try {
                rtcpSocket = DatagramSocket(serverRtcpPort).also { it.soTimeout = RTCP_RECEIVE_TIMEOUT_MS }
                scope.launch { listenForReceiverReports() }
            } catch (e: SocketException) {
                Timber.tag(TAG).w("Could not bind RTCP port $serverRtcpPort: ${e.message}")
            }
            Timber.tag(TAG).i("$kind UDP transport: server port $serverRtpPort, client ${params.clientRtpPort}")
            true
        } catch (e: SocketException) {
            Timber.tag(TAG).e(e, "Failed to set up UDP transport for $kind")
            false
        }
    }

    /**
     * Send one RTP packet. Over TCP a failed write means the connection is
     * gone, so the IOException propagates for the session to close.
     */
    fun sendRtp(packet: RtpPacket) {
        val data = packet.toByteArray()
        if (isInterleaved) {
            writeInterleaved(data, params.interleavedRtpChannel)
        } else {
            sendUdp(rtpSocket, data, params.clientRtpPort)
        }
        packetsSent++
        octetsSent += packet.payload.size
    }

    /** Send an RTCP packet (a sender report) to the client. */
    fun sendRtcp(data: ByteArray) {
        if (isInterleaved) {
            writeInterleaved(data, params.interleavedRtcpChannel)
        } else {
            sendUdp(rtcpSocket ?: rtpSocket, data, params.clientRtcpPort)
        }
    }

    fun close() {
        rtcpSocket?.close()
        rtpSocket?.close()
    }

    private fun sendUdp(socket: DatagramSocket?, data: ByteArray, port: Int) {
        try {
            socket?.send(DatagramPacket(data, data.size, clientAddress, port))
        } catch (e: IOException) {
            Timber.tag(TAG).w("Error sending UDP packet to port $port: ${e.message}")
        }
    }

    /** Interleaved binary frame on the control connection: '$', channel, 16-bit length, data. */
    private fun writeInterleaved(data: ByteArray, channel: Int) {
        val out = controlOutput ?: return
        val header = byteArrayOf(
            '$'.code.toByte(),
            channel.toByte(),
            (data.size shr 8).toByte(),
            (data.size and 0xFF).toByte()
        )
        // RTSP responses share this stream; the lock keeps them out of RTP frames
        synchronized(out) {
            out.write(header)
            out.write(data)
            out.flush()
        }
    }

    private fun listenForReceiverReports() {
        val socket = rtcpSocket ?: return
        val buffer = ByteArray(RTCP_BUFFER_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!socket.isClosed) {
            try {
                socket.receive(packet)
                onReceiverReports(RtcpParser.parseReceiverReports(buffer.copyOf(packet.length)))
            } catch (expected: SocketTimeoutException) {
                // Loop and check whether the socket was closed
            } catch (e: IOException) {
                if (!socket.isClosed) Timber.tag(TAG).w("RTCP listener stopped: ${e.message}")
                break
            }
        }
    }
}
