package com.gostevgit.heyglasstranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

/** Keeps translation running while the phone screen is off / in a pocket. */
class ListenTranslateService : Service() {

    private val router by lazy { BluetoothAudioRouter(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private var client: GeminiLiveTranslateClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing…"))

        when (intent?.action) {
            ACTION_START -> startTranslation(
                apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE).orEmpty().ifBlank { "ru" },
            )
            ACTION_TEST_AUDIO -> runAudioTest()
            ACTION_STOP -> stopEverything()
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        router.clear()
        releaseWakeLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    @Suppress("MissingPermission")
    private fun startTranslation(apiKey: String, targetLanguage: String) {
        if (apiKey.isBlank()) {
            reportError("Enter a Gemini API key first")
            return
        }

        client?.stop()
        client = null
        router.clear()

        val route = runCatching { router.routeToGlasses() }.getOrElse {
            Log.e(TAG, "Bluetooth route failed", it)
            null
        }
        if (route == null) {
            val devices = runCatching { router.describeAvailableDevices() }.getOrDefault("device list unavailable")
            reportError("Bluetooth headset/glasses microphone not found · $devices")
            return
        }

        acquireWakeLock()
        reportStatus("Glasses routed · ${route.description}")

        client = GeminiLiveTranslateClient(
            apiKey = apiKey,
            targetLanguageCode = targetLanguage,
            inputDevice = route.inputDevice,
            outputDevice = route.outputDevice,
            callback = object : GeminiLiveTranslateClient.Callback {
                override fun onStatus(message: String) {
                    reportStatus(message)
                }

                override fun onInputTranscript(text: String) {
                    broadcast(EXTRA_INPUT_TRANSCRIPT, text)
                }

                override fun onOutputTranscript(text: String) {
                    broadcast(EXTRA_OUTPUT_TRANSCRIPT, text)
                }

                override fun onError(message: String) {
                    reportError(message)
                }
            },
        ).also { it.start() }
    }

    private fun runAudioTest() {
        client?.stop()
        client = null
        router.clear()

        worker.execute {
            val route = runCatching { router.routeToGlasses() }.getOrNull()
            if (route == null) {
                val devices = runCatching { router.describeAvailableDevices() }.getOrDefault("device list unavailable")
                reportError("Bluetooth headset/glasses audio route not found · $devices")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@execute
            }

            val result = AudioRouteDiagnostic.run(route) { reportStatus(it) }
            result.onSuccess { reportStatus(it) }
                .onFailure { reportError("Audio test failed: ${it.message ?: "unknown error"}") }

            router.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopEverything() {
        client?.stop()
        client = null
        router.clear()
        releaseWakeLock()
        reportStatus("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportStatus(message: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(message))
        broadcast(EXTRA_STATUS, message)
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification("Error · $message"))
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_ERROR, message)
        sendBroadcast(intent)
    }

    private fun broadcast(key: String, value: String) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(key, value),
        )
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Listen Translate",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps live translation running through the glasses"
            },
        )
    }

    private fun buildNotification(status: String): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            2,
            Intent(this, ListenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle("HeyGlass Listen Translate")
            .setContentText(status)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_translate, "Stop", stopPi).build())
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:listen_translate")
            .apply { acquire(2 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ListenTranslateService"
        private const val CHANNEL_ID = "listen_translate"
        private const val NOTIFICATION_ID = 2206

        const val ACTION_START = "com.gostevgit.heyglasstranslate.START"
        const val ACTION_STOP = "com.gostevgit.heyglasstranslate.STOP"
        const val ACTION_TEST_AUDIO = "com.gostevgit.heyglasstranslate.TEST_AUDIO"
        const val ACTION_STATE = "com.gostevgit.heyglasstranslate.STATE"

        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TARGET_LANGUAGE = "target_language"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val EXTRA_INPUT_TRANSCRIPT = "input_transcript"
        const val EXTRA_OUTPUT_TRANSCRIPT = "output_transcript"
    }
}
