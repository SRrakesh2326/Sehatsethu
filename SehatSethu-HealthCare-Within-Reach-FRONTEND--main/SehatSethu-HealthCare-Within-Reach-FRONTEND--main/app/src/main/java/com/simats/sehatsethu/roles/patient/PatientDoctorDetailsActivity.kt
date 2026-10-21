package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PatientDoctorDetailsActivity : BaseActivity() {
    private var doctorFeeInr: Long = 0L
    private var doctorName: String = ""

    private var doctorId: Int = 0

    private lateinit var specialityKey: String
    private lateinit var specialityLabel: String

    private lateinit var rvToday: RecyclerView
    private lateinit var rvTomorrow: RecyclerView
    private lateinit var tvTodayEmpty: TextView
    private lateinit var tvTomorrowEmpty: TextView

    private lateinit var btnAudio: View
    private lateinit var btnVideo: View
    private lateinit var btnBooked: View

    private var hasActiveBooking = false
    private var activeBookingId: String = ""
    private var activeBookingPublicCode: String = ""
    private var shownBookedToast = false

    //  NEW: carry symptoms to booking
    private var symptomsText: String = ""

    private val dfIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_doctor_details)
        supportActionBar?.hide()

        doctorId = resolveDoctorId(intent)

        specialityKey =
            intent.getStringExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY)
                ?: intent.getStringExtra("speciality").orEmpty()

        specialityLabel =
            intent.getStringExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL)
                ?: intent.getStringExtra("speciality").orEmpty()

        //  NEW
        symptomsText = intent.getStringExtra(PatientDoctorListActivity.EXTRA_SYMPTOMS_TEXT).orEmpty()

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rvToday = findViewById(R.id.rvTodaySlots)
        rvTomorrow = findViewById(R.id.rvTomorrowSlots)
        tvTodayEmpty = findViewById(R.id.tvTodayEmpty)
        tvTomorrowEmpty = findViewById(R.id.tvTomorrowEmpty)

        btnAudio = findViewById(R.id.btnAudio)
        btnVideo = findViewById(R.id.btnVideo)
        btnBooked = findViewById(R.id.btnBooked)

        rvToday.layoutManager = GridLayoutManager(this, 3)
        rvTomorrow.layoutManager = GridLayoutManager(this, 3)
        rvToday.isNestedScrollingEnabled = false
        rvTomorrow.isNestedScrollingEnabled = false

        // safe default adapters
        rvToday.adapter = TimeChipAdapter(JSONArray()) { openSlots("AUDIO") }
        rvTomorrow.adapter = TimeChipAdapter(JSONArray()) { openSlots("AUDIO") }

        btnAudio.setOnClickListener { openSlots("AUDIO") }
        btnVideo.setOnClickListener { openSlots("VIDEO") }

        // Booked button now navigates to details
        btnBooked.setOnClickListener { openBookedAppointment() }

        // initial state
        btnBooked.visibility = View.GONE

        if (doctorId <= 0) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadDoctorFromDb()
        refreshGateAndSlots()
    }

    override fun onResume() {
        super.onResume()
        if (doctorId > 0) refreshGateAndSlots()
    }
    private fun parseFeeTextToLong(s: String?): Long {
        val raw = s?.trim().orEmpty()
        if (raw.isBlank()) return 0L

        val cleaned = raw
            .replace("₹", "")
            .replace("INR", "", true)
            .replace("Rs.", "", true)
            .replace("Rs", "", true)
            .replace(",", "")
            .trim()

        cleaned.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0L) }

        val m = Regex("""(\d+(\.\d+)?)""").find(cleaned)
        val num = m?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return 0L
        return num.toLong().coerceAtLeast(0L)
    }

    private fun refreshGateAndSlots() {
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                PatientApi.getDoctorBookingStatus(this@PatientDoctorDetailsActivity, doctorId)
            }

            val obj = (st?.optJSONObject("data") ?: st) ?: JSONObject()

            var gate = obj.optBoolean("hasActiveBooking", false)

            val status = obj.optString("status", "").trim().uppercase(Locale.US)
            val scheduledAt = obj.optString("scheduled_at", "").trim()

            //  If backend forgot to update status, don't block after time passed
            if (gate) {
                if (isTerminalStatus(status)) {
                    gate = false
                } else {
                    val scheduledMs = parseScheduledAtMillis(scheduledAt)
                    if (scheduledMs != null) {
                        val graceMs = 15L * 60L * 1000L
                        val nowMs = System.currentTimeMillis()
                        if (scheduledMs < (nowMs - graceMs) && status != "IN_PROGRESS") {
                            gate = false
                        }
                    }
                }
            }

            hasActiveBooking = gate
            activeBookingId = obj.optString("appointmentId", "").orEmpty()
            activeBookingPublicCode = obj.optString("public_code", "").orEmpty()

            applyBookingGateUi()
            loadSlotPreviewFromDb()
        }
    }

    private fun isTerminalStatus(s: String): Boolean {
        return when (s) {
            "COMPLETED", "FINISHED", "DONE", "CANCELLED", "CANCELED", "REJECTED", "FAILED", "NO_SHOW" -> true
            else -> false
        }
    }

    private fun parseScheduledAtMillis(s: String): Long? {
        val t = s.trim()
        if (t.isBlank()) return null

        val fmts = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS"
        )

        for (p in fmts) {
            try {
                val df = java.text.SimpleDateFormat(p, Locale.US).apply {
                    isLenient = true
                    timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                }
                val d = df.parse(t)
                if (d != null) return d.time
            } catch (_: Throwable) {}
        }
        return null
    }


    private fun applyBookingGateUi() {
        if (hasActiveBooking) {
            btnAudio.visibility = View.GONE
            btnVideo.visibility = View.GONE

            btnBooked.visibility = View.VISIBLE
            btnBooked.isEnabled = true
            btnBooked.isClickable = true
            btnBooked.alpha = 0.94f

            if (!shownBookedToast) {
                shownBookedToast = true
                Toast.makeText(
                    this,
                    getString(R.string.already_booked_with_doctor),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            btnAudio.visibility = View.VISIBLE
            btnVideo.visibility = View.VISIBLE

            btnBooked.visibility = View.GONE
            btnBooked.isEnabled = false
            btnBooked.isClickable = false
        }
    }

    private fun openBookedAppointment() {
        if (!hasActiveBooking) return

        if (activeBookingId.isBlank() && activeBookingPublicCode.isBlank()) {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            return
        }

        val itn = Intent(this, PatientAppointmentDetailsActivity::class.java).apply {
            if (activeBookingId.isNotBlank()) {
                putExtra("appointmentId", activeBookingId)
                putExtra("appointment_id", activeBookingId)
                putExtra("id", activeBookingId)
            }
            if (activeBookingPublicCode.isNotBlank()) {
                putExtra("public_code", activeBookingPublicCode)
                putExtra("publicCode", activeBookingPublicCode)
            }
            putExtra("doctorId", doctorId)
            putExtra("doctor_id", doctorId.toLong())
        }
        startActivity(itn)
    }

    private fun loadDoctorFromDb() {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                PatientApi.getDoctorDetail(this@PatientDoctorDetailsActivity, doctorId)
            }

            if (d == null) {
                Toast.makeText(
                    this@PatientDoctorDetailsActivity,
                    PatientApi.lastError ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            bindDoctor(d)
        }
    }

    private fun loadSlotPreviewFromDb() {
        lifecycleScope.launch {
            val daysArr = withContext(Dispatchers.IO) {
                PatientApi.getAvailableDays(this@PatientDoctorDetailsActivity, doctorId)
            }

            val todayIso = dfIso.format(Date())
            val tomorrowIso = dfIso.format(Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L))

            val todaySlots = extractEnabledSlots(daysArr, todayIso, maxSlots = 6)
            val tomorrowSlots = extractEnabledSlots(daysArr, tomorrowIso, maxSlots = 6)

            renderPreview(rvToday, tvTodayEmpty, todaySlots)
            renderPreview(rvTomorrow, tvTomorrowEmpty, tomorrowSlots)
        }
    }

    private fun extractEnabledSlots(daysArr: JSONArray?, dateIso: String, maxSlots: Int): JSONArray {
        val out = JSONArray()
        if (daysArr == null) return out

        var dayObj: JSONObject? = null
        for (i in 0 until daysArr.length()) {
            val o = daysArr.optJSONObject(i) ?: continue
            if (o.optString("date", "") == dateIso) { dayObj = o; break }
        }
        if (dayObj == null) return out
        if (!dayObj.optBoolean("enabled", false)) return out

        val sections = dayObj.optJSONArray("sections") ?: return out

        for (s in 0 until sections.length()) {
            val sec = sections.optJSONObject(s) ?: continue
            val slots = sec.optJSONArray("slots") ?: continue

            for (k in 0 until slots.length()) {
                val slot = slots.optJSONObject(k) ?: continue
                if (slot.optBoolean("disabled", false)) continue

                out.put(JSONObject().apply {
                    put("label", slot.optString("label", ""))
                    put("value", slot.optString("value", ""))
                })

                if (out.length() >= maxSlots) return out
            }
        }
        return out
    }

    private fun renderPreview(rv: RecyclerView, tvEmpty: TextView, slots: JSONArray) {
        if (slots.length() == 0) {
            rv.adapter = TimeChipAdapter(JSONArray()) { }
            tvEmpty.visibility = View.VISIBLE
            return
        }

        tvEmpty.visibility = View.GONE

        rv.adapter = if (hasActiveBooking) {
            TimeChipAdapter(slots) { }
        } else {
            TimeChipAdapter(slots) { openSlots("AUDIO") }
        }
    }

    private fun bindDoctor(d: JSONObject) {
        doctorName = d.optString("name", d.optString("full_name", "")).trim()
        setText(R.id.tvDocName, doctorName)
        setText(R.id.tvDocSpec, d.optString("specialization", d.optString("speciality", "")))

        setText(R.id.tvRating, d.optDouble("rating", 0.0).toString())
        setText(R.id.tvReviews, getString(R.string.n_reviews, d.optInt("reviews", 0)))

        setText(R.id.tvExp, d.optInt("experienceYears", d.optInt("experience", 0)).toString())
        setText(R.id.tvPatients, d.optInt("patients", 0).toString() + "+")

        // ✅ Fee: read from JSON robustly + store to pass to SelectTimeSlotActivity
        val feeFromJson: Long =
            when {
                d.has("feeInr") -> d.optLong("feeInr", 0L)
                d.has("fee") -> d.optLong("fee", d.optInt("fee", 0).toLong())
                d.has("consultFee") -> d.optLong("consultFee", 0L)
                d.has("price") -> d.optLong("price", 0L)
                else -> 0L
            }.coerceAtLeast(0L)

        doctorFeeInr = feeFromJson

        // render fee text (your existing UI)
        val feeText = if (doctorFeeInr > 0) "₹$doctorFeeInr" else "₹0"
        setText(R.id.tvFee, feeText)

        // if backend returned weird/0 but UI has something, fallback parse (safe)
        if (doctorFeeInr <= 0L) {
            val uiFee = findViewById<TextView>(R.id.tvFee)?.text?.toString()
            val parsed = parseFeeTextToLong(uiFee)
            if (parsed > 0L) doctorFeeInr = parsed
        }

        setText(R.id.tvAbout, d.optString("about", ""))
        setText(R.id.tvEducation, d.optString("education", ""))
        setText(R.id.tvWorksAt, d.optString("worksAt", ""))
        setText(R.id.tvLangs, d.optString("languages", ""))
    }


    private fun setText(id: Int, v: String) {
        findViewById<TextView>(id)?.text = v
    }

    private fun openSlots(type: String) {
        if (hasActiveBooking) {
            openBookedAppointment()
            return
        }

        // ✅ Fee from UI (tvFee shows like "₹450")
        val feeText = findViewById<TextView>(R.id.tvFee)?.text?.toString().orEmpty()

        fun parseFeeTextToLong(s: String?): Long {
            val raw = s?.trim().orEmpty()
            if (raw.isBlank()) return 0L

            val cleaned = raw
                .replace("₹", "")
                .replace("INR", "", true)
                .replace("Rs.", "", true)
                .replace("Rs", "", true)
                .replace(",", "")
                .trim()

            cleaned.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0L) }

            val m = Regex("""(\d+(\.\d+)?)""").find(cleaned)
            val num = m?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return 0L
            return num.toLong().coerceAtLeast(0L)
        }

        val feeInr = parseFeeTextToLong(feeText)

        val doctorName = findViewById<TextView>(R.id.tvDocName)?.text?.toString()?.trim().orEmpty()

        val itn = Intent(this, SelectTimeSlotActivity::class.java)

        itn.putExtra("doctorId", doctorId)
        itn.putExtra("doctor_id", doctorId.toLong())
        itn.putExtra("doctorIdLong", doctorId.toLong())
        itn.putExtra("doctor_id_str", doctorId.toString())

        itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY, specialityKey)
        itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL, specialityLabel)

        itn.putExtra("consultType", type)
        itn.putExtra("doctorName", doctorName)

        // ✅ PASS FEE HERE (this is what you were missing)
        itn.putExtra("feeInr", feeInr)     // long
        itn.putExtra("feeText", feeText)   // string fallback

        // carry symptoms
        itn.putExtra(PatientDoctorListActivity.EXTRA_SYMPTOMS_TEXT, symptomsText)

        startActivity(itn)
    }



    private fun resolveDoctorId(intent: Intent): Int {
        fun asIntFromString(s: String?): Int {
            return s?.trim()?.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        }

        val i1 = intent.getIntExtra("doctorId", 0)
        if (i1 > 0) return i1

        val i2 = intent.getIntExtra("doctor_id", 0)
        if (i2 > 0) return i2

        val l1 = intent.getLongExtra("doctor_id", 0L)
        if (l1 > 0L) return l1.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        val l2 = intent.getLongExtra("doctorIdLong", 0L)
        if (l2 > 0L) return l2.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        val s1 = asIntFromString(intent.getStringExtra("doctor_id"))
        if (s1 > 0) return s1

        val s2 = asIntFromString(intent.getStringExtra("doctorId"))
        if (s2 > 0) return s2

        val s3 = asIntFromString(intent.getStringExtra("doctor_id_str"))
        if (s3 > 0) return s3

        val s4 = asIntFromString(intent.getStringExtra("user_id"))
        if (s4 > 0) return s4

        val s5 = asIntFromString(intent.getStringExtra("id"))
        if (s5 > 0) return s5

        return 0
    }

    private class TimeChipAdapter(
        private val arr: JSONArray,
        private val onClick: (value: String) -> Unit
    ) : RecyclerView.Adapter<TimeChipVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): TimeChipVH {
            val view = android.view.LayoutInflater.from(p.context)
                .inflate(R.layout.item_time_chip, p, false)
            return TimeChipVH(view, onClick)
        }

        override fun getItemCount(): Int = arr.length()

        override fun onBindViewHolder(h: TimeChipVH, i: Int) {
            h.bind(arr.optJSONObject(i) ?: JSONObject())
        }
    }

    private class TimeChipVH(v: View, private val onClick: (String) -> Unit) :
        RecyclerView.ViewHolder(v) {

        private val tv = v.findViewById<TextView>(R.id.tvTime)

        fun bind(o: JSONObject) {
            val label = o.optString("label", "")
            val value = o.optString("value", "")
            tv.text = label
            itemView.isEnabled = value.isNotBlank()
            itemView.alpha = if (value.isBlank()) 0.4f else 1f
            itemView.setOnClickListener { if (value.isNotBlank()) onClick(value) }
        }
    }
}
