package com.simats.sehatsethu.roles.admin

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReviewApplicationActivity : BaseActivity() {

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_ROLE = "extra_role"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_APPLIED_AT = "extra_applied_at"
        const val EXTRA_DOCS_COUNT = "extra_docs_count"
    }

    private var userId: Long = 0L
    private var status: String = ""
    private var role: String = ""

    private lateinit var btnBack: ImageView
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvName: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvApplied: TextView

    private lateinit var tvRegLabel: TextView
    private lateinit var tvRegValue: TextView

    private lateinit var rowExp: View
    private lateinit var tvExpLabel: TextView
    private lateinit var tvExpValue: TextView

    private lateinit var tvHospitalLabel: TextView
    private lateinit var tvHospitalValue: TextView
    private lateinit var tvPhoneValue: TextView

    private lateinit var docRow1: View
    private lateinit var docRow2: View
    private lateinit var docRow3: View
    private lateinit var tvDoc1Title: TextView
    private lateinit var tvDoc2Title: TextView
    private lateinit var tvDoc3Title: TextView

    private lateinit var bottomActions: LinearLayout
    private lateinit var btnReject: View
    private lateinit var btnApprove: View

    private var visibleDocUrls: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review_applications)
        supportActionBar?.hide()

        userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        status = intent.getStringExtra(EXTRA_STATUS).orEmpty()
        role = intent.getStringExtra(EXTRA_ROLE).orEmpty()

        bindViews()
        applyStaticTexts()

        btnBack.setOnClickListener { goBack() }

        // Prefill (fast)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val appliedAt = intent.getStringExtra(EXTRA_APPLIED_AT).orEmpty()

        if (name.isNotBlank()) tvName.text = name
        if (subtitle.isNotBlank()) tvSubtitle.text = subtitle
        tvApplied.text = getString(R.string.applied_on, prettyDate(appliedAt).ifBlank { "—" })

        // Hide docs until loaded
        showDocs(emptyList())

        // Hide action bar if already verified
        val st = status.trim().uppercase(Locale.US)
        bottomActions.visibility = if (st == "VERIFIED") View.GONE else View.VISIBLE

        // Apply initial role UI (if passed)
        applyRoleUi(role.trim().uppercase(Locale.US))

        if (userId > 0) {
            lifecycleScope.launch { loadDetails(userId) }
        }

        btnApprove.setOnClickListener { confirmApprove() }
        btnReject.setOnClickListener { confirmReject() }
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvName = findViewById(R.id.tvApplicantName)
        tvSubtitle = findViewById(R.id.tvApplicantSubtitle)
        tvApplied = findViewById(R.id.tvAppliedDate)

        tvRegLabel = findViewById(R.id.tvRegNoLabel)
        tvRegValue = findViewById(R.id.tvRegNoValue)

        rowExp = findViewById(R.id.rowExp)
        tvExpLabel = findViewById(R.id.tvExperienceLabel)
        tvExpValue = findViewById(R.id.tvExperienceValue)

        tvHospitalLabel = findViewById(R.id.tvHospitalLabel)
        tvHospitalValue = findViewById(R.id.tvHospitalValue)
        tvPhoneValue = findViewById(R.id.tvPhoneValue)

        docRow1 = findViewById(R.id.docRow1)
        docRow2 = findViewById(R.id.docRow2)
        docRow3 = findViewById(R.id.docRow3)
        tvDoc1Title = findViewById(R.id.tvDoc1Title)
        tvDoc2Title = findViewById(R.id.tvDoc2Title)
        tvDoc3Title = findViewById(R.id.tvDoc3Title)

        bottomActions = findViewById(R.id.bottomActions)
        btnReject = findViewById(R.id.btnReject)
        btnApprove = findViewById(R.id.btnApprove)
    }

    private fun applyStaticTexts() {
        tvHeaderTitle.text = getString(R.string.review_application)

        // default doc titles for doctor, pharmacist will override in applyRoleUi()
        tvDoc1Title.text = getString(R.string.doc_medical_license)
        tvDoc2Title.text = getString(R.string.doc_aadhaar)
        tvDoc3Title.text = getString(R.string.doc_degree_certificate)
    }

    private fun applyRoleUi(r: String) {
        val roleUpper = r.trim().uppercase(Locale.US)
        role = roleUpper

        if (roleUpper == "PHARMACIST") {
            // pharmacist: different labels + hide experience
            tvRegLabel.text = "Drug License No"
            tvHospitalLabel.text = "Pharmacy Name"
            rowExp.visibility = View.GONE

            // docs title (generic)
            tvDoc1Title.text = getString(R.string.document_1)
            tvDoc2Title.text = getString(R.string.document_2)
            tvDoc3Title.text = getString(R.string.document_3)
        } else {
            // doctor
            tvRegLabel.text = getString(R.string.license_number)
            tvHospitalLabel.text = getString(R.string.hospital_or_store)
            rowExp.visibility = View.VISIBLE
            tvExpLabel.text = getString(R.string.experience)

            tvDoc1Title.text = getString(R.string.doc_medical_license)
            tvDoc2Title.text = getString(R.string.doc_aadhaar)
            tvDoc3Title.text = getString(R.string.doc_degree_certificate)
        }
    }

    private suspend fun loadDetails(userId: Long) {
        val res = withContext(Dispatchers.IO) {
            AdminApi.get(this@ReviewApplicationActivity, "admin/user_detail.php?id=$userId")
        }

        val j = res.json ?: return
        if (j.optBoolean("ok", false) != true) return

        val u = j.optJSONObject("user") ?: j

        tvName.text = u.optString("full_name", tvName.text.toString())

        val subtitle = u.optString("subtitle", "")
        if (subtitle.isNotBlank()) tvSubtitle.text = subtitle

        val appliedAt = u.optString("applied_at", "")
        if (appliedAt.isNotBlank()) {
            tvApplied.text = getString(R.string.applied_on, prettyDate(appliedAt).ifBlank { "—" })
        }

        val r = u.optString("role", role).trim().uppercase(Locale.US)
        applyRoleUi(r)

        if (r == "PHARMACIST") {
            tvRegValue.text = u.optString("drug_license_no", "—")
            // exp hidden already
            tvHospitalValue.text = u.optString("pharmacy_name", "—")
            tvPhoneValue.text = u.optString("phone", "—")
        } else {
            tvRegValue.text = u.optString("registration_no", "—")
            tvExpValue.text = u.optString("experience_years", "—")
            tvHospitalValue.text = u.optString("practice_place", "—")
            tvPhoneValue.text = u.optString("phone", "—")
        }

        val docsArr = u.optJSONArray("documents") ?: JSONArray()
        val urls = extractDocUrls(docsArr)
        showDocs(urls)
    }

    private fun extractDocUrls(arr: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = (o.optString("file_url").ifBlank { o.optString("url") }).trim()
            if (url.isNotBlank()) out.add(url)
        }
        return out
    }

    //  show only available docs
    private fun showDocs(urls: List<String>) {
        visibleDocUrls = urls.take(3)

        docRow1.visibility = View.GONE
        docRow2.visibility = View.GONE
        docRow3.visibility = View.GONE

        docRow1.setOnClickListener(null)
        docRow2.setOnClickListener(null)
        docRow3.setOnClickListener(null)

        if (visibleDocUrls.isNotEmpty()) {
            docRow1.visibility = View.VISIBLE
            docRow1.setOnClickListener { openDoc(0) }
        }
        if (visibleDocUrls.size >= 2) {
            docRow2.visibility = View.VISIBLE
            docRow2.setOnClickListener { openDoc(1) }
        }
        if (visibleDocUrls.size >= 3) {
            docRow3.visibility = View.VISIBLE
            docRow3.setOnClickListener { openDoc(2) }
        }
    }

    private fun openDoc(index: Int) {
        if (index !in visibleDocUrls.indices) {
            toast(getString(R.string.document_not_available))
            return
        }
        val url = visibleDocUrls[index]
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(getString(R.string.document_not_available)) }
    }

    private fun confirmApprove() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.approve))
            .setMessage(getString(R.string.confirm_approve))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.approve)) { _, _ ->
                lifecycleScope.launch { updateStatus("VERIFIED", "") }
            }
            .show()
    }

    private fun confirmReject() {
        val input = EditText(this).apply { hint = "Reason (optional)" }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reject))
            .setMessage(getString(R.string.confirm_reject))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.reject)) { _, _ ->
                val reason = input.text?.toString().orEmpty().trim()
                lifecycleScope.launch { updateStatus("REJECTED", reason) }
            }
            .show()
    }

    private suspend fun updateStatus(newStatus: String, reason: String) {
        btnApprove.isEnabled = false
        btnReject.isEnabled = false

        val payload = JSONObject().apply {
            put("user_id", userId)
            put("status", newStatus)
            if (reason.isNotBlank()) put("reason", reason)
        }

        val res = withContext(Dispatchers.IO) {
            AdminApi.postJson(this@ReviewApplicationActivity, "admin/update_verification.php", payload)
        }

        btnApprove.isEnabled = true
        btnReject.isEnabled = true

        if (!res.ok) {
            val msgFromJson = res.json?.optString("error").orEmpty()
            val raw = (res.error ?: "").replace("\n", " ").take(240)

            val msg = when {
                msgFromJson.isNotBlank() -> msgFromJson
                raw.trim().startsWith("<!DOCTYPE", ignoreCase = true) -> "Server returned HTML (wrong endpoint or auth). Check: ${res.url}"
                res.code == 404 -> "404 Not Found: ${res.url}"
                res.code == 401 -> "401 Unauthorized. Token missing/invalid."
                res.code == 403 -> "403 Forbidden. Admin only."
                res.code in 500..599 -> "Server error (${res.code}): $raw"
                else -> "Failed (${res.code}): $raw"
            }
            toast(msg)
            return
        }

        //  success: update local + return RESULT_OK so fragment refreshes
        status = newStatus
        if (newStatus.uppercase(Locale.US) == "VERIFIED") bottomActions.visibility = View.GONE

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_STATUS, newStatus)
        })
        finish()
    }

    private fun prettyDate(db: String): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val d = inFmt.parse(db) ?: return ""
            val outFmt = SimpleDateFormat("MMMM d, yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            outFmt.format(d)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
