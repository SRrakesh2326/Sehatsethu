package com.simats.sehatsethu.roles.patient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import java.util.Locale

class PatientAiSymptomCheckerActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var rvSymptoms: RecyclerView
    private lateinit var etDescribe: EditText
    private lateinit var btnAnalyze: MaterialButton
    private lateinit var btnMic: MaterialCardView

    // Manual selections (user taps)
    private val manualSelected = LinkedHashSet<String>()

    // Auto selections (from typed/voice text)
    private val autoSelected = LinkedHashSet<String>()

    // If user UNselects an auto-detected chip, keep it excluded
    private val manualExcluded = LinkedHashSet<String>()

    private fun allSelectedKeys(): LinkedHashSet<String> {
        val all = LinkedHashSet<String>()
        all.addAll(manualSelected)
        all.addAll(autoSelected)
        return all
    }

    private val symptoms = listOf(
        SymptomAdapter.Item("FEVER", "🤒", R.string.symptom_fever),
        SymptomAdapter.Item("COLD", "🤧", R.string.symptom_cold),
        SymptomAdapter.Item("COUGH", "😷", R.string.symptom_cough),
        SymptomAdapter.Item("SORE_THROAT", "😮", R.string.symptom_sore_throat),
        SymptomAdapter.Item("HEADACHE", "🤕", R.string.symptom_headache),
        SymptomAdapter.Item("STOMACH_PAIN", "🤢", R.string.symptom_stomach_pain),
        SymptomAdapter.Item("BODY_PAIN", "💪", R.string.symptom_body_pain),
        SymptomAdapter.Item("TIREDNESS", "😴", R.string.symptom_tiredness),
    )

    // OFFLINE VOSK model asset folder name (assets/<this-folder>)
    private val offlineModelFolder = "vosk-model-small-en-us-0.15"
    private var offlineVoice: OfflineVoiceHelper? = null
    private var isListening = false

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleOfflineVoice() else toast(getString(R.string.mic_permission_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_ai_symptom_checker)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        rvSymptoms = findViewById(R.id.rvSymptoms)
        etDescribe = findViewById(R.id.etDescribe)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnMic = findViewById(R.id.btnMic)

        ivBack.setOnClickListener { finishWithAiTransition() }

        rvSymptoms.layoutManager = GridLayoutManager(this, 2)
        rvSymptoms.setHasFixedSize(true)

        // IMPORTANT: add spacing only ONCE
        if (rvSymptoms.itemDecorationCount == 0) {
            rvSymptoms.addItemDecoration(SpacingDecoration(dp(12), includeEdge = true))
        }

        rvSymptoms.adapter = SymptomAdapter(
            items = symptoms,
            isSelected = { key -> isKeySelected(key) },
            onToggle = { key -> onToggleKey(key) }
        )

        etDescribe.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAutoSelectedFromText(s?.toString().orEmpty())
            }
        })

        btnMic.setOnClickListener { ensureMicAndToggle() }
        btnAnalyze.setOnClickListener { onAnalyze() }

        // Initial state
        updateAutoSelectedFromText(etDescribe.text?.toString().orEmpty())
        updateAnalyzeButton()
    }

    override fun onBackPressed() {
        finishWithAiTransition()
    }

    private fun finishWithAiTransition() {
        stopOfflineVoiceIfNeeded()
        finish()
        overridePendingTransition(R.anim.ai_pop_enter, R.anim.ai_pop_exit)
    }

    private fun ensureMicAndToggle() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) toggleOfflineVoice() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun toggleOfflineVoice() {
        if (isListening) {
            stopOfflineVoiceIfNeeded()
            return
        }

        isListening = true
        offlineVoice = OfflineVoiceHelper(
            ctx = this,
            modelAssetFolder = offlineModelFolder,
            onFinalText = { spoken ->
                runOnUiThread {
                    val current = etDescribe.text?.toString().orEmpty()
                    val merged = if (current.isBlank()) spoken else "$current\n$spoken"
                    etDescribe.setText(merged)
                    etDescribe.setSelection(etDescribe.text?.length ?: 0)
                    // TextWatcher will auto-select chips
                }
            },
            onErrorText = { err ->
                runOnUiThread {
                    toast(err)
                    stopOfflineVoiceIfNeeded()
                }
            }
        )

        offlineVoice?.start()
    }

    private fun stopOfflineVoiceIfNeeded() {
        isListening = false
        runCatching { offlineVoice?.stop() }
        offlineVoice = null
    }

    private fun updateAutoSelectedFromText(text: String) {
        autoSelected.clear()
        val extracted = SymptomNlp.extractKeys(this, text, Locale.getDefault())

        for (k in extracted) {
            if (!manualExcluded.contains(k)) autoSelected.add(k)
        }
        rvSymptoms.adapter?.notifyDataSetChanged()
        updateAnalyzeButton()
    }

    private fun isKeySelected(key: String): Boolean {
        return manualSelected.contains(key) || autoSelected.contains(key)
    }

    private fun onToggleKey(key: String) {
        val currentlySelected = isKeySelected(key)

        if (currentlySelected) {
            // If user unselects:
            if (manualSelected.contains(key)) {
                manualSelected.remove(key)
            } else {
                // It was auto-selected -> exclude it from auto
                manualExcluded.add(key)
                autoSelected.remove(key)
            }
        } else {
            // If user selects:
            manualExcluded.remove(key)
            manualSelected.add(key)
        }

        rvSymptoms.adapter?.notifyDataSetChanged()
        updateAnalyzeButton()
    }

    private fun updateAnalyzeButton() {
        val enabled = allSelectedKeys().isNotEmpty()

        btnAnalyze.isEnabled = enabled
        btnAnalyze.alpha = if (enabled) 1f else 0.9f

        // Disabled: light green, Enabled: existing green
        val tint = if (enabled) 0xFF059669.toInt() else 0xFFA7F3D0.toInt()
        btnAnalyze.setBackgroundColor(tint)
    }

    private fun onAnalyze() {
        val desc = etDescribe.text?.toString().orEmpty().trim()
        val keys = allSelectedKeys()

        if (keys.isEmpty() && desc.isBlank()) {
            toast(getString(R.string.ai_insight_empty))
            return
        }

        // Compute analysis + save locally
        val analysis = OfflineSymptomAnalyzer.analyze(
            selectedKeys = keys.toList(),
            desc = desc,
            locale = Locale.getDefault(),
            ctx = this
        )
        AiSymptomStore.saveResult(this, analysis)

        // Open results screen (reads latest saved result)
        startActivity(android.content.Intent(this, PatientAiSymptomResultsActivity::class.java))
        overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
