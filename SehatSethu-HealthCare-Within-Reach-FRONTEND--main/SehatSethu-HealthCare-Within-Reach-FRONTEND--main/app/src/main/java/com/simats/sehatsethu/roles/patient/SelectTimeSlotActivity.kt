package com.simats.sehatsethu.roles.patient

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

class SelectTimeSlotActivity : BaseActivity() {

    private var doctorId = 0

    // IMPORTANT: keep as STABLE KEY for backend (e.g., ORTHOPEDICS)
    private lateinit var specialityKey: String

    // AUDIO / VIDEO
    private lateinit var consultType: String

    private var selectedDate: String = ""   // yyyy-MM-dd
    private var selectedTime: String = ""   // HH:mm (24h)

    // NEW: symptoms carried from AI -> Details -> here
    private var symptomsText: String = ""

    // NEW: consultation fee carried from Doctor list/details
    private var consultFeeInr: Long = 0L

    // NEW: keep doctor name for payment + summary
    private var doctorName: String = ""

    private val days = ArrayList<JSONObject>()
    private lateinit var rvDays: RecyclerView
    private lateinit var rvSlots: RecyclerView

    private val dfIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dfDow = SimpleDateFormat("EEE", Locale.getDefault())
    private val dfMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    private var pendingPaymentId: String = ""
    private var pendingPaymentMethod: String = ""

    private val paymentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                pendingPaymentId = res.data?.getStringExtra(PaymentActivity.EXTRA_PAYMENT_ID).orEmpty()
                pendingPaymentMethod = res.data?.getStringExtra(PaymentActivity.EXTRA_PAYMENT_METHOD).orEmpty()

