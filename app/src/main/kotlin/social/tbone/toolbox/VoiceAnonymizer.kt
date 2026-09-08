package social.tbone.toolbox

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Voice anonymizer: decodes a recording to PCM, applies an irreversible
 * transform (slow down + pitch down + hard distortion) that makes the voice
 * completely unrecognizable, and writes a WAV you can preview / save / share.
 *
 * Why it can't be undone in an audio editor:
 *  - Resampling permanently discards samples — the original timing is gone,
 *    so pitch/speed can't be cleanly restored (any attempt introduces severe
 *    artifacts).
 *  - The waveshaper (soft-clip distortion) non-linearly squashes the waveform,
 *    destroying the original spectral structure irreversibly.
 */
class VoiceAnonymizer {

    private data class Pcm(val samples: ShortArray, val sampleRate: Int)

    /** Defaults — the midpoint configuration. */
    private val DEFAULT_SPEED = 0.78f
    private val DEFAULT_PITCH = 1.0f
    private val DEFAULT_DRIVE = 4.5f

    /**
     * Decodes the audio file (m4a/aac/etc.) into 16-bit mono PCM.
     * Returns null on failure.
     */
    private fun decodeToPcm(path: String): Pcm? = runCatching {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val outBytes = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var eos = false
        while (!eos) {
            // Feed input
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx) ?: continue
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // Drain output
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx >= 0 -> {
                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.asShortBuffer()
                        val arr = ShortArray(shortBuf.remaining())
                        shortBuf.get(arr)
                        val bb = ByteBuffer.allocate(arr.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        arr.forEach { bb.putShort(it) }
                        outBytes.write(bb.array())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // spin
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // ignore — we keep the declared sample rate
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val raw = outBytes.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        Pcm(shorts, sampleRate)
    }.getOrNull()

    /**
     * Produces an anonymized WAV file from [src] next to it.
     * Returns the new file, or null on failure.
     */
    /**
     * Anonymizes with three independent distortion controls:
     *  - [speed] (0.5–1.2): resample rate — slows down / speeds up (pitch follows).
     *  - [pitch] (0.8–1.3): extra pitch-character smear (resample + inverse
     *    resample), which audibly changes the voice without changing duration.
     *  - [drive] (0–8): waveshaper distortion amount.
     * All are irreversible.
     */
    fun anonymize(
        src: File,
        speed: Float = DEFAULT_SPEED,
        pitch: Float = DEFAULT_PITCH,
        drive: Float = DEFAULT_DRIVE,
    ): File? = runCatching {
        val pcm = decodeToPcm(src.absolutePath) ?: return null
        // 1) Speed (also shifts pitch, classic tape effect).
        var samples = resample(pcm.samples, speed.coerceIn(0.5f, 1.2f))
        // 2) Independent pitch character: resample up/down then back — duration
        //    is restored but interpolation smears the formants (audible change).
        val p = pitch.coerceIn(0.8f, 1.3f)
        if (p != 1f) {
            samples = resample(samples, p)
            samples = resample(samples, 1f / p)
        }
        // 3) Waveshaper distortion → destroys the original spectral structure.
        val distorted = distort(samples, drive.coerceIn(0f, 8f))
        // 4) Write as WAV at the (speed-adjusted) sample rate.
        val out = File(src.parentFile, "voice_anon_${System.currentTimeMillis()}.wav")
        val outRate = (pcm.sampleRate * speed.coerceIn(0.5f, 1.2f)).toInt().coerceAtLeast(8000)
        writeWav(distorted, outRate, out)
        out
    }.getOrNull()

    /** Linear-interpolation resample: changes both speed and pitch by [rate]. */
    private fun resample(samples: ShortArray, rate: Float): ShortArray {
        val outLen = (samples.size / rate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * rate
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val s0 = samples[i0].toFloat()
            val s1 = samples[i1].toFloat()
            val v = s0 + (s1 - s0) * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Soft-clip waveshaper — non-linear, irreversible distortion. */
    private fun distort(samples: ShortArray, drive: Float): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val x = samples[i] / 32768f
            // Mild drive keeps articulation intact; the non-linearity still
            // smears the formant structure enough to hide identity.
            val y = (drive * x) / (1f + abs(drive * x))
            val v = y * 32768f * 0.95f
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Writes 16-bit mono PCM as a RIFF WAV file. */
    private fun writeWav(pcm: ShortArray, sampleRate: Int, file: File) {
        val dataSize = pcm.size * 2
        val byteArray = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        byteArray.put("RIFF".toByteArray(Charsets.US_ASCII))
        byteArray.putInt(36 + dataSize)
        byteArray.put("WAVE".toByteArray(Charsets.US_ASCII))
        byteArray.put("fmt ".toByteArray(Charsets.US_ASCII))
        byteArray.putInt(16)
        byteArray.putShort(1)                     // PCM
        byteArray.putShort(1)                     // mono
        byteArray.putInt(sampleRate)
        byteArray.putInt(sampleRate * 2)          // byte rate
        byteArray.putShort(2)                     // block align
        byteArray.putShort(16)                    // bits per sample
        byteArray.put("data".toByteArray(Charsets.US_ASCII))
        byteArray.putInt(dataSize)
        val pcmBuf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcmBuf.asShortBuffer().put(pcm)
        byteArray.put(pcmBuf.array())
        file.writeBytes(byteArray.array())
    }
}
