package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PatientAiSymptomResultsActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var tvUrgencyTitle: TextView
    private lateinit var tvUrgencySub: TextView
    private lateinit var rvConditions: RecyclerView

    private lateinit var tvRec1: TextView
    private lateinit var tvRec2: TextView
    private lateinit var tvRec3: TextView
    private lateinit var tvRec4: TextView

    private lateinit var tvActionSub: TextView
    private lateinit var btnBook: MaterialButton
    private lateinit var cardSave: View

    //  keep from AI result
    private var suggestedKey: String = "GENERAL_PHYSICIAN"
    private var symptomsText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_ai_symptom_results)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        tvUrgencyTitle = findViewById(R.id.tvUrgencyTitle)
        tvUrgencySub = findViewById(R.id.tvUrgencySub)
        rvConditions = findViewById(R.id.rvConditions)

        tvRec1 = findViewById(R.id.tvRecText1)
        tvRec2 = findViewById(R.id.tvRecText2)
        tvRec3 = findViewById(R.id.tvRecText3)
        tvRec4 = findViewById(R.id.tvRecText4)

        tvActionSub = findViewById(R.id.tvActionSub)
        btnBook = findViewById(R.id.btnBook)
        cardSave = findViewById(R.id.cardSave)

        ivBack.setOnClickListener { finishWithAiTransition() }

        val last = runCatching { AiSymptomStore.loadLast(this) }.getOrNull()
        if (last == null) {
            Toast.makeText(this, getString(R.string.ai_no_saved_result), Toast.LENGTH_SHORT).show()
            finishWithAiTransition()
            return
        }

        bindSafe(last)

        //  Book → go to Doctor list filtered by suggestedKey
        btnBook.setOnClickListener {
            val key = suggestedKey.trim().ifBlank { "GENERAL_PHYSICIAN" }.uppercase(Locale.US)
            val label = specialityLabelForKey(key)

            val itn = Intent(this, PatientDoctorListActivity::class.java)
            itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_KEY, key)
            itn.putExtra(SelectSpecialityActivity.EXTRA_SPECIALITY_LABEL, label)

            if (symptomsText.isNotBlank()) {
                itn.putExtra(EXTRA_SYMPTOMS_TEXT, symptomsText)
            }

            itn.putExtra(EXTRA_AUTO_OPEN_FIRST, true)
            startActivity(itn)
            overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }
        cardSave.setOnClickListener {
            val itn = Intent(this, PatientRecordVitalsActivity::class.java)
            // prefill notes with symptoms so vitals record keeps context (optional)
            if (symptomsText.isNotBlank()) {
                itn.putExtra(PatientRecordVitalsActivity.EXTRA_PREFILL_NOTES, symptomsText)
            }
            startActivity(itn)
            overridePendingTransition(R.anim.ai_enter, R.anim.ai_exit)
        }


    }

    override fun onBackPressed() {
        finishWithAiTransition()
    }

    private fun finishWithAiTransition() {
        finish()
        overridePendingTransition(R.anim.ai_pop_enter, R.anim.ai_pop_exit)
    }

    private fun bindSafe(o: JSONObject) {
        try {
            bind(o)
        } catch (t: Throwable) {
            Log.e(TAG, "bindSafe failed", t)
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
            finishWithAiTransition()
        }
    }

    private fun bind(o: JSONObject) {
        // Urgency
        tvUrgencyTitle.text = firstNonBlank(
            o.optString("urgencyTitle", ""),
            o.optString("urgency_title", ""),
            getString(R.string.medium_urgency)
        )
        tvUrgencySub.text = firstNonBlank(
            o.optString("urgencySub", ""),
            o.optString("urgency_sub", ""),
            getString(R.string.urgency_24_48)
        )

        //  suggested speciality key (stable key)
        suggestedKey = firstNonBlank(
            o.optString("suggestedSpecialityKey", ""),
            o.optString("suggested_speciality_key", ""),
            o.optString("specialityKey", ""),
            "GENERAL_PHYSICIAN"
        ).trim().ifBlank { "GENERAL_PHYSICIAN" }.uppercase(Locale.US)

        //  capture symptoms text to carry forward (robust)
        symptomsText = firstNonBlank(
            o.optString("symptoms", ""),
            o.optString("symptomsText", ""),
            o.optString("inputSymptoms", ""),
            o.optString("userSymptoms", ""),
            o.optString("freeText", ""),
            buildSymptomTextFallback(o)
        ).trim()

        // Conditions list (robust keys)
        val condArr = o.optJSONArray("conditions")
            ?: o.optJSONArray("topConditions")
            ?: o.optJSONArray("possibleConditions")

        val list = ArrayList<ConditionAdapter.Row>()
        if (condArr != null) {
            for (i in 0 until condArr.length()) {
                val c = condArr.optJSONObject(i) ?: continue
                val name = firstNonBlank(
                    c.optString("name", ""),
                    c.optString("condition", ""),
                    c.optString("title", "")
                ).trim()

                val pct = when {
                    c.has("confidencePct") -> c.optInt("confidencePct", 0)
                    c.has("confidence_pct") -> c.optInt("confidence_pct", 0)
                    c.has("confidence") -> {
                        // allow confidence as 0..1 float OR int 0..100
                        val v = c.optDouble("confidence", 0.0)
                        if (v <= 1.0) (v * 100.0).toInt() else v.toInt()
                    }
                    else -> 0
                }.coerceIn(0, 100)

                val note = firstNonBlank(
                    c.optString("note", ""),
                    c.optString("desc", ""),
                    c.optString("description", "")
                ).trim()

                if (name.isNotBlank()) {
                    list.add(ConditionAdapter.Row(name = name, pct = pct, note = note))
                }
            }
        }

        rvConditions.layoutManager = LinearLayoutManager(this)
        rvConditions.setHasFixedSize(true)
        rvConditions.adapter = ConditionAdapter(list)
        rvConditions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        // Recommendations (fixed 4 lines but hide blanks)
        val recArr = o.optJSONArray("recommendations")
            ?: o.optJSONArray("recs")
            ?: o.optJSONArray("tips")

        val recs = readStringArray(recArr)
        val fixed = ArrayList<String>(4)
        for (s in recs) {
            if (s.isNotBlank()) fixed.add(s.trim())
            if (fixed.size == 4) break
        }
        while (fixed.size < 4) fixed.add("")

        bindRec(tvRec1, fixed[0])
        bindRec(tvRec2, fixed[1])
        bindRec(tvRec3, fixed[2])
        bindRec(tvRec4, fixed[3])

        // Suggested action line
        val specialityLabel = specialityLabelForKey(suggestedKey)
        tvActionSub.text = getString(R.string.ai_action_based_on_symptoms, specialityLabel)
    }

    private fun bindRec(tv: TextView, text: String) {
        tv.text = text
        tv.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildSymptomTextFallback(o: JSONObject): String {
        // If your analyzer saves selected keys in JSON, use them to build a readable fallback
        val arr = o.optJSONArray("selectedKeys") ?: o.optJSONArray("symptomKeys") ?: return ""
        val keys = readStringArray(arr).map { it.trim() }.filter { it.isNotBlank() }
        if (keys.isEmpty()) return ""
        // Keep it simple; do not introduce new UI strings
        return keys.joinToString(", ")
    }

    private fun readStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            when (v) {
                is String -> out.add(v)
                is JSONObject -> {
                    // in case items are {text:"..."} or similar
                    val s = firstNonBlank(v.optString("text", ""), v.optString("title", ""), v.optString("name", ""))
                    if (s.isNotBlank()) out.add(s)
                }
            }
        }
        return out
    }

    private fun firstNonBlank(vararg s: String): String {
        for (x in s) {
            val t = x.trim()
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun specialityLabelForKey(key: String): String {
        return when (key.uppercase(Locale.US)) {
            "GASTROENTEROLOGY" -> getString(R.string.speciality_gastro)
            "ENT" -> getString(R.string.speciality_ent)
            "NEUROLOGY" -> getString(R.string.speciality_neuro)
            "CARDIOLOGY" -> getString(R.string.speciality_heart)
            "ORTHOPEDICS" -> getString(R.string.speciality_bones)
            "DERMATOLOGY" -> getString(R.string.speciality_skin)
            "PEDIATRICS" -> getString(R.string.speciality_child)
            else -> getString(R.string.speciality_general_physician)
        }
    }

    companion object {
        private const val TAG = "AiResults"
        const val EXTRA_SYMPTOMS_TEXT = "symptoms_text"
        const val EXTRA_AUTO_OPEN_FIRST = "auto_open_first"
    }
}
