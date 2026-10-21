package com.simats.sehatsethu.roles.doctor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CreatePrescriptionActivity : BaseActivity() {

    private data class MedRow(
        val name: String,
        val dosage: String,
        val frequency: String,
        val duration: String,
        val instructions: String
    )

    private val meds = ArrayList<MedRow>()

    // keep as vars so we can resolve later if missing
    private var patientId: Long = 0L
    private var appointmentId: Long? = null
    private var appointmentKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_prescription)
        supportActionBar?.hide()

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Basic UI
        val tvPatientName = findViewById<TextView>(R.id.tvPatientName)
        val tvPatientMeta = findViewById<TextView>(R.id.tvPatientMeta)

        val passedPatientName = intent.getStringExtra("patientName").orEmpty()
        val passedPatientMeta = intent.getStringExtra("patientMeta").orEmpty()

        tvPatientName.text = passedPatientName.ifBlank { getString(R.string.patient) }
        tvPatientMeta.text = passedPatientMeta.ifBlank { getString(R.string.patient_meta_placeholder) }

        // Robust reads (Int/Long/String supported)
        patientId = readLongAny("patient_id", "patientId", "patient_id_long", "pid")
        val apptId = readLongAny("appointment_id", "appointmentId", "id", "apptId")
        appointmentId = if (apptId > 0L) apptId else null
        appointmentKey = intent.getStringExtra("appointmentKey")
            ?: intent.getStringExtra("appointment_key")
                    ?: intent.getStringExtra("public_code")
                    ?: intent.getStringExtra("publicCode")
                    ?: ""

        // Recycler
        val rv = findViewById<RecyclerView>(R.id.rvAddedMeds)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = AddedMedsAdapter(meds) { pos ->
            if (pos in 0 until meds.size) {
                meds.removeAt(pos)
                rv.adapter?.notifyDataSetChanged()
                syncAddedMedsVisibility()
            }
        }
        rv.adapter = adapter
        syncAddedMedsVisibility()

        // Suggestions chips -> fill medicine name
        val cg = findViewById<ChipGroup>(R.id.cgSuggestions)
        val etMedName = findViewById<TextInputEditText>(R.id.etMedName)
        for (i in 0 until cg.childCount) {
            (cg.getChildAt(i) as? Chip)?.setOnClickListener {
                etMedName.setText((it as Chip).text?.toString().orEmpty())
            }
        }

        // Dropdowns
        val ddDosage = findViewById<MaterialAutoCompleteTextView>(R.id.ddDosage)
        val ddFrequency = findViewById<MaterialAutoCompleteTextView>(R.id.ddFrequency)
        val ddDuration = findViewById<MaterialAutoCompleteTextView>(R.id.ddDuration)

        ddDosage.setSimpleItems(
            arrayOf(
                getString(R.string.dosage_1_tablet),
                getString(R.string.dosage_2_tablet),
                getString(R.string.dosage_5_ml),
                getString(R.string.dosage_10_ml)
            )
        )
        ddFrequency.setSimpleItems(
            arrayOf(
                getString(R.string.freq_once_daily),
                getString(R.string.freq_twice_daily),
                getString(R.string.freq_thrice_daily)
            )
        )
        ddDuration.setSimpleItems(
            arrayOf(
                getString(R.string.duration_3_days),
                getString(R.string.duration_5_days),
                getString(R.string.duration_7_days),
                getString(R.string.duration_10_days)
            )
        )

        val cardAdd = findViewById<View>(R.id.cardAddMedicine)
        findViewById<View>(R.id.btnAddMedicineTop).setOnClickListener {
            cardAdd.visibility = if (cardAdd.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val etInstructions = findViewById<TextInputEditText>(R.id.etInstructions)

        findViewById<MaterialButton>(R.id.btnCancelMed).setOnClickListener {
            clearMedForm()
            cardAdd.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnAddMed).setOnClickListener {
            val name = etMedName.text?.toString()?.trim().orEmpty()
            val dosage = ddDosage.text?.toString()?.trim().orEmpty()
            val freq = ddFrequency.text?.toString()?.trim().orEmpty()
            val dur = ddDuration.text?.toString()?.trim().orEmpty()
            val inst = etInstructions.text?.toString()?.trim().orEmpty()

            if (name.isBlank()) {
                toast(getString(R.string.enter_medicine_name))
                return@setOnClickListener
            }
            if (dosage.isBlank() || freq.isBlank() || dur.isBlank()) {
                toast(getString(R.string.select_dosage_frequency_duration))
                return@setOnClickListener
            }

            meds.add(MedRow(name, dosage, freq, dur, inst))
            rv.adapter?.notifyDataSetChanged()
            syncAddedMedsVisibility()

            clearMedForm()
            cardAdd.visibility = View.GONE
        }

        val btnSend = findViewById<MaterialButton>(R.id.btnSendPrescription)

        // If patientId is missing, try resolving using appointmentKey/appointmentId BEFORE allowing send.
        if (patientId <= 0L) {
            btnSend.isEnabled = false
            lifecycleScope.launch {
                val token = AppPrefs.getToken(this@CreatePrescriptionActivity).orEmpty()
                if (token.isBlank()) {
                    toast(getString(R.string.please_login_again))
                    finish()
                    return@launch
                }

                val resolved = withContext(Dispatchers.IO) {
                    resolvePatientFromAppointment(token)
                }

                if (!isDestroyed) {
                    if (resolved > 0L) {
                        patientId = resolved
                        btnSend.isEnabled = true
                    } else {
                        toast(getString(R.string.missing_patient_id))
                        finish()
                    }
                }
            }
        }

        // Send Prescription -> SAVE IN DB
        btnSend.setOnClickListener {
            val diagnosis = findViewById<TextInputEditText>(R.id.etDiagnosis)
                .text?.toString()?.trim().orEmpty()

            if (diagnosis.isBlank()) {
                toast(getString(R.string.enter_diagnosis))
                return@setOnClickListener
            }
            if (meds.isEmpty()) {
                toast(getString(R.string.add_at_least_one_medicine))
                return@setOnClickListener
            }
            if (patientId <= 0L) {
                toast(getString(R.string.missing_patient_id))
                return@setOnClickListener
            }

            val token = AppPrefs.getToken(this).orEmpty()
            if (token.isBlank()) {
                toast(getString(R.string.please_login_again))
                return@setOnClickListener
            }

            btnSend.isEnabled = false

            lifecycleScope.launch {
                val payload = buildPrescriptionPayload(diagnosis)

                val res = withContext(Dispatchers.IO) {
                    postJsonAuth(
                        BASE_URL + "doctor/prescription_create.php",
                        token,
                        payload
                    )
                }

                if (!isDestroyed) btnSend.isEnabled = true

                if (res.optBoolean("ok", false)) {
                    toast(getString(R.string.prescription_saved))
                    finish()
                } else {
                    toast(res.optString("error", getString(R.string.failed_to_save_prescription)))
                }
            }
        }
    }

    /**
     * Reads notes/follow-up safely WITHOUT requiring compile-time IDs.
     * If your XML has one of these ids, it will be sent.
     * If not, it will just be "" and backend won’t store.
     */
    private fun readOptionalTextInput(vararg idNames: String): String {
        for (name in idNames) {
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) {
                val v = findViewById<View>(id)
                when (v) {
                    is TextInputEditText -> return v.text?.toString()?.trim().orEmpty()
                    is MaterialAutoCompleteTextView -> return v.text?.toString()?.trim().orEmpty()
                    is TextView -> return v.text?.toString()?.trim().orEmpty()
                }
            }
        }
        return ""
    }

    private fun buildPrescriptionPayload(diagnosis: String): JSONObject {
        val doctorNotes = readOptionalTextInput(
            "etDoctorNotes", "etNotes", "etDoctorNote", "etDoctorNotesValue"
        )

        val followUpText = readOptionalTextInput(
            "etFollowUp", "etFollowUpText", "etFollowup", "etFollowupText", "etNextVisit"
        )

        return JSONObject().apply {
            put("patient_id", patientId)

            val appt = appointmentId
            if (appt != null && appt > 0L) put("appointment_id", appt)

            // helps backend resolve appointment if needed
            if (appointmentKey.isNotBlank()) put("appointment_key", appointmentKey)

            put("title", diagnosis)
            put("diagnosis", diagnosis)

            // ✅ send extra fields if present
            if (doctorNotes.isNotBlank()) put("doctor_notes", doctorNotes)
            if (followUpText.isNotBlank()) put("follow_up_text", followUpText)

            val arr = JSONArray()
            meds.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("name", m.name)
                        put("dosage", m.dosage)
                        put("frequency", m.frequency)
                        put("duration", m.duration)
                        put("instructions", m.instructions)
                    }
                )
            }
            put("items", arr)
        }
    }

    /**
     * Resolve patient_id using appointmentKey or appointmentId.
     * This makes the screen work even if caller didn't pass patient_id.
     */
    private fun resolvePatientFromAppointment(token: String): Long {
        val key = appointmentKey.trim()
        val appt = appointmentId

        if (key.isBlank() && (appt == null || appt <= 0L)) return 0L

        val body = JSONObject().apply {
            if (key.isNotBlank()) put("appointment_key", key)
            if (appt != null && appt > 0L) put("appointment_id", appt)
        }

        val res = postJsonAuth(BASE_URL + "doctor/appointment_resolve.php", token, body)
        if (!res.optBoolean("ok", false)) return 0L

        val data = res.optJSONObject("data") ?: return 0L
        val pidAny = data.opt("patient_id")
        val resolvedPid = when (pidAny) {
            is Number -> pidAny.toLong()
            is String -> pidAny.trim().toLongOrNull() ?: 0L
            else -> 0L
        }

        // also update appointmentId if server returns it
        val aidAny = data.opt("appointment_id")
        val resolvedAid = when (aidAny) {
            is Number -> aidAny.toLong()
            is String -> aidAny.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        if (resolvedAid > 0L) appointmentId = resolvedAid

        return resolvedPid
    }

    private fun readLongAny(vararg keys: String): Long {
        val b = intent.extras
        if (b != null) {
            for (k in keys) {
                val any = b.get(k)
                when (any) {
                    is Long -> if (any > 0L) return any
                    is Int -> if (any > 0) return any.toLong()
                    is String -> any.trim().toLongOrNull()?.let { if (it > 0L) return it }
                }
            }
        }
        // fallback to getLongExtra (only works if stored as Long)
        for (k in keys) {
            val v = intent.getLongExtra(k, 0L)
            if (v > 0L) return v
        }
        return 0L
    }

    private fun clearMedForm() {
        findViewById<TextInputEditText>(R.id.etMedName).setText("")
        findViewById<MaterialAutoCompleteTextView>(R.id.ddDosage).setText("", false)
        findViewById<MaterialAutoCompleteTextView>(R.id.ddFrequency).setText("", false)
        findViewById<MaterialAutoCompleteTextView>(R.id.ddDuration).setText("", false)
        findViewById<TextInputEditText>(R.id.etInstructions).setText("")
    }

    private fun syncAddedMedsVisibility() {
        val rv = findViewById<RecyclerView>(R.id.rvAddedMeds)
        rv.visibility = if (meds.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun postJsonAuth(urlStr: String, token: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 25000
                readTimeout = 25000
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }

            conn.outputStream.use { os ->
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                os.write(bytes)
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", false).put("error", "Bad response")
            }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private class AddedMedsAdapter(
        private val items: List<MedRow>,
        private val onRemove: (pos: Int) -> Unit
    ) : RecyclerView.Adapter<AddedMedsVH>() {

        override fun onCreateViewHolder(p: android.view.ViewGroup, v: Int): AddedMedsVH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_added_medicine, p, false)
            return AddedMedsVH(view, onRemove)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: AddedMedsVH, i: Int) {
            h.bind(items[i])
        }
    }

    private class AddedMedsVH(v: View, private val onRemove: (Int) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvName = v.findViewById<TextView>(R.id.tvMedName)
        private val tvMeta = v.findViewById<TextView>(R.id.tvMedMeta)
        private val btnRemove = v.findViewById<View>(R.id.btnRemove)

        fun bind(m: MedRow) {
            tvName.text = m.name
            tvMeta.text = "${m.dosage} • ${m.frequency} • ${m.duration}"
            btnRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemove(pos)
            }
        }
    }
}
