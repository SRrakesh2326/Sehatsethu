package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class BookingConfirmedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking_confirmed)
        supportActionBar?.hide()

        val bookingId = intent.getStringExtra("bookingId").orEmpty()
        if (bookingId.isBlank()) {
            Toast.makeText(this, getString(R.string.failed_to_load_appointment_details), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Tap card → open appointment details
        findViewById<View>(R.id.cardBooking).setOnClickListener {
            val itn = Intent(this, PatientAppointmentDetailsActivity::class.java)
            itn.putExtra("appointmentId", bookingId)
            startActivity(itn)
        }

        findViewById<View>(R.id.btnGoHome).setOnClickListener { finish() }

        // Fill instantly (fallback) from extras while DB loads
        val date = intent.getStringExtra("date").orEmpty()
        val time = intent.getStringExtra("time").orEmpty()
        val consult = intent.getStringExtra("consultType").orEmpty()

        findViewById<TextView>(R.id.tvBookingId).text = bookingId
        findViewById<TextView>(R.id.tvDate).text = date
        findViewById<TextView>(R.id.tvTime).text = time
        findViewById<TextView>(R.id.tvType).text =
            if (consult.equals("VIDEO", true)) getString(R.string.video_call) else getString(R.string.audio_call)

        //  Vibrate on success screen too (reliable)
        vibrateSuccess(findViewById(R.id.root))

        //  Load real content from DB
        loadFromDb(bookingId, date, time, consult)
    }

    private fun loadFromDb(appointmentId: String, fallbackDate: String, fallbackTime: String, fallbackType: String) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                PatientApi.getAppointmentDetail(this@BookingConfirmedActivity, appointmentId)
            }

            if (d == null) {
                Toast.makeText(
                    this@BookingConfirmedActivity,
                    PatientApi.lastError ?: getString(R.string.failed_to_load_appointment_details),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            bind(d, fallbackDate, fallbackTime, fallbackType)
        }
    }

    private fun bind(d: JSONObject, fallbackDate: String, fallbackTime: String, fallbackType: String) {
        // booking id
        val apptId = d.optString("appointmentId", "").ifBlank { intent.getStringExtra("bookingId").orEmpty() }
        findViewById<TextView>(R.id.tvBookingId).text = apptId

        // doctor
        findViewById<TextView>(R.id.tvDocName).text = d.optString("doctorName", "").ifBlank {
            getString(R.string.sample_doctor_name)
        }
        findViewById<TextView>(R.id.tvDocSpec).text = d.optString("specialization", "").ifBlank {
            getString(R.string.sample_doctor_specialty)
        }

        // date/time (prefer pretty labels from API; fallback to extras)
        val dateLabel = d.optString("dateLabel", "").ifBlank { fallbackDate }
        val timeLabel = d.optString("timeLabel", "").ifBlank { fallbackTime }
        findViewById<TextView>(R.id.tvDate).text = dateLabel
        findViewById<TextView>(R.id.tvTime).text = timeLabel

        // consult type
        val c = d.optString("consultType", fallbackType).uppercase(Locale.US)
        findViewById<TextView>(R.id.tvType).text =
            if (c == "VIDEO") getString(R.string.video_call) else getString(R.string.audio_call)

        // fee
        val fee = d.optInt("fee", -1)
        findViewById<TextView>(R.id.tvFee).text =
            if (fee >= 0) "₹$fee" else getString(R.string.sample_fee_amount)
    }

    private fun vibrateSuccess(anchorForHaptic: View) {
        // Haptic feedback fallback (works even if vibrator restrictions)
        try {
            anchorForHaptic.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } catch (_: Throwable) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val v = vm.defaultVibrator
                if (v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                if (v.hasVibrator()) v.vibrate(70)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }
}
