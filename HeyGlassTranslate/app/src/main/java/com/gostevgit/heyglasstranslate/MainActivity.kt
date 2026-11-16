package com.gostevgit.heyglasstranslate

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var continuousModeButton: Button
    private lateinit var statusView: TextView
    private lateinit var classicBluetoothView: TextView
    private lateinit var inputTranscriptView: TextView
    private lateinit var outputTranscriptView: TextView

    private var continuousMode = false
    private val preferences by lazy { getSharedPreferences("heyglass_translate", Context.MODE_PRIVATE) }

    private val languages = listOf(
        Language("Русский", "ru"),
        Language("Українська", "uk"),
        Language("Polski", "pl"),
        Language("English", "en"),
        Language("Deutsch", "de"),
        Language("Беларуская", "be"),
        Language("Español", "es"),
        Language("Français", "fr"),
    )

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            } ?: return
            showClassicBluetoothResult(device, "SDP complete")
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            intent.getStringExtra(ListenTranslateService.EXTRA_STATUS)?.let { statusView.text = it }
            intent.getStringExtra(ListenTranslateService.EXTRA_ERROR)?.let { statusView.text = "ERROR · $it" }
            intent.getStringExtra(ListenTranslateService.EXTRA_INPUT_TRANSCRIPT)?.let {
                inputTranscriptView.text = it
            }
            intent.getStringExtra(ListenTranslateService.EXTRA_OUTPUT_TRANSCRIPT)?.let {
                outputTranscriptView.text = it
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerStateReceiver()
        registerUuidReceiver()
        requestRuntimePermissionsIfNeeded()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(uuidReceiver) }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "HeyGlass Listen Translate"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Glasses mic → Gemini 3.5 Live Translate → glasses speakers"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(label("Gemini API key"))
        val savedKey = preferences.getString(PREF_API_KEY, "").orEmpty()
        apiKeyInput = EditText(this).apply {
            hint = if (savedKey.isBlank()) "AIza…" else "Saved key ••••••••"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
        }
        root.addView(apiKeyInput, matchWrap())

        root.addView(label("Translate everything you hear to"))
        languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.label },
        )
        val savedLanguage = preferences.getString(PREF_LANGUAGE, "ru")
        languageSpinner.setSelection(languages.indexOfFirst { it.code == savedLanguage }.coerceAtLeast(0))
        root.addView(languageSpinner, matchWrap())

        continuousMode = preferences.getBoolean(PREF_CONTINUOUS_MODE, false)
        continuousModeButton = Button(this).apply {
            updateContinuousModeButton(this)
            setOnClickListener {
                continuousMode = !continuousMode
                preferences.edit().putBoolean(PREF_CONTINUOUS_MODE, continuousMode).apply()
                updateContinuousModeButton(this)
                statusView.text = if (continuousMode) {
                    "Continuous mode ON · press START to apply"
                } else {
                    "Continuous mode OFF · press START to apply"
                }
            }
        }
        root.addView(continuousModeButton, topMargin(14))

        root.addView(Button(this).apply {
            text = "TEST GLASSES AUDIO"
            setOnClickListener {
                if (ensurePermissions()) startAudioTest()
            }
        }, topMargin(10))

        root.addView(Button(this).apply {
            text = "INSPECT CLASSIC / RCSP"
            setOnClickListener {
                if (ensurePermissions()) inspectClassicBluetooth()
            }
        }, topMargin(10))

        root.addView(Button(this).apply {
            text = "PROBE SPP (READ ONLY)"
            setOnClickListener {
                if (ensurePermissions()) probeSppReadOnly()
            }
        }, topMargin(10))

        root.addView(Button(this).apply {
            text = "START LISTEN TRANSLATE"
            setOnClickListener {
                if (ensurePermissions()) startTranslation()
            }
        }, topMargin(10))

        root.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { stopTranslation() }
        }, topMargin(8))

        root.addView(label("Status"))
        statusView = TextView(this).apply {
            text = "Ready · connect the glasses in Android Bluetooth settings"
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0x11000000)
        }
        root.addView(statusView, matchWrap())

        root.addView(label("Classic Bluetooth / RCSP diagnostic"))
        classicBluetoothView = TextView(this).apply {
            text = "Not inspected"
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        root.addView(classicBluetoothView, matchWrap())

        root.addView(label("Heard (diagnostic transcript)"))
        inputTranscriptView = TextView(this).apply {
            text = "—"
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(inputTranscriptView, matchWrap())

        root.addView(label("Translated (diagnostic transcript)"))
        outputTranscriptView = TextView(this).apply {
            text = "—"
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(outputTranscriptView, matchWrap())

        root.addView(TextView(this).apply {
            text = "Continuous mode keeps the current translated speech playing when more source speech starts. The API key is stored only in this app's private preferences."
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateContinuousModeButton(button: Button) {
        button.text = if (continuousMode) {
            "CONTINUOUS MODE: ON"
        } else {
            "CONTINUOUS MODE: OFF"
        }
    }

    @Suppress("MissingPermission")
    private fun inspectClassicBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: run {
            classicBluetoothView.text = "Bluetooth adapter unavailable"
            return
        }
        if (!adapter.isEnabled) {
            classicBluetoothView.text = "Bluetooth is disabled"
            return
        }

        val bonded = adapter.bondedDevices.orEmpty()
        val device = bonded.maxByOrNull { classicDeviceScore(it) }
        if (device == null || classicDeviceScore(device) <= 0) {
            classicBluetoothView.text = buildString {
                append("No bonded HeyCyan/M02-like device found.")
                if (bonded.isNotEmpty()) {
                    append("\nBonded devices: ")
                    append(bonded.joinToString { it.name ?: it.address })
                }
            }
            return
        }

        showClassicBluetoothResult(device, "Cached UUIDs")
        val requested = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        statusView.text = if (requested) {
            "Classic SDP requested · waiting for UUID result…"
        } else {
            "Classic SDP request could not be started · showing cached UUIDs"
        }
    }

    @Suppress("MissingPermission")
    private fun showClassicBluetoothResult(device: BluetoothDevice, phase: String) {
        val uuids = device.uuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        val spp = uuids.any { it == SPP_UUID }
        classicBluetoothView.text = buildString {
            append(phase)
            append("\nDevice: ")
            append(device.name ?: "unknown")
            append("\nAddress: ")
            append(device.address)
            append("\nClassic type: ")
            append(
                when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL (Classic + LE)"
                    BluetoothDevice.DEVICE_TYPE_LE -> "LE only"
                    else -> "unknown"
                },
            )
            append("\nSPP / RFCOMM 0x1101: ")
            append(if (spp) "YES" else "not advertised")
            append("\nUUIDs:")
            if (uuids.isEmpty()) {
                append("\n— none cached yet")
            } else {
                uuids.forEach { append("\n").append(it) }
            }
            append("\n\nThis test is read-only; it does not write commands to the glasses.")
        }
    }

    @Suppress("MissingPermission")
    private fun probeSppReadOnly() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: run {
            classicBluetoothView.text = "Bluetooth adapter unavailable"
            return
        }
        val device = adapter.bondedDevices.orEmpty()
            .maxByOrNull { classicDeviceScore(it) }
            ?.takeIf { classicDeviceScore(it) > 0 }

        if (device == null) {
            classicBluetoothView.text = "No bonded M02/HeyCyan-like device found"
            return
        }

        statusView.text = "Opening SPP 0x1101 in read-only mode…"
        classicBluetoothView.text = "SPP read-only probe\nDevice: ${device.name ?: device.address}\nNo bytes will be written."

        Thread {
            var socket: BluetoothSocket? = null
            var watchdog: Thread? = null
            try {
                adapter.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))

                val socketForWatchdog = socket
                watchdog = Thread {
                    try {
                        Thread.sleep(SPP_CONNECT_TIMEOUT_MS)
                        runCatching { socketForWatchdog.close() }
                    } catch (_: InterruptedException) {
                        // Normal: connection/probe completed before timeout.
                    }
                }.apply { start() }

                socket.connect()
                watchdog.interrupt()

                val input = socket.inputStream
                val captured = ArrayList<Byte>()
                val deadline = System.currentTimeMillis() + SPP_PASSIVE_READ_MS

                while (System.currentTimeMillis() < deadline && captured.size < SPP_CAPTURE_LIMIT) {
                    val available = runCatching { input.available() }.getOrDefault(0)
                    if (available > 0) {
                        val count = minOf(available, 256, SPP_CAPTURE_LIMIT - captured.size)
                        val buffer = ByteArray(count)
                        val read = input.read(buffer)
                        if (read > 0) {
                            repeat(read) { captured.add(buffer[it]) }
                        }
                    } else {
                        Thread.sleep(75)
                    }
                }

                val hex = captured.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                runOnUiThread {
                    statusView.text = "SPP read-only probe complete"
                    classicBluetoothView.text = buildString {
                        append("SPP / RFCOMM 0x1101: CONNECTED")
                        append("\nDevice: ").append(device.name ?: device.address)
                        append("\nBytes written: 0")
                        append("\nPassive listen: ").append(SPP_PASSIVE_READ_MS).append(" ms")
                        append("\nBytes received: ").append(captured.size)
                        if (captured.isNotEmpty()) {
                            append("\nRX hex: ").append(hex)
                        } else {
                            append("\nRX: no unsolicited data")
                            append("\nThis is compatible with an RCSP-style channel that waits for a client handshake.")
                        }
                    }
                }
            } catch (t: Throwable) {
                watchdog?.interrupt()
                runOnUiThread {
                    statusView.text = "SPP probe failed"
                    classicBluetoothView.text = buildString {
                        append("SPP / RFCOMM 0x1101 probe failed")
                        append("\nBytes written: 0")
                        append("\n")
                        append(t.javaClass.simpleName)
                        append(": ")
                        append(t.message ?: "no message")
                    }
                }
            } finally {
                watchdog?.interrupt()
                runCatching { socket?.close() }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun classicDeviceScore(device: BluetoothDevice): Int {
        val name = device.name?.lowercase().orEmpty()
        var score = 0
        if (name.startsWith("m02")) score += 500
        if ("hey" in name) score += 200
        if ("cyan" in name) score += 200
        if ("glass" in name) score += 100
        if (device.type == BluetoothDevice.DEVICE_TYPE_DUAL) score += 25
        return score
    }

    private fun startTranslation() {
        val enteredKey = apiKeyInput.text.toString().trim()
        val savedKey = preferences.getString(PREF_API_KEY, "").orEmpty()
        val apiKey = enteredKey.ifBlank { savedKey }
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Enter your Gemini API key", Toast.LENGTH_SHORT).show()
            return
        }

        val language = languages[languageSpinner.selectedItemPosition]
        preferences.edit().apply {
            if (enteredKey.isNotBlank()) putString(PREF_API_KEY, enteredKey)
            putString(PREF_LANGUAGE, language.code)
            putBoolean(PREF_CONTINUOUS_MODE, continuousMode)
        }.apply()

        if (enteredKey.isNotBlank()) {
            apiKeyInput.text.clear()
            apiKeyInput.hint = "Saved key ••••••••"
        }

        statusView.text = "Starting…"
        val intent = Intent(this, ListenTranslateService::class.java)
            .setAction(ListenTranslateService.ACTION_START)
            .putExtra(ListenTranslateService.EXTRA_API_KEY, apiKey)
            .putExtra(ListenTranslateService.EXTRA_TARGET_LANGUAGE, language.code)
            .putExtra(ListenTranslateService.EXTRA_CONTINUOUS_MODE, continuousMode)
        startForegroundService(intent)
    }

    private fun startAudioTest() {
        statusView.text = "Testing Bluetooth audio route…"
        startForegroundService(
            Intent(this, ListenTranslateService::class.java)
                .setAction(ListenTranslateService.ACTION_TEST_AUDIO),
        )
    }

    private fun stopTranslation() {
        startService(
            Intent(this, ListenTranslateService::class.java)
                .setAction(ListenTranslateService.ACTION_STOP),
        )
        statusView.text = "Stopping…"
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            Toast.makeText(this, "Grant microphone/Bluetooth permissions, then tap again", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerUuidReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(uuidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(uuidReceiver, filter)
        }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(ListenTranslateService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(5))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun topMargin(marginDp: Int) = matchWrap().apply { topMargin = dp(marginDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Language(val label: String, val code: String)

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private const val PREF_API_KEY = "gemini_api_key"
        private const val PREF_LANGUAGE = "target_language"
        private const val PREF_CONTINUOUS_MODE = "continuous_mode"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
        private const val SPP_CONNECT_TIMEOUT_MS = 8000L
        private const val SPP_PASSIVE_READ_MS = 3000L
        private const val SPP_CAPTURE_LIMIT = 4096
    }
}
