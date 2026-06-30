package com.example.aicallassistant

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var callStateValue: TextView
    private lateinit var callerNumberValue: TextView
    private lateinit var countdownValue: TextView
    private lateinit var autoAnswerStatusValue: TextView
    private lateinit var debugLogValue: TextView
    private lateinit var requestPermissionsButton: Button

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telecomManager: TelecomManager

    private var countDownTimer: CountDownTimer? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var phoneStateReceiverRegistered = false
    private var lastCallState: Int? = null

    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val essentialPermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
    }.toTypedArray()

    private val optionalPermissions = arrayOf(Manifest.permission.READ_CALL_LOG)

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

            val stateName = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (!incomingNumber.isNullOrBlank()) {
                updateCallerNumber(incomingNumber)
            }

            appendLog(
                "PHONE_STATE broadcast: state=${stateName.ifBlank { "UNKNOWN" }}, " +
                    "number=${incomingNumber?.takeIf { it.isNotBlank() } ?: "not provided"}"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        telephonyManager = getSystemService(TelephonyManager::class.java)
        telecomManager = getSystemService(TelecomManager::class.java)

        buildUi()
        appendLog("App started on Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLog("Default dialer package: ${telecomManager.defaultDialerPackage ?: "unknown"}")
        appendLog("This app package: $packageName")

        requestPermissionsButton.setOnClickListener {
            requestMissingPermissions()
        }

        if (hasEssentialPermissions()) {
            startCallMonitoring()
        } else {
            updateAutoAnswerStatus("Waiting for permissions")
            requestMissingPermissions()
        }
    }

    override fun onDestroy() {
        stopCountdown("Activity destroyed")
        stopCallMonitoring()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_PERMISSIONS_CODE) return

        permissions.forEachIndexed { index, permission ->
            val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            appendLog("Permission result: $permission = ${if (granted) "GRANTED" else "DENIED"}")
        }

        if (hasEssentialPermissions()) {
            updateAutoAnswerStatus("Permissions granted; monitoring calls")
            startCallMonitoring()
        } else {
            updateAutoAnswerStatus("Missing permissions; detection/answer may not work")
            appendLog("Missing required permissions. Tap Request Permissions after enabling them in Settings.")
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFFF8FAFC.toInt())
        }

        val title = TextView(this).apply {
            text = "AI Call Assistant"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Experiment 1: Incoming Call Detection & Auto Answer"
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(subtitle)

        callStateValue = addStatusRow(root, "Current Call State", "UNKNOWN")
        callerNumberValue = addStatusRow(root, "Caller Number", "Not available")
        countdownValue = addStatusRow(root, "Countdown Timer", "Not running")
        autoAnswerStatusValue = addStatusRow(root, "Auto Answer Status", "Starting")

        requestPermissionsButton = Button(this).apply {
            text = "Request Permissions"
            isAllCaps = false
        }
        root.addView(
            requestPermissionsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        val launchExp2Button = Button(this).apply {
            text = "Launch Experiment 2 →  Audio Capture"
            isAllCaps = false
            setBackgroundColor(0xFF1D4ED8.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, Experiment2Activity::class.java))
            }
        }
        root.addView(
            launchExp2Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        val logLabel = TextView(this).apply {
            text = "Debug Log"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(logLabel)

        debugLogValue = TextView(this).apply {
            text = ""
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF1E293B.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }

        val scrollView = ScrollView(this).apply {
            addView(debugLogValue)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun addStatusRow(parent: LinearLayout, label: String, initialValue: String): TextView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF334155.toInt())
        }
        container.addView(labelView)

        val valueView = TextView(this).apply {
            text = initialValue
            textSize = 20f
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(valueView)

        parent.addView(container)
        return valueView
    }

    private fun startCallMonitoring() {
        stopCallMonitoring()

        registerPhoneStateReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, null, "TelephonyCallback")
                }
            }
            telephonyCallback = callback
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            appendLog("Registered TelephonyCallback.CallStateListener")
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated by Android; used below API 31 for compatibility.")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber, "PhoneStateListener")
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            appendLog("Registered PhoneStateListener.LISTEN_CALL_STATE")
        }

        updateAutoAnswerStatus("Monitoring calls")
        requestPermissionsButton.visibility = View.GONE
    }

    private fun stopCallMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback ->
                telephonyManager.unregisterTelephonyCallback(callback)
                appendLog("Unregistered TelephonyCallback")
            }
        } else {
            phoneStateListener?.let { listener ->
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                appendLog("Unregistered PhoneStateListener")
            }
        }

        unregisterPhoneStateReceiver()
        telephonyCallback = null
        phoneStateListener = null
    }

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
        appendLog("Registered PHONE_STATE broadcast receiver for number visibility diagnostics")
    }

    private fun unregisterPhoneStateReceiver() {
        if (!phoneStateReceiverRegistered) return

        unregisterReceiver(phoneStateReceiver)
        phoneStateReceiverRegistered = false
        appendLog("Unregistered PHONE_STATE broadcast receiver")
    }

    private fun handleCallState(state: Int, phoneNumber: String?, source: String) {
        val stateName = state.toCallStateName()
        appendLog("$source reported state=$stateName, number=${phoneNumber?.takeIf { it.isNotBlank() } ?: "not provided"}")

        if (!phoneNumber.isNullOrBlank()) {
            updateCallerNumber(phoneNumber)
        }

        if (lastCallState == state) {
            updateCallState(stateName)
            return
        }

        lastCallState = state
        updateCallState(stateName)

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                updateAutoAnswerStatus("Ringing; waiting 25 seconds")
                startCountdown()
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                stopCountdown("Call is OFFHOOK")
                updateAutoAnswerStatus("Call is off-hook; countdown stopped")
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                stopCountdown("Call is IDLE")
                updateAutoAnswerStatus("Idle")
            }

            else -> {
                stopCountdown("Unknown call state")
                updateAutoAnswerStatus("Unknown call state: $state")
            }
        }
    }

    private fun startCountdown() {
        stopCountdown("Restarting countdown")
        updateCountdown("25 seconds remaining")
        appendLog("Started 25-second countdown")

        countDownTimer = object : CountDownTimer(AUTO_ANSWER_DELAY_MS, ONE_SECOND_MS) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = ((millisUntilFinished + 999L) / ONE_SECOND_MS).coerceAtLeast(1L)
                updateCountdown("$secondsRemaining seconds remaining")
            }

            override fun onFinish() {
                countDownTimer = null
                updateCountdown("0 seconds remaining")
                appendLog("Countdown finished; attempting auto-answer")
                attemptAutoAnswer()
            }
        }.start()
    }

    private fun stopCountdown(reason: String) {
        val timer = countDownTimer ?: return
        timer.cancel()
        countDownTimer = null
        updateCountdown("Not running")
        appendLog("Stopped countdown: $reason")
    }

    private fun attemptAutoAnswer() {
        if (lastCallState != TelephonyManager.CALL_STATE_RINGING) {
            updateAutoAnswerStatus("Skipped; call is no longer ringing")
            appendLog("Auto-answer skipped because latest state is ${lastCallState?.toCallStateName() ?: "UNKNOWN"}")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            updateAutoAnswerStatus("Unsupported below Android 8.0 / API 26")
            appendLog("TelecomManager.acceptRingingCall() is unavailable below API 26")
            return
        }

        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            updateAutoAnswerStatus("Missing ANSWER_PHONE_CALLS permission")
            appendLog("Cannot call acceptRingingCall(): ANSWER_PHONE_CALLS not granted")
            return
        }

        updateAutoAnswerStatus("Calling TelecomManager.acceptRingingCall()")
        appendLog("Invoking TelecomManager.acceptRingingCall()")

        try {
            telecomManager.acceptRingingCall()
            updateAutoAnswerStatus("Answer API invoked; waiting for OFFHOOK")
            appendLog("acceptRingingCall() returned without throwing")
        } catch (securityException: SecurityException) {
            updateAutoAnswerStatus("Blocked by Android security policy")
            appendLog("SecurityException from acceptRingingCall(): ${securityException.message}")
        } catch (exception: RuntimeException) {
            updateAutoAnswerStatus("Answer attempt failed")
            appendLog("RuntimeException from acceptRingingCall(): ${exception.message}")
        }
    }

    private fun requestMissingPermissions() {
        val missingPermissions = (essentialPermissions + optionalPermissions).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            appendLog("All required permissions already granted")
            startCallMonitoring()
            return
        }

        appendLog("Requesting permissions: ${missingPermissions.joinToString()}")
        requestPermissions(missingPermissions.toTypedArray(), REQUEST_PERMISSIONS_CODE)
    }

    private fun hasEssentialPermissions(): Boolean {
        return essentialPermissions.all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateCallState(value: String) {
        callStateValue.text = value
    }

    private fun updateCallerNumber(value: String) {
        callerNumberValue.text = value
    }

    private fun updateCountdown(value: String) {
        countdownValue.text = value
    }

    private fun updateAutoAnswerStatus(value: String) {
        autoAnswerStatusValue.text = value
    }

    private fun appendLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        Log.d(TAG, message)

        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }

        if (::debugLogValue.isInitialized) {
            debugLogValue.text = logLines.joinToString(separator = "\n")
        }
    }

    private fun Int.toCallStateName(): String {
        return when (this) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN($this)"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val TAG = "AI_CALL_ASSISTANT"
        private const val REQUEST_PERMISSIONS_CODE = 1001
        private const val AUTO_ANSWER_DELAY_MS = 25_000L
        private const val ONE_SECOND_MS = 1_000L
        private const val MAX_LOG_LINES = 120
    }
}
