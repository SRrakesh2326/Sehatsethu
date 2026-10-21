package com.simats.sehatsethu.roles.patient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

object OfflineSymptomMl {

    private const val ASSET_FILE = "symptom_model_nb_v1.json"

    data class Pred(val key: String, val prob: Float)

    @Volatile private var loaded = false
    private var labels: List<String> = emptyList()
    private var vocab: List<String> = emptyList()
    private var vid: HashMap<String, Int> = hashMapOf()
    private var logPrior: FloatArray = floatArrayOf()
    private var logProb: Array<FloatArray> = emptyArray()
    private var unkLogProb: FloatArray = floatArrayOf()

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            val raw = ctx.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val j = JSONObject(raw)

            val labs = j.getJSONArray("labels")
            labels = List(labs.length()) { labs.getString(it) }

            val v = j.getJSONArray("vocab")
            vocab = List(v.length()) { v.getString(it) }
            vid = HashMap<String, Int>(vocab.size * 2)
            for (i in vocab.indices) vid[vocab[i]] = i

            val lp = j.getJSONArray("log_prior")
            logPrior = FloatArray(lp.length()) { lp.getDouble(it).toFloat() }

            val ulp = j.getJSONArray("unk_log_prob")
            unkLogProb = FloatArray(ulp.length()) { ulp.getDouble(it).toFloat() }

            val lprob = j.getJSONArray("log_prob")
            val rows = Array(lprob.length()) { FloatArray(vocab.size) }
            for (r in 0 until lprob.length()) {
                val row = lprob.getJSONArray(r)
                val out = FloatArray(vocab.size)
                for (c in 0 until vocab.size) out[c] = row.getDouble(c).toFloat()
                rows[r] = out
            }
            logProb = rows

            loaded = true
        }
    }

    /**
     * Predict topK symptom keys from free text (offline).
     * Works best with typos due to char 4-gram features.
     */
    fun predictTopK(ctx: Context, text: String, locale: Locale, topK: Int = 2): List<Pred> {
        ensureLoaded(ctx)

        val feats = extractFeatures(text, locale)
        if (feats.isEmpty() || labels.isEmpty()) return emptyList()

        // Count features (term frequency)
        val counts = HashMap<Int, Int>()
        for (f in feats) {
            val idx = vid[f] ?: continue
            counts[idx] = (counts[idx] ?: 0) + 1
        }
        if (counts.isEmpty()) return emptyList()

        // score per class
        val scores = FloatArray(labels.size)
        for (c in labels.indices) {
            var s = logPrior[c]
            val row = logProb[c]
            val unk = unkLogProb[c]
            for ((fid, cnt) in counts) {
                s += cnt * (row.getOrNull(fid) ?: unk)
            }
            scores[c] = s
        }

        // softmax -> probabilities
        val maxS = scores.maxOrNull() ?: 0f
        var sum = 0.0
        val exps = DoubleArray(scores.size)
        for (i in scores.indices) {
            val e = exp((scores[i] - maxS).toDouble())
            exps[i] = e
            sum += e
        }

        val preds = ArrayList<Pred>()
        for (i in scores.indices) {
            val p = if (sum <= 0) 0f else (exps[i] / sum).toFloat()
            preds.add(Pred(labels[i], p))
        }

        preds.sortByDescending { it.prob }
        return preds.take(max(1, topK))
    }

    private fun extractFeatures(text: String, locale: Locale): List<String> {
        val t = text.lowercase(locale)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (t.isBlank()) return emptyList()

        val out = ArrayList<String>(64)
        val tokens = t.split(" ")
        for (w0 in tokens) {
            val w = w0.trim()
            if (w.length >= 2) out.add("w:$w")
            if (w.length >= 4) {
                for (i in 0 until (w.length - 3)) {
                    out.add("g:" + w.substring(i, i + 4))
                }
            }
        }
        return out
    }
}
