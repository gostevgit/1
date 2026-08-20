package com.gostevgit.heyglasstranslate

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal audio-to-audio client for Gemini 3.5 Live Translate.
 *
 * Input: raw little-endian PCM16 mono @ 16 kHz, sent in 100 ms chunks.
 * Output: raw little-endian PCM16 mono @ 24 kHz.
 */
class GeminiLiveTranslateClient(
    private val apiKey: String,
    private val targetLanguageCode: String,
    private val inputDevice: AudioDeviceInfo?,
    private val outputDevice: AudioDeviceInfo?,
    private val callback: Callback,
) {

    interface Callback {
        fun onStatus(message: String)
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onError(message: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val active = AtomicBoolean(false)
    private val audioStarted = AtomicBoolean(false)
    private val firstServerContentSeen = AtomicBoolean(false)
    private val sentChunks = AtomicLong(0)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(100)

    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun start() {
        if (!active.compareAndSet(false, true)) return
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }
        sentChunks.set(0)
        firstServerContentSeen.set(false)
        callback.onStatus("Connecting to Gemini 3.5 Live Translate…")

        val request = Request.Builder()
            .url("$WS_URL?key=$apiKey")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!active.get()) {
                    webSocket.close(1000, "stopped")
                    return
                }

                callback.onStatus("Connected · sending translation setup…")
                if (!sendSetup(webSocket)) {
                    callback.onError("Could not send Gemini setup message")
                    return
                }

                // The v1beta Live Translate preview has been observed to accept a valid
                // setup but not always surface setupComplete to raw WebSocket clients.
                // WebSocket frames are ordered, so setup is queued before realtime audio.
                // Start streaming immediately instead of blocking forever on setupComplete.
                if (audioStarted.compareAndSet(false, true)) {
                    callback.onStatus("Streaming glasses audio · waiting for Gemini…")
                    startAudio()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Defensive support in case a backend rollout returns JSON in a binary WS frame.
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (active.get()) callback.onError("Gemini connection closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (active.get()) {
                    Log.e(TAG, "Gemini WebSocket failed", t)
                    callback.onError("Gemini connection failed: ${t.message ?: "unknown error"}")
                }
            }
        })
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        socket?.close(1000, "user_stopped")
        socket = null
        stopAudio()
        playbackQueue.clear()
        audioStarted.set(false)
        firstServerContentSeen.set(false)
        callback.onStatus("Stopped")
    }

    private fun sendSetup(webSocket: WebSocket): Boolean {
        // Raw v1beta runtime currently expects transcription fields at setup level,
        // while translationConfig remains inside generationConfig.
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "translationConfig",
                JSONObject()
                    .put("targetLanguageCode", targetLanguageCode)
                    .put("echoTargetLanguage", false),
            )

        val setup = JSONObject()
            .put("model", MODEL)
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        val envelope = JSONObject().put("setup", setup).toString()
        Log.d(TAG, "Sending Live Translate setup for target=$targetLanguageCode")
        return webSocket.send(envelope)
    }

    private fun handleMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Ignoring malformed Gemini response: ${raw.take(120)}")
            return
        }

        if (message.has("setupComplete")) {
            callback.onStatus("Listening · Gemini setup confirmed · target=$targetLanguageCode")
        }

        message.optJSONObject("error")?.let { error ->
            callback.onError(error.optString("message", error.toString()))
        }

        val serverContent = message.optJSONObject("serverContent") ?: return
        if (firstServerContentSeen.compareAndSet(false, true)) {
            callback.onStatus("Gemini is receiving audio · translating to $targetLanguageCode")
        }

        serverContent.optJSONObject("inputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let(callback::onInputTranscript)

        serverContent.optJSONObject("outputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let(callback::onOutputTranscript)

        if (serverContent.optBoolean("interrupted", false)) {
            playbackQueue.clear()
            player?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.play() }
            }
        }

        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            val encoded = inlineData?.optString("data").orEmpty()
            if (encoded.isNotBlank()) {
                runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                    .getOrNull()
                    ?.let { bytes ->
                        if (!playbackQueue.offer(bytes)) {
                            playbackQueue.poll()
                            playbackQueue.offer(bytes)
                        }
                    }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startAudio() {
        if (!active.get()) return

        val recordMin = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (recordMin <= 0) {
            callback.onError("Bluetooth microphone cannot be initialized")
            return
        }

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(INPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(recordMin * 2, INPUT_CHUNK_BYTES * 4))
            .build()

        inputDevice?.let { audioRecord.setPreferredDevice(it) }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            callback.onError("AudioRecord failed to initialize")
            return
        }

        val trackMin = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE / 4)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(trackMin * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        outputDevice?.let { audioTrack.setPreferredDevice(it) }

        recorder = audioRecord
        player = audioTrack

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(audioRecord.audioSessionId) }
                .getOrNull()
                ?.also { it.setEnabled(true) }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(audioRecord.audioSessionId) }
                .getOrNull()
                ?.also { it.setEnabled(true) }
        }

        audioTrack.play()

        playbackThread = Thread({
            while (active.get()) {
                try {
                    val bytes = playbackQueue.take()
                    if (active.get()) {
                        audioTrack.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio playback failed", t)
                    if (active.get()) callback.onError("Translated audio playback failed: ${t.message ?: "unknown error"}")
                    break
                }
            }
        }, "gemini-translate-playback").also { it.start() }

        captureThread = Thread({
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            try {
                audioRecord.startRecording()
                callback.onStatus("Streaming glasses mic → Gemini · target=$targetLanguageCode")
                while (active.get()) {
                    val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        sendPcm(if (count == buffer.size) buffer else buffer.copyOf(count))
                    }
                }
            } catch (t: Throwable) {
                if (active.get()) {
                    Log.e(TAG, "Audio capture failed", t)
                    callback.onError("Microphone stream failed: ${t.message ?: "unknown error"}")
                }
            }
        }, "gemini-translate-capture").also { it.start() }
    }

    private fun sendPcm(bytes: ByteArray) {
        if (!active.get()) return
        val audio = JSONObject()
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
        val payload = JSONObject().put("realtimeInput", JSONObject().put("audio", audio))
        val sent = socket?.send(payload.toString()) == true
        if (!sent) {
            callback.onError("Gemini socket rejected an audio chunk")
            return
        }

        val chunks = sentChunks.incrementAndGet()
        if (chunks == 20L) {
            callback.onStatus("Audio streaming OK · 2.0 s sent · speak now")
        } else if (chunks == 100L && !firstServerContentSeen.get()) {
            callback.onStatus("Audio streaming OK · 10 s sent · waiting for Gemini response")
        }
    }

    private fun stopAudio() {
        captureThread?.interrupt()
        playbackThread?.interrupt()
        captureThread = null
        playbackThread = null

        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null

        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null

        player?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        player = null
    }

    private companion object {
        const val TAG = "GeminiTranslate"
        const val MODEL = "models/gemini-3.5-live-translate-preview"
        const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val INPUT_CHUNK_BYTES = 3_200 // 100 ms, mono PCM16 @ 16 kHz.
    }
}
