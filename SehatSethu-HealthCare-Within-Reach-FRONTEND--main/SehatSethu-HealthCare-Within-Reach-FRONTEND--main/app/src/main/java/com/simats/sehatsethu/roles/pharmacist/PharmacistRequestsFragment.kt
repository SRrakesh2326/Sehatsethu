package com.simats.sehatsethu.roles.pharmacist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class PharmacistRequestsFragment : Fragment() {

    companion object {
        private const val TAG = "PharmReq"
        private const val POLL_MS = 2500L
        private const val INV_REFRESH_MS = 2500L
    }

    private var rootRef: View? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var isLoading = false

    // inventory cache
    private var lastInvFetchMs = 0L
    private var invMap: MutableMap<String, InvRow> = mutableMapOf()

    private data class InvRow(val qty: Int, val reorder: Int)

    private val poller = object : Runnable {
        override fun run() {
            val r = rootRef
            if (r != null && isAdded) {
                if (!isLoading) loadRequests(r)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_pharmacist_medicine_requests, c, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        rootRef = v

        // initial
        setVisible(v, R.id.vReq1, false)
        setVisible(v, R.id.vReq2, false)
        setVisible(v, R.id.vReq3, false)
        setVisible(v, R.id.vEmptyState, true)
        setText(v, R.id.tvSubTitle, getString(R.string.pending_requests_count, 0))

        loadRequests(v)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(poller)
        rootRef = null
        super.onDestroyView()
    }

    private fun loadRequests(root: View) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            showEmpty(root)
            return
        }

        val url = BASE_URL +
                "pharmacist/requests_list.php?view=PENDING&limit=50&_ts=" +
                System.currentTimeMillis()

        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { httpGetRaw(url, token) }
                if (!isAdded) return@launch

                if (raw == null) {
                    showEmpty(root)
                    return@launch
                }

                val code = raw.first
                val body = raw.second
                Log.d(TAG, "requests_list code=$code body=${body.take(600)}")

                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json == null || !json.optBoolean("ok", false)) {
                    showEmpty(root)
                    return@launch
                }

                val data = json.optJSONObject("data") ?: JSONObject()
                val pendingCount = data.optInt("pending_count", 0)
                val arr = data.optJSONArray("items") ?: JSONArray()

                setText(root, R.id.tvSubTitle, getString(R.string.pending_requests_count, pendingCount))

                //  Empty state
                if (arr.length() == 0) {
                    showEmpty(root)
                    return@launch
                } else {
                    hideEmpty(root)
                }

                // inventory refresh for realtime status flip
                refreshInventoryIfNeeded(token)

                // bind first 3 cards
                bindCard(root, 1, arr.optJSONObject(0))
                bindCard(root, 2, arr.optJSONObject(1))
                bindCard(root, 3, arr.optJSONObject(2))

                if (arr.length() < 1) setVisible(root, R.id.vReq1, false)
                if (arr.length() < 2) setVisible(root, R.id.vReq2, false)
                if (arr.length() < 3) setVisible(root, R.id.vReq3, false)

            } finally {
                isLoading = false
            }
        }
    }

    private fun showEmpty(root: View) {
        setVisible(root, R.id.vReq1, false)
        setVisible(root, R.id.vReq2, false)
        setVisible(root, R.id.vReq3, false)
        setVisible(root, R.id.vEmptyState, true)
        setText(root, R.id.tvSubTitle, getString(R.string.pending_requests_count, 0))
    }

    private fun hideEmpty(root: View) {
        setVisible(root, R.id.vEmptyState, false)
    }

    private suspend fun refreshInventoryIfNeeded(token: String) {
        val now = System.currentTimeMillis()
        if (now - lastInvFetchMs < INV_REFRESH_MS) return

        val invUrl = BASE_URL + "pharmacist/inventory_list.php"
        val invJson = withContext(Dispatchers.IO) { postJson(invUrl, token, JSONObject()) }
        if (invJson?.optBoolean("ok", false) != true) return

        val items = invJson.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val map = mutableMapOf<String, InvRow>()

        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val name = o.optString("medicine_name", "").trim()
            val strength = o.optString("strength", "").trim()
            if (name.isBlank()) continue

            val qty = o.optInt("quantity", 0)
            val reorder = o.optInt("reorder_level", 5)
            map[keyOf(name, strength)] = InvRow(qty = qty, reorder = reorder)
        }

        invMap = map
        lastInvFetchMs = now
    }

    private fun bindCard(root: View, idx: Int, item: JSONObject?) {
        val cardId = when (idx) {
            1 -> R.id.vReq1
            2 -> R.id.vReq2
            else -> R.id.vReq3
        }
        if (item == null) {
            setVisible(root, cardId, false)
            return
        }
        setVisible(root, cardId, true)

        val patientName = item.optString("patient_name", getString(R.string.patient)).trim()
            .ifBlank { getString(R.string.patient) }
        val phone = item.optString("patient_phone", "").trim()
        val med = item.optString("medicine_name", "").trim()
        val strength = item.optString("strength", "").trim()
        val qty = item.optInt("quantity", 0)
        val createdAt = item.optString("created_at", "")
        val requestId = item.optInt("id", 0)

        val displayMed = if (strength.isBlank()) med else "$med $strength"
        val timeAgo = timeAgo(createdAt)

        val serverStatus = item.optString("stock_status", "OUT_OF_STOCK")
        val computed = computeStockStatusFromInventory(med, strength)
        val stockStatus = computed ?: serverStatus

        when (idx) {
            1 -> {
                setText(root, R.id.tvReq1Name, patientName)
                setText(root, R.id.tvReq1Time, timeAgo)
                setText(root, R.id.tvReq1Med, displayMed)
                setText(root, R.id.tvReq1Qty, getString(R.string.quantity_value, qty))
                styleStatusBadge(root.findViewById(R.id.tvReq1Status), stockStatus)

                root.findViewById<Button>(R.id.btnReq1Call).setOnClickListener { dial(phone) }

                val btnMark = root.findViewById<Button>(R.id.btnReq1Mark)
                val canMark = stockStatus == "IN_STOCK"
                btnMark.visibility = if (canMark) View.VISIBLE else View.GONE
                btnMark.isEnabled = canMark
                btnMark.setOnClickListener {
                    if (canMark && requestId > 0) markAvailableThenRefresh(requestId)
                }
            }

            2 -> {
                setText(root, R.id.tvReq2Name, patientName)
                setText(root, R.id.tvReq2Time, timeAgo)
                setText(root, R.id.tvReq2Med, displayMed)
                setText(root, R.id.tvReq2Qty, getString(R.string.quantity_value, qty))
                styleStatusBadge(root.findViewById(R.id.tvReq2Status), stockStatus)
                root.findViewById<Button>(R.id.btnReq2Call).setOnClickListener { dial(phone) }
            }

            else -> {
                setText(root, R.id.tvReq3Name, patientName)
                setText(root, R.id.tvReq3Time, timeAgo)
                setText(root, R.id.tvReq3Med, displayMed)
                setText(root, R.id.tvReq3Qty, getString(R.string.quantity_value, qty))
                styleStatusBadge(root.findViewById(R.id.tvReq3Status), stockStatus)
                root.findViewById<Button>(R.id.btnReq3Call).setOnClickListener { dial(phone) }
            }
        }
    }

    private fun computeStockStatusFromInventory(med: String, strength: String): String? {
        if (med.isBlank()) return null
        val row = invMap[keyOf(med, strength)] ?: return null
        return when {
            row.qty <= 0 -> "OUT_OF_STOCK"
            row.qty <= row.reorder -> "LOW_STOCK"
            else -> "IN_STOCK"
        }
    }

    private fun keyOf(name: String, strength: String): String {
        val n = name.trim().lowercase(Locale.getDefault())
        val s = strength.trim().lowercase(Locale.getDefault())
        return "$n||$s"
    }

    private fun markAvailableThenRefresh(requestId: Int) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) return

        val url = BASE_URL + "pharmacist/requests_mark_available.php"

        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                postJson(url, token, JSONObject().apply { put("request_id", requestId) })
            }
            if (!isAdded) return@launch

            if (res?.optBoolean("ok", false) == true) {
                toast(getString(R.string.request_fulfilled_toast))
                lastInvFetchMs = 0L
                rootRef?.let { loadRequests(it) }
            } else {
                toast(res?.optString("error") ?: getString(R.string.failed))
            }
        }
    }

    private fun styleStatusBadge(tv: TextView?, stockStatus: String) {
        if (tv == null) return
        when (stockStatus) {
            "IN_STOCK" -> {
                tv.text = getString(R.string.in_stock_badge)
                tv.setTextColor(0xFF059669.toInt())
                tv.setBackgroundResource(R.drawable.bg_badge_green)
            }
            "LOW_STOCK" -> {
                tv.text = getString(R.string.low_stock_badge)
                tv.setTextColor(0xFFD97706.toInt())
                tv.setBackgroundResource(R.drawable.bg_badge_yellow)
            }
            else -> {
                tv.text = getString(R.string.out_of_stock_badge)
                tv.setTextColor(0xFFDC2626.toInt())
                tv.setBackgroundResource(R.drawable.bg_badge_red_soft)
            }
        }
    }

    private fun dial(phone: String) {
        val p = phone.trim()
        if (p.isBlank()) {
            toast(getString(R.string.no_phone))
            return
        }
        val uri = Uri.parse("tel:$p")
        runCatching { startActivity(Intent(Intent.ACTION_DIAL).apply { data = uri }) }
            .onFailure { toast(getString(R.string.failed)) }
    }

    private fun setText(root: View, id: Int, text: String) {
        root.findViewById<TextView?>(id)?.text = text
    }

    private fun setVisible(root: View, id: Int, visible: Boolean) {
        root.findViewById<View?>(id)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    }

    private fun httpGetRaw(urlStr: String, token: String): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Pair(code, text)
        } catch (t: Throwable) {
            Log.e(TAG, "GET error", t)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun postJson(urlStr: String, token: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { JSONObject(text) }.getOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun timeAgo(mysqlDateTime: String): String {
        val s = mysqlDateTime.trim()
        if (s.isBlank()) return "—"
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            val t = df.parse(s)?.time ?: return "—"
            val diff = System.currentTimeMillis() - t
            val mins = abs(diff) / 60000
            val hrs = mins / 60
            val days = hrs / 24

            when {
                mins < 1 -> getString(R.string.just_now)
                mins < 60 -> getString(R.string.minutes_ago, mins)
                hrs < 24 -> if (hrs > 1) getString(R.string.hours_ago, hrs) else getString(R.string.hour_ago)
                else -> if (days > 1) getString(R.string.days_ago, days) else getString(R.string.day_ago)
            }
        } catch (_: Throwable) {
            "—"
        }
    }
}
