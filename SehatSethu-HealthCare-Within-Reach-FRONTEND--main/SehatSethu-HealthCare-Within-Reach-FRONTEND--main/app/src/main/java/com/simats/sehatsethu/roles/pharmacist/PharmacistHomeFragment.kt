package com.simats.sehatsethu.roles.pharmacist

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

class PharmacistHomeFragment : Fragment() {

    private var isRefreshing = false
    private var syncAnim: ObjectAnimator? = null

    private val prefs by lazy {
        requireContext().getSharedPreferences("pharmacist_home", 0)
    }

    //  The REAL container that hosts this fragment (no guessing)
    private var hostContainerId: Int = 0

    // keep last created_at so we can update "10 min ago" progressively
    private var req1CreatedAt: String = ""
    private var req2CreatedAt: String = ""

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val ticker = object : Runnable {
        override fun run() {
            view?.let { root ->
                updateLastUpdatedText(root)

                if (req1CreatedAt.isNotBlank()) setText(root, R.id.tvReq1Time, timeAgo(req1CreatedAt))
                if (req2CreatedAt.isNotBlank()) setText(root, R.id.tvReq2Time, timeAgo(req2CreatedAt))
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_pharmacist_home, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //  Detect the actual host container id safely
        hostContainerId = (view.parent as? ViewGroup)?.id ?: 0

        // Safe defaults
        setCounts(view, notifications = 0, totalStock = 0, alerts = 0)
        setText(view, R.id.tvStoreName, getString(R.string.store_name))

        // Low stock cards + empty
        setVisible(view, R.id.vLow1, false)
        setVisible(view, R.id.vLow2, false)
        setVisible(view, R.id.vLow3, false)
        setVisible(view, R.id.vLowEmpty, false)

        // Recent requests cards + empty
        setVisible(view, R.id.vReq1, false)
        setVisible(view, R.id.vReq2, false)
        setVisible(view, R.id.vReqEmpty, false)
        req1CreatedAt = ""
        req2CreatedAt = ""

        // last updated (from cache)
        updateLastUpdatedText(view)

        // Bell -> Notifications
        view.findViewById<ImageView?>(R.id.ivBell)?.setOnClickListener {
            try {
                startActivity(Intent(requireContext(), PharmacistNotificationsActivity::class.java))
            } catch (_: Throwable) {
                showInfo(getString(R.string.notifications), getString(R.string.failed))
            }
        }

        // Sync (card + Tap to sync)
        view.findViewById<View?>(R.id.vSyncCard)?.setOnClickListener { triggerRefresh(view) }
        view.findViewById<TextView?>(R.id.tvTapSync)?.setOnClickListener { triggerRefresh(view) }

        //  Update Stock -> UpdateStockActivity
        view.findViewById<TextView?>(R.id.tvUpdateStock)?.setOnClickListener {
            openIfExists("com.simats.sehatsethu.roles.pharmacist.UpdateStockActivity")
        }

        //  Add Stock -> AddMedicineActivity
        view.findViewById<View?>(R.id.vActionAdd)?.setOnClickListener {
            openIfExists("com.simats.sehatsethu.roles.pharmacist.AddMedicineActivity")
        }

        //  Search medicine
        view.findViewById<View?>(R.id.vActionSearch)?.setOnClickListener {
            try {
                startActivity(Intent(requireContext(), PharmacistMedicineSearchActivity::class.java))
            } catch (_: Throwable) {
                showInfo(getString(R.string.coming_soon), getString(R.string.coming_soon))
            }
        }

        view.findViewById<TextView?>(R.id.tvViewAll)?.setOnClickListener {
            (activity as? PharmacistActivity)?.openRequestsTab()
        }

        // Initial load
        triggerRefresh(view)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(ticker)
        stopSyncSpinner()
        super.onDestroyView()
    }

    private fun triggerRefresh(root: View) {
        if (isRefreshing) return
        isRefreshing = true

        setSyncEnabled(root, false)
        setSyncTextSyncing(root, true)
        startSyncSpinner(root)

        viewLifecycleOwner.lifecycleScope.launch {
            val success = try {
                refreshFromServer(root)
                true
            } catch (_: Throwable) {
                false
            } finally {
                isRefreshing = false
                stopSyncSpinner()
                setSyncEnabled(root, true)
                setSyncTextSyncing(root, false)
            }

            if (success) {
                saveLastUpdatedNow()
                updateLastUpdatedText(root)
            }
        }
    }

    private suspend fun refreshFromServer(root: View) {
        val token = AppPrefs.getToken(requireContext()).orEmpty()
        if (token.isBlank()) {
            setCounts(root, 0, 0, 0)

            // show friendly empty states instead of blank area
            showLowEmpty(root, true)
            setVisible(root, R.id.vLow1, false)
            setVisible(root, R.id.vLow2, false)
            setVisible(root, R.id.vLow3, false)

            showReqEmpty(root, true)
            setVisible(root, R.id.vReq1, false)
            setVisible(root, R.id.vReq2, false)
            req1CreatedAt = ""
            req2CreatedAt = ""
            return
        }

        // 1) Inventory list (same source as UpdateStock)
        val inv = withContext(Dispatchers.IO) {
            postJsonAuth(
                url = BASE_URL + "pharmacist/inventory_list.php",
                token = token,
                body = JSONObject()
            )
        }

        val ok = inv.optBoolean("ok", false)
        if (!ok) {
            setCounts(root, 0, 0, 0)

            showLowEmpty(root, true)
            setVisible(root, R.id.vLow1, false)
            setVisible(root, R.id.vLow2, false)
            setVisible(root, R.id.vLow3, false)

            showReqEmpty(root, true)
            setVisible(root, R.id.vReq1, false)
            setVisible(root, R.id.vReq2, false)
            req1CreatedAt = ""
            req2CreatedAt = ""
            return
        }

        val items = inv.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val totalStock = items.length()

        val lowList = ArrayList<JSONObject>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val qty = o.optInt("quantity", 0)
            val rl = o.optInt("reorder_level", 5)
            val isOut = qty <= 0
            val isLow = !isOut && qty <= rl
            if (isOut || isLow) lowList.add(o)
        }

        lowList.sortWith { a, b ->
            val aq = a.optInt("quantity", 0)
            val bq = b.optInt("quantity", 0)
            val aOut = aq <= 0
            val bOut = bq <= 0
            when {
                aOut && !bOut -> -1
                !aOut && bOut -> 1
                else -> a.optString("medicine_name", "").lowercase()
                    .compareTo(b.optString("medicine_name", "").lowercase())
            }
        }

        val alerts = lowList.size
        setCounts(root, notifications = 0, totalStock = totalStock, alerts = alerts)

        //  Friendly empty-state for Low Stock
        if (lowList.isEmpty()) {
            showLowEmpty(root, true)
            setVisible(root, R.id.vLow1, false)
            setVisible(root, R.id.vLow2, false)
            setVisible(root, R.id.vLow3, false)
        } else {
            showLowEmpty(root, false)
            bindLowCard(root, 1, lowList.getOrNull(0))
            bindLowCard(root, 2, lowList.getOrNull(1))
            bindLowCard(root, 3, lowList.getOrNull(2))
        }

        // 2) Recent Requests
        refreshRecentRequests(root, token)
    }

    private suspend fun refreshRecentRequests(root: View, token: String) {
        val url = BASE_URL + "pharmacist/requests_list.php?view=PENDING&limit=5&_ts=" + System.currentTimeMillis()
        val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }
        if (!isAdded) return

        if (res == null || !res.optBoolean("ok", false)) {
            // show friendly empty-state for requests
            showReqEmpty(root, true)
            setVisible(root, R.id.vReq1, false)
            setVisible(root, R.id.vReq2, false)
            req1CreatedAt = ""
            req2CreatedAt = ""
            return
        }

        val data = res.optJSONObject("data") ?: JSONObject()
        val pendingCount = data.optInt("pending_count", 0)
        val arr = data.optJSONArray("items") ?: JSONArray()

        setText(root, R.id.tvViewAll, getString(R.string.view_all_n, pendingCount))

        if (pendingCount <= 0 || arr.length() == 0) {
            showReqEmpty(root, true)
            setVisible(root, R.id.vReq1, false)
            setVisible(root, R.id.vReq2, false)
            req1CreatedAt = ""
            req2CreatedAt = ""
            return
        }

        showReqEmpty(root, false)
        bindRecentCard(root, 1, arr.optJSONObject(0))
        bindRecentCard(root, 2, arr.optJSONObject(1))
    }

