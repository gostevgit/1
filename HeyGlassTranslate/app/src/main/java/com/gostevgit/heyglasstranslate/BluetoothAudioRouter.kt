package com.gostevgit.heyglasstranslate

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Routes Android communication audio through a Bluetooth headset/glasses device.
 *
 * MVP deliberately targets Android 12+ (API 31+) so we can use
 * AudioManager.setCommunicationDevice() instead of legacy SCO toggling.
 */
class BluetoothAudioRouter(context: Context) {

    data class Route(
        val communicationDevice: AudioDeviceInfo,
        val inputDevice: AudioDeviceInfo?,
        val outputDevice: AudioDeviceInfo?,
    ) {
        val description: String
            get() = buildString {
                append(communicationDevice.productName ?: "Bluetooth device")
                append(" · input=")
                append(inputDevice?.typeName() ?: "system")
                append(" · output=")
                append(outputDevice?.typeName() ?: "system")
            }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("MissingPermission")
    fun routeToGlasses(): Route? {
        val communicationDevice = audioManager.availableCommunicationDevices.firstOrNull(::isBluetoothCommunicationDevice)
            ?: return null

        val previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val routed = runCatching { audioManager.setCommunicationDevice(communicationDevice) }
            .getOrDefault(false)
        if (!routed) {
            audioManager.mode = previousMode
            return null
        }

        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val input = inputs.firstOrNull { it.id == communicationDevice.id }
            ?: inputs.firstOrNull(::isBluetoothMic)
        val output = outputs.firstOrNull { it.id == communicationDevice.id }
            ?: outputs.firstOrNull(::isBluetoothSpeechOutput)

        return Route(
            communicationDevice = communicationDevice,
            inputDevice = input,
            outputDevice = output,
        )
    }

    fun clear() {
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun isBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun isBluetoothMic(device: AudioDeviceInfo): Boolean =
        device.isSource && isBluetoothCommunicationDevice(device)

    private fun isBluetoothSpeechOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && (
            isBluetoothCommunicationDevice(device) ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            )

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> type.toString()
    }
}
