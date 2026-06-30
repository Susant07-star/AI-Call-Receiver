package com.example.aicallassistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Experiment 2 – Audio Diagnostics Engine
 *
 * Probes every officially-supported Android audio source available to a standard
 * (non-system, non-privileged) application during an active cellular phone call.
 *
 * Design decisions:
 *  - Runs entirely on a dedicated background thread; never blocks the UI thread.
 *  - Reports results incrementally via [onProgress] callback.
 *  - Saves one 10-second WAV file using the best source that succeeds.
 *  - Does NOT use reflection, hidden APIs, root access, or unsupported hacks.
 */
class AudioDiagnostics(
    private val context: Context,
    private val onProgress: (String) -> Unit,
    private val onComplete: (DiagnosticsReport) -> Unit
) {

    companion object {
        private const val TAG = "AI_CALL_EXP2"

        private const val SAMPLE_RATE = 16_000          // Hz — good for voice; Gemini Live uses 16 kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RECORD_DURATION_MS = 10_000L  // 10 seconds
        private const val BYTES_PER_SAMPLE = 2          // 16-bit = 2 bytes
    }

    // All AudioSource values we probe in order
    private val audioSourcesToProbe: List<Pair<String, Int>> = buildList {
        add("MIC" to MediaRecorder.AudioSource.MIC)
        add("VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        add("VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION)
        add("CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER)
        add("VOICE_CALL" to MediaRecorder.AudioSource.VOICE_CALL)
        // VOICE_DOWNLINK / VOICE_UPLINK are available as constants on all API levels
        add("VOICE_DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK)
        add("VOICE_UPLINK" to MediaRecorder.AudioSource.VOICE_UPLINK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // UNPROCESSED = raw mic, no signal processing
            add("UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED)
        }
    }

    @Volatile private var cancelled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Start diagnostics on a background thread. Safe to call from the UI thread.
     */
    fun startAsync() {
        Thread({
            runDiagnostics()
        }, "AudioDiagnosticsThread").start()
    }

    fun cancel() {
        cancelled = true
    }

    // -------------------------------------------------------------------------
    // Core diagnostic pipeline
    // -------------------------------------------------------------------------

    private fun runDiagnostics() {
        val report = DiagnosticsReport()

        log("=== Experiment 2: Audio Capture Diagnostics ===")
        log("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        // 1. Audio Manager diagnostics
        val audioManager = context.getSystemService(AudioManager::class.java)
        runAudioManagerDiagnostics(audioManager, report)
        if (cancelled) return

        // 2. Probe each audio source
        log("\n--- Probing Audio Sources ---")
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        log("AudioRecord.getMinBufferSize(@16kHz, MONO, PCM16) = $minBuf bytes")

        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            log("ERROR: getMinBufferSize returned error code $minBuf — hardware may not support this config")
            report.bufferMinSize = minBuf
            report.bufferError = "getMinBufferSize failed: $minBuf"
        } else {
            report.bufferMinSize = minBuf
        }

        // Use 4× min buffer for safety
        val bufferSize = if (minBuf > 0) minBuf * 4 else 8192

        for ((sourceName, sourceInt) in audioSourcesToProbe) {
            if (cancelled) return
            val result = probeAudioSource(sourceName, sourceInt, bufferSize)
            report.sourceResults[sourceName] = result
            log(result.summary())
        }

        // 3. Find the best usable source and record a 10-second WAV
        val wavFile = recordBestSource(report, bufferSize, audioManager)
        report.wavFile = wavFile

        // 4. Frame energy analysis
        analyzeRecording(report)

        // 5. Generate Gemini Live compatibility section
        generateGeminiCompatibilityReport(report)

        log("\n=== Diagnostics Complete ===")
        mainHandler.post { onComplete(report) }
    }

    // -------------------------------------------------------------------------
    // AudioManager diagnostics
    // -------------------------------------------------------------------------

    private fun runAudioManagerDiagnostics(am: AudioManager, report: DiagnosticsReport) {
        log("\n--- AudioManager State ---")

        val modeName = when (am.mode) {
            AudioManager.MODE_NORMAL          -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE        -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL         -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING  -> "MODE_CALL_SCREENING"
            else                               -> "UNKNOWN(${am.mode})"
        }

        report.audioMode = modeName
        log("Audio mode: $modeName")

        if (modeName == "MODE_IN_CALL") {
            log("  ✓ System is in cellular call mode — audio routing is active")
        } else if (modeName == "MODE_IN_COMMUNICATION") {
            log("  ✓ System is in VoIP call mode")
        } else {
            log("  ⚠ Audio mode is NOT in-call — run this during an active call for meaningful results")
        }

        val route = buildList {
            if (am.isSpeakerphoneOn) add("SPEAKER")
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) add("BLUETOOTH_SCO")
            @Suppress("DEPRECATION")
            if (am.isWiredHeadsetOn) add("WIRED_HEADSET")
            if (isEmpty()) add("EARPIECE")
        }.joinToString(" + ")

        report.audioRoute = route
        log("Audio route: $route")

        report.isMicMuted = am.isMicrophoneMute
        log("Microphone muted by system: ${am.isMicrophoneMute}")

        // List available microphones (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val mics = am.microphones
                report.availableMics = mics.size
                log("Available microphones (AudioManager.getMicrophones): ${mics.size}")
                mics.forEach { mic ->
                    log("  Mic: ${mic.description}, location=${mic.location}, group=${mic.group}")
                }
            } catch (e: Exception) {
                log("getMicrophones() failed: ${e.message}")
            }
        } else {
            log("getMicrophones() not available below API 28")
            report.availableMics = -1
        }
    }

    // -------------------------------------------------------------------------
    // Single audio source probe (non-recording — just init + state check)
    // -------------------------------------------------------------------------

    private fun probeAudioSource(
        sourceName: String,
        sourceInt: Int,
        bufferSize: Int
    ): SourceProbeResult {
        log("\n[PROBE] AudioSource.$sourceName (constant=$sourceInt)")
        val result = SourceProbeResult(sourceName = sourceName, sourceConstant = sourceInt)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(sourceInt, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            result.constructionSuccess = true
            log("  AudioRecord constructed successfully")

            result.recordState = record.state
            val stateLabel = when (record.state) {
                AudioRecord.STATE_INITIALIZED   -> "STATE_INITIALIZED"
                AudioRecord.STATE_UNINITIALIZED -> "STATE_UNINITIALIZED"
                else                             -> "UNKNOWN(${record.state})"
            }
            log("  record.state = $stateLabel")

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                result.initSuccess = true

                // Quick 100ms read to check if the source delivers frames
                record.startRecording()
                val testBuf = ByteArray(bufferSize)
                val bytesRead = record.read(testBuf, 0, testBuf.size)
                record.stop()

                result.testBytesRead = bytesRead
                log("  read() during quick probe = $bytesRead bytes")

                if (bytesRead > 0) {
                    val rms = WavWriter.computeRms(testBuf.copyOf(bytesRead))
                    result.testRms = rms
                    log("  Quick-probe RMS = ${"%.5f".format(rms)} (${if (rms < 0.001) "SILENCE" else "AUDIO PRESENT"})")
                }
            } else {
                result.error = "AudioRecord STATE_UNINITIALIZED after construction — " +
                    "source $sourceName may require privileged/system permission"
                log("  ✗ ${result.error}")
            }

        } catch (se: SecurityException) {
            result.constructionSuccess = false
            result.error = "SecurityException: ${se.message}"
            log("  ✗ SecurityException — requires privileged permission: ${se.message}")
        } catch (e: IllegalArgumentException) {
            result.constructionSuccess = false
            result.error = "IllegalArgumentException: ${e.message}"
            log("  ✗ IllegalArgumentException: ${e.message}")
        } catch (e: Exception) {
            result.constructionSuccess = false
            result.error = "${e.javaClass.simpleName}: ${e.message}"
            log("  ✗ Unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            record?.release()
        }

        return result
    }

    // -------------------------------------------------------------------------
    // 10-second recording from the best available source
    // -------------------------------------------------------------------------

    private fun recordBestSource(
        report: DiagnosticsReport,
        bufferSize: Int,
        am: AudioManager
    ): File? {
        log("\n--- 10-Second Recording ---")

        // Priority: VOICE_CALL first (we want to know if caller audio is present),
        // then VOICE_COMMUNICATION, then plain MIC as fallback.
        val priority = listOf(
            "VOICE_CALL",
            "VOICE_COMMUNICATION",
            "MIC",
            "VOICE_RECOGNITION"
        )

        val bestSource = priority.firstOrNull { name ->
            report.sourceResults[name]?.initSuccess == true
        }

        if (bestSource == null) {
            log("  ✗ No audio source initialized successfully — cannot record WAV")
            report.wavError = "No usable audio source found — all sources failed initialization"
            return null
        }

        val sourceInt = audioSourcesToProbe.first { it.first == bestSource }.second
        log("  Selected source: AudioSource.$bestSource")
        report.wavRecordedSource = bestSource

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(sourceInt, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord not initialized after construction with source $bestSource")
            }

            val totalBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * (RECORD_DURATION_MS / 1000L).toInt()
            val allPcm = ByteArray(totalBytes.toInt())
            var bytesRecorded = 0

            log("  Starting ${RECORD_DURATION_MS / 1000}s recording into ${totalBytes} byte buffer")
            record.startRecording()

            val chunkBuf = ByteArray(bufferSize)
            val deadline = System.currentTimeMillis() + RECORD_DURATION_MS

            while (!cancelled && System.currentTimeMillis() < deadline && bytesRecorded < allPcm.size) {
                val toRead = minOf(chunkBuf.size, allPcm.size - bytesRecorded)
                val read = record.read(chunkBuf, 0, toRead)
                if (read > 0) {
                    System.arraycopy(chunkBuf, 0, allPcm, bytesRecorded, read)
                    bytesRecorded += read
                } else if (read < 0) {
                    log("  AudioRecord.read() returned error code: $read")
                    break
                }
            }

            record.stop()
            log("  Recording complete: $bytesRecorded bytes captured")
            report.wavBytesRecorded = bytesRecorded

            if (bytesRecorded == 0) {
                report.wavError = "No bytes captured despite successful startRecording()"
                return null
            }

            // Save WAV
            val dir = context.getExternalFilesDir(null)
                ?: context.filesDir  // fallback to internal storage
            val wavFile = File(dir, "exp2_recording_${bestSource.lowercase()}.wav")
            WavWriter.write(wavFile, allPcm.copyOf(bytesRecorded), SAMPLE_RATE)
            log("  WAV saved: ${wavFile.absolutePath}")
            log("  WAV size: ${wavFile.length()} bytes")

            // Compute and log overall RMS
            val overallRms = WavWriter.computeRms(allPcm.copyOf(bytesRecorded))
            report.wavRms = overallRms
            log("  Overall RMS amplitude: ${"%.5f".format(overallRms)}")

            wavFile

        } catch (se: SecurityException) {
            report.wavError = "SecurityException during recording with $bestSource: ${se.message}"
            log("  ✗ SecurityException: ${se.message}")
            null
        } catch (e: Exception) {
            report.wavError = "Exception during recording: ${e.javaClass.simpleName}: ${e.message}"
            log("  ✗ Exception: ${e.message}")
            null
        } finally {
            record?.release()
        }
    }

    // -------------------------------------------------------------------------
    // Frame-level analysis: segment audio into 1-second windows
    // -------------------------------------------------------------------------

    private fun analyzeRecording(report: DiagnosticsReport) {
        val wavFile = report.wavFile ?: run {
            log("\n--- Frame Analysis: Skipped (no WAV file) ---")
            return
        }

        log("\n--- Frame Energy Analysis ---")

        // Read raw PCM bytes from WAV (skip 44-byte header)
        val wavBytes = wavFile.readBytes()
        if (wavBytes.size <= 44) {
            log("WAV file too small to analyze")
            return
        }
        val pcm = wavBytes.drop(44).toByteArray()

        val bytesPerSecond = SAMPLE_RATE * BYTES_PER_SAMPLE
        val windowCount = pcm.size / bytesPerSecond
        val windowResults = mutableListOf<Double>()

        for (i in 0 until windowCount) {
            val start = i * bytesPerSecond
            val end = minOf(start + bytesPerSecond, pcm.size)
            val window = pcm.copyOfRange(start, end)
            val rms = WavWriter.computeRms(window)
            windowResults.add(rms)
            log("  Second ${i + 1}: RMS=${"%.5f".format(rms)} (${rmsLabel(rms)})")
        }

        report.perSecondRms = windowResults

        val maxRms = windowResults.maxOrNull() ?: 0.0
        val avgRms = if (windowResults.isEmpty()) 0.0 else windowResults.average()
        val silentSeconds = windowResults.count { it < 0.001 }
        val activeSeconds = windowResults.size - silentSeconds

        log("\n  Peak RMS: ${"%.5f".format(maxRms)}")
        log("  Avg RMS: ${"%.5f".format(avgRms)}")
        log("  Silent seconds: $silentSeconds / ${windowResults.size}")
        log("  Active seconds: $activeSeconds / ${windowResults.size}")

        report.wavRms = avgRms

        // Classify what the WAV contains
        val isCallActive = report.audioMode == "MODE_IN_CALL" || report.audioMode == "MODE_IN_COMMUNICATION"
        val classification = classifyAudioContent(maxRms, avgRms, report.wavRecordedSource, isCallActive)
        report.contentClassification = classification
        log("\n  CONTENT CLASSIFICATION: $classification")
    }

    private fun rmsLabel(rms: Double): String = when {
        rms < 0.001 -> "silence"
        rms < 0.01  -> "near-silence"
        rms < 0.05  -> "low amplitude"
        rms < 0.15  -> "moderate"
        else         -> "loud"
    }

    private fun classifyAudioContent(
        maxRms: Double,
        avgRms: Double,
        source: String?,
        isCallActive: Boolean
    ): String {
        if (maxRms < 0.001) {
            return "D) SILENCE — The recording contains no audio.\n" +
                "   The audio source '$source' did not deliver any audio frames.\n" +
                "   This is consistent with Android blocking access to cellular call audio\n" +
                "   for standard (non-privileged) applications."
        }

        if (maxRms < 0.005) {
            return "D) NEAR-SILENCE — Extremely low energy. The source '$source' either returned\n" +
                "   near-zero noise or was blocked. Cannot distinguish caller voice from silence."
        }

        return if (isCallActive) {
            when (source) {
                "VOICE_CALL" ->
                    "A) or C) Audio captured from VOICE_CALL source.\n" +
                    "   ⚠ IMPORTANT: Play the WAV file to determine:\n" +
                    "     A) Only your microphone (near-end)\n" +
                    "     B) Only caller voice (far-end) — unlikely on standard app\n" +
                    "     C) Both voices\n" +
                    "     D) Background audio/noise\n" +
                    "   RMS peak=${"%.4f".format(maxRms)} avg=${"%.4f".format(avgRms)}"
                "VOICE_COMMUNICATION", "MIC", "VOICE_RECOGNITION" ->
                    "A) Microphone audio — The recording likely contains only your voice\n" +
                    "   (near-end/local microphone). The source '$source' provides local mic\n" +
                    "   access but does NOT include the remote caller's downlink audio.\n" +
                    "   RMS peak=${"%.4f".format(maxRms)} avg=${"%.4f".format(avgRms)}"
                else ->
                    "A) Audio present from source '$source'.\n" +
                    "   Play WAV to confirm content. RMS peak=${"%.4f".format(maxRms)}"
            }
        } else {
            "A) Audio captured (call NOT in-call mode at time of analysis).\n" +
                "   Re-run this experiment during an active cellular call for valid results."
        }
    }

    // -------------------------------------------------------------------------
    // Gemini Live Compatibility Report
    // -------------------------------------------------------------------------

    private fun generateGeminiCompatibilityReport(report: DiagnosticsReport) {
        log("\n=== GEMINI LIVE COMPATIBILITY REPORT ===")

        val usableSources = report.sourceResults.values
            .filter { it.initSuccess && (it.testBytesRead ?: 0) > 0 }
            .map { it.sourceName }

        val hasAnyAudio = (report.wavRms ?: 0.0) > 0.005
        val bestSource = report.wavRecordedSource

        // Can we stream PCM in real time?
        val streamingFeasible: Boolean
        val streamingVerdict: String
        val streamingReason: String
        val streamingApi: String

        if (usableSources.isEmpty()) {
            streamingFeasible = false
            streamingVerdict = "NO"
            streamingReason = "No audio source succeeded initialization. AudioRecord is completely blocked.\n" +
                "   This occurs when RECORD_AUDIO is not granted, or when all sources\n" +
                "   require system-level permissions not available to standard apps."
            streamingApi = "N/A"
        } else if (!hasAnyAudio && bestSource !in listOf("VOICE_CALL")) {
            streamingFeasible = false
            streamingVerdict = "NO — Partial"
            streamingReason = "AudioRecord initializes on sources: ${usableSources.joinToString()},\n" +
                "   but these sources deliver LOCAL MICROPHONE AUDIO ONLY.\n" +
                "   The remote caller's voice is NOT accessible to standard Android apps.\n" +
                "   Caller audio is mixed at the hardware/baseband level and never exposed\n" +
                "   to Java APIs. This is enforced by Android's audio policy (ANDROID_AUDIO_POLICY_\n" +
                "   CONFIGURATION) and requires CAPTURE_AUDIO_OUTPUT — a privileged permission\n" +
                "   granted only to pre-installed system apps signed with the platform key.\n" +
                "   API responsible: android.permission.CAPTURE_AUDIO_OUTPUT (protectionLevel=privileged)"
            streamingApi = "AudioRecord(VOICE_COMMUNICATION, 16000, MONO, PCM_16BIT) → PCM frames → Gemini Live WebSocket"
        } else {
            streamingFeasible = true
            streamingVerdict = "YES — With limitations"
            streamingReason = "AudioRecord with source(s) [${usableSources.joinToString()}] delivers\n" +
                "   real-time PCM audio frames. These can be streamed to Gemini Live.\n" +
                "   Audio content: ${report.contentClassification?.take(60) ?: "see classification above"}\n" +
                "   If the WAV contains caller audio, real-time streaming to Gemini Live IS feasible."
            streamingApi = "AudioRecord.read() loop → PCM ByteArray → Gemini Live WebSocket (16kHz, MONO, PCM16)"
        }

        report.geminiStreamingFeasible = streamingFeasible
        report.geminiStreamingVerdict = streamingVerdict
        report.geminiStreamingReason = streamingReason
        report.geminiStreamingApi = streamingApi

        log("\nCan we stream this audio to Gemini Live in real time?")
        log("Verdict: $streamingVerdict")
        log("\nReason:\n$streamingReason")
        if (streamingFeasible) {
            log("\nAPI to use:\n$streamingApi")
        }
        log("\n=== END OF REPORT ===")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun log(message: String) {
        Log.d(TAG, message)
        mainHandler.post { onProgress(message) }
    }
}

