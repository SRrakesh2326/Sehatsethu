package com.simats.sehatsethu.roles.pharmacist

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class PharmacistNotificationsActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var tvSubtitle: TextView
    private lateinit var layoutFilters: LinearLayout
    private lateinit var rv: RecyclerView

    private lateinit var chipAll: TextView
    private lateinit var chipUnread: TextView

    private val items = mutableListOf<NotifRow>()
    private lateinit var adapter: NotifAdapter

    private var onlyUnread = false
    private var loading = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacist_notifications)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        layoutFilters = findViewById(R.id.layoutFilters)

        //  In your XML this is RecyclerView
        rv = findViewById(R.id.listNotifications)

        // chips might not have IDs → fallback to children 0/1
        chipAll = findOptionalTextViewId("tvChipAll") ?: (layoutFilters.getChildAt(0) as TextView)
        chipUnread = findOptionalTextViewId("tvChipUnread") ?: (layoutFilters.getChildAt(1) as TextView)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = NotifAdapter(
            list = items,
            onDismiss = { row -> dismiss(row) },
            onOpen = { row ->
                //  1) mark read if needed
                if (!row.isRead) markRead(row)

                //  2) If it's a medicine request notification → open PharmacistRequestFragment (via PharmacistActivity tab)
                if (isMedicineRequest(row)) {
                    openRequestsTab()
                }
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        chipAll.setOnClickListener {
            if (loading) return@setOnClickListener
            onlyUnread = false
            setChipUi()
            load()
        }
        chipUnread.setOnClickListener {
            if (loading) return@setOnClickListener
            onlyUnread = true
            setChipUi()
            load()
        }

        onlyUnread = false
        setChipUi()
        load()
    }

    private fun findOptionalTextViewId(name: String): TextView? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun setChipUi() {
        if (!onlyUnread) {
            chipAll.setBackgroundResource(R.drawable.bg_chip_selected)
            chipAll.setTextColor(Color.parseColor("#FFFFFF"))
            chipUnread.setBackgroundResource(R.drawable.bg_chip_unselected)
            chipUnread.setTextColor(Color.parseColor("#334155"))
        } else {
            chipUnread.setBackgroundResource(R.drawable.bg_chip_selected)
            chipUnread.setTextColor(Color.parseColor("#FFFFFF"))
            chipAll.setBackgroundResource(R.drawable.bg_chip_unselected)
            chipAll.setTextColor(Color.parseColor("#334155"))
        }
    }

    private fun load() {
        if (loading) return

        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank()) {
            toast(getString(R.string.please_login_again))
            finish()
            return
        }

        loading = true
        tvSubtitle.text = getString(R.string.loading)

        scope.launch {
            val url = BASE_URL + "pharmacist/notifications_list.php?only_unread=" + (if (onlyUnread) "1" else "0")
            val res = withContext(Dispatchers.IO) { getJsonAuth(url, token) }

            loading = false

            if (!res.optBoolean("ok", false)) {
                items.clear()
                adapter.notifyDataSetChanged()
                tvSubtitle.text = getString(R.string.all_caught_up)
                toast(res.optString("error", getString(R.string.failed)))
                return@launch
            }

            val arr = (res.optJSONObject("data") ?: JSONObject()).optJSONArray("items") ?: JSONArray()

            items.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    NotifRow(
                        id = o.optLong("id", 0L),
                        title = o.optString("title", "").trim(),
                        body = o.optString("body", "").trim(),
                        type = o.optString("type", "").trim(),
                        isRead = o.optInt("is_read", 0) == 1,
                        createdAt = o.optString("created_at", "").trim()
                    )
                )
            }
            adapter.notifyDataSetChanged()

            tvSubtitle.text =
                if (items.isEmpty()) getString(R.string.all_caught_up)
                else if (onlyUnread) getString(R.string.showing_unread)
                else getString(R.string.showing_all)
        }
    }

    private fun dismiss(row: NotifRow) {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank() || row.id <= 0) return

        scope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    BASE_URL + "pharmacist/notifications_dismiss.php",
                    token,
                    JSONObject().put("id", row.id)
                )
            }
            if (res.optBoolean("ok", false)) {
                items.removeAll { it.id == row.id }
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) tvSubtitle.text = getString(R.string.all_caught_up)
            } else {
                toast(res.optString("error", getString(R.string.failed)))
            }
        }
    }

    private fun markRead(row: NotifRow) {
        val token = AppPrefs.getToken(this).orEmpty()
        if (token.isBlank() || row.id <= 0) return

        scope.launch {
            val res = withContext(Dispatchers.IO) {
                postJsonAuth(
                    BASE_URL + "pharmacist/notifications_mark_read.php",
                    token,
                    JSONObject().put("id", row.id)
                )
            }
            if (res.optBoolean("ok", false)) {
                row.isRead = true
                if (onlyUnread) items.removeAll { it.id == row.id }
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) tvSubtitle.text = getString(R.string.all_caught_up)
            }
        }
    }

    //  Detect "medicine request" notification from title/body/type
    private fun isMedicineRequest(row: NotifRow): Boolean {
        val key = (row.type + " " + row.title + " " + row.body).uppercase(Locale.US)
        return key.contains("MEDICINE") && key.contains("REQUEST") ||
                key.contains("MEDICINE_REQUEST") ||
                key.contains("NEW MEDICINE REQUEST") ||
                key.contains("REQUEST RECEIVED")
    }

    //  Open PharmacistRequestFragment by launching PharmacistActivity and selecting Requests tab
    // (uses reflection so it won’t crash compilation if you renamed activity)
    private fun openRequestsTab() {
        try {
            val cls = Class.forName("com.simats.sehatsethu.roles.pharmacist.PharmacistActivity")
            val i = Intent(this, cls).apply {
                // common patterns — your PharmacistActivity can read ANY one of these
                putExtra("open_tab", "REQUESTS")
                putExtra("tab", "REQUESTS")
                putExtra("open_requests", true)
            }
            startActivity(i)
            finish() // close notifications screen
        } catch (_: Throwable) {
            toast(getString(R.string.failed))
        }
    }

    private fun getJsonAuth(urlStr: String, token: String): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (txt.isBlank()) JSONObject().put("ok", false).put("error", "Empty response") else JSONObject(txt)
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun postJsonAuth(urlStr: String, token: String, body: JSONObject): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (txt.isBlank()) JSONObject().put("ok", false).put("error", "Empty response") else JSONObject(txt)
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    data class NotifRow(
        val id: Long = 0L,
        val title: String = "",
        val body: String = "",
        val type: String = "",
        var isRead: Boolean = false,
        val createdAt: String = ""
    )

    inner class NotifAdapter(
        private val list: List<NotifRow>,
        private val onDismiss: (NotifRow) -> Unit,
        private val onOpen: (NotifRow) -> Unit
    ) : RecyclerView.Adapter<NotifAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pharmacist_notification, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = list[position]
            val ctx = h.itemView.context

            h.tvTitle.text = row.title.ifBlank { ctx.getString(R.string.notification) }
            h.tvBody.text = row.body.ifBlank { "--" }
            h.tvTime.text = timeAgo(row.createdAt)

            val key = (row.type.ifBlank { row.title + " " + row.body }).uppercase(Locale.US)

            when {
                key.contains("REQUEST") || key.contains("MEDICINE") -> {
                    h.ivIcon.setImageResource(R.drawable.ic_document)
                    h.ivIcon.setBackgroundColor(Color.parseColor("#E0E7FF"))
                }
                key.contains("LOW") || key.contains("OUT") || key.contains("STOCK") -> {
                    h.ivIcon.setImageResource(R.drawable.ic_box)
                    h.ivIcon.setBackgroundColor(Color.parseColor("#FEE2E2"))
                }
                key.contains("VERIFIED") || key.contains("APPROVED") -> {
                    h.ivIcon.setImageResource(R.drawable.ic_verified)
                    h.ivIcon.setBackgroundColor(Color.parseColor("#DCFCE7"))
                }
                else -> {
                    h.ivIcon.setImageResource(R.drawable.ic_document)
                    h.ivIcon.setBackgroundColor(Color.parseColor("#E0E7FF"))
                }
            }

            h.ivClose.setOnClickListener { onDismiss(row) }
            h.itemView.setOnClickListener { onOpen(row) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivIcon)
            val tvTitle: TextView = v.findViewById(R.id.tvNTitle)
            val tvBody: TextView = v.findViewById(R.id.tvNBody)
            val tvTime: TextView = v.findViewById(R.id.tvNTime)
            val ivClose: ImageView = v.findViewById(R.id.ivClose)
        }
    }

    private fun timeAgo(createdAt: String): String {
        val dt = parseDate(createdAt) ?: return "—"
        val diffMs = System.currentTimeMillis() - dt.time
        val diff = abs(diffMs)

        val mins = (diff / 60000L).toInt()
        val hrs = (diff / 3600000L).toInt()
        val days = (diff / 86400000L).toInt()

        return when {
            diff < 15000L -> getString(R.string.just_now)
            mins < 60 -> if (mins <= 1) getString(R.string.minute_ago) else getString(R.string.minutes_ago, mins)
            hrs < 24 -> if (hrs == 1) getString(R.string.hour_ago) else getString(R.string.hours_ago, hrs)
            else -> if (days == 1) getString(R.string.day_ago) else getString(R.string.days_ago, days)
        }
    }

    private fun parseDate(s: String): java.util.Date? {
        val ss = s.trim()
        if (ss.isBlank()) return null
        val fmts = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS")
        for (p in fmts) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = true
                val d = f.parse(ss)
                if (d != null) return d
            } catch (_: Throwable) {}
        }
        return null
    }
}
