package com.simats.sehatsethu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton

class LanguageSelectActivity : BaseActivity() {

    private var selected: String? = null

    private data class LangUi(val code: String, val card: View, val check: ImageView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_language)
        supportActionBar?.hide()

        findViewById<TextView>(R.id.title).text = getString(R.string.choose_language_title)
        findViewById<TextView>(R.id.subtitle).text = getString(R.string.choose_language_subtitle)

        val btnContinue = findViewById<AppCompatButton>(R.id.btn_continue)
        btnContinue.text = getString(R.string.continue_arrow)

        val langs = listOf(
            LangUi(
                code = "en",
                card = findViewById(R.id.card_english),
                check = findViewById(R.id.check_icon_english)
            ),
            LangUi(
                code = "hi",
                card = findViewById(R.id.card_hindi),
                check = findViewById(R.id.check_icon_hindi)
            ),
            LangUi(
                code = "ta",
                card = findViewById(R.id.card_tamil),
                check = findViewById(R.id.check_icon_tamil)
            ),
            LangUi(
                code = "pa",
                card = findViewById(R.id.card_punjabi),
                check = findViewById(R.id.check_icon_punjabi)
            ),
            LangUi(
                code = "te",
                card = findViewById(R.id.card_telugu),
                check = findViewById(R.id.check_icon_telugu)
            )
        )

        // default
        pick("en", langs)

        langs.forEach { item ->
            item.card.setOnClickListener { pick(item.code, langs) }
        }

        btnContinue.setOnClickListener {
            val lang = selected
            if (lang == null) {
                Toast.makeText(this, getString(R.string.select_language_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AppPrefs.setLang(this, lang)

            startActivity(Intent(this, OnboardingActivity::class.java).apply {
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
            itUi.card.setBackgroundResource(if (isSelected) R.drawable.bg_lang_card_selected else R.drawable.bg_lang_card)
            itUi.check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }
    }
}
