package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

class PatientHomeFragment : Fragment() {

    interface HomeNav {
        fun openBookingsTab()
        fun openMedicineTab()
        fun openRecordsTab()
    }

    private lateinit var scroll: NestedScrollView
    private lateinit var rvUpcoming: RecyclerView
    private lateinit var tvEmptyUpcoming: TextView
    private lateinit var tvBadge: TextView
    private lateinit var tvName: TextView

    private val rows = ArrayList<ApptRow>()
    private lateinit var adapter: UpcomingAdapter

    private var badgeLoading = false
    private var lastUnreadCount = 0

    private var headerLoading = false
    private var lastHeaderName = ""

    private var pollingJob: Job? = null
    private var loadingUpcoming = false

    // ---- cache keys
    private val KEY_HOME_UPCOMING_JSON = "patient_home_upcoming_json"
    private val KEY_HOME_BADGE_COUNT = "patient_home_badge_count"
    private val KEY_HOME_HEADER_NAME = "patient_home_header_name"
    private val KEY_HOME_UPCOMING_TS = "patient_home_upcoming_ts"
    private val KEY_HOME_BADGE_TS = "patient_home_badge_ts"
    private val KEY_HOME_HEADER_TS = "patient_home_header_ts"

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_patient_home, c, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        scroll = v.findViewById(R.id.scroll)
        rvUpcoming = v.findViewById(R.id.rv_upcoming)
        tvEmptyUpcoming = v.findViewById(R.id.tv_empty_upcoming)
        tvBadge = v.findViewById(R.id.tv_badge)
        tvName = v.findViewById(R.id.tv_name)

        applyBottomInsetsToScroll()

        rvUpcoming.layoutManager = LinearLayoutManager(requireContext())
        rvUpcoming.setHasFixedSize(true)

        adapter = UpcomingAdapter(rows) { row ->
            val itn = Intent(requireContext(), PatientAppointmentDetailsActivity::class.java).apply {
                putExtra("appointment_id", row.appointmentId)
                putExtra("appointmentId", row.appointmentId)
                putExtra("id", row.appointmentId)

                if (row.publicCode.isNotBlank()) {
                    putExtra("public_code", row.publicCode)
                    putExtra("publicCode", row.publicCode)
                }

                if (row.doctorId > 0) {
                    putExtra("doctorId", row.doctorId)
                    putExtra("doctor_id", row.doctorId.toLong())
                }
            }
            startActivity(itn)
            requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }
        rvUpcoming.adapter = adapter

