package com.simats.sehatsethu.roles.patient

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class PatientRecordVitalsActivity : BaseActivity() {

    private lateinit var ivBack: View
    private lateinit var btnSave: MaterialButton

    private lateinit var etSys: EditText
    private lateinit var etDia: EditText
    private lateinit var etSugar: EditText
    private lateinit var btnFasting: MaterialButton
    private lateinit var btnAfterMeal: MaterialButton
    private lateinit var btnRandom: MaterialButton
    private lateinit var etTemp: EditText
    private lateinit var etWeight: EditText
    private lateinit var etNotes: EditText

    private var sugarContext: String = "FASTING"
    private val scope = MainScope()

    // optional local grouping (if passed)
    private var patientIdLocal: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_record_vitals)
        supportActionBar?.hide()

        patientIdLocal = readLongAny("patient_id", "patientId", "user_id", "uid", "id")

        ivBack = findViewById(R.id.ivBack)
        btnSave = findViewById(R.id.btnSave)

        etSys = findViewById(R.id.etSys)
        etDia = findViewById(R.id.etDia)
        etSugar = findViewById(R.id.etSugar)
        btnFasting = findViewById(R.id.btnFasting)
        btnAfterMeal = findViewById(R.id.btnAfterMeal)
        btnRandom = findViewById(R.id.btnRandom)
        etTemp = findViewById(R.id.etTemp)
        etWeight = findViewById(R.id.etWeight)
        etNotes = findViewById(R.id.etNotes)

        val prefill = intent.getStringExtra(EXTRA_PREFILL_NOTES).orEmpty().trim()
        if (prefill.isNotBlank()) etNotes.setText(prefill)

        ivBack.setOnClickListener { finishWithAiTransition() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithAiTransition()
            }
        })

        btnFasting.setOnClickListener { setSugarContext("FASTING") }
        btnAfterMeal.setOnClickListener { setSugarContext("AFTER_MEAL") }
        btnRandom.setOnClickListener { setSugarContext("RANDOM") }
        setSugarContext("FASTING")

        //  silent sync
        scope.launch { syncPendingIfAny() }

        btnSave.setOnClickListener { onSave() }
    }

    private fun finishWithAiTransition() {
        finish()
        overridePendingTransition(R.anim.ai_pop_enter, R.anim.ai_pop_exit)
    }

    private fun setSugarContext(v: String) {
        sugarContext = v
        fun style(b: MaterialButton, active: Boolean) {
            if (active) {
                b.setBackgroundColor(0xFFE2E8F0.toInt())
                b.setTextColor(0xFF0F172A.toInt())
            } else {
                b.setBackgroundColor(0xFFF1F5F9.toInt())
                b.setTextColor(0xFF475569.toInt())
            }
        }
        style(btnFasting, v == "FASTING")
        style(btnAfterMeal, v == "AFTER_MEAL")
        style(btnRandom, v == "RANDOM")
    }

    private fun onSave() {
        val systolic = etSys.text?.toString()?.trim()?.toIntOrNull()
        val diastolic = etDia.text?.toString()?.trim()?.toIntOrNull()
        val sugar = etSugar.text?.toString()?.trim()?.toIntOrNull()
        val tempF = etTemp.text?.toString()?.trim()?.toDoubleOrNull()
        val weightKg = etWeight.text?.toString()?.trim()?.toDoubleOrNull()
        val notes = etNotes.text?.toString().orEmpty().trim()

        if (systolic == null && diastolic == null && sugar == null && tempF == null && weightKg == null && notes.isBlank()) {
            toast(getString(R.string.vitals_enter_any_value))
            return
        }
        if ((systolic != null && diastolic == null) || (systolic == null && diastolic != null)) {
            toast(getString(R.string.vitals_bp_both_required))
            return
        }

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            return
        }

        val recordedAtMs = System.currentTimeMillis()

        //  Always local save first
        val db = PatientVitalsLocalDb(this)
        val localId = db.insertLocal(
            patientId = patientIdLocal, // might be 0; OK
            recordedAtMs = recordedAtMs,
            systolic = systolic,
            diastolic = diastolic,
            sugar = sugar,
            sugarContext = sugarContext,
            temperatureF = tempF,
            weightKg = weightKg,
            notes = notes
        )

        toast(getString(R.string.vitals_saved_offline))
        btnSave.isEnabled = false

        scope.launch {
            val serverId = withContext(Dispatchers.IO) {
                postVitalsToServer(
                    token = token,
                    systolic = systolic,
                    diastolic = diastolic,
                    sugar = sugar,
                    sugarContext = sugarContext,
                    tempF = tempF,
                    weightKg = weightKg,
                    notes = notes,
                    clientRecordedAtMs = recordedAtMs
                )
            }

            btnSave.isEnabled = true

            if (serverId > 0L) {
                db.markSynced(localId, serverId)
                toast(getString(R.string.vitals_saved_online))
            } else {
                toast(getString(R.string.vitals_will_sync_later))
            }

            finishWithAiTransition()
        }
    }

    private suspend fun syncPendingIfAny() {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) return

        val db = PatientVitalsLocalDb(this)
        val pending = db.listPendingAny(limit = 25)
        if (pending.isEmpty()) return

        for (p in pending) {
            val serverId = withContext(Dispatchers.IO) {
                postVitalsToServer(
                    token = token,
                    systolic = p.systolic,
                    diastolic = p.diastolic,
                    sugar = p.sugar,
                    sugarContext = p.sugarContext,
                    tempF = p.temperatureF,
                    weightKg = p.weightKg,
                    notes = p.notes,
                    clientRecordedAtMs = p.recordedAtMs
                )
            }
            if (serverId > 0L) {
                db.markSynced(p.localId, serverId)
            } else {
                break
            }
        }
    }

    private fun postVitalsToServer(
        token: String,
        systolic: Int?,
        diastolic: Int?,
        sugar: Int?,
        sugarContext: String,
        tempF: Double?,
        weightKg: Double?,
        notes: String,
        clientRecordedAtMs: Long
    ): Long {
        val urlStr = BASE_URL + "patient/vitals_create.php"
        var conn: HttpURLConnection? = null

        return try {
            val body = JSONObject().apply {
                if (systolic != null) put("systolic", systolic)
                if (diastolic != null) put("diastolic", diastolic)
                if (sugar != null) put("sugar", sugar)
                put("sugar_context", sugarContext.uppercase(Locale.US))
                if (tempF != null) put("temperature_f", tempF)
                if (weightKg != null) put("weight_kg", weightKg)
                if (notes.isNotBlank()) put("notes", notes)
                put("client_recorded_at_ms", clientRecordedAtMs)
            }

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
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val res = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }

            if (!res.optBoolean("ok", false)) return 0L
            val data = res.optJSONObject("data") ?: return 0L
            val idAny = data.opt("id") ?: return 0L

            when (idAny) {
                is Number -> idAny.toLong()
                is String -> idAny.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
        } catch (_: Throwable) {
            0L
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun readLongAny(vararg keys: String): Long {
        val b = intent.extras ?: return 0L
        for (k in keys) {
            val any = b.get(k)
            when (any) {
                is Long -> if (any > 0L) return any
                is Int -> if (any > 0) return any.toLong()
                is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        return 0L
    }

    companion object {
        const val EXTRA_PREFILL_NOTES = "prefill_notes"
    }
}
