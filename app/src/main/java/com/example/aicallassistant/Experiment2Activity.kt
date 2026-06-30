package com.example.aicallassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast

/**
 * Experiment 2 – Live Call Audio Capture Feasibility
 *
 * Diagnostic screen that:
 *  1. Monitors call state (RINGING → OFFHOOK).
 *  2. Auto-launches audio diagnostics when the call is answered.
 *  3. Probes every officially-supported Android AudioSource.
 *  4. Saves a 10-second WAV file.
 *  5. Performs per-second RMS frame analysis.
 *  6. Generates a Gemini Live compatibility verdict.
 */
class Experiment2Activity : Activity() {

    companion object {
        private const val TAG = "AI_CALL_EXP2_UI"
        private const val PERM_REQUEST_CODE = 2001
    }

    // ── UI refs ─────────────────────────────────────────────────────────────
    private lateinit var tvCallState: TextView
    private lateinit var tvAudioMode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStartManual: Button
    private lateinit var btnRequestPerms: Button
    private lateinit var scrollLog: ScrollView

    // ── State ────────────────────────────────────────────────────────────────
    private var diagnostics: AudioDiagnostics? = null
    private var diagnosticsRunning = false
    private var phoneStateReceiverRegistered = false
    private val logLines = ArrayDeque<String>()
    private var savedLogFile: java.io.File? = null