    private fun bindRecentCard(root: View, idx: Int, item: JSONObject?) {
        val cardId = if (idx == 1) R.id.vReq1 else R.id.vReq2
        if (item == null) {
            setVisible(root, cardId, false)
            if (idx == 1) req1CreatedAt = "" else req2CreatedAt = ""
            return
        }
        setVisible(root, cardId, true)

        val nameId = if (idx == 1) R.id.tvReq1Name else R.id.tvReq2Name
        val timeId = if (idx == 1) R.id.tvReq1Time else R.id.tvReq2Time
        val detailId = if (idx == 1) R.id.tvReq1Detail else R.id.tvReq2Detail

        val patientName = item.optString("patient_name", getString(R.string.patient)).trim()
            .ifBlank { getString(R.string.patient) }

        val med = item.optString("medicine_name", "").trim()
        val strength = item.optString("strength", "").trim()
        val displayMed = if (strength.isBlank()) med else "$med $strength"

        val createdAt = item.optString("created_at", "").trim()
        val ago = timeAgo(createdAt)

        setText(root, nameId, patientName)
        setText(root, timeId, ago)
        setText(root, detailId, getString(R.string.looking_for_fmt, displayMed.ifBlank { "--" }))

        if (idx == 1) req1CreatedAt = createdAt else req2CreatedAt = createdAt
    }

