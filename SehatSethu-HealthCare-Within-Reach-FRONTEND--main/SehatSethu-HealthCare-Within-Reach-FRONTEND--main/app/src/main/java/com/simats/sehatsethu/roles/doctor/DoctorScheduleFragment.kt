package com.simats.sehatsethu.roles.doctor

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
import java.util.Locale

class DoctorScheduleFragment : Fragment() {

    private lateinit var btnBack: ImageView
    private lateinit var btnEnableWeekdays: MaterialButton
    private lateinit var btnDisableAll: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var daysContainer: LinearLayout

    private data class Day(
        val dayOfWeek: Int, // 1=Mon ... 7=Sun
        val labelRes: Int,
        var enabled: Boolean,
        var start: String,
        var end: String
    )

    private data class RowRefs(
        val sw: SwitchMaterial,
        val timeRow: View,
        val tvStart: TextView,
        val tvEnd: TextView
    )

    private val days = mutableListOf<Day>()
    private val rows = mutableMapOf<Int, RowRefs>()

    private var isSaving = false
    private var isLoading = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        // EXACT SAME STYLING: reuse the same layout
        return i.inflate(R.layout.activity_doctors_availability, c, false)
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        btnBack = v.findViewById(R.id.btnBack)
        btnEnableWeekdays = v.findViewById(R.id.btnEnableWeekdays)
        btnDisableAll = v.findViewById(R.id.btnDisableAll)
        btnSave = v.findViewById(R.id.btnSave)
        daysContainer = v.findViewById(R.id.daysContainer)

        btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        seedDefaults()
        renderDays(v)

        btnEnableWeekdays.setOnClickListener {
            days.forEach { d -> if (d.dayOfWeek in 1..5) d.enabled = true }
            applyStateToUI()
        }

        btnDisableAll.setOnClickListener {
            days.forEach { it.enabled = false }
            applyStateToUI()
        }

        btnSave.setOnClickListener { saveAvailability() }

