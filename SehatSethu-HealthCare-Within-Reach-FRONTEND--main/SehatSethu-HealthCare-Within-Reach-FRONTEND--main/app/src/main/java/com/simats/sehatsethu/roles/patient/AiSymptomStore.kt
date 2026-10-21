package com.simats.sehatsethu.roles.patient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AiSymptomStore {
    private const val SP = "ai_symptom_store"
    private const val KEY_LAST = "last_result"
    private const val KEY_HISTORY = "history"

    fun saveResult(ctx: Context, analysis: OfflineSymptomAnalyzer.AnalysisResult) {
        val sp = ctx.getSharedPreferences(SP, 0)
        val obj = analysis.toJson()

        sp.edit().putString(KEY_LAST, obj.toString()).apply()

        val arr = runCatching {
            JSONArray(sp.getString(KEY_HISTORY, "[]") ?: "[]")
        }.getOrElse { JSONArray() }

        arr.put(0, obj) // newest first

        // keep last 20
        val trimmed = JSONArray()
        for (i in 0 until minOf(arr.length(), 20)) trimmed.put(arr.getJSONObject(i))

        sp.edit().putString(KEY_HISTORY, trimmed.toString()).apply()
    }

    fun loadLast(ctx: Context): JSONObject? {
        val sp = ctx.getSharedPreferences(SP, 0)
        val raw = sp.getString(KEY_LAST, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }
}
