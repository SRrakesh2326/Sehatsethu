package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.LocalCache
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PatientPrescriptionDetailsActivity : BaseActivity() {

    private lateinit var ivBack: View
    private lateinit var tvTopTitle: TextView

    private lateinit var tvDoctorName: TextView
    private lateinit var tvDoctorSpec: TextView
    private lateinit var tvDoctorPlace: TextView
    private lateinit var ivVerified: ImageView
    private lateinit var tvPrescribedOn: TextView

    private lateinit var tvDiagnosisValue: TextView
    private lateinit var diagnosisCard: View

    private lateinit var tvNotesValue: TextView
    private lateinit var notesWrap: View

    private lateinit var rvMeds: RecyclerView
    private lateinit var tvEmptyMeds: TextView

    private lateinit var followupCard: View
    private lateinit var tvFollowupText: TextView

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnShare: MaterialButton

    private lateinit var vLoading: View

    private var prescriptionId: Long = 0L

    private val meds = ArrayList<MedRow>()
    private lateinit var adapter: MedAdapter

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfPretty by lazy {
        SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    private val CACHE_PREFIX = "prescription_detail"

    data class MedRow(
        val name: String,
        val dosage: String,
        val frequency: String,
        val duration: String,
        val instructions: String
    )

    private val saveDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(buildShareText().toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                Toast.makeText(this, getString(R.string.download_saved), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_prescription_details)
        supportActionBar?.hide()

        prescriptionId = readLongAny("prescription_id", "prescriptionId", "id")
        if (prescriptionId <= 0L) {
            Toast.makeText(this, getString(R.string.no_prescription_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ivBack = findViewById(R.id.ivBack)
        tvTopTitle = findViewById(R.id.tvTitle)

        tvDoctorName = findViewById(R.id.tvDocName)
        tvDoctorSpec = findViewById(R.id.tvDocSpec)
        tvDoctorPlace = findViewById(R.id.tvDocPlace)
        ivVerified = findViewById(R.id.ivVerified)
        tvPrescribedOn = findViewById(R.id.tvPrescribedOn)

        diagnosisCard = findViewById(R.id.cardDiagnosis)
        tvDiagnosisValue = findViewById(R.id.tvDiagnosisValue)

        notesWrap = findViewById(R.id.wrapNotes)
        tvNotesValue = findViewById(R.id.tvNotesValue)

        rvMeds = findViewById(R.id.rvMeds)
        tvEmptyMeds = findViewById(R.id.tvEmptyMeds)

        followupCard = findViewById(R.id.cardFollowup)
        tvFollowupText = findViewById(R.id.tvFollowupText)

        btnDownload = findViewById(R.id.btnDownload)
        btnShare = findViewById(R.id.btnShare)

        vLoading = findViewById(R.id.vLoading)

        tvTopTitle.text = getString(R.string.prescription)
        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rvMeds.layoutManager = LinearLayoutManager(this)
        rvMeds.setHasFixedSize(false)
        adapter = MedAdapter(meds)
        rvMeds.adapter = adapter

        btnShare.isEnabled = false
        btnDownload.isEnabled = false

        btnShare.setOnClickListener {
            runCatching {
                val t = buildShareText()
                val itn = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.prescription))
                    putExtra(Intent.EXTRA_TEXT, t)
                }
                startActivity(Intent.createChooser(itn, getString(R.string.share)))
            }.onFailure {
                Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
            }
        }

        btnDownload.setOnClickListener {
            val fileName = "Prescription_${prescriptionId}.txt"
            saveDocLauncher.launch(fileName)
        }

        // ✅ try cache immediately for instant offline display
        if (loadFromCacheIfAvailable()) {
            btnShare.isEnabled = true
            btnDownload.isEnabled = true
        }

        // then attempt refresh online
        load()
    }

    private fun cacheKey(): String = LocalCache.key(CACHE_PREFIX, prescriptionId)

    private fun loadFromCacheIfAvailable(): Boolean {
        val cached = LocalCache.getString(this, cacheKey()).orEmpty()
        if (cached.isBlank()) return false

        val data = runCatching { JSONObject(cached) }.getOrNull() ?: return false
        bind(data)
        return true
    }

    private fun load() {
        val token = AppPrefs.getToken(this).orEmpty()

        // ✅ if token missing/expired -> keep cache view (don’t finish)
        if (token.isBlank()) {
            if (!loadFromCacheIfAvailable()) {
                Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch {
            vLoading.isVisible = true

            val url = BASE_URL +
                    "patient/prescription_detail.php?prescription_id=" + prescriptionId +
                    "&_ts=" + System.currentTimeMillis()

            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }

            vLoading.isVisible = false

            if (!res.optBoolean("ok", false)) {
                // ✅ fallback to cache on any failure
                if (!loadFromCacheIfAvailable()) {
                    val err = res.optString("error", "").trim()
                    Toast.makeText(
                        this@PatientPrescriptionDetailsActivity,
                        if (err.isNotBlank()) err else getString(R.string.failed_to_load),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()
            bind(data)

            // ✅ cache full detail for offline
            LocalCache.putString(this@PatientPrescriptionDetailsActivity, cacheKey(), data.toString())
            LocalCache.putLong(this@PatientPrescriptionDetailsActivity, cacheKey() + "_ts", System.currentTimeMillis())

            btnShare.isEnabled = true
            btnDownload.isEnabled = true
        }
    }

    private fun bind(data: JSONObject) {
        val p = data.optJSONObject("prescription") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()

        val docName = p.optString("doctor_name", "").trim().ifBlank { getString(R.string.doctor) }
        val spec = p.optString("doctor_specialization", "").trim().ifBlank { getString(R.string.general_physician) }

        val place = p.optString("works_at", "").trim()
            .ifBlank { p.optString("practice_place", "").trim() }
            .ifBlank { p.optString("doctor_place", "").trim() }
            .ifBlank { "" }

        val verified = p.optBoolean("doctor_verified", true)

        tvDoctorName.text = docName
        tvDoctorSpec.text = spec
        tvDoctorPlace.text = place
        tvDoctorPlace.isVisible = place.isNotBlank()
        ivVerified.isVisible = verified

        val createdAt = p.optString("created_at", "").trim()
        tvPrescribedOn.text = getString(R.string.prescribed_on_fmt, prettyDateFromCreatedAt(createdAt))

        val diagnosis = p.optString("diagnosis", "").trim()
        if (diagnosis.isNotBlank()) {
            diagnosisCard.isVisible = true
            tvDiagnosisValue.text = diagnosis
        } else {
            diagnosisCard.isVisible = false
        }

        val notes = p.optString("doctor_notes", "").trim()
        if (notes.isNotBlank()) {
            notesWrap.isVisible = true
            tvNotesValue.text = notes
        } else {
            notesWrap.isVisible = false
        }

        val follow = p.optString("followup_note", "").trim()
        if (follow.isNotBlank()) {
            followupCard.isVisible = true
            tvFollowupText.text = follow
        } else {
            followupCard.isVisible = false
        }

        meds.clear()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            meds.add(
                MedRow(
                    name = o.optString("name", "").trim(),
                    dosage = o.optString("dosage", "").trim(),
                    frequency = o.optString("frequency", "").trim(),
                    duration = o.optString("duration", "").trim(),
                    instructions = o.optString("instructions", "").trim()
                )
            )
        }
        adapter.notifyDataSetChanged()

        val hasMeds = meds.isNotEmpty()
        rvMeds.isVisible = hasMeds
        tvEmptyMeds.isVisible = !hasMeds

        btnShare.isEnabled = true
        btnDownload.isEnabled = true
    }

    private fun prettyDateFromCreatedAt(mysql: String): String {
        if (mysql.isBlank()) return "—"
        return try {
            val d = dfServer.parse(mysql)
            if (d != null) dfPretty.format(d) else "—"
        } catch (_: Throwable) { "—" }
    }

    private fun buildShareText(): String {
        val name = tvDoctorName.text?.toString().orEmpty().trim()
        val spec = tvDoctorSpec.text?.toString().orEmpty().trim()
        val place = tvDoctorPlace.text?.toString().orEmpty().trim()
        val prescribedOn = tvPrescribedOn.text?.toString().orEmpty().trim()

        val diagnosis = if (diagnosisCard.isVisible) tvDiagnosisValue.text?.toString().orEmpty().trim() else ""
        val notes = if (notesWrap.isVisible) tvNotesValue.text?.toString().orEmpty().trim() else ""
        val follow = if (followupCard.isVisible) tvFollowupText.text?.toString().orEmpty().trim() else ""

        val sb = StringBuilder()
        sb.appendLine(getString(R.string.prescription))
        sb.appendLine("—")
        if (name.isNotBlank()) sb.appendLine(name)
        if (spec.isNotBlank()) sb.appendLine(spec)
        if (place.isNotBlank()) sb.appendLine(place)
        if (prescribedOn.isNotBlank()) sb.appendLine(prescribedOn)
        sb.appendLine()

        if (diagnosis.isNotBlank()) {
            sb.appendLine(getString(R.string.diagnosis))
            sb.appendLine(diagnosis)
            sb.appendLine()
        }

        if (notes.isNotBlank()) {
            sb.appendLine(getString(R.string.doctors_notes))
            sb.appendLine(notes)
            sb.appendLine()
        }

        sb.appendLine(getString(R.string.prescribed_medicines))
        if (meds.isEmpty()) {
            sb.appendLine("—")
        } else {
            meds.forEachIndexed { idx, m ->
                sb.append("${idx + 1}. ").append(m.name.ifBlank { "—" })
                val parts = ArrayList<String>()
                if (m.dosage.isNotBlank()) parts.add(m.dosage)
                if (m.frequency.isNotBlank()) parts.add(m.frequency)
                if (parts.isNotEmpty()) sb.append(" (${parts.joinToString(" • ")})")
                sb.appendLine()
                if (m.duration.isNotBlank()) sb.appendLine("   ${getString(R.string.duration_label)}: ${m.duration}")
                if (m.instructions.isNotBlank()) sb.appendLine("   ${m.instructions}")
            }
        }
        sb.appendLine()

        if (follow.isNotBlank()) {
            sb.appendLine(getString(R.string.follow_up_recommended))
            sb.appendLine(follow)
        }

        return sb.toString().trim()
    }

    private inner class MedAdapter(private val items: List<MedRow>) :
        RecyclerView.Adapter<MedVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedVH {
            val v = layoutInflater.inflate(R.layout.item_patient_prescribed_medicine, parent, false)
            return MedVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: MedVH, position: Int) {
            val m = items[position]
            h.tvName.text = m.name.ifBlank { "—" }

            val line1Parts = ArrayList<String>()
            if (m.dosage.isNotBlank()) line1Parts.add(m.dosage)
            if (m.frequency.isNotBlank()) line1Parts.add(m.frequency)
            h.tvMeta.text = line1Parts.joinToString("  •  ").ifBlank { "—" }

            if (m.duration.isNotBlank()) {
                h.tvDuration.isVisible = true
                h.tvDuration.text = getString(R.string.duration_fmt, m.duration)
            } else {
                h.tvDuration.isVisible = false
            }

            if (m.instructions.isNotBlank()) {
                h.rowInstruction.isVisible = true
                h.tvInstruction.text = m.instructions
            } else {
                h.rowInstruction.isVisible = false
            }
        }
    }

    private class MedVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvMName)
        val tvMeta: TextView = v.findViewById(R.id.tvMMeta)
        val tvDuration: TextView = v.findViewById(R.id.tvMDuration)
        val rowInstruction: View = v.findViewById(R.id.rowInstruction)
        val tvInstruction: TextView = v.findViewById(R.id.tvMInstruction)
    }

    private fun readLongAny(vararg keys: String): Long {
        for (k in keys) {
            val any = intent.extras?.get(k)
            when (any) {
                is Long -> if (any > 0L) return any
                is Int -> if (any > 0) return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun getJsonAuth(urlStr: String, token: String): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "Invalid server response")
            }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
