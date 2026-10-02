package com.lensdaemon.encoder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The clock camera frames, and therefore video presentation times, are
 * stamped on (CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE). Audio is
 * stamped on the same clock so players can line the two up: the two clocks
 * drift apart by however long the phone has spent in deep sleep.
 */
enum class MediaClock {
    /** CLOCK_MONOTONIC: System.nanoTime(), AudioTimestamp.TIMEBASE_MONOTONIC. */
    MONOTONIC,

    /** CLOCK_BOOTTIME: SystemClock.elapsedRealtimeNanos(), AudioTimestamp.TIMEBASE_BOOTTIME. */
    BOOTTIME;

    fun nowNanos(): Long = if (this == BOOTTIME) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    fun nowUs(): Long = nowNanos() / 1000

    val audioTimebase: Int
        get() = if (this == BOOTTIME) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
}

/**
 * A source of 16-bit little-endian PCM for [AudioEncoder].
 */
interface PcmSource {
    val sampleRate: Int
    val channelCount: Int

    /** Start capturing. False if this source cannot (no microphone, no permission). */
    fun start(): Boolean

    /**
     * Read up to [size] bytes into [buffer], blocking until data is
     * available. Returns the bytes read, or a negative value on error.
     */
    fun read(buffer: ByteArray, size: Int): Int

    /**
     * Presentation time of the frame at [framePosition] (frames since
     * start), given that [framesJustRead] frames have just been read.
     */
    fun presentationTimeUs(framePosition: Long, framesJustRead: Int): Long

    /** Stop capturing and release the source. Unblocks a pending [read]. */
    fun close()
}

/**
 * The phone's microphone. Prefers the camcorder source (tuned for video,
 * stereo where the phone has the microphones for it) and falls back to the
 * plain microphone and to mono.
 */
class MicrophonePcmSource(
    private val requested: AudioConfig,
    private val clock: MediaClock
) : PcmSource {

    companion object {
        private const val TAG = "MicrophonePcmSource"
        private const val BYTES_PER_SAMPLE = 2

        /** Buffer at least this many AAC frames in AudioRecord so a scheduling hiccup loses nothing. */
        private const val BUFFERED_AAC_FRAMES = 8
    }

    private var record: AudioRecord? = null
    private val timestamp = AudioTimestamp()

    override val sampleRate: Int = requested.sampleRate
    override var channelCount: Int = requested.channelCount
        private set

    /** Caller must hold RECORD_AUDIO; without it AudioRecord fails to initialize and this returns false. */
    override fun start(): Boolean {
        for (channels in listOf(requested.channelCount, 1).distinct()) {
            for (source in listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
                val started = openRecording(source, channels) ?: continue
                record = started
                channelCount = channels
                Timber.tag(TAG).i("Recording audio: source $source, $sampleRate Hz, $channels channel(s)")
                return true
            }
        }
        Timber.tag(TAG).w("No microphone could be opened")
        return false
    }

    /** An AudioRecord for [source] and [channels] that is recording, or null. */
    private fun openRecording(source: Int, channels: Int): AudioRecord? {
        val candidate = create(source, channels) ?: return null
        try {
            candidate.startRecording()
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).w(e, "Audio source $source would not start")
        }
        if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            candidate.release()
            return null
        }
        return candidate
    }

    @SuppressLint("MissingPermission")
    private fun create(source: Int, channels: Int): AudioRecord? {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer * 2, Aac.SAMPLES_PER_FRAME * channels * BYTES_PER_SAMPLE * BUFFERED_AAC_FRAMES)
        val candidate = try {
            AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w(e, "Audio source $source rejected $sampleRate Hz x $channels")
            return null
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Microphone permission missing")
            return null
        }
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            return null
        }
        return candidate
    }

    override fun read(buffer: ByteArray, size: Int): Int = record?.read(buffer, 0, size) ?: -1

    override fun presentationTimeUs(framePosition: Long, framesJustRead: Int): Long {
        val current = record
        // AudioRecord's timestamp maps a frame position to the capture clock;
        // extrapolate from it at the sample rate.
        if (current != null && current.getTimestamp(timestamp, clock.audioTimebase) == AudioRecord.SUCCESS) {
            val offsetNanos = (framePosition - timestamp.framePosition) * 1_000_000_000L / sampleRate
            return (timestamp.nanoTime + offsetNanos) / 1000
        }
        // No timestamp yet (the first reads): the frames just read ended about now
        return clock.nowUs() - framesJustRead * 1_000_000L / sampleRate
    }

    override fun close() {
        val current = record ?: return
        record = null
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).w(e, "AudioRecord was not recording")
        }
        current.release()
    }
}