    // ── Permissions ──────────────────────────────────────────────────────────
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
    }.toTypedArray()

    // ── Call state receiver ──────────────────────────────────────────────────
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            appendLog("Call state broadcast: $state")
            tvCallState.text = state ?: "UNKNOWN"

            if (state == TelephonyManager.EXTRA_STATE_OFFHOOK && !diagnosticsRunning) {
                appendLog("OFFHOOK detected → auto-starting audio diagnostics")
                startDiagnostics()
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                tvStatus.text = "Call ended"
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        appendLog("Experiment 2 started — Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")

        if (hasRequiredPermissions()) {
            appendLog("All permissions granted — waiting for call OFFHOOK state")
            tvStatus.text = "Ready — place or receive a call"
            registerPhoneStateReceiver()
        } else {
            tvStatus.text = "Permissions required"
            requestMissingPermissions()
        }
    }

    override fun onDestroy() {
        diagnostics?.cancel()
        unregisterPhoneStateReceiverSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST_CODE) return

        permissions.forEachIndexed { i, perm ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            appendLog("Permission ${perm.substringAfterLast('.')}: ${if (ok) "GRANTED" else "DENIED"}")
        }

        if (hasRequiredPermissions()) {
            tvStatus.text = "Permissions granted — place or receive a call"
            btnRequestPerms.visibility = View.GONE
            registerPhoneStateReceiver()
        } else {
            tvStatus.text = "Missing permissions — tap button to retry"
        }
    }

    // ── UI construction ──────────────────────────────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF0F172A.toInt())   // dark slate background
        }

        // Title bar
        root.addView(textView("AI Call Assistant", 22f, 0xFFF8FAFC, bold = true))
        root.addView(textView(
            "Experiment 2 — Live Call Audio Capture Feasibility",
            13f, 0xFF94A3B8, padTop = 4, padBottom = 20
        ))

        // Status cards
        root.addView(sectionLabel("Call State"))
        tvCallState = valueText("IDLE")
        root.addView(card(tvCallState))

        root.addView(sectionLabel("Audio Mode"))
        tvAudioMode = valueText("—")
        root.addView(card(tvAudioMode))

        root.addView(sectionLabel("Diagnostic Status"))
        tvStatus = valueText("Initialising…")
        root.addView(card(tvStatus))

        // Buttons
        btnRequestPerms = Button(this).apply {
            text = "Request Permissions"
            isAllCaps = false
            setOnClickListener { requestMissingPermissions() }
        }
        root.addView(btnRequestPerms, matchWidthLayout(topMargin = dp(8)))

        btnStartManual = Button(this).apply {
            text = "▶  Start Diagnostics Manually"
            isAllCaps = false
            setBackgroundColor(0xFF1D4ED8.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { startDiagnostics() }
        }
        root.addView(btnStartManual, matchWidthLayout(topMargin = dp(8)))

        // Log header row: label + Copy + Share buttons side by side
        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(20), 0, dp(4))
        }
        logHeaderRow.addView(
            sectionLabel("Diagnostic Log & Report", padTop = 0),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val btnCopyLog = Button(this).apply {
            text = "📋 Copy"
            isAllCaps = false
            textSize = 12f
            setBackgroundColor(0xFF334155.toInt())
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { copyLogToClipboard() }
        }
        logHeaderRow.addView(btnCopyLog, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })

        val btnShareLog = Button(this).apply {
            text = "↗ Share"
            isAllCaps = false
            textSize = 12f
            setBackgroundColor(0xFF334155.toInt())
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { shareLog() }
        }
        logHeaderRow.addView(btnShareLog, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })

        root.addView(logHeaderRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Scrollable log
        tvLog = TextView(this).apply {
            text = ""
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF86EFAC.toInt())   // green monospace
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF0D1117.toInt())
            // Allow long-press text selection so user can also select-copy partial text
            setTextIsSelectable(true)
        }
        scrollLog = ScrollView(this).apply {
            addView(tvLog)
            setBackgroundColor(0xFF0D1117.toInt())
        }
        root.addView(
            scrollLog,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(4) }
        )

        setContentView(root)
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    private fun startDiagnostics() {
        if (diagnosticsRunning) {
            appendLog("Diagnostics already running — please wait")
            return
        }
        if (!hasRecordAudioPermission()) {
            appendLog("Cannot start: RECORD_AUDIO permission not granted")
            tvStatus.text = "RECORD_AUDIO required — tap Request Permissions"
            return
        }

        diagnosticsRunning = true
        tvStatus.text = "Diagnostics running…"
        btnStartManual.isEnabled = false

        diagnostics = AudioDiagnostics(
            context = this,
            onProgress = { line -> appendLog(line) },
            onComplete = { report -> handleReport(report) }
        )
        diagnostics!!.startAsync()
    }

    private fun handleReport(report: DiagnosticsReport) {
        diagnosticsRunning = false
        btnStartManual.isEnabled = true

        // Update audio mode card
        tvAudioMode.text = report.audioMode

        // Build final verdict banner
        val verdict = buildString {
            appendLine("══════════════════════════════")
            appendLine("GEMINI LIVE COMPATIBILITY")
            appendLine("══════════════════════════════")
            appendLine()
            appendLine("Can we stream audio to Gemini Live in real time?")
            appendLine()
            appendLine("  ${report.geminiStreamingVerdict}")
            appendLine()
            appendLine("Reason:")
            appendLine(report.geminiStreamingReason)
            if (report.geminiStreamingFeasible && report.geminiStreamingApi.isNotBlank()) {
                appendLine()
                appendLine("API to use:")
                appendLine(report.geminiStreamingApi)
            }
            appendLine()
            appendLine("══════════════════════════════")
            appendLine("WAV Recording:")
            if (report.wavFile != null) {
                appendLine("  Saved: ${report.wavFile!!.absolutePath}")
                appendLine("  Size: ${report.wavFile!!.length()} bytes")
                appendLine("  RMS: ${"%.5f".format(report.wavRms ?: 0.0)}")
                appendLine()
                appendLine("Pull with ADB:")
                appendLine("  adb pull \"${report.wavFile!!.absolutePath}\"")
            } else {
                appendLine("  Not saved — ${report.wavError ?: "unknown error"}")
            }
            appendLine()
            appendLine("Content classification:")
            appendLine(report.contentClassification ?: "Not determined")
            appendLine("══════════════════════════════")
        }

        appendLog(verdict)
        tvStatus.text = "Complete — Verdict: ${report.geminiStreamingVerdict}"

        // Auto-save the full log to disk so it can be pulled via ADB
        saveLogToFile()

        Log.d(TAG, "Experiment 2 complete. Verdict: ${report.geminiStreamingVerdict}")
    }

    // ── Call state receiver registration ─────────────────────────────────────

    private fun registerPhoneStateReceiver() {
        if (phoneStateReceiverRegistered) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(phoneStateReceiver, filter)
        }
        phoneStateReceiverRegistered = true
        appendLog("Registered PHONE_STATE broadcast receiver")
    }

    private fun unregisterPhoneStateReceiverSafely() {
        if (!phoneStateReceiverRegistered) return
        try { unregisterReceiver(phoneStateReceiver) } catch (_: Exception) {}
        phoneStateReceiverRegistered = false
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun hasRequiredPermissions() = requiredPermissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        appendLog("Requesting: ${missing.map { it.substringAfterLast('.') }.joinToString()}")
        requestPermissions(missing.toTypedArray(), PERM_REQUEST_CODE)
    }

    // ── Log ──────────────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        message.lines().forEach { logLines.addLast(it) }
        while (logLines.size > 300) logLines.removeFirst()
        if (::tvLog.isInitialized) {
            tvLog.text = logLines.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun fullLogText(): String = logLines.joinToString("\n")

    private fun saveLogToFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "exp2_log_$timestamp.txt")
            file.writeText(fullLogText())
            savedLogFile = file
            val adbCmd = "adb pull \"${file.absolutePath}\""
            appendLog("")
            appendLog("📄 Log saved: ${file.absolutePath}")
            appendLog("ADB pull: $adbCmd")
            Log.d(TAG, "Log saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            appendLog("⚠ Could not save log to file: ${e.message}")
        }
    }

    private fun copyLogToClipboard() {
        val text = fullLogText()
        if (text.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Call Assistant — Exp2 Log", text))
        // On Android 13+ the system shows its own copy confirmation bubble,
        // so we only show a Toast on older versions to avoid double feedback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLog() {
        val text = fullLogText()
        if (text.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AI Call Assistant — Experiment 2 Log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "Share log via…"))
    }

    // ── View helpers ─────────────────────────────────────────────────────────

    private fun textView(
        text: String,
        size: Float,
        color: Long,
        bold: Boolean = false,
        padTop: Int = 0,
        padBottom: Int = 0
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(padTop), 0, dp(padBottom))
    }

    private fun sectionLabel(text: String, padTop: Int = 12) =
        TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(2), dp(padTop), 0, dp(4))
            letterSpacing = 0.1f
        }

    private fun valueText(initial: String) = TextView(this).apply {
        text = initial
        textSize = 16f
        setTextColor(0xFFF1F5F9.toInt())
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(child: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(0xFF1E293B.toInt())
        addView(child)
    }

    private fun matchWidthLayout(topMargin: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