                // payment succeeded -> now book
                doBooking()
            } else {
                // payment cancelled/failed
                val btn = findViewById<View>(R.id.btnConfirm)
                btn.isEnabled = true
                Toast.makeText(this, getString(R.string.payment_cancelled), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_time_slot)
        supportActionBar?.hide()

        // Keep your existing int extra, but also support long/string without breaking anything
        doctorId = resolveDoctorId(intent)

        specialityKey =
            intent.getStringExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY)
                ?: intent.getStringExtra("speciality").orEmpty()

        consultType = intent.getStringExtra("consultType").orEmpty()

        // NEW
        symptomsText = intent.getStringExtra(PatientDoctorListActivity.EXTRA_SYMPTOMS_TEXT).orEmpty()

        // NEW: doctorName + fee from previous screen (safe fallbacks)
        doctorName = intent.getStringExtra("doctorName").orEmpty()
        consultFeeInr = resolveFeeInr(intent)

        if (doctorName.isNotBlank()) {
            findViewById<TextView>(R.id.tvDoctorName).text = doctorName
        }
        findViewById<TextView>(R.id.tvConsultType).text = consultTypeLabel(this, consultType)

        // Optional: show fee if you have a TextView for it (only if exists)
        try {
            val tvFee = findViewById<TextView>(R.id.tvFee)
            if (consultFeeInr > 0) {
                tvFee.visibility = View.VISIBLE
                tvFee.text = getString(R.string.consultation_fee_value, formatInr(consultFeeInr))
            } else {
                tvFee.visibility = View.GONE
            }
        } catch (_: Throwable) {}

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rvDays = findViewById(R.id.rvDays)
        rvSlots = findViewById(R.id.rvSlots)

        rvDays.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSlots.layoutManager = LinearLayoutManager(this)

        val dayAdapter = DayAdapter(
            items = days,
            getLabel = { dateStr, enabled ->
                buildDayLabel(this, dateStr, enabled)
            },
            onPick = { day ->
                selectedDate = day.optString("date", "")
                selectedTime = ""
                renderSlots(day.optJSONArray("sections") ?: JSONArray())
                findViewById<View>(R.id.btnConfirm).isEnabled = false

                setMonthHeader(day.optString("date", ""))
            }
        )
        rvDays.adapter = dayAdapter

        findViewById<View>(R.id.btnConfirm).setOnClickListener {
            if (selectedDate.isBlank() || selectedTime.isBlank()) return@setOnClickListener
            showConfirmBookingDialog()
        }

        loadDays()
    }

    private fun loadDays() {
        lifecycleScope.launch {
            val arr = withContext(Dispatchers.IO) {
                PatientApi.getSlots(this@SelectTimeSlotActivity, doctorId)
            }

            days.clear()
            if (arr != null) {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { days.add(it) }
            } else {
                Toast.makeText(
                    this@SelectTimeSlotActivity,
                    PatientApi.lastError ?: "Failed to load slots",
                    Toast.LENGTH_SHORT
                ).show()
            }

            rvDays.adapter?.notifyDataSetChanged()

            val firstEnabledIndex =
                days.indexOfFirst { it.optBoolean("enabled", true) }.let { if (it < 0) 0 else it }

            if (days.isNotEmpty()) {
                selectedDate = days[firstEnabledIndex].optString("date", "")
                setMonthHeader(selectedDate)
                renderSlots(days[firstEnabledIndex].optJSONArray("sections") ?: JSONArray())
            }
        }
    }

    private fun setMonthHeader(dateStr: String) {
        val tvMonth = findViewById<TextView>(R.id.tvMonth)
        val d = parseDate(dateStr) ?: return
        tvMonth.text = dfMonth.format(d)
    }

    private fun renderSlots(sections: JSONArray) {
        val adapter = SlotSectionAdapter(
            sections = sections,
            isSelected = { value -> value == selectedTime },
            onPick = { hhmm ->
                selectedTime = hhmm
                findViewById<View>(R.id.btnConfirm).isEnabled = true
                rvSlots.adapter?.notifyDataSetChanged()
            }
        )
        rvSlots.adapter = adapter
    }

    private fun showConfirmBookingDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_booking, null)

        view.findViewById<TextView>(R.id.tvConfirmTitle).text = getString(R.string.confirm_booking_title)

        val msg = if (consultFeeInr > 0) {
            getString(R.string.confirm_booking_message_with_payment)
        } else {
            getString(R.string.confirm_booking_message)
        }
        view.findViewById<TextView>(R.id.tvConfirmMsg).text = msg

        val prettyType = consultTypeLabel(this, consultType)
        val prettyTime = selectedTime

        val feeLine = if (consultFeeInr > 0) {
            " • ${getString(R.string.pay_amount_inline, formatInr(consultFeeInr))}"
        } else ""

        view.findViewById<TextView>(R.id.tvConfirmDetails).text =
            "$prettyType • $selectedDate • $prettyTime$feeLine"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnYesBook).setOnClickListener {
            dialog.dismiss()

            val btn = findViewById<View>(R.id.btnConfirm)
            btn.isEnabled = false

            // Always open the hospital-bill screen (even if fee=0, show ₹0 bill)
            openPayment()

        }

        dialog.show()
    }

    private fun openPayment() {
        val itn = Intent(this, PaymentActivity::class.java)
        itn.putExtra(PaymentActivity.EXTRA_AMOUNT_INR, consultFeeInr)
        itn.putExtra(PaymentActivity.EXTRA_DOCTOR_NAME, doctorName)
        itn.putExtra(PaymentActivity.EXTRA_DATE, selectedDate)
        itn.putExtra(PaymentActivity.EXTRA_TIME, selectedTime)
        itn.putExtra(PaymentActivity.EXTRA_CONSULT_TYPE, consultTypeLabel(this, consultType))
        itn.putExtra(PaymentActivity.EXTRA_SPECIALITY, specialityKey)
        paymentLauncher.launch(itn)
    }

    private fun doBooking() {
        lifecycleScope.launch {
            val btn = findViewById<View>(R.id.btnConfirm)
            btn.isEnabled = false

            val data = withContext(Dispatchers.IO) {
                PatientApi.book(
                    ctx = this@SelectTimeSlotActivity,
                    doctorId = doctorId,
                    specialityKey = specialityKey,
                    consultType = consultType,
                    date = selectedDate,
                    time = selectedTime,
                    symptoms = symptomsText // IMPORTANT
                    // If later you update backend to accept payment_id/method, add here (without breaking)
                )
            }

            if (data == null) {
                Toast.makeText(
                    this@SelectTimeSlotActivity,
                    PatientApi.lastError ?: "Booking failed",
                    Toast.LENGTH_SHORT
                ).show()
                btn.isEnabled = true
                return@launch
            }

            val bookingId = data.optString("bookingId", "")

            vibrateSuccess()

            val itn = Intent(this@SelectTimeSlotActivity, BookingConfirmedActivity::class.java)
            itn.putExtra("bookingId", bookingId)
            itn.putExtra("doctorId", doctorId)
            itn.putExtra("speciality", specialityKey)
            itn.putExtra("consultType", consultType)
            itn.putExtra("date", selectedDate)
            itn.putExtra("time", selectedTime)

            // optional: keep carrying
            itn.putExtra(PatientDoctorListActivity.EXTRA_SYMPTOMS_TEXT, symptomsText)

            // optional: payment receipt info for UI (won’t break if ignored)
            if (pendingPaymentId.isNotBlank()) {
                itn.putExtra("paymentId", pendingPaymentId)
                itn.putExtra("paymentMethod", pendingPaymentMethod)
                itn.putExtra("paidAmountInr", consultFeeInr)
            }

            startActivity(itn)
            finish()
        }
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val v = vm.defaultVibrator
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(60)
            }
        } catch (_: Throwable) {}
    }

    private fun parseDate(iso: String): Date? = try { dfIso.parse(iso) } catch (_: Exception) { null }

    private fun buildDayLabel(ctx: Context, iso: String, enabled: Boolean): String {
        val d = parseDate(iso) ?: return ""
        val today = dfIso.format(Date())
        if (iso == today) return ctx.getString(R.string.today)
        return dfDow.format(d)
    }

    private fun consultTypeLabel(ctx: Context, type: String): String {
        return when (type.uppercase(Locale.US)) {
            "VIDEO" -> ctx.getString(R.string.video_consultation)
            else -> ctx.getString(R.string.audio_consultation)
        }
    }

    private fun sectionTitle(ctx: Context, key: String): String {
        return when (key.uppercase(Locale.US)) {
            "MORNING" -> ctx.getString(R.string.morning)
            "AFTERNOON" -> ctx.getString(R.string.afternoon)
            "EVENING" -> ctx.getString(R.string.evening)
            else -> key
        }
    }

    private fun resolveDoctorId(intent: Intent): Int {
        fun asIntFromString(s: String?): Int {
            return s?.trim()?.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        }

        val i1 = intent.getIntExtra("doctorId", 0)
        if (i1 > 0) return i1

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

    private fun resolveFeeInr(intent: Intent): Long {

        fun extractNumber(s: String?): Long {
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

        // long extras
        intent.getLongExtra("feeInr", 0L).takeIf { it > 0 }?.let { return it }
        intent.getLongExtra("fee", 0L).takeIf { it > 0 }?.let { return it }

        // int extra
        intent.getIntExtra("feeInt", 0).takeIf { it > 0 }?.let { return it.toLong() }

        // string extras
        extractNumber(intent.getStringExtra("feeInr")).takeIf { it > 0 }?.let { return it }
        extractNumber(intent.getStringExtra("fee")).takeIf { it > 0 }?.let { return it }
        extractNumber(intent.getStringExtra("price")).takeIf { it > 0 }?.let { return it }
        extractNumber(intent.getStringExtra("consultFee")).takeIf { it > 0 }?.let { return it }
        extractNumber(intent.getStringExtra("consultationFee")).takeIf { it > 0 }?.let { return it }
        extractNumber(intent.getStringExtra("doctorFee")).takeIf { it > 0 }?.let { return it }

        // ✅ IMPORTANT: fee from PatientDoctorDetailsActivity UI text
        extractNumber(intent.getStringExtra("feeText")).takeIf { it > 0 }?.let { return it }

        return 0L
    }



    private fun formatInr(amount: Long): String = "₹${amount}"

    private class DayAdapter(
        private val items: List<JSONObject>,
        private val getLabel: (dateIso: String, enabled: Boolean) -> String,
        private val onPick: (JSONObject) -> Unit
    ) : RecyclerView.Adapter<DayVH>() {

        private var selected = 0

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): DayVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_day_chip, p, false)
            return DayVH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: DayVH, i: Int) {
            val o = items[i]
            val enabled = o.optBoolean("enabled", true)
            val date = o.optString("date", "")
            val label = getLabel(date, enabled)
            val dayNum = o.optInt("dayNum", 0)

            val isSel = i == selected
            h.bind(label, dayNum, isSel, enabled)

            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                val picked = items[pos]
                if (!picked.optBoolean("enabled", true)) return@setOnClickListener

                selected = pos
                notifyDataSetChanged()
                onPick(picked)
            }
        }
    }

    private class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvLabel = v.findViewById<TextView>(R.id.tvDayLabel)
        private val tvNum = v.findViewById<TextView>(R.id.tvDayNum)

        fun bind(label: String, dayNum: Int, selected: Boolean, enabled: Boolean) {
            tvLabel.text = label
            tvNum.text = dayNum.toString()

            itemView.isSelected = selected
            itemView.isEnabled = enabled
            itemView.alpha = if (enabled) 1f else 0.45f
        }
    }

    private inner class SlotSectionAdapter(
        private val sections: JSONArray,
        private val isSelected: (String) -> Boolean,
        private val onPick: (String) -> Unit
    ) : RecyclerView.Adapter<SectionVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): SectionVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_slot_section, p, false)
            return SectionVH(
                v = view,
                isSelected = isSelected,
                onPick = onPick
            ) { key -> sectionTitle(p.context, key) }
        }

        override fun getItemCount(): Int = sections.length()

        override fun onBindViewHolder(h: SectionVH, i: Int) {
            h.bind(sections.optJSONObject(i) ?: JSONObject())
        }
    }

    private class SectionVH(
        v: View,
        private val isSelected: (String) -> Boolean,
        private val onPick: (String) -> Unit,
        private val titleForKey: (String) -> String
    ) : RecyclerView.ViewHolder(v) {

        private val tvTitle = v.findViewById<TextView>(R.id.tvSectionTitle)
        private val rv = v.findViewById<RecyclerView>(R.id.rvSectionSlots)

        fun bind(sec: JSONObject) {
            val key = sec.optString("key", sec.optString("title", ""))
            val title = titleForKey(key)
            val arr = sec.optJSONArray("slots") ?: JSONArray()

            var available = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optBoolean("disabled", false)) available++
            }

            if (available == 0) {
                rv.visibility = View.GONE
                tvTitle.text = "$title • ${itemView.context.getString(R.string.no_slots_available)}"
                return
            }

            tvTitle.text = title
            rv.visibility = View.VISIBLE
            rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(itemView.context, 3)

            rv.adapter = SlotAdapter(
                arr = arr,
                isSelected = isSelected,
                onPick = onPick
            )
        }
    }

    private class SlotAdapter(
        private val arr: JSONArray,
        private val isSelected: (String) -> Boolean,
        private val onPick: (String) -> Unit
    ) : RecyclerView.Adapter<SlotVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): SlotVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_time_chip, p, false)
            return SlotVH(view, isSelected, onPick)
        }

        override fun getItemCount(): Int = arr.length()

        override fun onBindViewHolder(h: SlotVH, i: Int) {
            h.bind(arr.optJSONObject(i) ?: JSONObject())
        }
    }

    private class SlotVH(
        v: View,
        private val isSelected: (String) -> Boolean,
        private val onPick: (String) -> Unit
    ) : RecyclerView.ViewHolder(v) {

        private val tv = v.findViewById<TextView>(R.id.tvTime)

        fun bind(o: JSONObject) {
            val value = o.optString("value", "")
            val label = o.optString("label", value)
            val disabled = o.optBoolean("disabled", false)

            tv.text = label

            itemView.isEnabled = !disabled
            itemView.alpha = if (disabled) 0.4f else 1f

            val active = (!disabled && value.isNotBlank() && isSelected(value))

            tv.isSelected = active
            itemView.isSelected = active

            if (active) {
                tv.setBackgroundResource(R.drawable.bg_language_selected)
                itemView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(80).start()
            } else {
                tv.setBackgroundResource(R.drawable.bg_time_chip_unselected)
                itemView.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }

            itemView.setOnClickListener {
                if (disabled) return@setOnClickListener
                if (value.isBlank()) return@setOnClickListener
                onPick(value)
            }
        }
    }
}
