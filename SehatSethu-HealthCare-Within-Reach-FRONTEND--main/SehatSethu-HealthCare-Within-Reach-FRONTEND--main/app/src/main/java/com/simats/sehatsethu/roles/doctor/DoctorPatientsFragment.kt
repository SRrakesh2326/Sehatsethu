package com.simats.sehatsethu.roles.doctor

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class DoctorPatientsFragment : Fragment() {

    private enum class ViewMode { ALL, ACTIVE, RECOVERED }

    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var chipAll: TextView
    private lateinit var chipActive: TextView
    private lateinit var chipRecovered: TextView
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    private val master = ArrayList<Row>()
    private val rows = ArrayList<Row>()
    private lateinit var adapter: PatientsAdapter

    private var mode: ViewMode = ViewMode.ALL
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var lastRequestKey: String = ""
    private var watcher: TextWatcher? = null

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfServer by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.doctor_fragment_my_patients, c, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        tvSubtitle = v.findViewById(R.id.tvSubtitle)
        etSearch = v.findViewById(R.id.etSearch)
        chipAll = v.findViewById(R.id.chipAll)
        chipActive = v.findViewById(R.id.chipActive)
        chipRecovered = v.findViewById(R.id.chipRecovered)
        rv = v.findViewById(R.id.rvPatients)
        tvEmpty = v.findViewById(R.id.tvEmpty)

        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = PatientsAdapter(rows)
        rv.adapter = adapter

        chipAll.setOnClickListener { setMode(ViewMode.ALL) }
        chipActive.setOnClickListener { setMode(ViewMode.ACTIVE) }
        chipRecovered.setOnClickListener { setMode(ViewMode.RECOVERED) }

        watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                applyLocalFilter(q)
                debounceServerLoad()
            }
        }
        etSearch.addTextChangedListener(watcher)

        etSearch.imeOptions = EditorInfo.IME_ACTION_SEARCH
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                debounceServerLoad(immediate = true)
                true
            } else false
        }

        setMode(ViewMode.ALL)
    }

    override fun onDestroyView() {
        watcher?.let { etSearch.removeTextChangedListener(it) }
        watcher = null
        loadJob?.cancel()
        searchJob?.cancel()
        super.onDestroyView()
    }

    private fun setMode(m: ViewMode) {
        mode = m
        applyChipUi()
        debounceServerLoad(immediate = true)
    }

    private fun applyChipUi() {
        fun setActive(tv: TextView, active: Boolean) {
            tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF475569.toInt())
            tv.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
        }
        setActive(chipAll, mode == ViewMode.ALL)
        setActive(chipActive, mode == ViewMode.ACTIVE)
        setActive(chipRecovered, mode == ViewMode.RECOVERED)
    }

    private fun debounceServerLoad(immediate: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(350)
            loadFromServer()
        }
    }

    private fun loadFromServer() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val token = AppPrefs.getToken(requireContext()).orEmpty()
            if (token.isBlank()) {
                toast(getString(R.string.please_login_again))
                master.clear()
                rows.clear()
                adapter.notifyDataSetChanged()
                setSubtitleCount(0)
                showEmpty(true)
                return@launch
            }

            val viewParam = when (mode) {
                ViewMode.ALL -> "ALL"
                ViewMode.ACTIVE -> "ACTIVE"
                ViewMode.RECOVERED -> "RECOVERED"
            }

            val q = etSearch.text?.toString()?.trim().orEmpty()
            val requestKey = "$viewParam|$q"
            if (requestKey == lastRequestKey && master.isNotEmpty()) {
                applyLocalFilter(q)
                return@launch
            }
            lastRequestKey = requestKey

            val url = BASE_URL +
                    "doctor/patients_list.php?view=$viewParam&limit=500&offset=0&q=" +
                    URLEncoder.encode(q, "UTF-8") +
                    "&_ts=" + System.currentTimeMillis()

            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }
            if (!isAdded) return@launch

            if (!res.optBoolean("ok", false)) {
                master.clear()
                rows.clear()
                adapter.notifyDataSetChanged()
                setSubtitleCount(0)
                showEmpty(true)
                return@launch
            }

            val data = res.optJSONObject("data") ?: JSONObject()
            val total = data.optInt("total", 0)
            val arr = data.optJSONArray("items") ?: JSONArray()

            master.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                master.add(parseRow(o))
            }

            setSubtitleCount(total)
            applyLocalFilter(q, fromServer = true)
        }
    }

    private fun applyLocalFilter(queryRaw: String, fromServer: Boolean = false) {
        val q = queryRaw.trim().lowercase(Locale.getDefault())

        rows.clear()
        if (q.isBlank()) {
            rows.addAll(master)
        } else {
            for (r in master) {
                val hay =
                    (r.patientName + " " + r.meta + " " + r.issue + " " + r.lastVisitLabel)
                        .lowercase(Locale.getDefault())
                if (hay.contains(q)) rows.add(r)
            }
        }
        adapter.notifyDataSetChanged()
        showEmpty(rows.isEmpty())
    }

    private fun setSubtitleCount(n: Int) {
        tvSubtitle.text = getString(R.string.total_patients_fmt, n)
    }

    private fun showEmpty(show: Boolean) {
        tvEmpty.isVisible = show
        rv.isVisible = !show
    }

    private fun parseRow(o: JSONObject): Row {
        val patientId = optLongAny(o, "patient_id", "patientId", "id")

        val name = o.optString("patient_name", "").trim()
            .ifBlank { getString(R.string.default_patient_name) }

        val age = o.optInt("patient_age", 0)
        val genderRaw = o.optString("patient_gender", "").trim()
        val gender = genderRaw.ifBlank { getString(R.string.unknown_gender) }

        val issue = o.optString("last_issue", "").trim()
            .ifBlank { getString(R.string.default_patient_issue) }

        val statusStr = o.optString("status", "ACTIVE").trim().uppercase(Locale.getDefault())
        val lastVisitAt = o.optString("last_visit_at", "").trim()

        val meta =
            if (age > 0) getString(R.string.patient_meta_fmt, age, gender)
            else getString(R.string.patient_meta_no_age_fmt, gender)

        val lastVisitLabel = buildLastVisitLabel(lastVisitAt)

        return Row(
            patientId = patientId,
            patientName = name,
            meta = meta,
            issue = issue,
            status = if (statusStr == "RECOVERED") Status.RECOVERED else Status.ACTIVE,
            lastVisitLabel = lastVisitLabel,
            avatarType = if (genderRaw.equals("female", true)) Avatar.FEMALE else Avatar.MALE
        )
    }

    private fun buildLastVisitLabel(mysql: String): String {
        val ms = parseServerMs(mysql) ?: return getString(R.string.last_visit_unknown)
        val now = System.currentTimeMillis()
        val diff = abs(now - ms)

        val calNow = Calendar.getInstance(tz).apply { timeInMillis = now }
        val calThen = Calendar.getInstance(tz).apply { timeInMillis = ms }

        val sameDay =
            calNow.get(Calendar.YEAR) == calThen.get(Calendar.YEAR) &&
                    calNow.get(Calendar.DAY_OF_YEAR) == calThen.get(Calendar.DAY_OF_YEAR)

        if (sameDay) return getString(R.string.last_visit_today)

        val days = (diff / 86400000L).toInt().coerceAtLeast(1)
        return when {
            days < 7 -> getString(R.string.last_visit_days_ago, days)
            days < 30 -> {
                val weeks = (days / 7).coerceAtLeast(1)
                if (weeks == 1) getString(R.string.last_visit_week_ago)
                else getString(R.string.last_visit_weeks_ago, weeks)
            }
            else -> getString(R.string.last_visit_days_ago, days)
        }
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

    // ---------------- Adapter (INSIDE FRAGMENT) ----------------

    private enum class Status { ACTIVE, RECOVERED }
    private enum class Avatar { MALE, FEMALE }

    private data class Row(
        val patientId: Long,
        val patientName: String,
        val meta: String,
        val issue: String,
        val status: Status,
        val lastVisitLabel: String,
        val avatarType: Avatar
    )

    private inner class PatientsAdapter(private val items: List<Row>) :
        RecyclerView.Adapter<PatientVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_doctor_patient_card, parent, false)
            return PatientVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: PatientVH, position: Int) {
            val r = items[position]
            h.tvName.text = r.patientName
            h.tvMeta.text = r.meta
            h.tvCond.text = r.issue
            h.tvVisit.text = r.lastVisitLabel

            h.ivAvatar.setImageResource(
                if (r.avatarType == Avatar.FEMALE) R.drawable.ic_female else R.drawable.ic_male
            )

            if (r.status == Status.ACTIVE) {
                h.tvStatus.text = getString(R.string.status_active_pill)
                h.tvStatus.setTextColor(0xFF059669.toInt())
                h.tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
            } else {
                h.tvStatus.text = getString(R.string.status_recovered_pill)
                h.tvStatus.setTextColor(0xFF475569.toInt())
                h.tvStatus.setBackgroundResource(R.drawable.bg_badge_gray_soft)
            }

            //  CLICK → open patient record screen with all available data
            h.itemView.setOnClickListener {
                if (r.patientId <= 0L) {
                    toast(getString(R.string.failed))
                    return@setOnClickListener
                }
                val itn = Intent(requireContext(), DoctorPatientRecordsActivity::class.java).apply {
                    putExtra("patient_id", r.patientId)
                    putExtra("patientId", r.patientId)
                    putExtra("id", r.patientId)

                    putExtra("patientName", r.patientName)
                    putExtra("patientMeta", r.meta)
                    putExtra("patientIssue", r.issue)
                    putExtra("patientLastVisitLabel", r.lastVisitLabel)
                    putExtra("patientAvatar", if (r.avatarType == Avatar.FEMALE) "F" else "M")
                }
                startActivity(itn)
                requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
            }
        }
    }

    private class PatientVH(v: View) : RecyclerView.ViewHolder(v) {
        val ivAvatar: androidx.appcompat.widget.AppCompatImageView = v.findViewById(R.id.ivAvatar)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvMeta: TextView = v.findViewById(R.id.tvMeta)
        val tvCond: TextView = v.findViewById(R.id.tvCond)
        val tvVisit: TextView = v.findViewById(R.id.tvVisit)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
    }
}
