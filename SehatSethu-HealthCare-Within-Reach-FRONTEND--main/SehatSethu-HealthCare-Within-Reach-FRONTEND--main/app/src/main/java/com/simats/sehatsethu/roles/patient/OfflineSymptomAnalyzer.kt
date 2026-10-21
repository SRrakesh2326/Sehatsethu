package com.simats.sehatsethu.roles.patient

import android.content.Context
import com.simats.sehatsethu.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object OfflineSymptomAnalyzer {

    data class Condition(val name: String, val confidencePct: Int, val note: String)
    data class AnalysisResult(
        val createdAt: Long,
        val selectedKeys: List<String>,
        val desc: String,
        val urgencyLevel: String, // LOW / MEDIUM / HIGH
        val urgencyTitle: String,
        val urgencySub: String,
        val conditions: List<Condition>,
        val recommendations: List<String>,
        val suggestedSpecialityKey: String
    ) {
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("createdAt", createdAt)
            o.put("desc", desc)
            o.put("urgencyLevel", urgencyLevel)
            o.put("urgencyTitle", urgencyTitle)
            o.put("urgencySub", urgencySub)
            o.put("suggestedSpecialityKey", suggestedSpecialityKey)

            val keysArr = JSONArray()
            selectedKeys.forEach { keysArr.put(it) }
            o.put("selectedKeys", keysArr)

            val condArr = JSONArray()
            conditions.forEach {
                val c = JSONObject()
                c.put("name", it.name)
                c.put("confidencePct", it.confidencePct)
                c.put("note", it.note)
                condArr.put(c)
            }
            o.put("conditions", condArr)

            val recArr = JSONArray()
            recommendations.forEach { recArr.put(it) }
            o.put("recommendations", recArr)

            return o
        }
    }

    fun analyze(selectedKeys: List<String>, desc: String, locale: Locale, ctx: Context): AnalysisResult {
        val set = selectedKeys.toSet()

        val hasFever = set.contains("FEVER")
        val hasCold = set.contains("COLD")
        val hasCough = set.contains("COUGH")
        val hasSore = set.contains("SORE_THROAT")
        val hasHeadache = set.contains("HEADACHE")
        val hasStomach = set.contains("STOMACH_PAIN")
        val hasBody = set.contains("BODY_PAIN")
        val hasTired = set.contains("TIREDNESS")

        val conditions = ArrayList<Condition>()
        var urgency = "LOW"
        var specialityKey = "GENERAL_PHYSICIAN"

        // Rule bucket: respiratory
        if (hasFever && (hasCough || hasCold || hasSore)) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_viral_urti),
                    confidencePct = 72,
                    note = ctx.getString(R.string.cond_note_viral_urti)
                )
            )
            urgency = "MEDIUM"
            specialityKey = "GENERAL_PHYSICIAN"
        } else if ((hasCough || hasCold || hasSore) && !hasStomach) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_common_cold),
                    confidencePct = 66,
                    note = ctx.getString(R.string.cond_note_common_cold)
                )
            )
            urgency = "LOW"
            specialityKey = "GENERAL_PHYSICIAN"
        }

        // Digestive
        if (hasStomach) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_gastric),
                    confidencePct = if (hasFever) 62 else 70,
                    note = ctx.getString(R.string.cond_note_gastric)
                )
            )
            urgency = if (hasFever) "MEDIUM" else urgency
            specialityKey = "GASTROENTEROLOGY"
        }

        // Headache dominant
        if (hasHeadache && !hasStomach && !hasCough) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_tension_headache),
                    confidencePct = if (hasFever) 55 else 68,
                    note = ctx.getString(R.string.cond_note_tension_headache)
                )
            )
            specialityKey = "GENERAL_PHYSICIAN"
        }

        // Body pain + tired
        if (hasBody && hasTired && conditions.isEmpty()) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_fatigue),
                    confidencePct = 60,
                    note = ctx.getString(R.string.cond_note_fatigue)
                )
            )
            urgency = "LOW"
            specialityKey = "GENERAL_PHYSICIAN"
        }

        if (conditions.isEmpty()) {
            conditions.add(
                Condition(
                    name = ctx.getString(R.string.cond_general),
                    confidencePct = 55,
                    note = ctx.getString(R.string.cond_note_general)
                )
            )
            urgency = "LOW"
            specialityKey = "GENERAL_PHYSICIAN"
        }

        // Urgency strings
        val (uTitle, uSub) = when (urgency) {
            "HIGH" -> ctx.getString(R.string.high_urgency) to ctx.getString(R.string.urgency_now)
            "MEDIUM" -> ctx.getString(R.string.medium_urgency) to ctx.getString(R.string.urgency_24_48)
            else -> ctx.getString(R.string.low_urgency) to ctx.getString(R.string.urgency_self_care)
        }

        // Always produce 4 recs (so your fixed layout stays perfect)
        val recs = listOf(
            ctx.getString(R.string.rec_rest_hydrated),
            ctx.getString(R.string.rec_otc_if_needed),
            ctx.getString(R.string.rec_monitor_2_3),
            ctx.getString(R.string.rec_consult_if_worse)
        )

        return AnalysisResult(
            createdAt = System.currentTimeMillis(),
            selectedKeys = selectedKeys.distinct(),
            desc = desc,
            urgencyLevel = urgency,
            urgencyTitle = uTitle,
            urgencySub = uSub,
            conditions = conditions,
            recommendations = recs,
            suggestedSpecialityKey = specialityKey
        )
    }
}