    private fun showLowEmpty(root: View, show: Boolean) {
        setVisible(root, R.id.vLowEmpty, show)
    }

    private fun showReqEmpty(root: View, show: Boolean) {
        setVisible(root, R.id.vReqEmpty, show)
    }

    // -------------------- Sync UI --------------------

    private fun startSyncSpinner(root: View) {
        val iv = root.findViewById<ImageView?>(R.id.ivSync) ?: return
        syncAnim?.cancel()
        iv.rotation = 0f

        syncAnim = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, 360f).apply {
            duration = 650L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSyncSpinner() {
        syncAnim?.cancel()
        syncAnim = null
    }

    private fun setSyncEnabled(root: View, enabled: Boolean) {
        root.findViewById<View?>(R.id.vSyncCard)?.isEnabled = enabled
        root.findViewById<TextView?>(R.id.tvTapSync)?.isEnabled = enabled
    }

    private fun setSyncTextSyncing(root: View, syncing: Boolean) {
        val tv = root.findViewById<TextView?>(R.id.tvSyncText) ?: return
        tv.text = if (syncing) getString(R.string.syncing) else getString(R.string.sync_text)
        if (!syncing) updateLastUpdatedText(root)
    }

    private fun saveLastUpdatedNow() {
        prefs.edit().putLong("last_sync_ms", System.currentTimeMillis()).apply()
    }

    private fun updateLastUpdatedText(root: View) {
        val tv = root.findViewById<TextView?>(R.id.tvSyncText) ?: return
        val ms = prefs.getLong("last_sync_ms", 0L)
        if (ms <= 0L) {
            tv.text = getString(R.string.sync_text)
            return
        }

        val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
        val mins = (diff / 60000L).toInt()
        val hrs = (diff / 3600000L).toInt()
        val days = (diff / 86400000L).toInt()

        val ago = when {
            diff < 15000L -> getString(R.string.just_now)
            mins == 1 -> getString(R.string.minute_ago)
            mins in 2..59 -> getString(R.string.minutes_ago, mins)
            hrs == 1 -> getString(R.string.hour_ago)
            hrs in 2..23 -> getString(R.string.hours_ago, hrs)
            days == 1 -> getString(R.string.day_ago)
            else -> getString(R.string.days_ago, days)
        }

        tv.text = getString(R.string.last_updated_ago_fmt, ago)
    }

    // -------------------- Low stock bind --------------------

    private fun bindLowCard(root: View, idx: Int, item: JSONObject?) {
        val containerId = when (idx) {
            1 -> R.id.vLow1
            2 -> R.id.vLow2
            else -> R.id.vLow3
        }

        val container = root.findViewById<View?>(containerId)
        if (container == null || item == null) {
            setVisible(root, containerId, false)
            return
        }
        setVisible(root, containerId, true)

        val name = item.optString("medicine_name", "").trim().ifBlank { "--" }
        val strength = item.optString("strength", "").trim()
        val qty = item.optInt("quantity", 0)
        val isOut = qty <= 0

        fun lowText(): String {
            return if (isOut) getString(R.string.out_of_stock_small)
            else getString(R.string.only_left_n, qty.coerceAtLeast(0))
        }

        val detailLine = if (strength.isBlank()) lowText() else "$strength • ${lowText()}"

        when (idx) {
            1 -> {
                setText(root, R.id.tvLow1Title, name)
                setVisible(root, R.id.tvLow1Sub, false)
                setText(root, R.id.tvLow1Sub, "")
                setText(root, R.id.tvLow1Qty, detailLine)

                applyBadgeStyle(
                    badge = container.findViewById(R.id.vLow1Badge),
                    icon = container.findViewById(R.id.ivLow1Warn),
                    text = container.findViewById(R.id.tvLow1BadgeText),
                    isOut = isOut
                )
            }
            2 -> {
                setText(root, R.id.tvLow2Title, name)
                setText(root, R.id.tvLow2Qty, detailLine)
                applyBadgeStyle(
                    badge = container.findViewById(R.id.vLow2Badge),
                    icon = container.findViewById(R.id.ivLow2Warn),
                    text = container.findViewById(R.id.tvLow2BadgeText),
                    isOut = isOut
                )
            }
            3 -> {
                setText(root, R.id.tvLow3Title, name)
                setText(root, R.id.tvLow3Qty, detailLine)
                applyBadgeStyle(
                    badge = container.findViewById(R.id.vLow3Badge),
                    icon = container.findViewById(R.id.ivLow3X),
                    text = container.findViewById(R.id.tvLow3BadgeText),
                    isOut = isOut
                )
            }
        }
    }

    private fun applyBadgeStyle(badge: View?, icon: ImageView?, text: TextView?, isOut: Boolean) {
        if (badge == null || icon == null || text == null) return

        if (isOut) {
            badge.setBackgroundResource(R.drawable.bg_badge_out)
            icon.setImageResource(R.drawable.ic_x)
            icon.setColorFilter(0xFFEF4444.toInt())
            text.setTextColor(0xFFEF4444.toInt())
            text.text = getString(R.string.out_of_stock)
        } else {
            badge.setBackgroundResource(R.drawable.bg_badge_low)
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(0xFFF59E0B.toInt())
            text.setTextColor(0xFFF59E0B.toInt())
            text.text = getString(R.string.low_stock)
        }
    }

    // -------------------- Network helpers --------------------

    private fun postJsonAuth(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(txt) }.getOrElse { JSONObject().put("ok", false).put("error", "Bad JSON") }
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun getJsonAuth(url: String, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (txt.isBlank()) null else runCatching { JSONObject(txt) }.getOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // -------------------- Time ago --------------------

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

    // -------------------- Small helpers --------------------

    private fun setCounts(root: View, notifications: Int, totalStock: Int, alerts: Int) {
        val badge = root.findViewById<TextView?>(R.id.tvBellBadge)
        badge?.text = notifications.toString()
        badge?.visibility = if (notifications > 0) View.VISIBLE else View.GONE

        root.findViewById<TextView?>(R.id.tvStockCount)?.text = totalStock.toString()
        root.findViewById<TextView?>(R.id.tvAlertCount)?.text = alerts.toString()
    }

    private fun setText(root: View, id: Int, text: String) {
        root.findViewById<TextView?>(id)?.text = text
    }

    private fun setVisible(root: View, id: Int, visible: Boolean) {
        root.findViewById<View?>(id)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showInfo(title: String, msg: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun openIfExists(className: String) {
        try {
            val clazz = Class.forName(className)
            startActivity(Intent(requireContext(), clazz))
        } catch (_: Exception) {
            showInfo(getString(R.string.coming_soon), getString(R.string.coming_soon))
        }
    }

    private fun dial(phone: String) {
        val p = phone.trim()
        if (p.isBlank()) return
        val uri = Uri.parse("tel:$p")
        try {
            startActivity(Intent(Intent.ACTION_DIAL).apply { data = uri })
        } catch (_: Throwable) {}
    }
}
