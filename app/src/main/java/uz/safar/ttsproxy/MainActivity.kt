package uz.safar.ttsproxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lets the user set this app as the device's default text-to-speech engine.
 * Android doesn't allow an app to set itself as default silently - the user
 * has to pick it in system TTS settings - so this just gets them there in one
 * tap instead of hunting through Settings.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private var probeTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            setPadding(32, 48, 32, 16)
            textSize = 16f
            text = "Tekshirilmoqda..."
        }

        val openSettingsButton = Button(this).apply {
            text = "Standart nutq mexanizmi sozlamalarini ochish"
            setOnClickListener {
                // Settings.ACTION_TTS_SETTINGS is @hide in recent SDKs, so use
                // the underlying action string directly.
                runCatching {
                    startActivity(Intent("android.settings.TTS_SETTINGS"))
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(statusView)
            addView(openSettingsButton)
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // getDefaultEngine() is an instance method, not static - it needs a
        // live TextToSpeech client to ask.
        probeTts?.shutdown()
        probeTts = TextToSpeech(this) { }.also { tts ->
            val defaultEngine = tts.defaultEngine
            statusView.text = if (defaultEngine == packageName) {
                "Bu ilova hozir standart nutq mexanizmi qilib tanlangan."
            } else {
                "Hozirgi standart nutq mexanizmi: $defaultEngine.\n\n" +
                    "Bu ilovani standart qilib tanlash uchun quyidagi tugmani bosing, " +
                    "so'ng ro'yxatdan \"Phone Grouping TTS\"ni tanlang."
            }
        }
    }

    override fun onDestroy() {
        probeTts?.shutdown()
        probeTts = null
        super.onDestroy()
    }
}
