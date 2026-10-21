package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton

class LanguageChangeActivity : BaseActivity() {

    private var selected: String? = null

    private data class LangUi(val code: String, val card: View, val check: ImageView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_changing)
        supportActionBar?.hide()

        findViewById<TextView>(R.id.title).text = getString(R.string.change_language_title)
        findViewById<TextView>(R.id.subtitle).text = getString(R.string.change_language_subtitle)

        val btnContinue = findViewById<AppCompatButton>(R.id.btn_continue)
        btnContinue.text = getString(R.string.continue_arrow)

        //  Back button
        findViewById<ImageView>(R.id.ivBack).setOnClickListener { goBack() }

        val items = listOf(
            LangUi("en", findViewById(R.id.card_english), findViewById(R.id.check_icon_english)),
            LangUi("hi", findViewById(R.id.card_hindi), findViewById(R.id.check_icon_hindi)),
            LangUi("ta", findViewById(R.id.card_tamil), findViewById(R.id.check_icon_tamil)),
            LangUi("pa", findViewById(R.id.card_punjabi), findViewById(R.id.check_icon_punjabi)),
            LangUi("te", findViewById(R.id.card_telugu), findViewById(R.id.check_icon_telugu))
        )

        // Default to saved language (fallback: en)
        val saved = AppPrefs.getLang(this)?.trim().takeIf { !it.isNullOrBlank() } ?: "en"
        pick(saved, items)

        items.forEach { item ->
            item.card.setOnClickListener { pick(item.code, items) }
        }

        btnContinue.setOnClickListener {
            val lang = selected
            if (lang.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.select_language_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AppPrefs.setLang(this, lang)

            // Restart from Splash so locale applies everywhere cleanly
            startActivity(Intent(this, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun pick(lang: String, items: List<LangUi>) {
        selected = lang

        items.forEach { itUi ->
            val isSelected = itUi.code == lang
            itUi.card.setBackgroundResource(if (isSelected) R.drawable.bg_language_selected else R.drawable.bg_language_normal)
            itUi.check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }
    }
}
