package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class PatientBookingsFragment : Fragment(R.layout.fragment_patient_bookings) {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private lateinit var toggleTabs: MaterialButtonToggleGroup
    private lateinit var btnUpcoming: MaterialButton
    private lateinit var btnPast: MaterialButton
    private lateinit var btnAll: MaterialButton

    private lateinit var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView

    private val rows = ArrayList<AppointmentRow>()
    private lateinit var adapter: AppointmentAdapter

    private var currentView: String = "UPCOMING"
    private var ignoreTabCallback = false

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        tvTitle = v.findViewById(R.id.tvTitle)
        tvSubtitle = v.findViewById(R.id.tvSubtitle)

        toggleTabs = v.findViewById(R.id.toggleTabs)
        btnUpcoming = v.findViewById(R.id.btnUpcoming)
        btnPast = v.findViewById(R.id.btnPast)
        btnAll = v.findViewById(R.id.btnAll)

        swipe = v.findViewById(R.id.swipe)
        rv = v.findViewById(R.id.rvAppointments)

        emptyBox = v.findViewById(R.id.boxEmpty)
        tvEmptyTitle = v.findViewById(R.id.tvEmptyTitle)
        tvEmptySub = v.findViewById(R.id.tvEmptySub)

        tvTitle.text = getString(R.string.bookings_title)
        tvSubtitle.text = getString(R.string.bookings_subtitle)

        adapter = AppointmentAdapter(rows) { row ->
            val itn = Intent(requireContext(), PatientAppointmentDetailsActivity::class.java).apply {
                // IMPORTANT: details API expects appointment_id (id or public_code)
                putExtra("appointment_id", row.appointmentId)
                putExtra("appointmentId", row.appointmentId)
                putExtra("id", row.appointmentId)

                if (row.publicCode.isNotBlank()) {
                    putExtra("public_code", row.publicCode)
                    putExtra("publicCode", row.publicCode)
                }

                putExtra("doctorId", row.doctorId)
                putExtra("doctor_id", row.doctorId.toLong())
            }
            startActivity(itn)
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.isNestedScrollingEnabled = true

        swipe.setOnRefreshListener { load() }

        toggleTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || ignoreTabCallback) return@addOnButtonCheckedListener
            currentView = when (checkedId) {
                R.id.btnUpcoming -> "UPCOMING"
                R.id.btnPast -> "PAST"
                else -> "ALL"
            }
            load()
        }

        //  force Upcoming selected initially (even after returning)
        ignoreTabCallback = true
        toggleTabs.check(R.id.btnUpcoming)
        ignoreTabCallback = false
        currentView = "UPCOMING"

        load()
    }

    private fun load() {
        swipe.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            // Always fetch ALL so IN_PROGRESS never gets dropped by backend view filters
            val items = withContext(Dispatchers.IO) {
                PatientApi.listAppointments(requireContext(), "ALL", limit = 200, offset = 0)
            }

            if (!isAdded) return@launch
            swipe.isRefreshing = false

            if (items == null) {
                showEmpty(
                    title = getString(R.string.bookings_empty_title),
                    sub = PatientApi.lastError ?: getString(R.string.failed)
                )
                return@launch
            }

            val all = ArrayList<AppointmentRow>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                all.add(AppointmentRow.from(o))
            }

            val filtered = when (currentView) {
                "UPCOMING" -> all.filter { isUpcoming(it) }
                "PAST" -> all.filter { isPast(it) }
                else -> all
            }

            rows.clear()
            rows.addAll(filtered.sortedWith(compareBy<AppointmentRow> {
                // sort by scheduled time (unknown times go last)
                parseTimeMs(it.scheduledAt) ?: Long.MAX_VALUE
            }))

            adapter.notifyDataSetChanged()

            if (rows.isEmpty()) {
                val t = when (currentView) {
                    "PAST" -> getString(R.string.bookings_empty_past_title)
                    "ALL" -> getString(R.string.bookings_empty_all_title)
                    else -> getString(R.string.bookings_empty_upcoming_title)
                }
                showEmpty(t, getString(R.string.bookings_empty_hint))
            } else {
                emptyBox.isVisible = false
                rv.isVisible = true
            }
        }
    }

    private fun isUpcoming(r: AppointmentRow): Boolean {
        val st = r.status.trim().uppercase(Locale.US)

        //  Always treat IN_PROGRESS as upcoming
        if (st == "IN_PROGRESS" || st == "ONGOING" || st == "STARTED") return true

        // Completed/cancelled/rejected are never upcoming
        if (st == "COMPLETED" || st == "DONE" || st == "CANCELLED" || st == "CANCELED" || st == "REJECTED") return false

        // If time is parseable: future => upcoming
        val t = parseTimeMs(r.scheduledAt)
        if (t != null) return t >= (System.currentTimeMillis() - 5 * 60 * 1000L) // 5 min grace

        // If time not parseable: BOOKED/CONFIRMED should still show
        return (st == "BOOKED" || st == "CONFIRMED")
    }

    private fun isPast(r: AppointmentRow): Boolean {
        val st = r.status.trim().uppercase(Locale.US)

        if (st == "COMPLETED" || st == "DONE" || st == "CANCELLED" || st == "CANCELED" || st == "REJECTED") return true
        if (st == "IN_PROGRESS" || st == "ONGOING" || st == "STARTED") return false

        val t = parseTimeMs(r.scheduledAt)
        if (t != null) return t < (System.currentTimeMillis() - 60 * 60 * 1000L) // 1 hr past => past

        return false
    }

    private fun parseTimeMs(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank() || s.equals("null", true)) return null

        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )

        for (p in patterns) {
            try {
                val df = SimpleDateFormat(p, Locale.US)
                val d = df.parse(s)
                if (d != null) return d.time
            } catch (_: Throwable) { }
        }
        return null
    }


    private fun showEmpty(title: String, sub: String) {
        rv.isVisible = false
        emptyBox.isVisible = true
        tvEmptyTitle.text = title
        tvEmptySub.text = sub
    }

    data class AppointmentRow(
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
                        is String -> if (any.trim().isNotBlank()) return any.trim()
                    }
                }
                return ""
            }

            fun from(o: JSONObject): AppointmentRow {
                val id = parseIdAny(o, "appointmentId", "appointment_id", "id")
                val publicCode = o.optString("public_code", o.optString("publicCode", ""))
                val doctorId = o.optInt("doctor_id", o.optInt("doctorId", 0))
                val doctorName = o.optString("doctor_name", o.optString("doctorName", ""))
                val spec = o.optString("speciality_key", o.optString("specialty", o.optString("specialization", "")))
                val consult = o.optString("consult_type", o.optString("consultType", ""))
                val scheduledAt = o.optString("scheduled_at", o.optString("scheduledAt", ""))
                val status = o.optString("status", "BOOKED")
                val fee = o.optInt("fee_amount", o.optInt("fee", 0))

                return AppointmentRow(
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

    private class AppointmentAdapter(
        private val rows: List<AppointmentRow>,
        private val onClick: (AppointmentRow) -> Unit
    ) : RecyclerView.Adapter<AppointmentVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): AppointmentVH {
            val view = android.view.LayoutInflater.from(p.context)
                .inflate(R.layout.item_patient_appointment, p, false)
            return AppointmentVH(view, onClick)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: AppointmentVH, i: Int) {
            h.bind(rows[i])
        }
    }

    private class AppointmentVH(
        v: View,
        private val onClick: (AppointmentRow) -> Unit
    ) : RecyclerView.ViewHolder(v) {

        private val tvDoctor = v.findViewById<TextView>(R.id.tvDoctor)
        private val tvMeta = v.findViewById<TextView>(R.id.tvMeta)
        private val tvWhen = v.findViewById<TextView>(R.id.tvWhen)
        private val tvFee = v.findViewById<TextView>(R.id.tvFee)
        private val tvStatus = v.findViewById<TextView>(R.id.tvStatus)
        private val tvCode = v.findViewById<TextView>(R.id.tvCode)

        private val dfIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val dfOut = SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.US)

        fun bind(r: AppointmentRow) {
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

            val whenTxt = runCatching {
                val d = dfIn.parse(r.scheduledAt)
                if (d != null) dfOut.format(d) else r.scheduledAt
            }.getOrDefault(r.scheduledAt)
            tvWhen.text = whenTxt.ifBlank { " " }

            tvFee.text = "₹${r.feeAmount}"

            tvStatus.text = mapStatus(r.status)
            tvStatus.setBackgroundResource(bgForStatus(r.status))

            tvCode.isVisible = r.publicCode.isNotBlank()
            tvCode.text =
                if (r.publicCode.isNotBlank())
                    itemView.context.getString(R.string.booking_code_fmt, r.publicCode)
                else ""
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
