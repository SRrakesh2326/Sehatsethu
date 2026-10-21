package com.simats.sehatsethu.roles.doctor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max

class DoctorConsultFragment : Fragment() {

    private enum class ViewMode { ALL, UPCOMING, COMPLETED }

    private lateinit var tvCount: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipUpcoming: TextView
    private lateinit var chipCompleted: TextView
    private lateinit var etSearch: EditText
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    private val rows = ArrayList<Row>()
    private lateinit var adapter: ConsultAdapter

    private var mode: ViewMode = ViewMode.ALL
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var watcher: TextWatcher? = null

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfTime by lazy {
        SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfDatePretty by lazy {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    private var lastServerNowMs: Long = 0L
    private var lastServerNowClientMs: Long = 0L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_doctor_consultations, c, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        tvCount = v.findViewById(R.id.tvCount)
        chipAll = v.findViewById(R.id.chipAll)
        chipUpcoming = v.findViewById(R.id.chipUpcoming)
        chipCompleted = v.findViewById(R.id.chipCompleted)
        etSearch = v.findViewById(R.id.etSearch)
        rv = v.findViewById(R.id.rvConsultations)
        tvEmpty = v.findViewById(R.id.tvEmpty)

        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = ConsultAdapter(rows)
        rv.adapter = adapter

        chipAll.setOnClickListener { setMode(ViewMode.ALL) }
        chipUpcoming.setOnClickListener { setMode(ViewMode.UPCOMING) }
        chipCompleted.setOnClickListener { setMode(ViewMode.COMPLETED) }

        watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    load()
                }
            }
        }
        etSearch.addTextChangedListener(watcher)

        setMode(ViewMode.ALL)
    }

    override fun onDestroyView() {
        watcher?.let { etSearch.removeTextChangedListener(it) }
        watcher = null
        searchJob?.cancel()
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun setMode(m: ViewMode) {
        mode = m
        applyChipUi()
        load()
    }

    private fun applyChipUi() {
        fun setActive(tv: TextView, active: Boolean) {
            tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            tv.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
        }
        setActive(chipAll, mode == ViewMode.ALL)
        setActive(chipUpcoming, mode == ViewMode.UPCOMING)
        setActive(chipCompleted, mode == ViewMode.COMPLETED)
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val token = AppPrefs.getToken(requireContext()).orEmpty()
            if (token.isBlank()) {
                toast(getString(R.string.please_login_again))
                rows.clear()
                adapter.notifyDataSetChanged()
                updateCount(0)
                showEmpty(true)
                return@launch
            }

            val q = etSearch.text?.toString()?.trim().orEmpty()
            val viewParam = when (mode) {
                ViewMode.ALL -> "ALL"
                ViewMode.UPCOMING -> "UPCOMING"
                ViewMode.COMPLETED -> "COMPLETED"
            }

            val url = BASE_URL +
                    "doctor/appointments_list.php?view=$viewParam&limit=300&offset=0&q=" +
                    java.net.URLEncoder.encode(q, "UTF-8") +
                    "&_ts=" + System.currentTimeMillis()

            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }
            if (!isAdded) return@launch

            if (!res.optBoolean("ok", false)) {
                rows.clear()
                adapter.notifyDataSetChanged()
                updateCount(0)
                showEmpty(true)
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()

            val serverNowMs = data.optLong("server_now_ms", 0L)
            if (serverNowMs > 0L) {
                lastServerNowMs = serverNowMs
                lastServerNowClientMs = System.currentTimeMillis()
            }

            val total = data.optInt("total", 0)
            val items = data.optJSONArray("items") ?: JSONArray()

            rows.clear()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                rows.add(parseRow(o))
            }

            adapter.notifyDataSetChanged()
            updateCount(total)
            showEmpty(rows.isEmpty())
        }
    }

    private fun updateCount(n: Int) {
        tvCount.text = getString(R.string.total_count_fmt, n)
    }

    private fun showEmpty(show: Boolean) {
        tvEmpty.isVisible = show
        rv.isVisible = !show
    }

    private fun nowMsReliable(): Long {
        if (lastServerNowMs > 0L && lastServerNowClientMs > 0L) {
            val delta = System.currentTimeMillis() - lastServerNowClientMs
            return lastServerNowMs + max(0L, delta)
        }
        return System.currentTimeMillis()
    }

    private fun parseRow(o: JSONObject): Row {
        val apptId = optLongAny(o, "id", "appointment_id", "appointmentId")
        val publicCode = o.optString("public_code", "").trim()

        val patientId = optLongAny(o, "patient_id", "patientId", "pid", "patient_user_id", "user_id")

        val room = o.optString("room", "").trim().ifBlank {
            if (publicCode.isNotBlank()) "ss_appt_${publicCode.replace(Regex("[^a-zA-Z0-9_]"), "_")}" else ""
        }

        val patientName = o.optString("patient_name", "").trim()
            .ifBlank { getString(R.string.default_patient_name) }

        val age = o.optInt("patient_age", 0)
        val gender = o.optString("patient_gender", "").trim()

        val symptoms = o.optString("symptoms", "").trim()
            .ifBlank { getString(R.string.default_patient_issue) }

        val patientPhone = o.optString("patient_phone", "").trim()

        val consultType = o.optString("consult_type", "").trim().uppercase(Locale.getDefault())
        val status = o.optString("status", "").trim().uppercase(Locale.getDefault())
        val scheduledAt = o.optString("scheduled_at", "").trim()
        val durationMin = o.optInt("duration_min", 15).coerceAtLeast(1)

        val meta = if (age > 0) getString(
            R.string.patient_meta_fmt,
            age,
            gender.ifBlank { getString(R.string.unknown_gender) }
        ) else getString(
            R.string.patient_meta_no_age_fmt,
            gender.ifBlank { getString(R.string.unknown_gender) }
        )

        val (dateLabel, timeLabel) = formatDateTimeLabels(scheduledAt)

        val pill = when (status) {
            "COMPLETED", "DONE", "NO_SHOW" -> Pill.DONE
            else -> Pill.UPCOMING
        }

        val action = when (consultType) {
            "AUDIO" -> Action.CALL
            else -> Action.VIDEO
        }

        val canStartFromApi = o.optBoolean("can_start", false)

        val scheduledMs = parseServerMs(scheduledAt) ?: 0L
        val endMs = if (scheduledMs > 0) scheduledMs + (durationMin * 60_000L) else 0L
        val secLeft = if (scheduledMs > 0) ((scheduledMs - nowMsReliable()) / 1000L).toInt() else 999999

        val canStartFallback = if (scheduledMs > 0 && endMs > 0) {
            val earlyMs = scheduledMs - 120_000L
            val now = nowMsReliable()
            now >= earlyMs && now < endMs
        } else false

        // ✅ FIX: IN_PROGRESS must ALWAYS be startable (until end) + still block DONE
        val canStart = when (status) {
            "IN_PROGRESS" -> pill != Pill.DONE
            else -> (canStartFromApi || canStartFallback) && pill != Pill.DONE
        }

        return Row(
            appointmentId = apptId,
            publicCode = publicCode,
            room = room,
            patientId = patientId,
            patientName = patientName,
            meta = meta,
            symptoms = symptoms,
            patientPhone = patientPhone,
            pill = pill,
            action = action,
            consultType = consultType,
            status = status,
            dateLabel = dateLabel,
            timeLabel = timeLabel,
            scheduledAt = scheduledAt,
            secondsToStart = secLeft,
            canStart = canStart
        )
    }

    private fun openPatientRecord(row: Row) {
        val itn = Intent(requireContext(), DoctorPatientRecordsActivity::class.java).apply {
            putExtra("patient_id", row.patientId)
            putExtra("patientId", row.patientId)
            putExtra("id", row.patientId)

            putExtra("patientName", row.patientName)
            putExtra("patientMeta", row.meta)
            putExtra("patientIssue", row.symptoms)
            putExtra("patientLastVisitLabel", row.dateLabel)
        }
        startActivity(itn)
        requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
    }

    private fun resolvePatientIdAndOpen(row: Row) {
        val apptId = row.appointmentId
        if (apptId <= 0L) {
            toast(getString(R.string.failed))
            return
        }

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val url = BASE_URL + "doctor/appointment_patient.php?appointment_id=$apptId&_ts=" + System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }
            if (!isAdded) return@launch

            if (!res.optBoolean("ok", false)) {
                toast(getString(R.string.failed))
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()
            val pid = optLongAny(data, "patient_id", "patientId", "id")
            if (pid <= 0L) {
                toast(getString(R.string.failed))
                return@launch
            }

            openPatientRecord(row.copy(patientId = pid))
        }
    }

    private fun formatDateTimeLabels(mysql: String): Pair<String, String> {
        val ms = parseServerMs(mysql) ?: return Pair("—", "—")
        val dt = Date(ms)
        val time = dfTime.format(dt)

        val cal = Calendar.getInstance(tz)
        val todayY = cal.get(Calendar.YEAR)
        val todayD = cal.get(Calendar.DAY_OF_YEAR)

        cal.time = dt
        val y = cal.get(Calendar.YEAR)
        val d = cal.get(Calendar.DAY_OF_YEAR)

        val dateLabel = when {
            y == todayY && d == todayD -> getString(R.string.today)
            else -> dfDatePretty.format(dt)
        }
        return Pair(dateLabel, time)
    }

    private fun parseServerMs(server: String): Long? {
        val s = server.trim()
        if (s.isBlank()) return null
        return try { dfServer.parse(s)?.time } catch (_: Throwable) { null }
    }

    private fun toast(s: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
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
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
        } catch (_: Throwable) {
            JSONObject().put("ok", false)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ✅ NEW: call your endpoint to mark COMPLETED (does not break anything if it fails)
    private suspend fun completeAppointment(token: String, appointmentKey: String): JSONObject {
        val body = JSONObject().put("appointment_key", appointmentKey)
        return postJsonAuth(BASE_URL + "doctor/appointment_create.php", token, body)
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

    private fun optLongAny(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val any = o.opt(k)
            when (any) {
                is Number -> return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    // ---------------- Adapter ----------------

    private enum class Pill { UPCOMING, DONE }
    private enum class Action { VIDEO, CALL }

    private data class Row(
        val appointmentId: Long,
        val publicCode: String,
        val room: String,
        val patientId: Long,
        val patientName: String,
        val meta: String,
        val symptoms: String,
        val patientPhone: String,
        val pill: Pill,
        val action: Action,
        val consultType: String,
        val status: String,
        val dateLabel: String,
        val timeLabel: String,
        val scheduledAt: String,
        val secondsToStart: Int,
        val canStart: Boolean
    )

    private inner class ConsultAdapter(private val items: List<Row>) :
        RecyclerView.Adapter<ConsultVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConsultVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_doctor_consultation, parent, false)
            return ConsultVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: ConsultVH, position: Int) {
            val r = items[position]

            h.itemView.setOnClickListener {
                if (r.patientId > 0L) openPatientRecord(r)
                else resolvePatientIdAndOpen(r)
            }

            h.tvName.text = r.patientName
            h.tvMeta.text = r.meta
            h.tvIssue.text = r.symptoms
            h.tvDate.text = r.dateLabel
            h.tvTime.text = r.timeLabel

            when (r.pill) {
                Pill.UPCOMING -> {
                    h.tvStatus.text = getString(R.string.upcoming)
                    h.tvStatus.setBackgroundResource(R.drawable.bg_pill_upcoming)
                    h.dot.setImageResource(R.drawable.ic_dot_green)
                    h.dot.isVisible = true
                }
                Pill.DONE -> {
                    h.tvStatus.text = getString(R.string.done)
                    h.tvStatus.setBackgroundResource(R.drawable.bg_pill_done)
                    h.dot.isVisible = false
                }
            }

            when (r.action) {
                Action.VIDEO -> {
                    h.btnAction.setBackgroundResource(R.drawable.bg_action_blue)
                    h.ivAction.setImageResource(R.drawable.ic_video_white)
                }
                Action.CALL -> {
                    h.btnAction.setBackgroundResource(R.drawable.bg_action_green)
                    h.ivAction.setImageResource(R.drawable.ic_phone_white)
                }
            }

            h.btnAction.alpha = if (r.canStart) 1f else 0.55f
            h.btnAction.isEnabled = r.canStart

            h.btnAction.setOnClickListener {
                if (!r.canStart) {
                    val mins = ceil(r.secondsToStart / 60.0).toInt().coerceAtLeast(0)
                    toast(
                        if (mins <= 0) getString(R.string.starting_now)
                        else getString(R.string.starting_in_min_fmt, mins)
                    )
                    return@setOnClickListener
                }

                val token = AppPrefs.getToken(requireContext()).orEmpty()
                if (token.isBlank()) {
                    toast(getString(R.string.please_login_again))
                    return@setOnClickListener
                }

                val apptKey = if (r.publicCode.isNotBlank()) r.publicCode else r.appointmentId.toString()

                // ✅ Mark COMPLETED when user clicks action (phone/video) then proceed
                h.btnAction.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { completeAppointment(token, apptKey) }
                    } catch (_: Throwable) {
                        // do nothing; still proceed
                    } finally {
                        if (!isAdded) return@launch

                        if (r.consultType.equals("VIDEO", true)) {
                            if (r.room.isBlank()) {
                                toast(getString(R.string.failed))
                                h.btnAction.isEnabled = true
                                return@launch
                            }
                            val itn = Intent(requireContext(), DoctorVideoConsultationActivity::class.java).apply {
                                putExtra("room", r.room)
                                putExtra("name", getString(R.string.doctor))
                                putExtra("appointmentKey", apptKey)

                                // pass ids too (safe)
                                if (r.patientId > 0L) {
                                    putExtra("patient_id", r.patientId)
                                    putExtra("patientId", r.patientId)
                                    putExtra("pid", r.patientId)
                                }
                                if (r.appointmentId > 0L) {
                                    putExtra("appointment_id", r.appointmentId)
                                    putExtra("appointmentId", r.appointmentId)
                                    putExtra("id", r.appointmentId)
                                    putExtra("apptId", r.appointmentId)
                                }

                                putExtra("patientName", r.patientName)
                                putExtra("patientMeta", r.meta)
                            }
                            startActivity(itn)
                        } else {
                            val p = r.patientPhone.trim()
                            if (p.isBlank()) {
                                toast(getString(R.string.failed))
                                h.btnAction.isEnabled = true
                                return@launch
                            }
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p")))
                        }

                        h.btnAction.isEnabled = true
                    }
                }
            }
        }
    }

    private class ConsultVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvMeta: TextView = v.findViewById(R.id.tvMeta)
        val tvIssue: TextView = v.findViewById(R.id.tvIssue)
        val tvDate: TextView = v.findViewById(R.id.tvDate)
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
        val dot: ImageView = v.findViewById(R.id.ivDot)
        val btnAction: View = v.findViewById(R.id.btnAction)
        val ivAction: ImageView = v.findViewById(R.id.ivAction)
    }
}
