package com.simats.sehatsethu.roles.patient

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PatientConsultationSummaryActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var tvTitle: TextView

    private lateinit var tvBannerTitle: TextView
    private lateinit var tvBannerSub: TextView

    private lateinit var tvDoctorName: TextView
    private lateinit var tvDoctorSpec: TextView

    private lateinit var tvDiagnosis: TextView
    private lateinit var tvNotes: TextView

    private lateinit var rvMeds: RecyclerView
    private lateinit var tvViewFull: TextView

    private lateinit var cardFollow: View

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnHome: MaterialButton

    private val meds = ArrayList<MedRow>()
    private lateinit var adapter: MedAdapter

    private var appointmentId: Long = 0L
    private var appointmentKey: String = ""
    private var prescriptionId: Long = 0L

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz } }
    private val dfPretty by lazy { SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).apply { timeZone = tz } }

    data class MedRow(val name: String, val line1: String, val line2: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_consultation_summary)
        supportActionBar?.hide()

        appointmentId = readLongAny("appointment_id", "appointmentId", "id", "apptId")
        prescriptionId = readLongAny("prescription_id", "prescriptionId", "pid_rx")
        appointmentKey = intent.getStringExtra("appointmentKey")
            ?: intent.getStringExtra("appointment_key")
                    ?: intent.getStringExtra("public_code")
                    ?: intent.getStringExtra("publicCode")
                    ?: ""

        ivBack = findViewById(R.id.ivBack)
        tvTitle = findViewById(R.id.tvTitle)

        tvBannerTitle = findViewById(R.id.tvBannerTitle)
        tvBannerSub = findViewById(R.id.tvBannerSub)

        tvDoctorName = findViewById(R.id.tvDoctorName)
        tvDoctorSpec = findViewById(R.id.tvDoctorSpec)

        tvDiagnosis = findViewById(R.id.tvDiagnosis)
        tvNotes = findViewById(R.id.tvNotes)

        rvMeds = findViewById(R.id.rvMeds)
        tvViewFull = findViewById(R.id.tvViewFull)

        cardFollow = findViewById(R.id.cardFollow)

        btnDownload = findViewById(R.id.btnDownload)
        btnShare = findViewById(R.id.btnShare)
        btnHome = findViewById(R.id.btnHome)

        ivBack.setOnClickListener { finishWithAnim() }

        rvMeds.layoutManager = LinearLayoutManager(this)
        adapter = MedAdapter(meds)
        rvMeds.adapter = adapter

        tvViewFull.setOnClickListener {
            Toast.makeText(this, getString(R.string.view_full_prescription), Toast.LENGTH_SHORT).show()
        }

        btnHome.setOnClickListener { finishWithAnim() }
        btnDownload.setOnClickListener { createPdf(andShare = false) }
        btnShare.setOnClickListener { createPdf(andShare = true) }

        loadSummary()
    }

    override fun onBackPressed() {
        finishWithAnim()
    }

    private fun finishWithAnim() {
        finish()
        overridePendingTransition(R.anim.ai_pop_enter, R.anim.ai_pop_exit)
    }

    private fun loadSummary() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finishWithAnim()
            return
        }

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    BASE_URL + "patient/consultation_summary.php",
                    token,
                    JSONObject().apply {
                        if (appointmentId > 0L) put("appointment_id", appointmentId)
                        if (appointmentKey.isNotBlank()) put("appointment_key", appointmentKey)
                        if (prescriptionId > 0L) put("prescription_id", prescriptionId)
                    }
                )
            }

            if (!res.optBoolean("ok", false)) {
                toast(res.optString("error", getString(R.string.failed_to_load)))
                finishWithAnim()
                return@launch
            }

            bind(res.optJSONObject("data") ?: JSONObject())
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bind(d: JSONObject) {
        tvTitle.text = getString(R.string.consultation_summary)

        tvBannerTitle.text = getString(R.string.consultation_completed)

        val completedAt = d.optString("completed_at", "").trim()
        val durationMin = d.optInt("duration_minutes", 0)

        val datePretty = prettyDate(completedAt)
        val durPretty = if (durationMin > 0) getString(R.string.duration_minutes_fmt, durationMin)
        else getString(R.string.duration_minutes_unknown)

        tvBannerSub.text = "$datePretty  •  $durPretty"

        tvDoctorName.text = d.optString("doctor_name", "").ifBlank { getString(R.string.doctor) }
        tvDoctorSpec.text = d.optString("doctor_speciality", "").ifBlank { getString(R.string.speciality_general_physician) }

        val diagnosis = d.optString("diagnosis", "").trim()
        tvDiagnosis.text = if (diagnosis.isNotBlank()) diagnosis else getString(R.string.consultation_summary_unavailable)

        val notes = d.optString("doctor_notes", "").trim()
        tvNotes.text = if (notes.isNotBlank()) notes else getString(R.string.consultation_summary_unavailable)

        meds.clear()
        val arr = d.optJSONArray("medicines") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isBlank()) continue
            meds.add(
                MedRow(
                    name = name,
                    line1 = o.optString("line1", "").trim(),
                    line2 = o.optString("line2", "").trim()
                )
            )
        }
        adapter.notifyDataSetChanged()

        tvViewFull.visibility = if (meds.isNotEmpty()) View.VISIBLE else View.GONE

        // Follow-up not stored by your prescription flow currently
        cardFollow.visibility = View.GONE
    }

    private fun prettyDate(mysql: String): String {
        if (mysql.isBlank()) return getString(R.string.date_unknown)
        return try {
            val dt = dfServer.parse(mysql)
            if (dt != null) dfPretty.format(dt) else getString(R.string.date_unknown)
        } catch (_: Throwable) {
            getString(R.string.date_unknown)
        }
    }

    private fun createPdf(andShare: Boolean) {
        val outDir = File(cacheDir, "summaries").apply { mkdirs() }
        val outFile = File(outDir, "consultation_summary_${System.currentTimeMillis()}.pdf")

        try {
            val doc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas

            val p = android.graphics.Paint().apply { isAntiAlias = true }
            var y = 60f

            p.textSize = 18f
            c.drawText(tvTitle.text.toString(), 40f, y, p)
            y += 28f

            p.textSize = 14f
            c.drawText(tvBannerTitle.text.toString(), 40f, y, p); y += 18f
            c.drawText(tvBannerSub.text.toString(), 40f, y, p); y += 22f

            c.drawText("${tvDoctorName.text} - ${tvDoctorSpec.text}", 40f, y, p); y += 24f

            p.textSize = 15f
            c.drawText(getString(R.string.diagnosis), 40f, y, p); y += 18f
            p.textSize = 13f
            c.drawText(tvDiagnosis.text.toString(), 40f, y, p); y += 22f

            p.textSize = 15f
            c.drawText(getString(R.string.doctors_notes), 40f, y, p); y += 18f
            p.textSize = 13f
            y = drawWrapped(c, p, tvNotes.text.toString(), 40f, y, 520f, 18f) + 10f

            p.textSize = 15f
            c.drawText(getString(R.string.prescribed_medicines), 40f, y, p); y += 18f
            p.textSize = 13f

            meds.forEachIndexed { idx, m ->
                c.drawText("${idx + 1}. ${m.name}", 40f, y, p); y += 16f
                if (m.line1.isNotBlank()) { c.drawText(m.line1, 60f, y, p); y += 15f }
                if (m.line2.isNotBlank()) { c.drawText(m.line2, 60f, y, p); y += 15f }
                y += 8f
            }

            doc.finishPage(page)
            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()

            if (!andShare) {
                toast(getString(R.string.downloaded))
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".fileprovider",
                outFile
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share)))

        } catch (_: Throwable) {
            toast(getString(R.string.failed))
        }
    }

    private fun drawWrapped(
        canvas: android.graphics.Canvas,
        paint: android.graphics.Paint,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float
    ): Float {
        var y = startY
        val words = text.split(Regex("\\s+"))
        var line = ""
        for (w in words) {
            val test = if (line.isBlank()) w else "$line $w"
            if (paint.measureText(test) <= maxWidth) {
                line = test
            } else {
                canvas.drawText(line, x, y, paint)
                y += lineHeight
                line = w
            }
        }
        if (line.isNotBlank()) {
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return y
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun readLongAny(vararg keys: String): Long {
        val b = intent.extras
        if (b != null) {
            for (k in keys) {
                when (val any = b.get(k)) {
                    is Long -> if (any > 0L) return any
                    is Int -> if (any > 0) return any.toLong()
                    is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
                }
            }
        }
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun postJsonAuth(urlStr: String, token: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false).put("error", "Bad response") }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private inner class MedAdapter(private val items: List<MedRow>) :
        RecyclerView.Adapter<MedVH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MedVH {
            val v = layoutInflater.inflate(R.layout.item_prescribed_medicine, parent, false)
            return MedVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: MedVH, position: Int) {
            val m = items[position]
            h.tvName.text = m.name
            h.tvLine1.text = m.line1
            h.tvLine2.text = m.line2
            h.tvLine1.visibility = if (m.line1.isBlank()) View.GONE else View.VISIBLE
            h.tvLine2.visibility = if (m.line2.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private class MedVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvMedName)
        val tvLine1: TextView = v.findViewById(R.id.tvMedLine1)
        val tvLine2: TextView = v.findViewById(R.id.tvMedLine2)
    }
}
