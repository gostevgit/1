package com.gostevgit.heyglasstranslate

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
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var statusView: TextView
    private lateinit var inputTranscriptView: TextView
    private lateinit var outputTranscriptView: TextView

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
        requestRuntimePermissionsIfNeeded()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
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
        apiKeyInput = EditText(this).apply {
            hint = "AIza…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(preferences.getString(PREF_API_KEY, ""))
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

        root.addView(Button(this).apply {
            text = "TEST GLASSES AUDIO"
            setOnClickListener {
                if (ensurePermissions()) startAudioTest()
            }
        }, topMargin(18))

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
            text = "MVP note: the API key is stored only in this app's private preferences. After the hardware test passes, the next security step is short-lived Gemini ephemeral tokens."
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startTranslation() {
        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Enter your Gemini API key", Toast.LENGTH_SHORT).show()
            return
        }
        val language = languages[languageSpinner.selectedItemPosition]
        preferences.edit()
            .putString(PREF_API_KEY, apiKey)
            .putString(PREF_LANGUAGE, language.code)
            .apply()

        statusView.text = "Starting…"
        val intent = Intent(this, ListenTranslateService::class.java)
            .setAction(ListenTranslateService.ACTION_START)
            .putExtra(ListenTranslateService.EXTRA_API_KEY, apiKey)
            .putExtra(ListenTranslateService.EXTRA_TARGET_LANGUAGE, language.code)
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
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
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
    }
}