// -------------------------------------------------------------------------
// Data classes
// -------------------------------------------------------------------------

data class SourceProbeResult(
    val sourceName: String,
    val sourceConstant: Int,
    var constructionSuccess: Boolean = false,
    var recordState: Int = -1,
    var initSuccess: Boolean = false,
    var testBytesRead: Int? = null,
    var testRms: Double? = null,
    var error: String? = null
) {
    fun summary(): String {
        val status = when {
            !constructionSuccess -> "✗ CONSTRUCTION FAILED — ${error ?: "unknown"}"
            !initSuccess         -> "✗ NOT INITIALIZED — ${error ?: "STATE_UNINITIALIZED"}"
            (testBytesRead ?: 0) <= 0 -> "⚠ INITIALIZED but read() returned ${testBytesRead} — may be blocked"
            (testRms ?: 0.0) < 0.001  -> "⚠ INITIALIZED, reads frames, but RMS≈0 (silence/blocked)"
            else                 -> "✓ WORKING — RMS=${"%.5f".format(testRms)}"
        }
        return "  → $sourceName: $status"
    }
}

data class DiagnosticsReport(
    var audioMode: String = "Unknown",
    var audioRoute: String = "Unknown",
    var isMicMuted: Boolean = false,
    var availableMics: Int = 0,
    var bufferMinSize: Int = 0,
    var bufferError: String? = null,
    val sourceResults: MutableMap<String, SourceProbeResult> = mutableMapOf(),
    var wavFile: java.io.File? = null,
    var wavError: String? = null,
    var wavRecordedSource: String? = null,
    var wavBytesRecorded: Int = 0,
    var wavRms: Double? = null,
    var perSecondRms: List<Double> = emptyList(),
    var contentClassification: String? = null,
    var geminiStreamingFeasible: Boolean = false,
    var geminiStreamingVerdict: String = "",
    var geminiStreamingReason: String = "",
    var geminiStreamingApi: String = ""
)
