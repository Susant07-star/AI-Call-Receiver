package com.example.aicallassistant

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV file writer.
 * Writes a standard RIFF/WAVE header followed by 16-bit PCM audio data.
 * No external libraries required.
 */
object WavWriter {

    /**
     * Write PCM samples to a WAV file.
     *
     * @param file        Destination file (will be created/overwritten).
     * @param pcmData     Raw 16-bit PCM samples in little-endian byte order.
     * @param sampleRate  Sample rate in Hz (e.g. 16000, 44100).
     * @param channels    Number of channels (1 = mono, 2 = stereo).
     * @param bitDepth    Bits per sample (16).
     * @throws IOException on any I/O failure.
     */
    @Throws(IOException::class)
    fun write(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitDepth: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitDepth / 8
        val blockAlign = (channels * bitDepth / 8)
        val dataChunkSize = pcmData.size
        val riffChunkSize = 36 + dataChunkSize   // 4 (WAVE) + 24 (fmt chunk) + 8 (data header)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF chunk descriptor
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt sub-chunk
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                       // sub-chunk size (PCM = 16)
            putShort(1)                      // audio format: PCM = 1
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitDepth.toShort())

            // data sub-chunk
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataChunkSize)
        }

        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(pcmData)
        }
    }

    /**
     * Compute Root-Mean-Square amplitude of 16-bit PCM bytes.
     * Returns a value in [0.0, 1.0] where 0.0 = silence, 1.0 = max amplitude.
     */
    fun computeRms(pcmBytes: ByteArray): Double {
        if (pcmBytes.size < 2) return 0.0
        var sumOfSquares = 0.0
        var sampleCount = 0
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 2) {
            val sample = buf.short.toDouble()
            sumOfSquares += sample * sample
            sampleCount++
        }
        if (sampleCount == 0) return 0.0
        val rms = Math.sqrt(sumOfSquares / sampleCount)
        return (rms / 32768.0).coerceIn(0.0, 1.0)
    }

    /**
     * Classify what the recording likely contains based on RMS and contextual analysis.
     * Returns a human-readable classification string.
     */
    fun classifyContent(rms: Double, isCallActive: Boolean): String {
        return when {
            rms < 0.001 -> "D) Silence — no audio captured (source blocked or muted)"
            rms < 0.01  -> "D) Near-silence — very low energy, likely blocked source"
            rms < 0.05  -> if (isCallActive)
                "A) Likely microphone only — low amplitude consistent with local voice"
            else
                "A) Ambient noise from microphone"
            rms < 0.20  -> "A) or C) Moderate amplitude — microphone audio captured; " +
                "caller audio cannot be confirmed from amplitude alone"
            else         -> "A) or C) High amplitude — strong audio captured; " +
                "manual listening required to determine source"
        }
    }
}