        loadAvailability()
    }

    private fun seedDefaults() {
        days.clear()
        days.add(Day(1, R.string.monday, true, "09:00", "17:00"))
        days.add(Day(2, R.string.tuesday, true, "09:00", "17:00"))
        days.add(Day(3, R.string.wednesday, true, "09:00", "17:00"))
        days.add(Day(4, R.string.thursday, true, "09:00", "17:00"))
        days.add(Day(5, R.string.friday, true, "09:00", "17:00"))
        days.add(Day(6, R.string.saturday, false, "09:00", "17:00"))
        days.add(Day(7, R.string.sunday, false, "09:00", "17:00"))
    }

    private fun renderDays(root: View) {
        daysContainer.removeAllViews()
        rows.clear()

        days.forEach { d ->
            val row = layoutInflater.inflate(R.layout.item_day_availability, daysContainer, false)

            val tvDay = row.findViewById<TextView>(R.id.tvDay)
            val sw = row.findViewById<SwitchMaterial>(R.id.switchEnable)
            val timeRow = row.findViewById<View>(R.id.timeRow)
            val tvStart = row.findViewById<TextView>(R.id.tvStart)
            val tvEnd = row.findViewById<TextView>(R.id.tvEnd)

            tvDay.setText(d.labelRes)
            sw.isChecked = d.enabled
            tvStart.text = d.start
            tvEnd.text = d.end
            timeRow.visibility = if (d.enabled) View.VISIBLE else View.GONE

            sw.setOnCheckedChangeListener { _, checked ->
                d.enabled = checked
                timeRow.visibility = if (checked) View.VISIBLE else View.GONE
            }

            tvStart.setOnClickListener {
                if (!d.enabled) return@setOnClickListener
                pickTime(d.start) { picked ->
                    d.start = picked
                    tvStart.text = picked
                    if (!isEndAfterStart(d.start, d.end)) {
                        d.end = nextMinute(d.start)
                        tvEnd.text = d.end
                    }
                }
            }

            tvEnd.setOnClickListener {
                if (!d.enabled) return@setOnClickListener
                pickTime(d.end) { picked ->
                    if (!isEndAfterStart(d.start, picked)) {
                        toast(getString(R.string.err_end_time_after_start))
                        return@pickTime
                    }
                    d.end = picked
                    tvEnd.text = picked
                }
            }

            timeRow.setOnClickListener {
                if (!d.enabled) return@setOnClickListener
                pickTime(d.start) { picked ->
                    d.start = picked
                    tvStart.text = picked
                    if (!isEndAfterStart(d.start, d.end)) {
                        d.end = nextMinute(d.start)
                        tvEnd.text = d.end
                    }
                }
            }

            rows[d.dayOfWeek] = RowRefs(sw, timeRow, tvStart, tvEnd)
            daysContainer.addView(row)
        }
    }

    private fun applyStateToUI() {
        days.forEach { d ->
            val r = rows[d.dayOfWeek] ?: return@forEach
            r.sw.isChecked = d.enabled
            r.timeRow.visibility = if (d.enabled) View.VISIBLE else View.GONE
            r.tvStart.text = d.start
            r.tvEnd.text = d.end
        }
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val (h, m) = parseTime(current)
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                onPicked(String.format(Locale.US, "%02d:%02d", hour, minute))
            },
            h, m, true
        ).show()
    }

    private fun parseTime(t: String): Pair<Int, Int> {
        val p = t.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 9
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return h to m
    }

    private fun isEndAfterStart(start: String, end: String): Boolean {
        val (sh, sm) = parseTime(start)
        val (eh, em) = parseTime(end)
        val s = sh * 60 + sm
        val e = eh * 60 + em
        return e > s
    }

    private fun nextMinute(start: String): String {
        val (sh, sm) = parseTime(start)
        val total = sh * 60 + sm + 1
        val h = (total / 60).coerceAtMost(23)
        val m = (total % 60)
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    private fun loadAvailability() {
        if (isLoading) return
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = AppPrefs.getToken(requireContext()).orEmpty()
                if (token.isBlank()) return@launch

                val loaded = withContext(Dispatchers.IO) { apiGetAvailability(token) }
                if (!isAdded) return@launch

                if (loaded != null) {
                    applyLoaded(loaded)
                    applyStateToUI()
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun saveAvailability() {
        if (isSaving) return

        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            return
        }

        val bad = days.firstOrNull { it.enabled && !isEndAfterStart(it.start, it.end) }
        if (bad != null) {
            toast(getString(R.string.err_end_time_after_start))
            return
        }

        setSaving(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, err) = withContext(Dispatchers.IO) { apiSaveAvailability(token) }
            setSaving(false)

            if (!isAdded) return@launch

            if (ok) {
                toast(getString(R.string.saved))
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                toast(err ?: getString(R.string.failed))
            }
        }
    }

    private fun setSaving(saving: Boolean) {
        isSaving = saving
        btnSave.isEnabled = !saving
        btnEnableWeekdays.isEnabled = !saving
        btnDisableAll.isEnabled = !saving
        daysContainer.alpha = if (saving) 0.7f else 1f
    }

    private fun applyLoaded(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dow = o.optInt("day_of_week", -1)
            val d = days.firstOrNull { it.dayOfWeek == dow } ?: continue

            d.enabled = o.optInt("enabled", 0) == 1
            d.start = o.optString("start_time", d.start)
            d.end = o.optString("end_time", d.end)

            if (!isEndAfterStart(d.start, d.end)) d.end = nextMinute(d.start)
        }
    }

    // ---------------- API ----------------

    private fun apiGetAvailability(token: String): JSONArray? {
        return try {
            val url = URL(BASE_URL + "doctor/availability_get.php")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            val json = JSONObject(body.ifBlank { "{}" })
            if (json.optBoolean("ok", false) != true) return null

            // supports both keys to be safe
            json.optJSONArray("availability") ?: json.optJSONArray("data")
        } catch (_: Throwable) {
            null
        }
    }

    private fun apiSaveAvailability(token: String): Pair<Boolean, String?> {
        return try {
            val payload = JSONObject()
            val list = JSONArray()
            days.forEach { d ->
                list.put(JSONObject().apply {
                    put("day_of_week", d.dayOfWeek)
                    put("enabled", if (d.enabled) 1 else 0)
                    put("start_time", d.start)
                    put("end_time", d.end)
                })
            }
            payload.put("availability", list)

            val url = URL(BASE_URL + "doctor/availability_save.php")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()

            val json = JSONObject(body.ifBlank { "{}" })
            val ok = json.optBoolean("ok", false) == true
            val err = json.optString("error").takeIf { it.isNotBlank() }
            ok to err
        } catch (_: Throwable) {
            false to null
        }
    }

    private fun toast(m: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
    }
}
