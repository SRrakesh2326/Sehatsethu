package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.simats.sehatsethu.LocalCache
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
import java.util.Locale
import java.util.TimeZone

class PatientRecordsFragment : Fragment() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var vLoading: View

    private val rows = ArrayList<Row>()
    private val allCache = ArrayList<Row>() // in-memory last full list

    private lateinit var adapter: PrescriptionsAdapter

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    private val tz by lazy { TimeZone.getTimeZone("Asia/Kolkata") }
    private val dfYmd by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = tz }
    }
    private val dfPretty by lazy {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply { timeZone = tz }
    }

    private val KEY_RECORDS_LIST_JSON = "patient_records_list_json"
    private val KEY_RECORDS_LIST_TS = "patient_records_list_ts"

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_myprescriptions, c, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        tvTitle = v.findViewById(R.id.tvTitle)
        tvSubtitle = v.findViewById(R.id.tvSubtitle)
        etSearch = v.findViewById(R.id.etSearch)
        rv = v.findViewById(R.id.rvPrescriptions)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        vLoading = v.findViewById(R.id.vLoading)

        tvTitle.text = getString(R.string.my_prescriptions)

        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = PrescriptionsAdapter(rows)
        rv.adapter = adapter

        // ✅ show cached immediately
        loadCachedListIntoUi()

        // Debounced search
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(280)
                    val q = etSearch.text?.toString()?.trim().orEmpty()

                    // ✅ if offline (or token invalid), do local filter from cache
                    if (q.isNotBlank()) {
                        val base = if (allCache.isNotEmpty()) allCache else rows
                        val filtered = base.filter { r ->
                            (r.title + " " + r.doctorName + " " + r.specialization + " " + r.dateLabel)
                                .lowercase(Locale.getDefault())
                                .contains(q.lowercase(Locale.getDefault()))
                        }
                        rows.clear()
                        rows.addAll(filtered)
                        adapter.notifyDataSetChanged()
                        setSubtitleCount(rows.size)
                        showEmpty(rows.isEmpty())
                        return@launch
                    } else {
                        // empty query -> restore full cache if present
                        if (allCache.isNotEmpty()) {
                            rows.clear()
                            rows.addAll(allCache)
                            adapter.notifyDataSetChanged()
                            setSubtitleCount(rows.size)
                            showEmpty(rows.isEmpty())
                            return@launch
                        }
                    }

                    // else try online refresh
                    load(q)
                }
            }
        })

        // Initial refresh online
        load("")
    }

    private fun loadCachedListIntoUi() {
        val cached = LocalCache.getString(requireContext(), KEY_RECORDS_LIST_JSON).orEmpty()
        if (cached.isBlank()) return

        val arr = runCatching { JSONArray(cached) }.getOrNull() ?: return
        val newList = ArrayList<Row>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            newList.add(parseRow(o))
        }

        rows.clear()
        rows.addAll(newList)
        adapter.notifyDataSetChanged()

        allCache.clear()
        allCache.addAll(newList)

        setSubtitleCount(rows.size)
        showEmpty(rows.isEmpty())
    }

    private fun load(q: String) {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val token = AppPrefs.getToken(requireContext()).orEmpty()

            // ✅ if token missing -> stay offline mode (use cache)
            if (token.isBlank()) {
                if (rows.isEmpty()) loadCachedListIntoUi()
                showEmpty(rows.isEmpty())
                return@launch
            }

            vLoading.isVisible = true

            val url = BASE_URL +
                    "patient/prescriptions_list.php?limit=200&offset=0&q=" +
                    URLEncoder.encode(q, "UTF-8") +
                    "&_ts=" + System.currentTimeMillis()

            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }
            if (!isAdded) return@launch

            vLoading.isVisible = false

            if (!res.optBoolean("ok", false)) {
                // ✅ keep cached visible instead of wiping
                if (rows.isEmpty()) loadCachedListIntoUi()
                showEmpty(rows.isEmpty())
                return@launch
            }

            val data = res.optJSONObject("data") ?: res
            val arr = data.optJSONArray("items")
                ?: data.optJSONArray("prescriptions")
                ?: JSONArray()

            val total = runCatching { data.optInt("total", arr.length()) }.getOrElse { arr.length() }

            val newList = ArrayList<Row>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                newList.add(parseRow(o))
            }

            rows.clear()
            rows.addAll(newList)
            adapter.notifyDataSetChanged()

            if (q.isEmpty()) {
                allCache.clear()
                allCache.addAll(newList)

                // ✅ cache full list for offline
                LocalCache.putString(requireContext(), KEY_RECORDS_LIST_JSON, arr.toString())
                LocalCache.putLong(requireContext(), KEY_RECORDS_LIST_TS, System.currentTimeMillis())
            }

            setSubtitleCount(if (total > 0) total else rows.size)
            showEmpty(rows.isEmpty())
        }
    }

    private fun parseRow(o: JSONObject): Row {
        val pid = o.optLong("id", 0L)
            .takeIf { it > 0 } ?: o.optLong("prescription_id", 0L)
            .takeIf { it > 0 } ?: 0L

        val title = o.optString("title", "").trim().ifBlank {
            if (pid > 0) "${getString(R.string.prescription)} #$pid" else getString(R.string.prescription)
        }

        val doctorName = o.optString("doctor_name", "").trim()
            .ifBlank { o.optString("doctor_full_name", "").trim() }
            .ifBlank { getString(R.string.doctor) }

        val speciality = o.optString("specialization", "").trim()
            .ifBlank { o.optString("doctor_specialization", "").trim() }
            .ifBlank { getString(R.string.general_physician) }

        val rawDate = o.optString("date", "").trim()
            .ifBlank { o.optString("created_at", "").trim() }
            .ifBlank { o.optString("issued_at", "").trim() }

        val dateYmd = extractYmd(rawDate)

        val medsCount = when {
            o.has("medicines_count") -> o.optInt("medicines_count", 0)
            o.has("meds_count") -> o.optInt("meds_count", 0)
            o.has("items_count") -> o.optInt("items_count", 0)
            else -> 0
        }.coerceAtLeast(0)

        val verified = when {
            o.has("doctor_verified") -> o.optInt("doctor_verified", 1) == 1
            o.has("verified") -> o.optBoolean("verified", true)
            else -> {
                val s = o.optString("admin_verification_status", "").trim().uppercase(Locale.getDefault())
                if (s.isNotBlank()) s == "VERIFIED" else true
            }
        }

        return Row(
            id = pid,
            title = title,
            doctorName = doctorName,
            specialization = speciality,
            dateLabel = prettyDate(dateYmd),
            medicinesLabel = getString(R.string.medicines_count_fmt, medsCount),
            verified = verified
        )
    }

    private fun extractYmd(s: String): String {
        val t = s.trim()
        if (t.isBlank()) return ""
        return if (t.length >= 10) t.substring(0, 10) else t
    }

    private fun prettyDate(ymd: String): String {
        val s = ymd.trim()
        if (s.isBlank()) return "—"
        return try {
            val d = dfYmd.parse(s) ?: return "—"
            dfPretty.format(d)
        } catch (_: Throwable) {
            "—"
        }
    }

    private fun setSubtitleCount(n: Int) {
        tvSubtitle.text = getString(R.string.prescriptions_count_fmt, n)
    }

    private fun showEmpty(show: Boolean) {
        tvEmpty.isVisible = show
        rv.isVisible = !show
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
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "Invalid server response")
            }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---------------- Adapter INSIDE Fragment ----------------

    private data class Row(
        val id: Long,
        val title: String,
        val doctorName: String,
        val specialization: String,
        val dateLabel: String,
        val medicinesLabel: String,
        val verified: Boolean
    )

    private inner class PrescriptionsAdapter(private val items: List<Row>) :
        RecyclerView.Adapter<PrescriptionVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrescriptionVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_patient_prescription, parent, false)
            return PrescriptionVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: PrescriptionVH, position: Int) {
            val r = items[position]
            h.tvTitle.text = r.title
            h.tvDoctor.text = r.doctorName
            h.tvSpec.text = r.specialization
            h.tvDate.text = r.dateLabel
            h.tvMeds.text = r.medicinesLabel
            h.ivVerified.isVisible = r.verified

            h.itemView.setOnClickListener {
                val itn = Intent(requireContext(), PatientPrescriptionDetailsActivity::class.java).apply {
                    putExtra("prescription_id", r.id)
                    putExtra("prescriptionId", r.id)
                    putExtra("id", r.id)
                }
                startActivity(itn)
                activity?.overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
            }
        }
    }

    private class PrescriptionVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvPTitle)
        val tvDoctor: TextView = v.findViewById(R.id.tvPDoctor)
        val tvSpec: TextView = v.findViewById(R.id.tvPSpec)
        val tvDate: TextView = v.findViewById(R.id.tvPDate)
        val tvMeds: TextView = v.findViewById(R.id.tvPMeds)
        val ivVerified: View = v.findViewById(R.id.ivVerified)
    }
}
