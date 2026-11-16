package com.gostevgit.heyglasstranslate

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Routes communication audio through Bluetooth glasses/headset. */
class BluetoothAudioRouter(context: Context) {

    data class Route(
        val communicationDevice: AudioDeviceInfo?,
        val inputDevice: AudioDeviceInfo?,
        val outputDevice: AudioDeviceInfo?,
        val legacySco: Boolean,
    ) {
        val description: String
            get() = buildString {
                append(communicationDevice?.productName ?: inputDevice?.productName ?: outputDevice?.productName ?: "Bluetooth device")
                append(" · input=")
                append(typeName(inputDevice))
                append(" · output=")
                append(typeName(outputDevice))
                if (legacySco) append(" · legacy SCO")
            }

        private fun typeName(device: AudioDeviceInfo?): String = when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "PHONE_MIC"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "PHONE_SPEAKER"
            else -> device?.type?.toString() ?: "system"
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("MissingPermission", "DEPRECATION")
    fun routeToGlasses(): Route? {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = audioManager.availableCommunicationDevices
                .filter(::isBluetoothCommunicationDevice)
            val communicationDevice = candidates.maxByOrNull(::glassesPreferenceScore) ?: return null
            val routed = runCatching { audioManager.setCommunicationDevice(communicationDevice) }
                .getOrDefault(false)
            if (!routed) return null

            Thread.sleep(250)
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val input = inputs.firstOrNull { it.id == communicationDevice.id }
                ?: inputs.filter(::isBluetoothMic).maxByOrNull(::glassesPreferenceScore)
            val output = outputs.firstOrNull { it.id == communicationDevice.id }
                ?: outputs.filter(::isBluetoothSpeechOutput).maxByOrNull(::glassesPreferenceScore)

            Route(communicationDevice, input, output, legacySco = false)
        } else {
            runCatching { audioManager.startBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = true }
            Thread.sleep(1200)

            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val input = inputs.filter(::isBluetoothMic).maxByOrNull(::glassesPreferenceScore)
            val output = outputs.filter(::isBluetoothSpeechOutput).maxByOrNull(::glassesPreferenceScore)
            val anchor = input ?: output
            if (anchor == null) {
                runCatching { audioManager.isBluetoothScoOn = false }
                runCatching { audioManager.stopBluetoothSco() }
                audioManager.mode = AudioManager.MODE_NORMAL
                null
            } else {
                Route(anchor, input, output, legacySco = true)
            }
        }
    }

    @Suppress("MissingPermission")
    fun describeAvailableDevices(): String {
        val communication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.joinToString { it.debugName() }
        } else {
            "legacy-sco"
        }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { it.debugName() }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { it.debugName() }
        return "comm=[$communication] · inputs=[$inputs] · outputs=[$outputs]"
    }

    @Suppress("DEPRECATION")
    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching { audioManager.isBluetoothScoOn = false }
            runCatching { audioManager.stopBluetoothSco() }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @Suppress("MissingPermission")
    private fun glassesPreferenceScore(device: AudioDeviceInfo): Int {
        val name = device.productName?.toString()?.lowercase().orEmpty()
        var score = 0
        if ("hey" in name) score += 100
        if ("cyan" in name) score += 100
        if ("glass" in name) score += 100
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) score += 10
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) score += 15
        return score
    }

    private fun isBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isBluetoothMic(device: AudioDeviceInfo): Boolean =
        device.isSource && isBluetoothCommunicationDevice(device)

    private fun isBluetoothSpeechOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && (
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            )

    @Suppress("MissingPermission")
    private fun AudioDeviceInfo.debugName(): String =
        "${productName ?: "?"}:${typeName(this)}#${id}"

    private fun typeName(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "PHONE_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "PHONE_SPEAKER"
        else -> device?.type?.toString() ?: "system"
    }
}
