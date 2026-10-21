package com.simats.sehatsethu.roles.patient

import android.content.Context
import java.util.Locale

object SymptomNlp {

    // keep old signature for safety (other call sites won’t break)
    fun extractKeys(text: String, locale: Locale): Set<String> = extractKeys(null, text, locale)

    fun extractKeys(ctx: Context?, text: String, locale: Locale): Set<String> {
        val t = text.lowercase(locale).trim()
        if (t.isBlank()) return emptySet()

        val out = LinkedHashSet<String>()

        fun hasAny(vararg xs: String): Boolean = xs.any { t.contains(it) }

        // FEVER
        if (hasAny("fever", "temperature", "high temp", "pyrexia", "बुखार", "ज्वर", "காய்ச்சல்", "జ్వరం", "ਬੁਖਾਰ")) {
            out.add("FEVER")
        }

        // COLD
        if (hasAny("cold", "runny nose", "sneezing", "blocked nose", "nasal congestion", "सर्दी", "जुकाम", "ஜலதோஷம்", "జలుబు", "ਜ਼ੁਕਾਮ")) {
            out.add("COLD")
        }

        // COUGH
        if (hasAny("cough", "coughing", "खांसी", "இருமல்", "దగ్గు", "ਖਾਂਸੀ")) {
            out.add("COUGH")
        }

        // SORE THROAT
        if (hasAny("sore throat", "throat pain", "gargle", "गला दर्द", "गले में दर्द", "தொண்டை வலி", "గొంతు నొప్పి", "ਗਲੇ ਦਾ ਦਰਦ")) {
            out.add("SORE_THROAT")
        }

        // HEADACHE
        if (hasAny("headache", "head pain", "migraine", "सिरदर्द", "தலைவலி", "తలనొప్పి", "ਸਿਰ ਦਰਦ")) {
            out.add("HEADACHE")
        }

        // STOMACH PAIN
        if (hasAny("stomach pain", "abdominal pain", "gas", "vomit", "vomiting", "nausea", "पेट दर्द", "वमन", "उल्टी", "வயிற்று வலி", "కడుపునొప్పి", "పొత్తికడుపు", "ਪੇਟ ਦਰਦ")) {
            out.add("STOMACH_PAIN")
        }

        // BODY PAIN
        if (hasAny("body pain", "body ache", "muscle pain", "myalgia", "शरीर दर्द", "उंगलियों में दर्द", "உடல் வலி", "శరీర నొప్పి", "ਸਰੀਰ ਦਰਦ")) {
            out.add("BODY_PAIN")
        }

        // TIREDNESS
        if (hasAny("tired", "tiredness", "fatigue", "weak", "weakness", "थकान", "कमजोरी", "சோர்வு", "அலசல்", "అలసట", "బలహీనতা", "ਥਕਾਵਟ")) {
            out.add("TIREDNESS")
        }

        //  ML fallback/add-on (offline) — helps with typos / wrong wording
        if (ctx != null) {
            val preds = runCatching {
                OfflineSymptomMl.predictTopK(ctx, text, locale, topK = 2)
            }.getOrNull().orEmpty()

            for (p in preds) {
                // threshold tuned for small model
                if (p.prob >= 0.35f) out.add(p.key)
            }
        }

        return out
    }
}