/**
 * Encodes PCM from a [PcmSource] to AAC-LC on a dedicated thread and hands
 * each frame to [onFrame]: first the AudioSpecificConfig (as a config frame),
 * then one raw AAC frame per 1024 samples, stamped on the source's clock.
 * [onFrame] runs on the encoder thread and must not throw or block for long.
 */
class AudioEncoder(
    private val source: PcmSource,
    private val bitrateBps: Int = AudioConfig().bitrateBps,
    private val onFrame: (EncodedAudioFrame) -> Unit
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val BYTES_PER_SAMPLE = 2
        private const val STOP_TIMEOUT_MS = 2_000L
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val framesEncoded = AtomicLong(0)

    /** The settings actually in use (the source may have fallen back to mono). */
    val config: AudioConfig
        get() = AudioConfig(source.sampleRate, source.channelCount, bitrateBps)

    val isRunning: Boolean get() = running

    fun getFramesEncoded(): Long = framesEncoded.get()

    /**
     * Start capturing and encoding. Returns false, leaving nothing running,
     * if the microphone or the AAC encoder is unavailable.
     */
    fun start(): Boolean {
        if (running) return true
        if (!Aac.isSupportedSampleRate(source.sampleRate)) {
            Timber.tag(TAG).e("${source.sampleRate} Hz is not an AAC sampling frequency")
            return false
        }
        if (!source.start()) return false

        val codec = try {
            createCodec()
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "No AAC encoder")
            null
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "AAC encoder refused the format")
            null
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).e(e, "AAC encoder refused the format")
            null
        }
        if (codec == null) {
            source.close()
            return false
        }

        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            encodeLoop(codec)
        }, "AudioEncoder").apply { start() }
        Timber.tag(TAG).i("AAC encoding started: ${config.sampleRate} Hz, ${config.channelCount} ch, $bitrateBps bps")
        return true
    }

    /** Stop capturing and encoding and release everything. Safe to call more than once. */
    fun stop() {
        running = false
        source.close()
        try {
            thread?.join(STOP_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        Timber.tag(TAG).i("AAC encoding stopped after ${framesEncoded.get()} frames")
    }

    private fun createCodec(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, source.sampleRate, source.channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Aac.SAMPLES_PER_FRAME * source.channelCount * BYTES_PER_SAMPLE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        }
        return codec
    }

    /** Position of the next PCM frame to be read, and the last presentation time queued. */
    private var framePosition = 0L
    private var lastPtsUs = 0L

    /**
     * Feed one AAC frame's worth of PCM per input buffer and drain whatever
     * the encoder has produced, until stopped. Owns and releases [codec].
     */
    private fun encodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(Aac.SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * source.channelCount)
        framePosition = 0L
        lastPtsUs = 0L
        try {
            while (running && feedInput(codec, chunk)) {
                drain(codec, info)
            }
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "AAC encoder failed")
        } finally {
            running = false
            // Free the microphone even when the loop ended on an error, so a
            // new encoder can open it
            source.close()
            try {
                codec.stop()
            } catch (e: IllegalStateException) {
                Timber.tag(TAG).w(e, "AAC encoder already stopped")
            }
            codec.release()
        }
    }

    /**
     * Queue one AAC frame's worth of PCM if the encoder has an input buffer
     * free. Returns false once the source has failed.
     */
    private fun feedInput(codec: MediaCodec, chunk: ByteArray): Boolean {
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return true

        val input = codec.getInputBuffer(inputIndex)
        val read = if (input != null) source.read(chunk, minOf(chunk.size, input.capacity())) else 0
        if (read <= 0 || input == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, lastPtsUs, 0)
            if (read < 0 && running) Timber.tag(TAG).e("Microphone read failed ($read)")
            return read >= 0
        }

        val frames = read / (BYTES_PER_SAMPLE * source.channelCount)
        // Presentation times must never go backwards, even when the source
        // switches from its estimate to its real timestamps
        val ptsUs = maxOf(source.presentationTimeUs(framePosition, frames), lastPtsUs + 1)
        lastPtsUs = ptsUs
        input.clear()
        input.put(chunk, 0, read)
        codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
        framePosition += frames
        return true
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) return // try again later, or a format/buffers change
            val output: ByteBuffer? = codec.getOutputBuffer(outputIndex)
            if (output != null && info.size > 0) {
                val data = ByteArray(info.size)
                output.position(info.offset)
                output.get(data)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig) framesEncoded.incrementAndGet()
                onFrame(EncodedAudioFrame(data, info.presentationTimeUs, isConfig))
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }
}

/**
 * The MediaMuxer track format for this AAC stream: what the encoder's own
 * output format carries, built up front so a recording segment can add its
 * audio track before the encoder has produced anything.
 */
fun AudioConfig.toMuxerFormat(): MediaFormat =
    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        setByteBuffer("csd-0", ByteBuffer.wrap(Aac.audioSpecificConfig(sampleRate, channelCount)))
    }
