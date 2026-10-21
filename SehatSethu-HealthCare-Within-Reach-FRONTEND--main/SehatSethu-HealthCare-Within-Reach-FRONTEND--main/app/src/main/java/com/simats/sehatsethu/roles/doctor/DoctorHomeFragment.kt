package com.simats.sehatsethu.roles.doctor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
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
import kotlin.math.max

class DoctorHomeFragment : Fragment(R.layout.fragment_doctor_home) {

    private var isLoading = false

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfTime12 by lazy {
        SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = tz }
    }

    private var lastServerNowMs: Long = 0L
    private var lastServerNowClientMs: Long = 0L

    private var nextCanStartBackend: Boolean = false
    private var nextRoom: String = ""
    private var nextConsultType: String = ""
    private var nextPatientPhone: String = ""
    private var nextApptKey: String = ""
    private var nextPatientName: String = ""
    private var nextPatientMeta: String = ""
    private var nextScheduledAt: String = ""
    private var nextDurationMin: Int = 15

    private var nextPatientId: Long = 0L
    private var nextAppointmentId: Long = 0L

    // ✅ NEW: cache status so IN_PROGRESS works
    private var nextStatus: String = ""

    private var emptyApptView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setText(view, R.id.tvDoctorName, getString(R.string.default_doctor_name))
        setText(view, R.id.tvDoctorRole, getString(R.string.default_doctor_role))
        setBadge(view, 0)

        setText(view, R.id.tvPatients, getString(R.string.doctor_patients_fmt, 0))
        setText(view, R.id.tvCompleted, getString(R.string.doctor_completed_fmt, 0))
        setText(view, R.id.tvTotal, getString(R.string.doctor_amount_fmt, 0))
        setText(view, R.id.tvRating, getString(R.string.doctor_rating_fmt, "0.0"))

        setNextApptDefaults(view)
        hideExtraApptCards(view)
        clearNextCache()
        setStartVisible(view, false)
        hideEmptyApptMessage()

        view.findViewById<View>(R.id.notificationWrap).setOnClickListener {
            runCatching {
                startActivity(Intent(requireContext(), DoctorNotificationsActivity::class.java))
            }.onFailure {
                toast(getString(R.string.failed))
            }
        }

        view.findViewById<View>(R.id.tvViewAll).setOnClickListener {
            (activity as? DoctorActivity)?.openConsultTab()
                ?: navigateFallback(DoctorConsultFragment(), "DoctorConsultFragment")
        }

        view.findViewById<View>(R.id.btnStart).setOnClickListener { startNextIfAllowed() }

        view.findViewById<View>(R.id.cardMyPatients).setOnClickListener {
            (activity as? DoctorActivity)?.openPatientsTab()
                ?: navigateFallback(DoctorPatientsFragment(), "DoctorPatientsFragment")
        }

        view.findViewById<View>(R.id.cardSchedule).setOnClickListener {
            (activity as? DoctorActivity)?.openScheduleTab()
                ?: navigateFallback(DoctorScheduleFragment(), "DoctorScheduleFragment")
        }

        refresh(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { refresh(it) }
    }

    private fun navigateFallback(next: Fragment, backStackName: String) {
        if (!isAdded) return
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, next)
            .addToBackStack(backStackName)
            .commit()
    }

    private fun refresh(root: View) {
        if (isLoading) return
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = AppPrefs.getToken(requireContext()).orEmpty()
                if (token.isBlank()) {
                    toast(getString(R.string.please_login_again))
                    return@launch
                }

                val res = withContext(Dispatchers.IO) {
                    getJsonAuth(
                        BASE_URL + "doctor/home_dashboard.php?_ts=" + System.currentTimeMillis(),
                        token
                    )
                }

                if (!isAdded) return@launch
                if (!res.optBoolean("ok", false)) return@launch

                val data = res.optJSONObject("data") ?: JSONObject()

                val serverNowMs = data.optLong("server_now_ms", 0L)
                if (serverNowMs > 0L) {
                    lastServerNowMs = serverNowMs
                    lastServerNowClientMs = System.currentTimeMillis()
                }

                val doctorName = data.optString("doctor_name", "").trim()
                if (doctorName.isNotBlank()) setText(root, R.id.tvDoctorName, doctorName)

                val spec = data.optString("doctor_specialization", "").trim()
                if (spec.isNotBlank()) setText(root, R.id.tvDoctorRole, spec)

                val notif = data.optInt("notifications_count", 0).coerceAtLeast(0)
                setBadge(root, notif)

                val todayPatients = data.optInt("today_patients", 0).coerceAtLeast(0)
                val todayCompleted = data.optInt("today_completed", 0).coerceAtLeast(0)
                val todayAmount = data.optInt("today_amount", 0).coerceAtLeast(0)

                setText(root, R.id.tvPatients, getString(R.string.doctor_patients_fmt, todayPatients))
                setText(root, R.id.tvCompleted, getString(R.string.doctor_completed_fmt, todayCompleted))
                setText(root, R.id.tvTotal, getString(R.string.doctor_amount_fmt, todayAmount))

                val rating = data.optDouble("rating", 0.0)
                setText(root, R.id.tvRating, getString(R.string.doctor_rating_fmt, format1dp(rating)))

                val arr = data.optJSONArray("today_upcoming") ?: JSONArray()
                bindAppointments(root, arr)

            } catch (_: Throwable) {
            } finally {
                isLoading = false
            }
        }
    }

    private fun startNextIfAllowed() {
        if (!isAdded) return
        val root = view ?: return

        if (!shouldShowStartButton()) {
            setStartVisible(root, false)
            toast(getString(R.string.starting_unknown))
            return
        }

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            return
        }

        val apptKey = nextApptKey.ifBlank {
            if (nextAppointmentId > 0L) nextAppointmentId.toString() else ""
        }
        if (apptKey.isBlank()) {
            toast(getString(R.string.failed))
            return
        }

        // ✅ Mark COMPLETED on click, then proceed (video/audio)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    completeAppointment(token, apptKey)
                }
            } catch (_: Throwable) {
            } finally {
                if (!isAdded) return@launch

                if (nextConsultType.equals("VIDEO", true)) {
                    if (nextRoom.isBlank()) {
                        toast(getString(R.string.failed))
                        return@launch
                    }

                    val itn = Intent(requireContext(), DoctorVideoConsultationActivity::class.java).apply {
                        putExtra("room", nextRoom)
                        putExtra("name", getString(R.string.doctor))

                        if (nextApptKey.isNotBlank()) putExtra("appointmentKey", nextApptKey)
                        if (nextPatientName.isNotBlank()) putExtra("patientName", nextPatientName)
                        if (nextPatientMeta.isNotBlank()) putExtra("patientMeta", nextPatientMeta)

                        if (nextPatientId > 0L) {
                            putExtra("patient_id", nextPatientId)
                            putExtra("patientId", nextPatientId)
                            putExtra("pid", nextPatientId)
                        }
                        if (nextAppointmentId > 0L) {
                            putExtra("appointment_id", nextAppointmentId)
                            putExtra("appointmentId", nextAppointmentId)
                            putExtra("id", nextAppointmentId)
                            putExtra("apptId", nextAppointmentId)
                        }
                    }
                    startActivity(itn)
                } else {
                    val p = nextPatientPhone.trim()
                    if (p.isBlank()) {
                        toast(getString(R.string.failed))
                        return@launch
                    }
                    if (nextPatientId <= 0L) {
                        toast(getString(R.string.invalid_patient))
                        return@launch
                    }

                    val rxIntent = Intent(requireContext(), CreatePrescriptionActivity::class.java).apply {
                        putExtra("patient_id", nextPatientId)
                        putExtra("patientId", nextPatientId)
                        putExtra("pid", nextPatientId)

                        if (nextAppointmentId > 0L) {
                            putExtra("appointment_id", nextAppointmentId)
                            putExtra("appointmentId", nextAppointmentId)
                            putExtra("id", nextAppointmentId)
                            putExtra("apptId", nextAppointmentId)
                        }

                        if (nextApptKey.isNotBlank()) {
                            putExtra("appointmentKey", nextApptKey)
                            putExtra("public_code", nextApptKey)
                            putExtra("publicCode", nextApptKey)
                        }

                        if (nextPatientName.isNotBlank()) putExtra("patientName", nextPatientName)
                        if (nextPatientMeta.isNotBlank()) putExtra("patientMeta", nextPatientMeta)

                        putExtra("consult_type", "AUDIO")
                    }
                    startActivity(rxIntent)

                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p"))
                    startActivity(dialIntent)
                }
            }
        }
    }

    /**
     * ✅ Production rule unchanged:
     * Start button shows near time. BUT FIXED:
     * If status is IN_PROGRESS => allow until end (don’t hide/disable).
     */
    private fun shouldShowStartButton(): Boolean {
        // ✅ IN_PROGRESS must show until end
        if (nextStatus == "IN_PROGRESS" && nextScheduledAt.isNotBlank()) {
            return !isPastEnd(nextScheduledAt, nextDurationMin)
        }

        if (nextScheduledAt.isBlank()) return false

        val mins = minutesUntil(nextScheduledAt) ?: return false
        val within1Min = mins <= 1

        if (nextCanStartBackend) return within1Min
        return within1Min && !isPastEnd(nextScheduledAt, nextDurationMin)
    }

    private fun isPastEnd(scheduledAt: String, durationMin: Int): Boolean {
        val startMs = parseServerMs(scheduledAt) ?: return true
        val endMs = startMs + (durationMin.coerceAtLeast(1) * 60_000L)
        return nowMsReliable() >= endMs
    }

    private fun bindAppointments(root: View, arr: JSONArray) {
        if (arr.length() <= 0) {
            clearNextCache()
            setStartVisible(root, false)

            root.findViewById<View>(R.id.cardNextPatient)?.visibility = View.GONE
            root.findViewById<View>(R.id.cardAppt2)?.visibility = View.GONE
            root.findViewById<View>(R.id.cardAppt3)?.visibility = View.GONE

            showEmptyApptMessageUnderTodaysHeader(root)
            return
        }

        hideEmptyApptMessage()

        val first = arr.optJSONObject(0) ?: JSONObject()
        cacheNextFrom(first)

        root.findViewById<View>(R.id.cardNextPatient).visibility = View.VISIBLE
        root.findViewById<View>(R.id.cardAppt2).visibility = if (arr.length() >= 2) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.cardAppt3).visibility = if (arr.length() >= 3) View.VISIBLE else View.GONE

        bindOne(root, first, pos = 1)
        if (arr.length() >= 2) bindOne(root, arr.optJSONObject(1), pos = 2)
        if (arr.length() >= 3) bindOne(root, arr.optJSONObject(2), pos = 3)

        setStartVisible(root, shouldShowStartButton())
    }

    private fun cacheNextFrom(o: JSONObject) {
        val consultType = o.optString("consult_type", "").trim().uppercase(Locale.getDefault())
        val publicCode = o.optString("public_code", "").trim()
        val idAny = o.optString("id", "").trim()

        val scheduledAt = o.optString("scheduled_at", "").trim()
        val durMin = o.optInt("duration_min", 15).coerceAtLeast(1)

        nextConsultType = consultType
        nextScheduledAt = scheduledAt
        nextDurationMin = durMin

        // ✅ needs backend to include patient_id (we fixed home_dashboard.php below)
        nextPatientId = parseLongAny(o, "patient_id", "patientId")
        nextAppointmentId = parseLongAny(o, "appointment_id", "appointmentId", "id")

        nextRoom = o.optString("room", "").trim().ifBlank { roomFromPublicCode(publicCode) }

        nextPatientPhone = o.optString("patient_phone", "").trim()
        nextApptKey = if (publicCode.isNotBlank()) publicCode else idAny

        nextPatientName = o.optString("patient_name", "").trim()
            .ifBlank { getString(R.string.default_patient_name) }

        val age = o.optInt("patient_age", 0)
        val gender = o.optString("patient_gender", "").trim()
        nextPatientMeta = buildPatientMeta(age, gender)

        nextCanStartBackend = o.optBoolean("can_start", false)

        // ✅ NEW: status cache for IN_PROGRESS logic
        nextStatus = o.optString("status", "").trim().uppercase(Locale.getDefault())
    }

    private fun roomFromPublicCode(code: String): String {
        val clean = code.trim().replace(Regex("[^a-zA-Z0-9_]"), "_")
        return if (clean.isNotBlank()) "ss_appt_$clean" else ""
    }

    private fun parseLongAny(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val any = o.opt(k)
            when (any) {
                is Number -> if (any.toLong() > 0L) return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        return 0L
    }

    private fun clearNextCache() {
        nextCanStartBackend = false
        nextRoom = ""
        nextConsultType = ""
        nextPatientPhone = ""
        nextApptKey = ""
        nextPatientName = ""
        nextPatientMeta = ""
        nextScheduledAt = ""
        nextDurationMin = 15
        nextPatientId = 0L
        nextAppointmentId = 0L
        nextStatus = ""
    }

    private fun bindOne(root: View, o: JSONObject?, pos: Int) {
        if (o == null) return

        val patientName = o.optString("patient_name", "").trim()
            .ifBlank { getString(R.string.default_patient_name) }

        val age = o.optInt("patient_age", 0)
        val gender = o.optString("patient_gender", "").trim()
        val meta = buildPatientMeta(age, gender)

        val issue = o.optString("symptoms", "").trim()
            .ifBlank { getString(R.string.default_patient_issue) }

        val consultType = o.optString("consult_type", "").trim().uppercase(Locale.getDefault())
        val scheduledAt = o.optString("scheduled_at", "").trim()

        val timeLabel = formatTime12(scheduledAt)
        val minsToGo = minutesUntil(scheduledAt)
        val startingIn = when {
            minsToGo == null -> getString(R.string.starting_unknown)
            minsToGo <= 0 -> getString(R.string.starting_now)
            else -> getString(R.string.starting_in_min_fmt, minsToGo)
        }

        when (pos) {
            1 -> {
                setText(root, R.id.tvPatientName, patientName)
                setText(root, R.id.tvPatientMeta, meta)
                setText(root, R.id.tvPatientIssue, issue)
                setText(root, R.id.tvTime, timeLabel)
                setText(root, R.id.tvStartingIn, startingIn)
                applyConsultTag(root.findViewById(R.id.tagVideo), consultType)
            }
            2 -> {
                setText(root, R.id.p2Name, patientName)
                setText(root, R.id.p2Meta, meta)
                setText(root, R.id.p2Issue, issue)
                setText(root, R.id.p2Time, timeLabel)
                applyConsultTag(root.findViewById(R.id.tagAudio), consultType)
            }
            3 -> {
                setText(root, R.id.p3Name, patientName)
                setText(root, R.id.p3Meta, meta)
                setText(root, R.id.p3Issue, issue)
                setText(root, R.id.p3Time, timeLabel)
                applyConsultTag(root.findViewById(R.id.tagVideo2), consultType)
            }
        }
    }

    private fun applyConsultTag(tv: TextView, consultType: String) {
        val t = consultType.trim().uppercase(Locale.getDefault())

        when (t) {
            "AUDIO", "PHONE", "CALL" -> {
                tv.text = getString(R.string.audio)
                tv.visibility = View.VISIBLE
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_call1, 0, 0, 0)
                tv.compoundDrawablePadding = dp(6)
            }
            "VIDEO" -> {
                tv.text = getString(R.string.video)
                tv.visibility = View.VISIBLE
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_video, 0, 0, 0)
                tv.compoundDrawablePadding = dp(6)
            }
            else -> {
                tv.text = ""
                tv.visibility = View.GONE
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
        }
    }

    private fun hideExtraApptCards(root: View) {
        root.findViewById<View>(R.id.cardNextPatient).visibility = View.VISIBLE
        root.findViewById<View>(R.id.cardAppt2).visibility = View.GONE
        root.findViewById<View>(R.id.cardAppt3).visibility = View.GONE
    }

    private fun setNextApptDefaults(root: View) {
        setText(root, R.id.tvPatientName, getString(R.string.default_patient_name))
        setText(root, R.id.tvPatientMeta, getString(R.string.default_patient_meta))
        setText(root, R.id.tvPatientIssue, getString(R.string.default_patient_issue))
        setText(root, R.id.tvTime, "--:--")
        setText(root, R.id.tvStartingIn, getString(R.string.starting_unknown))

        root.findViewById<TextView>(R.id.tagVideo).apply {
            text = ""
            visibility = View.GONE
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun setStartVisible(root: View, visible: Boolean) {
        root.findViewById<View>(R.id.btnStart).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setBadge(root: View, count: Int) {
        val tv = root.findViewById<TextView>(R.id.tvBadge)
        tv.text = count.toString()
        tv.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun setText(root: View, id: Int, text: String) {
        root.findViewById<TextView>(id).text = text
    }

    private fun toast(s: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime12(server: String): String {
        val ms = parseServerMs(server) ?: return "--:--"
        return dfTime12.format(Date(ms))
    }

    private fun minutesUntil(server: String): Int? {
        val ms = parseServerMs(server) ?: return null
        val diff = ms - nowMsReliable()
        return (diff / 60000L).toInt()
    }

    private fun nowMsReliable(): Long {
        if (lastServerNowMs > 0L && lastServerNowClientMs > 0L) {
            val delta = System.currentTimeMillis() - lastServerNowClientMs
            return lastServerNowMs + max(0L, delta)
        }
        return System.currentTimeMillis()
    }

    private fun parseServerMs(server: String): Long? {
        val s = server.trim()
        if (s.isBlank()) return null
        return try { dfServer.parse(s)?.time } catch (_: Throwable) { null }
    }

    private fun buildPatientMeta(age: Int, gender: String): String {
        val g = gender.ifBlank { getString(R.string.unknown_gender) }
        return if (age > 0) getString(R.string.patient_meta_fmt, age, g)
        else getString(R.string.patient_meta_no_age_fmt, g)
    }

    private fun format1dp(v: Double): String {
        val safe = if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
        return String.format(Locale.getDefault(), "%.1f", safe)
    }

    private fun getJsonAuth(urlStr: String, token: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
        } catch (_: Throwable) {
            JSONObject().put("ok", false)
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    // ✅ NEW: mark completed using your existing endpoint
    private fun completeAppointment(token: String, appointmentKey: String): JSONObject {
        return postJsonAuth(
            BASE_URL + "doctor/appointment_create.php",
            token,
            JSONObject().put("appointment_key", appointmentKey)
        )
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

    // ---------------- Empty message under Today's appointments ----------------

    private fun showEmptyApptMessageUnderTodaysHeader(root: View) {
        val tvTodayAppt = root.findViewById<View>(R.id.tvTodayAppt) ?: return
        val headerRow = tvTodayAppt.parent as? View ?: return
        val listParent = headerRow.parent as? LinearLayout ?: run {
            val scroll = root.findViewById<NestedScrollView>(R.id.scroll) ?: return
            return (scroll.getChildAt(0) as? LinearLayout)?.let { ll ->
                showEmptyInLinear(ll, headerIndex = 1)
            } ?: Unit
        }

        val headerIndex = listParent.indexOfChild(headerRow).coerceAtLeast(0)
        showEmptyInLinear(listParent, headerIndex + 1)
    }

    private fun showEmptyInLinear(parent: LinearLayout, headerIndex: Int) {
        val msg = emptyApptView ?: TextView(requireContext()).also { emptyApptView = it }

        msg.text = getString(R.string.doctor_no_appointments_today)
        msg.visibility = View.VISIBLE
        msg.gravity = Gravity.START
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        msg.setTextColor(0xFF64748B.toInt())
        msg.setPadding(0, dp(10), 0, 0)

        msg.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 0
            bottomMargin = dp(8)
        }

        val oldParent = msg.parent as? ViewGroup
        if (oldParent != null && oldParent !== parent) oldParent.removeView(msg)

        if (msg.parent == null) {
            val idx = headerIndex.coerceIn(0, parent.childCount)
            parent.addView(msg, idx)
        } else {
            val curIdx = parent.indexOfChild(msg)
            val desired = headerIndex.coerceIn(0, parent.childCount - 1)
            if (curIdx != desired) {
                parent.removeView(msg)
                parent.addView(msg, desired)
            }
        }
    }

    private fun hideEmptyApptMessage() {
        emptyApptView?.visibility = View.GONE
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d).toInt()
    }
}
