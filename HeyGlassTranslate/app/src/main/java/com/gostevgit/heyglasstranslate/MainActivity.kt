package com.gostevgit.heyglasstranslate

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import java.util.concurrent.atomic.AtomicBoolean

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
            text = "RCSP TARGET INFO (READ ONLY)"
            setOnClickListener {
                if (ensurePermissions()) probeRcspTargetInfo()
            }
        }, topMargin(10))

        root.addView(Button(this).apply {
            text = "HEYCYAN BLE VERSION (READ ONLY)"
            setOnClickListener {
                if (ensurePermissions()) probeHeyCyanVersion()
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
    private fun probeRcspTargetInfo() {
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

        statusView.text = "Sending read-only RCSP GetTargetInfo…"
        classicBluetoothView.text = buildString {
            append("RCSP GetTargetInfo probe")
            append("\nDevice: ").append(device.name ?: device.address)
            append("\nOperation: READ ONLY")
            append("\nNo settings or firmware writes")
        }

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
                    }
                }.apply { start() }

                socket.connect()
                watchdog.interrupt()

                val seq = 0x01
                val mask = RCSP_TARGET_MASK
                val payload = byteArrayOf(
                    seq.toByte(),
                    ((mask ushr 24) and 0xFF).toByte(),
                    ((mask ushr 16) and 0xFF).toByte(),
                    ((mask ushr 8) and 0xFF).toByte(),
                    (mask and 0xFF).toByte(),
                    0x00,
                )
                val frame = ByteArray(8 + payload.size)
                frame[0] = 0xFE.toByte()
                frame[1] = 0xDC.toByte()
                frame[2] = 0xBA.toByte()
                frame[3] = 0xC0.toByte()
                frame[4] = 0x03
                frame[5] = ((payload.size ushr 8) and 0xFF).toByte()
                frame[6] = (payload.size and 0xFF).toByte()
                payload.copyInto(frame, destinationOffset = 7)
                frame[frame.lastIndex] = 0xEF.toByte()

                socket.outputStream.write(frame)
                socket.outputStream.flush()

                val input = socket.inputStream
                val captured = ArrayList<Byte>()
                val deadline = System.currentTimeMillis() + RCSP_RESPONSE_WAIT_MS
                while (System.currentTimeMillis() < deadline && captured.size < RCSP_CAPTURE_LIMIT) {
                    val available = runCatching { input.available() }.getOrDefault(0)
                    if (available > 0) {
                        val count = minOf(available, 512, RCSP_CAPTURE_LIMIT - captured.size)
                        val buffer = ByteArray(count)
                        val read = input.read(buffer)
                        if (read > 0) repeat(read) { captured.add(buffer[it]) }
                    } else {
                        Thread.sleep(50)
                    }
                }

                val txHex = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                val rxBytes = captured.toByteArray()
                val rxHex = rxBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                val parsed = parseRcspResponse(rxBytes)

                runOnUiThread {
                    statusView.text = if (captured.isEmpty()) {
                        "RCSP request sent · no response"
                    } else {
                        "RCSP response received"
                    }
                    classicBluetoothView.text = buildString {
                        append("SPP / RFCOMM 0x1101: CONNECTED")
                        append("\nRCSP command: GetTargetInfo (0x03)")
                        append("\nRead-only mask: 0x").append(mask.toString(16).uppercase().padStart(8, '0'))
                        append("\nTX: ").append(txHex)
                        append("\nBytes received: ").append(rxBytes.size)
                        if (rxBytes.isNotEmpty()) {
                            append("\nRX: ").append(rxHex)
                            append("\n").append(parsed)
                        } else {
                            append("\nRX: no response")
                            append("\nLikely causes: RCSP authentication required, or SPP 0x1101 is not the RCSP transport.")
                        }
                    }
                }
            } catch (t: Throwable) {
                watchdog?.interrupt()
                runOnUiThread {
                    statusView.text = "RCSP probe failed"
                    classicBluetoothView.text = buildString {
                        append("RCSP GetTargetInfo failed")
                        append("\nOperation was read-only.")
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

    private fun parseRcspResponse(bytes: ByteArray): String {
        if (bytes.size < 8) return "RCSP parse: response too short"
        val head = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
        var start = -1
        for (i in 0..bytes.size - 3) {
            if (bytes[i] == head[0] && bytes[i + 1] == head[1] && bytes[i + 2] == head[2]) {
                start = i
                break
            }
        }
        if (start < 0 || start + 8 > bytes.size) return "RCSP parse: FE DC BA frame not found"

        val flag = bytes[start + 3].toInt() and 0xFF
        val cmd = bytes[start + 4].toInt() and 0xFF
        val len = ((bytes[start + 5].toInt() and 0xFF) shl 8) or (bytes[start + 6].toInt() and 0xFF)
        val total = 8 + len
        if (start + total > bytes.size) return "RCSP parse: truncated frame cmd=0x%02X len=%d".format(cmd, len)
        val tail = bytes[start + 7 + len].toInt() and 0xFF
        val payload = bytes.copyOfRange(start + 7, start + 7 + len)

        return buildString {
            append("RCSP frame: flag=0x%02X cmd=0x%02X len=%d tail=0x%02X".format(flag, cmd, len, tail))
            if (cmd == 0x03 && payload.size >= 2) {
                val status = payload[0].toInt() and 0xFF
                val seq = payload[1].toInt() and 0xFF
                append("\nGetTargetInfo status=0x%02X seq=0x%02X".format(status, seq))
                if (status == 0x00) {
                    append("\nSUCCESS: target information returned")
                    val tlv = payload.copyOfRange(2, payload.size)
                    val decoded = decodeTargetInfoTlv(tlv)
                    if (decoded.isNotBlank()) append("\n").append(decoded)
                } else {
                    append("\nDevice rejected/needs protocol authentication")
                }
            }
        }
    }

    private fun decodeTargetInfoTlv(bytes: ByteArray): String {
        val lines = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val attrLen = bytes[i].toInt() and 0xFF
            if (attrLen < 1 || i + 1 + attrLen > bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            val data = bytes.copyOfRange(i + 2, i + 1 + attrLen)
            val name = when (type) {
                0 -> "protocol_version"
                2 -> "edr_addr"
                5 -> "firmware_info"
                13 -> "fw_version"
                16 -> "name"
                else -> "attr_$type"
            }
            val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val ascii = data.takeIf { arr -> arr.isNotEmpty() && arr.all { b -> (b.toInt() and 0xFF) in 0x20..0x7E } }
                ?.toString(Charsets.US_ASCII)
            lines += if (ascii != null) "$name: $ascii [$hex]" else "$name: $hex"
            i += attrLen + 1
        }
        return lines.joinToString("\n")
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun probeHeyCyanVersion() {
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

        statusView.text = "Connecting to HeyCyan vendor BLE channel…"
        classicBluetoothView.text = buildString {
            append("HeyCyan vendor BLE probe")
            append("\nService: de5bf728…")
            append("\nCommand: Get Version (0x43)")
            append("\nOperation: READ ONLY")
        }

        val completed = AtomicBoolean(false)
        var gattRef: BluetoothGatt? = null

        fun finish(text: String, status: String) {
            if (!completed.compareAndSet(false, true)) return
            runOnUiThread {
                statusView.text = status
                classicBluetoothView.text = text
            }
            runCatching { gattRef?.disconnect() }
            runCatching { gattRef?.close() }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(
                        "HeyCyan BLE connect failed\nstatus=$status state=$newState",
                        "HeyCyan BLE probe failed",
                    )
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread { statusView.text = "BLE connected · discovering HeyCyan service…" }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !completed.get()) {
                    finish("HeyCyan BLE disconnected before response", "HeyCyan BLE probe failed")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Service discovery failed: $status", "HeyCyan BLE probe failed")
                    return
                }

                val service = gatt.getService(UUID.fromString(HEYCYAN_SERVICE_UUID))
                val writeChar = service?.getCharacteristic(UUID.fromString(HEYCYAN_WRITE_UUID))
                val notifyChar = service?.getCharacteristic(UUID.fromString(HEYCYAN_NOTIFY_UUID))

                if (service == null || writeChar == null || notifyChar == null) {
                    finish(
                        buildString {
                            append("HeyCyan vendor service/characteristics not found")
                            append("\nservice=").append(service != null)
                            append(" write=").append(writeChar != null)
                            append(" notify=").append(notifyChar != null)
                        },
                        "HeyCyan BLE probe failed",
                    )
                    return
                }

                if (!gatt.setCharacteristicNotification(notifyChar, true)) {
                    finish("Could not enable de5bf729 notifications", "HeyCyan BLE probe failed")
                    return
                }

                val cccd = notifyChar.getDescriptor(UUID.fromString(CCCD_UUID))
                if (cccd == null) {
                    finish("Notify CCCD 0x2902 not found", "HeyCyan BLE probe failed")
                    return
                }

                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(cccd)) {
                    finish("Failed to write notify CCCD", "HeyCyan BLE probe failed")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid.toString().lowercase() != CCCD_UUID || completed.get()) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Notify CCCD write failed: $status", "HeyCyan BLE probe failed")
                    return
                }

                val service = gatt.getService(UUID.fromString(HEYCYAN_SERVICE_UUID))
                val writeChar = service?.getCharacteristic(UUID.fromString(HEYCYAN_WRITE_UUID))
                if (writeChar == null) {
                    finish("HeyCyan write characteristic disappeared", "HeyCyan BLE probe failed")
                    return
                }

                val packet = byteArrayOf(
                    0xBC.toByte(),
                    0x43,
                    0x00,
                    0x00,
                    0xFF.toByte(),
                    0xFF.toByte(),
                )
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                writeChar.value = packet
                val started = gatt.writeCharacteristic(writeChar)

                runOnUiThread {
                    classicBluetoothView.text = buildString {
                        append("HeyCyan vendor BLE connected")
                        append("\nService: de5bf728…")
                        append("\nNotify: de5bf729… ENABLED")
                        append("\nCommand: Get Version (0x43) · READ ONLY")
                        append("\nTX: BC 43 00 00 FF FF")
                        append("\nWaiting for notification…")
                    }
                }

                if (!started) {
                    finish("BLE Get Version write could not be started", "HeyCyan BLE probe failed")
                    return
                }

                Thread {
                    Thread.sleep(HEYCYAN_RESPONSE_WAIT_MS)
                    if (!completed.get()) {
                        finish(
                            buildString {
                                append("HeyCyan vendor BLE connected")
                                append("\nTX: BC 43 00 00 FF FF")
                                append("\nRX: no notification within ")
                                append(HEYCYAN_RESPONSE_WAIT_MS)
                                append(" ms")
                            },
                            "HeyCyan BLE request sent · no response",
                        )
                    }
                }.start()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid.toString().lowercase() != HEYCYAN_NOTIFY_UUID || completed.get()) return
                val data = characteristic.value ?: byteArrayOf()
                val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                val parsed = parseHeyCyanPacket(data)
                finish(
                    buildString {
                        append("HeyCyan vendor BLE: RESPONSE RECEIVED")
                        append("\nCommand: Get Version (0x43) · READ ONLY")
                        append("\nTX: BC 43 00 00 FF FF")
                        append("\nRX bytes: ").append(data.size)
                        append("\nRX: ").append(hex)
                        append("\n").append(parsed)
                    },
                    "HeyCyan BLE response received",
                )
            }
        }

        gattRef = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun parseHeyCyanPacket(data: ByteArray): String {
        if (data.size < 6) return "HeyCyan parse: response too short"
        if ((data[0].toInt() and 0xFF) != 0xBC) return "HeyCyan parse: missing 0xBC magic"
        val cmd = data[1].toInt() and 0xFF
        val len = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val crc = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        val available = (data.size - 6).coerceAtLeast(0)
        val payloadLen = minOf(len, available)
        val payload = data.copyOfRange(6, 6 + payloadLen)
        val payloadHex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val ascii = payload.takeIf { arr ->
            arr.isNotEmpty() && arr.count { b -> (b.toInt() and 0xFF) in 0x20..0x7E } >= arr.size / 2
        }?.toString(Charsets.US_ASCII)?.replace("\u0000", "")

        return buildString {
            append("HeyCyan packet: cmd=0x%02X len=%d crc=0x%04X".format(cmd, len, crc))
            if (payload.isNotEmpty()) append("\nPayload: ").append(payloadHex)
            if (!ascii.isNullOrBlank()) append("\nASCII: ").append(ascii)
        }
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
        private const val RCSP_RESPONSE_WAIT_MS = 4000L
        private const val RCSP_CAPTURE_LIMIT = 8192
        private const val RCSP_TARGET_MASK = 0x00012025
        private const val HEYCYAN_SERVICE_UUID = "de5bf728-d711-4e47-af26-65e3012a5dc7"
        private const val HEYCYAN_WRITE_UUID = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
        private const val HEYCYAN_NOTIFY_UUID = "de5bf729-d711-4e47-af26-65e3012a5dc7"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val HEYCYAN_RESPONSE_WAIT_MS = 5000L
    }
}
