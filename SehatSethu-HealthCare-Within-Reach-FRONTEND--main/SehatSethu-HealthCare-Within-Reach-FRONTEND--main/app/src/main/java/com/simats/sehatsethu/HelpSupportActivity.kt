package com.simats.sehatsethu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager

class HelpSupportActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_support)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        supportActionBar?.hide()
        // Emergency dial
        findViewById<android.view.View>(R.id.btnEmergency).setOnClickListener {
            dialNumber(getString(R.string.emergency_phone_number_dial))
        }

        // Contact Support cards
        findViewById<android.view.View>(R.id.cardCall).setOnClickListener {
            dialNumber(getString(R.string.support_phone_number_dial))
        }

        findViewById<android.view.View>(R.id.cardChat).setOnClickListener {
            openWhatsAppOrFallback()
        }

        findViewById<android.view.View>(R.id.cardEmail).setOnClickListener {
            composeEmail(
                to = getString(R.string.support_email),
                subject = getString(R.string.support_email_subject)
            )
        }

        // Resources
        findViewById<android.view.View>(R.id.cardGuide).setOnClickListener {
            openUrl(getString(R.string.user_guide_url))
        }
        findViewById<android.view.View>(R.id.cardVideo).setOnClickListener {
            openUrl(getString(R.string.video_tutorials_url))
        }
        findViewById<android.view.View>(R.id.cardFaq).setOnClickListener {
            openUrl(getString(R.string.faqs_url))
        }

        // Expand / Collapse Common Issues (accordion)
        bindIssueToggle(R.id.issueRow1, R.id.issueAns1, R.id.issueArrow1)
        bindIssueToggle(R.id.issueRow2, R.id.issueAns2, R.id.issueArrow2)
        bindIssueToggle(R.id.issueRow3, R.id.issueAns3, R.id.issueArrow3)
        bindIssueToggle(R.id.issueRow4, R.id.issueAns4, R.id.issueArrow4)
    }

    private fun bindIssueToggle(rowId: Int, answerId: Int, arrowId: Int) {
        val row = findViewById<android.view.View>(rowId)
        val answer = findViewById<android.view.View>(answerId)
        val arrow = findViewById<ImageView>(arrowId)

        row.setOnClickListener {
            val parent = row.parent
            if (parent is android.view.ViewGroup) {
                TransitionManager.beginDelayedTransition(parent, AutoTransition())
            }

            val show = answer.visibility != android.view.View.VISIBLE
            answer.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            arrow.rotation = if (show) 90f else 0f
        }
    }

    private fun dialNumber(raw: String) {
        val number = raw.trim()
        if (number.isBlank() || number.contains("X", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.number_not_set), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.unable_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun composeEmail(to: String, subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$to")
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        val u = url.trim()
        if (u.isBlank()) {
            Toast.makeText(this, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.unable_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWhatsAppOrFallback() {
        val wa = getString(R.string.support_whatsapp_number).trim()
        if (wa.isBlank()) {
            Toast.makeText(this, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
            return
        }

        // WhatsApp deep link
        val uri = Uri.parse("https://wa.me/$wa")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        runCatching { startActivity(intent) }.onFailure {
            // fallback to email
            composeEmail(
                to = getString(R.string.support_email),
                subject = getString(R.string.support_email_subject)
            )
        }
    }
}
