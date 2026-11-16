package com.gostevgit.heyglasstranslate

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Simple physical test: play a short tone in the glasses, then measure the glasses mic. */
object AudioRouteDiagnostic {

    private data class MicResult(
        val device: String,
        val dbFs: Double,
    )

    @Suppress("MissingPermission")
    fun run(route: BluetoothAudioRouter.Route, status: (String) -> Unit): Result<String> = runCatching {
        status("Requested audio route: ${route.description}")
        val actualOutput = playTone(route, status)
        val mic = measureMicrophone(route, status)
        "Audio test OK · output=$actualOutput · input=${mic.device} · mic %.1f dBFS".format(mic.dbFs)
    }

    @Suppress("MissingPermission")
    private fun playTone(route: BluetoothAudioRouter.Route, status: (String) -> Unit): String {
        val sampleRate = 24_000
        val durationMs = 350
        val sampleCount = sampleRate * durationMs / 1000
        val pcm = ByteArray(sampleCount * 2)
        val frequencyHz = 660.0
        val amplitude = 0.12 * Short.MAX_VALUE

        for (i in 0 until sampleCount) {
            val sample = (sin(2.0 * PI * frequencyHz * i / sampleRate) * amplitude).toInt()
            pcm[i * 2] = (sample and 0xff).toByte()
            pcm[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        route.outputDevice?.let { track.setPreferredDevice(it) }
        status("Speaker test: you should hear a short beep in the glasses")

        var actualOutput = route.outputDevice.deviceLabel()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(80)
            actualOutput = track.routedDevice.deviceLabel()
            status("Actual output: $actualOutput")
            Thread.sleep((durationMs + 100 - 80).toLong())
        } finally {
            track.release()
        }
        return actualOutput
    }

    @Suppress("MissingPermission")
    private fun measureMicrophone(route: BluetoothAudioRouter.Route, status: (String) -> Unit): MicResult {
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Microphone buffer is unavailable" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        route.inputDevice?.let { recorder.setPreferredDevice(it) }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Bluetooth microphone failed to initialize" }

        status("Microphone test: speak normally for 2 seconds")
        val buffer = ShortArray(1_600)
        var sumSquares = 0.0
        var samples = 0L
        var actualInput = route.inputDevice.deviceLabel()
        val deadline = System.currentTimeMillis() + 2_000L

        try {
            recorder.startRecording()
            while (System.currentTimeMillis() < deadline) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    actualInput = recorder.routedDevice.deviceLabel()
                    for (i in 0 until count) {
                        val v = buffer[i].toDouble() / Short.MAX_VALUE
                        sumSquares += v * v
                    }
                    samples += count
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        check(samples > 0) { "No samples received from microphone" }
        val rms = sqrt(sumSquares / samples)
        val dbFs = if (rms > 0.0) 20.0 * log10(rms) else -120.0
        status("Actual input: $actualInput")
        return MicResult(actualInput, dbFs)
    }

    @Suppress("MissingPermission")
    private fun AudioDeviceInfo?.deviceLabel(): String {
        if (this == null) return "system/default"
        return "${productName ?: "?"}(type=$type,id=$id)"
    }
}
