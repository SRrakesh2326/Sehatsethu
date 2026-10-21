package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R

class PaymentActivity : BaseActivity() {

    companion object {
        const val EXTRA_AMOUNT_INR      = "amountInr"
        const val EXTRA_DOCTOR_NAME     = "doctorName"
        const val EXTRA_DATE            = "date"
        const val EXTRA_TIME            = "time"
        const val EXTRA_CONSULT_TYPE    = "consultTypeLabel"
        const val EXTRA_SPECIALITY      = "speciality"

        const val EXTRA_PAYMENT_ID      = "paymentId"
        const val EXTRA_PAYMENT_METHOD  = "paymentMethod"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hospital_bill)
        supportActionBar?.hide()

        val amountInr      = intent.getLongExtra(EXTRA_AMOUNT_INR, 0L).coerceAtLeast(0L)
        val doctorName     = intent.getStringExtra(EXTRA_DOCTOR_NAME).orEmpty()
        val date           = intent.getStringExtra(EXTRA_DATE).orEmpty()
        val time           = intent.getStringExtra(EXTRA_TIME).orEmpty()
        val consultType    = intent.getStringExtra(EXTRA_CONSULT_TYPE).orEmpty()
        val speciality     = intent.getStringExtra(EXTRA_SPECIALITY).orEmpty()

        // Populate bill card
        safeSetText(R.id.tvBillDoctorName,  if (doctorName.isNotBlank()) doctorName else "—")
        safeSetText(R.id.tvBillSpeciality,  if (speciality.isNotBlank()) speciality else "—")
        safeSetText(R.id.tvBillConsultType, if (consultType.isNotBlank()) consultType else "—")
        safeSetText(R.id.tvBillDate,        if (date.isNotBlank()) date else "—")
        safeSetText(R.id.tvBillTime,        if (time.isNotBlank()) time else "—")

        val feeStr = if (amountInr > 0) "₹$amountInr" else "₹0"
        safeSetText(R.id.tvBillFeeAmount, feeStr)
        safeSetText(R.id.tvBillTotal,     feeStr)
        safeSetText(R.id.tvBillNote,      getString(R.string.pay_at_hospital_note))

        findViewById<ImageView>(R.id.ivBillBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnConfirmPayAtHospital).setOnClickListener {
            vibrateSuccess()
            Toast.makeText(this, getString(R.string.appointment_confirmed_pay_at_hospital), Toast.LENGTH_SHORT).show()
            val result = android.content.Intent()
            result.putExtra(EXTRA_PAYMENT_ID, "HOSP-${System.currentTimeMillis()}")
            result.putExtra(EXTRA_PAYMENT_METHOD, "PAY_AT_HOSPITAL")
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun safeSetText(id: Int, text: String) {
        try { findViewById<TextView>(id)?.text = text } catch (_: Throwable) {}
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(60)
            }
        } catch (_: Throwable) {}
    }
}
