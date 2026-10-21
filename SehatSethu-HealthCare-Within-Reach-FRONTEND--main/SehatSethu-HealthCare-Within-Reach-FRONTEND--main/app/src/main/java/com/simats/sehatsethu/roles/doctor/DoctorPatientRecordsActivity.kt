package com.simats.sehatsethu.roles.doctor

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DoctorPatientRecordsActivity : BaseActivity() {

    private lateinit var ivBack: View
    private lateinit var ivAvatar: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvVisitCount: TextView
    private lateinit var tvLastDate: TextView

    private lateinit var cgConditions: ChipGroup
    private lateinit var cgAllergies: ChipGroup

    private lateinit var rvPrescriptions: RecyclerView
    private lateinit var tvEmptyPrescriptions: TextView
    private lateinit var tvViewAllRx: TextView

    private lateinit var rvVitals: RecyclerView
    private lateinit var tvEmptyVitals: TextView

    private lateinit var btnStart: AppCompatButton

    private val allRx = ArrayList<RxRow>()
    private val shownRx = ArrayList<RxRow>()
    private lateinit var rxAdapter: RxAdapter
    private var rxExpanded = false

    private val allVitals = ArrayList<VitalRow>()
    private val shownVitals = ArrayList<VitalRow>()
    private lateinit var vitalsAdapter: VitalsAdapter

    private var patientId: Long = 0L
    private var latestRoom: String = ""
    private var latestPublicCode: String = ""
    private var latestAppointmentId: Long = 0L
    private var patientName: String = ""
    private var patientMeta: String = ""

    //  NEW: status + consult type from latest appointment
    private var latestStatus: String = ""
    private var latestConsultType: String = ""

    //  NEW: patient phone (for AUDIO dialer)
    private var patientPhone: String = ""

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }

    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfPrettyDate by lazy {
        SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    data class RxRow(
        val id: Long,
        val title: String,
        val createdAt: String,
        val itemsCount: Int
    )

    data class VitalRow(
        val id: Long,
        val whenMs: Long,
        val systolic: Int?,
        val diastolic: Int?,
        val sugar: Int?,
        val tempF: Double?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doctor_patient_record)
        supportActionBar?.hide()

        patientId = readLongAny("patient_id", "patientId", "pid")

        ivBack = findViewById(R.id.ivBack)
        ivAvatar = findViewById(R.id.ivAvatar)
        tvName = findViewById(R.id.tvName)
        tvInfo = findViewById(R.id.tvInfo)
        tvLocation = findViewById(R.id.tvLocation)
        tvVisitCount = findViewById(R.id.tvVisitCount)
        tvLastDate = findViewById(R.id.tvLastDate)

        cgConditions = findViewById(R.id.cgConditions)
        cgAllergies = findViewById(R.id.cgAllergies)

        rvPrescriptions = findViewById(R.id.rvPrescriptions)
        tvEmptyPrescriptions = findViewById(R.id.tvEmptyPrescriptions)
        tvViewAllRx = findViewById(R.id.tvViewAllRx)

        rvVitals = findViewById(R.id.rvVitals)
        tvEmptyVitals = findViewById(R.id.tvEmptyVitals)

        btnStart = findViewById(R.id.btnStart)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rvPrescriptions.layoutManager = LinearLayoutManager(this)
        rvPrescriptions.setHasFixedSize(false)
        rxAdapter = RxAdapter(shownRx)
        rvPrescriptions.adapter = rxAdapter

        rvVitals.layoutManager = LinearLayoutManager(this)
        rvVitals.setHasFixedSize(false)
        vitalsAdapter = VitalsAdapter(shownVitals)
        rvVitals.adapter = vitalsAdapter

        tvViewAllRx.setOnClickListener {
            rxExpanded = !rxExpanded
            updateShownPrescriptions()
            tvViewAllRx.text = if (rxExpanded) getString(R.string.show_less) else getString(R.string.view_all)
        }

        btnStart.setOnClickListener {
            if (!canStartConsultation()) {
                Toast.makeText(this, getString(R.string.no_active_appointment), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            //  VIDEO: open Jitsi
            if (latestConsultType == "VIDEO") {
                if (latestRoom.isBlank() || latestPublicCode.isBlank()) {
                    Toast.makeText(this, getString(R.string.no_active_appointment), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val itn = Intent(this, DoctorVideoConsultationActivity::class.java).apply {
                    putExtra("room", latestRoom)
                    putExtra("name", getString(R.string.doctor))
                    putExtra("appointmentKey", latestPublicCode)

                    putExtra("patient_id", patientId)
                    putExtra("patientId", patientId)
                    putExtra("pid", patientId)

                    if (latestAppointmentId > 0L) {
                        putExtra("appointment_id", latestAppointmentId)
                        putExtra("appointmentId", latestAppointmentId)
                        putExtra("id", latestAppointmentId)
                        putExtra("apptId", latestAppointmentId)
                    }

                    if (patientName.isNotBlank()) putExtra("patientName", patientName)
                    if (patientMeta.isNotBlank()) putExtra("patientMeta", patientMeta)
                }

                startActivity(itn)
                overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
                return@setOnClickListener
            }

            //  AUDIO: open CreatePrescriptionActivity + dialer
            if (latestConsultType == "AUDIO") {
                val rxIntent = Intent(this, CreatePrescriptionActivity::class.java).apply {
                    putExtra("patient_id", patientId)
                    putExtra("patientId", patientId)
                    putExtra("pid", patientId)

                    if (latestAppointmentId > 0L) {
                        putExtra("appointment_id", latestAppointmentId)
                        putExtra("appointmentId", latestAppointmentId)
                        putExtra("id", latestAppointmentId)
                        putExtra("apptId", latestAppointmentId)
                    }

                    if (latestPublicCode.isNotBlank()) {
                        putExtra("appointmentKey", latestPublicCode)
                        putExtra("public_code", latestPublicCode)
                        putExtra("publicCode", latestPublicCode)
                    }

                    if (patientName.isNotBlank()) putExtra("patientName", patientName)
                    if (patientMeta.isNotBlank()) putExtra("patientMeta", patientMeta)

                    putExtra("consult_type", "AUDIO")
                }
                startActivity(rxIntent)
                overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)

                val p = patientPhone.trim()
                if (p.isNotBlank()) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p")))
                }
                return@setOnClickListener
            }

            Toast.makeText(this, getString(R.string.no_active_appointment), Toast.LENGTH_SHORT).show()
        }

        // default hidden until we load + decide
        btnStart.visibility = View.GONE
        btnStart.isEnabled = false

        if (patientId <= 0L) {
            Toast.makeText(this, getString(R.string.invalid_patient), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadRecord()
    }

    private fun loadRecord() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.please_login_again), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    urlStr = BASE_URL + "doctor/patient_record.php",
                    token = token,
                    body = JSONObject().apply { put("patient_id", patientId) }
                )
            }

            if (!res.optBoolean("ok", false)) {
                Toast.makeText(this@DoctorPatientRecordsActivity, getString(R.string.failed_to_load), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()
            bindUi(data)
        }
    }

    //  Start allowed when latest appointment is IN_PROGRESS (VIDEO or AUDIO)
    private fun canStartConsultation(): Boolean {
        val st = latestStatus.trim().uppercase(Locale.getDefault())
        val ct = latestConsultType.trim().uppercase(Locale.getDefault())
        val inProgress = (st == "IN_PROGRESS" || st == "INPROGRESS")
        return inProgress && (ct == "VIDEO" || ct == "AUDIO")
    }

    private fun applyStartButtonVisibility() {
        val show = canStartConsultation() && (
                (latestConsultType == "VIDEO" && latestRoom.isNotBlank() && latestPublicCode.isNotBlank()) ||
                        (latestConsultType == "AUDIO")
                )
        btnStart.visibility = if (show) View.VISIBLE else View.GONE
        btnStart.isEnabled = show
        btnStart.alpha = if (show) 1f else 0.6f
    }

    @SuppressLint("SetTextI18n")
    private fun bindUi(data: JSONObject) {
        val p = data.optJSONObject("patient") ?: JSONObject()
        val stats = data.optJSONObject("stats") ?: JSONObject()

        patientName = p.optString("full_name", "").trim()
        val age = p.optInt("age", 0)
        val gender = p.optString("gender", "").trim()
        val village = p.optString("village", "").trim()
        val blood = p.optString("blood_group", "").trim()

        //  try multiple keys for phone (safe)
        patientPhone = p.optString("phone", "").trim()
            .ifBlank { p.optString("mobile", "").trim() }
            .ifBlank { p.optString("patient_phone", "").trim() }

        tvName.text = if (patientName.isNotBlank()) patientName else getString(R.string.default_patient_name)

        val genderLabel = if (gender.isNotBlank()) gender else getString(R.string.unknown_gender)
        patientMeta = if (age > 0) {
            if (blood.isNotBlank()) "${age} ${getString(R.string.years_short)}, $genderLabel  •  $blood"
            else "${age} ${getString(R.string.years_short)}, $genderLabel"
        } else {
            if (blood.isNotBlank()) "$genderLabel  •  $blood"
            else genderLabel
        }
        tvInfo.text = patientMeta

        tvLocation.text = if (village.isNotBlank()) village else getString(R.string.location_unknown)

        ivAvatar.setImageResource(
            if (gender.equals("female", true)) R.drawable.ic_female else R.drawable.ic_male
        )

        tvVisitCount.text = stats.optInt("total_visits", 0).toString()
        tvLastDate.text = formatLastVisit(stats.optString("last_visit_at", "").trim())

        // -----------------------------
        //  Latest appointment gating
        // -----------------------------
        val la = data.optJSONObject("latest_appointment")

        latestRoom = la?.optString("room", "")?.trim().orEmpty()
        latestPublicCode = la?.optString("public_code", "")?.trim().orEmpty()
        latestAppointmentId = optLongAny(la, "appointment_id")

        latestStatus = (la?.optString("status", "") ?: "").trim()
        latestConsultType = (
                la?.optString("consult_type", "")
                    ?: la?.optString("type", "")
                    ?: ""
                ).trim().uppercase(Locale.getDefault())

        //  fallback: sometimes phone comes from latest appointment
        if (patientPhone.isBlank()) {
            patientPhone = la?.optString("patient_phone", "")?.trim().orEmpty()
        }

        applyStartButtonVisibility()

        // Medical history chips (robust keys)
        bindMedicalChips(
            chronic = firstArray(
                p.optJSONArray("chronic_conditions"),
                p.optJSONArray("conditions"),
                data.optJSONArray("chronic_conditions")
            ),
            allergies = firstArray(
                p.optJSONArray("allergies"),
                data.optJSONArray("allergies")
            )
        )

        // Prescriptions
        allRx.clear()
        val rxArr = data.optJSONArray("prescriptions")
        if (rxArr != null) {
            for (i in 0 until rxArr.length()) {
                val o = rxArr.optJSONObject(i) ?: continue
                allRx.add(
                    RxRow(
                        id = optLongAny(o, "id"),
                        title = o.optString("title", "").ifBlank { getString(R.string.prescription) },
                        createdAt = o.optString("created_at", ""),
                        itemsCount = o.optInt("items_count", 0)
                    )
                )
            }
        }

        rxExpanded = false
        updateShownPrescriptions()

        val hasRx = allRx.isNotEmpty()
        tvEmptyPrescriptions.visibility = if (hasRx) View.GONE else View.VISIBLE
        rvPrescriptions.visibility = if (hasRx) View.VISIBLE else View.GONE
        tvViewAllRx.visibility = if (allRx.size > 2) View.VISIBLE else View.GONE
        tvViewAllRx.text = getString(R.string.view_all)

        // Vitals
        allVitals.clear()
        val vitArr = data.optJSONArray("vitals_history")
        if (vitArr != null) {
            for (i in 0 until vitArr.length()) {
                val o = vitArr.optJSONObject(i) ?: continue
                allVitals.add(
                    VitalRow(
                        id = optLongAny(o, "id"),
                        whenMs = vitalWhenMs(o),
                        systolic = optIntNullable(o, "systolic"),
                        diastolic = optIntNullable(o, "diastolic"),
                        sugar = optIntNullable(o, "sugar"),
                        tempF = optDoubleNullable(o, "temperature_f")
                    )
                )
            }
        }

        shownVitals.clear()
        val maxVitals = minOf(3, allVitals.size)
        for (i in 0 until maxVitals) shownVitals.add(allVitals[i])
        vitalsAdapter.notifyDataSetChanged()

        val hasVitals = allVitals.isNotEmpty()
        tvEmptyVitals.visibility = if (hasVitals) View.GONE else View.VISIBLE
        rvVitals.visibility = if (hasVitals) View.VISIBLE else View.GONE
    }

    private fun bindMedicalChips(chronic: JSONArray?, allergies: JSONArray?) {
        cgConditions.removeAllViews()
        cgAllergies.removeAllViews()

        val chronicList = readStrings(chronic)
        val allergyList = readStrings(allergies)

        if (chronicList.isEmpty()) {
            cgConditions.addView(makeChip(getString(R.string.none), isChronic = true))
        } else {
            for (s in chronicList) cgConditions.addView(makeChip(s, isChronic = true))
        }

        if (allergyList.isEmpty()) {
            cgAllergies.addView(makeChip(getString(R.string.none), isChronic = false))
        } else {
            for (s in allergyList) cgAllergies.addView(makeChip(s, isChronic = false))
        }
    }

    private fun makeChip(text: String, isChronic: Boolean): Chip {
        return Chip(this).apply {
            this.text = text
            isClickable = false
            isCheckable = false
            setTextColor(if (isChronic) 0xFFDC2626.toInt() else 0xFFB45309.toInt())
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                if (isChronic) 0xFFFEE2E2.toInt() else 0xFFFEF3C7.toInt()
            )
            chipCornerRadius = 12f
        }
    }

    private fun updateShownPrescriptions() {
        shownRx.clear()
        if (allRx.isEmpty()) {
            rxAdapter.notifyDataSetChanged()
            return
        }
        val max = if (rxExpanded) allRx.size else minOf(2, allRx.size)
        for (i in 0 until max) shownRx.add(allRx[i])
        rxAdapter.notifyDataSetChanged()
    }

    private inner class RxAdapter(private val items: List<RxRow>) :
        RecyclerView.Adapter<RxVH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RxVH {
            val v = layoutInflater.inflate(R.layout.item_doctor_prescription_row_for_doctor, parent, false)
            return RxVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: RxVH, position: Int) {
            val r = items[position]
            h.tvTitle.text = r.title

            val datePretty = formatCreatedAt(r.createdAt)
            val medLabel = resources.getQuantityString(R.plurals.medicines_count_fmt, r.itemsCount, r.itemsCount)
            h.tvMeta.text = "$medLabel  •  $datePretty"
        }
    }

    private class RxVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvRxTitle)
        val tvMeta: TextView = v.findViewById(R.id.tvRxMeta)
    }

    private inner class VitalsAdapter(private val items: List<VitalRow>) :
        RecyclerView.Adapter<VitalVH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VitalVH {
            val v = layoutInflater.inflate(R.layout.item_doctor_vital_row, parent, false)
            return VitalVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VitalVH, position: Int) {
            val v = items[position]
            h.tvDate.text = dfPrettyDate.format(Date(v.whenMs))

            h.tvBpValue.text = if (v.systolic != null && v.diastolic != null) "${v.systolic}/${v.diastolic}" else "—"
            h.tvSugarValue.text = if (v.sugar != null) "${v.sugar} mg/dL" else "—"

            h.tvTempValue.text = if (v.tempF != null) {
                val t = String.format(Locale.getDefault(), "%.1f", v.tempF)
                "$t°F"
            } else "—"
        }
    }

    private class VitalVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvVitalDate)
        val tvBpValue: TextView = v.findViewById(R.id.tvBpValue)
        val tvSugarValue: TextView = v.findViewById(R.id.tvSugarValue)
        val tvTempValue: TextView = v.findViewById(R.id.tvTempValue)
    }

    private fun formatLastVisit(mysql: String): String {
        if (mysql.isBlank()) return getString(R.string.last_visit_unknown)
        return try {
            val dt = dfServer.parse(mysql)
            if (dt != null) dfPrettyDate.format(dt) else getString(R.string.last_visit_unknown)
        } catch (_: Throwable) {
            getString(R.string.last_visit_unknown)
        }
    }

    private fun formatCreatedAt(mysql: String): String {
        if (mysql.isBlank()) return getString(R.string.date_unknown)
        return try {
            val dt = dfServer.parse(mysql)
            if (dt != null) dfPrettyDate.format(dt) else getString(R.string.date_unknown)
        } catch (_: Throwable) {
            getString(R.string.date_unknown)
        }
    }

    private fun vitalWhenMs(o: JSONObject): Long {
        val client = o.opt("client_recorded_at_ms")
        val ms = when (client) {
            is Number -> client.toLong()
            is String -> client.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        if (ms > 0L) return ms

        val createdAt = o.optString("created_at", "").trim()
        if (createdAt.isNotBlank()) {
            try {
                val dt = dfServer.parse(createdAt)
                if (dt != null) return dt.time
            } catch (_: Throwable) {}
        }
        return System.currentTimeMillis()
    }

    private fun optLongAny(o: JSONObject?, key: String): Long {
        if (o == null) return 0L
        val any = o.opt(key)
        return when (any) {
            is Number -> any.toLong()
            is String -> any.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun optIntNullable(o: JSONObject, key: String): Int? {
        if (!o.has(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun optDoubleNullable(o: JSONObject, key: String): Double? {
        if (!o.has(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
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
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
        } catch (_: Throwable) {
            JSONObject().put("ok", false)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun firstArray(vararg arr: JSONArray?): JSONArray? {
        for (a in arr) if (a != null && a.length() > 0) return a
        return arr.firstOrNull()
    }

    private fun readStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) {
                val t = v.trim()
                if (t.isNotBlank()) out.add(t)
            }
        }
        return out
    }
}