        // Tiles
        bindTile(v, R.id.card_book) {
            startActivity(Intent(requireContext(), SelectSpecialityActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }
        bindTile(v, R.id.card_ai) {
            startActivity(Intent(requireContext(), PatientAiSymptomCheckerActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }
        bindTile(v, R.id.card_med) { (activity as? HomeNav)?.openMedicineTab() }
        bindTile(v, R.id.card_records) { (activity as? HomeNav)?.openRecordsTab() }

        v.findViewById<View>(R.id.tv_view_all).setOnClickListener {
            (activity as? HomeNav)?.openBookingsTab()
        }

        v.findViewById<View>(R.id.box_bell).setOnClickListener {
            startActivity(Intent(requireContext(), PatientNotificationsActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }

        bindTile(v, R.id.card_emergency) {
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:108"))) }
        }

        // ✅ show cached immediately (offline fast)
        loadCachedHeaderName()
        loadCachedUpcoming()
        loadCachedBadge()

        // then refresh online (if token valid)
        loadHeaderName(force = true)
        loadUpcoming(force = true)
        loadUnreadBadge(force = true)
    }

    override fun onStart() {
        super.onStart()
        startRealtimeRefresh()
    }

    override fun onStop() {
        super.onStop()
        stopRealtimeRefresh()
    }

    override fun onResume() {
        super.onResume()
        // show cache instantly again (no flicker)
        loadCachedHeaderName()
        loadCachedUpcoming()
        loadCachedBadge()

        // refresh online
        loadHeaderName(force = false)
        loadUpcoming(force = true)
        loadUnreadBadge(force = true)
    }

    private fun startRealtimeRefresh() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                loadUpcoming(force = false)
                loadUnreadBadge(force = false)
            }
        }
    }

    private fun stopRealtimeRefresh() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun applyBottomInsetsToScroll() {
        val minBottom = dp(92)
        val baseBottom = max(scroll.paddingBottom, minBottom)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottom + sysBottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun bindTile(root: View, id: Int, action: () -> Unit) {
        val card = root.findViewById<View>(id) ?: return
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { action() }

        val vg = card as? ViewGroup
        if (vg != null && vg.childCount > 0) {
            val child = vg.getChildAt(0)
            child.isClickable = true
            child.isFocusable = false
            child.setOnClickListener { action() }
        }
    }

    // ----------------------------
    // ✅ Cached Header Name
    // ----------------------------
    private fun loadCachedHeaderName() {
        val cached = LocalCache.getString(requireContext(), KEY_HOME_HEADER_NAME).orEmpty().trim()
        if (cached.isNotBlank()) {
            lastHeaderName = cached
            tvName.text = formatNameForHeader(cached)
        }
    }

    // ----------------------------
    // Header name from dashboard.php
    // ----------------------------
    private fun loadHeaderName(force: Boolean) {
        if (headerLoading && !force) return

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) return // offline ok (cache already shown)

        headerLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) {
                    PatientApi.getDashboard(requireContext())
                }
                if (!isAdded) return@launch
                if (dash == null || dash.optBoolean("ok", false) != true) return@launch

                val fullName = extractFullNameFromDashboard(dash)
                if (fullName.isBlank()) return@launch

                if (fullName != lastHeaderName) {
                    lastHeaderName = fullName
                    tvName.text = formatNameForHeader(fullName)
                }

                // ✅ cache
                LocalCache.putString(requireContext(), KEY_HOME_HEADER_NAME, fullName)
                LocalCache.putLong(requireContext(), KEY_HOME_HEADER_TS, System.currentTimeMillis())

            } catch (t: Throwable) {
                Log.e("PatientHome", "loadHeaderName failed", t)
            } finally {
                headerLoading = false
            }
        }
    }

    private fun extractFullNameFromDashboard(root: JSONObject): String {
        fun clean(s: String?): String {
            val t = (s ?: "").trim()
            if (t.isBlank()) return ""
            if (t.equals("null", true)) return ""
            return t
        }
        val data = root.optJSONObject("data")
        val user = data?.optJSONObject("user") ?: root.optJSONObject("user")

        return clean(user?.optString("full_name"))
            .ifBlank { clean(data?.optString("full_name")) }
            .ifBlank { clean(root.optString("full_name")) }
    }

    private fun formatNameForHeader(name: String): String {
        val n = name.trim()
        if (n.isBlank()) return ""
        return if (n.contains("👋")) n else "$n 👋"
    }

    // ----------------------------
    // ✅ Cached Upcoming
    // ----------------------------
    private fun loadCachedUpcoming() {
        val cached = LocalCache.getString(requireContext(), KEY_HOME_UPCOMING_JSON).orEmpty()
        if (cached.isBlank()) return

        val arr = runCatching { JSONArray(cached) }.getOrNull() ?: return
        val newRows = ArrayList<ApptRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            ApptRow.from(o)?.let { newRows.add(it) }
        }
        if (newRows.isNotEmpty() || rows.isEmpty()) {
            rows.clear()
            rows.addAll(newRows)
            adapter.notifyDataSetChanged()
            showEmpty(rows.isEmpty())
        }
    }

    // ----------------------------
    // Upcoming loader (refresh online; fallback to cache on fail)
    // ----------------------------
    private fun loadUpcoming(force: Boolean) {
        if (loadingUpcoming && !force) return
        loadingUpcoming = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items: JSONArray? = withContext(Dispatchers.IO) {
                    PatientApi.listAppointments(
                        requireContext(),
                        view = "UPCOMING",
                        limit = 5,
                        offset = 0
                    )
                }

                if (!isAdded) return@launch

                if (items == null) {
                    // ✅ keep cached / keep current list
                    Log.e("PatientHome", "listAppointments(UPCOMING) null: ${PatientApi.lastError}")
                    if (rows.isEmpty()) loadCachedUpcoming()
                    showEmpty(rows.isEmpty())
                    return@launch
                }

                val newRows = ArrayList<ApptRow>()
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    ApptRow.from(o)?.let { newRows.add(it) }
                }

                rows.clear()
                rows.addAll(newRows)
                adapter.notifyDataSetChanged()
                showEmpty(rows.isEmpty())

                // ✅ cache raw array for offline
                LocalCache.putString(requireContext(), KEY_HOME_UPCOMING_JSON, items.toString())
                LocalCache.putLong(requireContext(), KEY_HOME_UPCOMING_TS, System.currentTimeMillis())

            } catch (t: Throwable) {
                if (!isAdded) return@launch
                Log.e("PatientHome", "loadUpcoming failed", t)
                if (rows.isEmpty()) loadCachedUpcoming()
                showEmpty(rows.isEmpty())
            } finally {
                loadingUpcoming = false
            }
        }
    }

    private fun showEmpty(empty: Boolean) {
        tvEmptyUpcoming.isVisible = empty
        rvUpcoming.isVisible = !empty
    }

    // ----------------------------
    // ✅ Cached Badge
    // ----------------------------
    private fun loadCachedBadge() {
        val c = LocalCache.getInt(requireContext(), KEY_HOME_BADGE_COUNT, 0)
        lastUnreadCount = c
        setBadge(c)
    }

    // ----------------------------
    // Unread badge loader (fallback to cached count)
    // ----------------------------
    private fun loadUnreadBadge(force: Boolean) {
        if (badgeLoading && !force) return

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            loadCachedBadge()
            return
        }

        badgeLoading = true

        val url = BASE_URL +
                "patient/notifications_list.php?unread=1&limit=200&_ts=" +
                System.currentTimeMillis()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { httpGetRaw(url, token) }
                if (!isAdded) return@launch

                if (raw == null) {
                    setBadge(lastUnreadCount)
                    return@launch
                }

                val body = raw.second
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json?.optBoolean("ok", false) != true) {
                    // token could be expired -> keep cached
                    setBadge(lastUnreadCount)
                    return@launch
                }

                val arr = json.optJSONArray("data") ?: JSONArray()
                val count = arr.length()
                lastUnreadCount = count
                setBadge(count)

                // ✅ cache badge
                LocalCache.putInt(requireContext(), KEY_HOME_BADGE_COUNT, count)
                LocalCache.putLong(requireContext(), KEY_HOME_BADGE_TS, System.currentTimeMillis())

            } finally {
                badgeLoading = false
            }
        }
    }

    private fun setBadge(count: Int) {
        if (!isAdded) return
        if (count <= 0) {
            tvBadge.visibility = View.GONE
            tvBadge.text = ""
            return
        }
        tvBadge.visibility = View.VISIBLE
        tvBadge.text = if (count > 99) "99+" else count.toString()
    }

    private fun httpGetRaw(urlStr: String, token: String): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Pair(code, text)
        } catch (t: Throwable) {
            Log.e("PatientHome", "GET error", t)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    // ----------------------------
    // Model + Adapter INSIDE fragment
    // ----------------------------

    data class ApptRow(
        val appointmentId: String,
        val publicCode: String,
        val doctorId: Int,
        val doctorName: String,
        val specialityKey: String,
        val consultType: String,
        val scheduledAt: String,
        val status: String,
        val feeAmount: Int
    ) {
        companion object {
            private fun parseIdAny(o: JSONObject, vararg keys: String): String {
                for (k in keys) {
                    val any = o.opt(k)
                    when (any) {
                        is Number -> if (any.toLong() > 0) return any.toLong().toString()
                        is String -> if (any.trim().isNotBlank() && any.trim().lowercase(Locale.US) != "null") return any.trim()
                    }
                }
                return ""
            }

            fun from(o: JSONObject): ApptRow? {
                val id = parseIdAny(o, "appointmentId", "appointment_id", "id")
                if (id.isBlank()) return null

                val publicCode = o.optString("public_code", o.optString("publicCode", ""))
                val doctorId = o.optInt("doctor_id", o.optInt("doctorId", 0))
                val doctorName = o.optString("doctor_name", o.optString("doctorName", ""))
                val spec = o.optString("speciality_key", o.optString("specialty", o.optString("specialization", "")))
                val consult = o.optString("consult_type", o.optString("consultType", ""))
                val scheduledAt = o.optString("scheduled_at", o.optString("scheduledAt", ""))
                val status = o.optString("status", "BOOKED")
                val fee = o.optInt("fee_amount", o.optInt("fee", 0))

                return ApptRow(
                    appointmentId = id,
                    publicCode = publicCode,
                    doctorId = doctorId,
                    doctorName = doctorName,
                    specialityKey = spec,
                    consultType = consult,
                    scheduledAt = scheduledAt,
                    status = status,
                    feeAmount = fee
                )
            }
        }
    }

    private class UpcomingAdapter(
        private val items: List<ApptRow>,
        private val onClick: (ApptRow) -> Unit
    ) : RecyclerView.Adapter<UpcomingAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_patient_appointment, parent, false)
            return VH(v, onClick)
        }

        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        class VH(itemView: View, private val onClick: (ApptRow) -> Unit) : RecyclerView.ViewHolder(itemView) {

            private val tvDoctor = itemView.findViewById<TextView>(R.id.tvDoctor)
            private val tvMeta = itemView.findViewById<TextView>(R.id.tvMeta)
            private val tvWhen = itemView.findViewById<TextView>(R.id.tvWhen)
            private val tvFee = itemView.findViewById<TextView>(R.id.tvFee)
            private val tvStatus = itemView.findViewById<TextView>(R.id.tvStatus)
            private val tvCode = itemView.findViewById<TextView>(R.id.tvCode)

            private val dfOut = SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.US)
            private val patterns = arrayOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            )

            fun bind(r: ApptRow) {
                itemView.setOnClickListener { onClick(r) }

                tvDoctor.text = r.doctorName.ifBlank { itemView.context.getString(R.string.doctor) }

                val meta = buildString {
                    if (r.specialityKey.isNotBlank()) append(r.specialityKey)
                    if (r.consultType.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(r.consultType.uppercase(Locale.US))
                    }
                }
                tvMeta.text = meta.ifBlank { " " }

                tvWhen.text = formatWhen(r.scheduledAt).ifBlank { " " }
                tvFee.text = "₹${r.feeAmount}"

                tvStatus.text = mapStatus(r.status)
                tvStatus.setBackgroundResource(bgForStatus(r.status))

                tvCode.isVisible = r.publicCode.isNotBlank()
                tvCode.text =
                    if (r.publicCode.isNotBlank())
                        itemView.context.getString(R.string.booking_code_fmt, r.publicCode)
                    else ""
            }

            private fun formatWhen(raw: String): String {
                val s = raw.trim()
                if (s.isBlank() || s.lowercase(Locale.US) == "null") return "—"
                for (p in patterns) {
                    try {
                        val inFmt = SimpleDateFormat(p, Locale.US)
                        val d = inFmt.parse(s)
                        if (d != null) return dfOut.format(d)
                    } catch (_: Throwable) { }
                }
                return s
            }

            private fun mapStatus(s: String): String {
                val x = s.uppercase(Locale.US)
                return when (x) {
                    "BOOKED" -> itemView.context.getString(R.string.status_booked)
                    "CONFIRMED" -> itemView.context.getString(R.string.status_confirmed)
                    "COMPLETED", "DONE" -> itemView.context.getString(R.string.status_completed)
                    "CANCELLED", "CANCELED" -> itemView.context.getString(R.string.status_cancelled)
                    "REJECTED" -> itemView.context.getString(R.string.status_rejected)
                    else -> x
                }
            }

            private fun bgForStatus(s: String): Int {
                val x = s.uppercase(Locale.US)
                return when (x) {
                    "BOOKED", "CONFIRMED" -> R.drawable.bg_status_ok
                    "COMPLETED", "DONE" -> R.drawable.bg_status_done
                    "CANCELLED", "CANCELED", "REJECTED" -> R.drawable.bg_status_bad
                    else -> R.drawable.bg_status_neutral
                }
            }
        }
    }
}
