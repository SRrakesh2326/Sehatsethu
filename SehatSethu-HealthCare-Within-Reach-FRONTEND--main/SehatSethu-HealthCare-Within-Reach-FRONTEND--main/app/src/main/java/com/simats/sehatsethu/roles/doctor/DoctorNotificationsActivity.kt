package com.simats.sehatsethu.roles.doctor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.ApiConfig.BASE_URL
import com.simats.sehatsethu.AppPrefs
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DoctorNotificationsActivity : BaseActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvChipAll: TextView
    private lateinit var tvChipUnread: TextView
    private lateinit var rvNotifs: RecyclerView
    private lateinit var tvEmpty: TextView

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val items = ArrayList<NotifRow>()
    private lateinit var adapter: NotifAdapter

    private var onlyUnread = false
    private var loading = false

    private val tzIst = TimeZone.getTimeZone("Asia/Kolkata")
    private val dfServer = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tzIst }
    private val dfDateShort = SimpleDateFormat("d MMM", Locale.getDefault()).apply { timeZone = tzIst }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doctor_notifications)
        supportActionBar?.hide()

        ivBack = findViewById(R.id.ivBack)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvChipAll = findViewById(R.id.tvChipAll)
        tvChipUnread = findViewById(R.id.tvChipUnread)
        rvNotifs = findViewById(R.id.rvNotifs)
        tvEmpty = findViewById(R.id.tvEmpty)

        ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = NotifAdapter(
            rows = items,
            onMarkRead = { row -> if (row.id > 0) markRead(row.id) },
            onDismiss = { row -> if (row.id > 0) dismiss(row.id) },
            onAction = { row -> onActionClick(row) }
        )

        rvNotifs.layoutManager = LinearLayoutManager(this)
        rvNotifs.adapter = adapter

        tvChipAll.setOnClickListener {
            if (onlyUnread) {
                onlyUnread = false
                updateChips()
                load()
            }
        }
        tvChipUnread.setOnClickListener {
            if (!onlyUnread) {
                onlyUnread = true
                updateChips()
                load()
            }
        }

        // safe initial UI
        updateChips()
        tvSubtitle.text = getString(R.string.unread_count_fmt, 0)
        tvChipUnread.text = getString(R.string.unread_chip_fmt, 0)
        tvEmpty.visibility = View.GONE

        load()
    }

    private fun updateChips() {
        val selBg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_chip_selected_green)
        val unselBg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_chip_unselected_gray)

        if (!onlyUnread) {
            tvChipAll.background = selBg
            tvChipAll.setTextColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.white))

            tvChipUnread.background = unselBg
            tvChipUnread.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.black))
        } else {
            tvChipUnread.background = selBg
            tvChipUnread.setTextColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.white))

            tvChipAll.background = unselBg
            tvChipAll.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.black))
        }
    }

    private fun load() {
        if (loading) return
        loading = true

        uiScope.launch {
            try {
                val token = AppPrefs.getToken(this@DoctorNotificationsActivity).orEmpty()
                if (token.isBlank()) {
                    toast(getString(R.string.please_login_again))
                    items.clear()
                    adapter.notifyDataSetChanged()
                    tvEmpty.visibility = View.VISIBLE
                    tvSubtitle.text = getString(R.string.unread_count_fmt, 0)
                    tvChipUnread.text = getString(R.string.unread_chip_fmt, 0)
                    return@launch
                }

                val res = withContext(Dispatchers.IO) {
                    DoctorNotifApi.list(token = token, unreadOnly = onlyUnread)
                }

                if (!res.optBoolean("ok", false)) {
                    toast(res.optString("error", getString(R.string.failed)))
                    return@launch
                }

                val arr = res.optJSONArray("data") ?: JSONArray()

                items.clear()
                var unreadCount = 0

                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val row = NotifRow.fromJson(o)
                    items.add(row)
                    if (!row.isRead) unreadCount++
                }

                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

                tvSubtitle.text = getString(R.string.unread_count_fmt, unreadCount)
                tvChipUnread.text = getString(R.string.unread_chip_fmt, unreadCount)

            } catch (_: Throwable) {
                toast(getString(R.string.failed))
            } finally {
                loading = false
            }
        }
    }

    private fun markRead(id: Long) {
        uiScope.launch {
            try {
                val token = AppPrefs.getToken(this@DoctorNotificationsActivity).orEmpty()
                if (token.isBlank()) return@launch

                val ok = withContext(Dispatchers.IO) {
                    DoctorNotifApi.markRead(token = token, id = id)
                }
                if (ok) load()
            } catch (_: Throwable) {
                toast(getString(R.string.failed))
            }
        }
    }

    private fun dismiss(id: Long) {
        uiScope.launch {
            try {
                val token = AppPrefs.getToken(this@DoctorNotificationsActivity).orEmpty()
                if (token.isBlank()) return@launch

                val ok = withContext(Dispatchers.IO) {
                    DoctorNotifApi.dismiss(token = token, id = id)
                }
                if (ok) load()
            } catch (_: Throwable) {
                toast(getString(R.string.failed))
            }
        }
    }

    //  UPDATED: NO DoctorActivity. Opens a dedicated Consultations host Activity.
    private fun onActionClick(row: NotifRow) {
        if (!row.isRead && row.id > 0) markRead(row.id)

        startActivity(Intent(this, DoctorConsultationsActivity::class.java))
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------- Row model ----------
    private data class NotifRow(
        val id: Long,
        val title: String,
        val body: String,
        val createdAt: String,
        val isRead: Boolean,
        val dataJson: String?
    ) {
        val actionLabel: String = computeActionLabel(title, body, dataJson)

        companion object {
            fun fromJson(o: JSONObject): NotifRow {
                return NotifRow(
                    id = o.optLong("id", 0L),
                    title = o.optString("title", "").ifBlank { "Notification" },
                    body = o.optString("body", ""),
                    createdAt = o.optString("created_at", ""),
                    isRead = o.optInt("is_read", 0) == 1,
                    dataJson = if (o.has("data_json") && !o.isNull("data_json")) o.optString("data_json") else null
                )
            }

            private fun computeActionLabel(title: String, body: String, dataJson: String?): String {
                val fromJson = runCatching {
                    if (!dataJson.isNullOrBlank()) JSONObject(dataJson).optString("action_label", "") else ""
                }.getOrDefault("")
                if (fromJson.isNotBlank()) return fromJson

                val t = title.lowercase(Locale.getDefault())
                val b = body.lowercase(Locale.getDefault())

                return when {
                    t.contains("appointment") || b.contains("appointment") -> "View Schedule"
                    t.contains("record") || b.contains("report") -> "View Record"
                    else -> "View Schedule"
                }
            }
        }
    }

    // ---------- Adapter ----------
    private inner class NotifAdapter(
        private val rows: List<NotifRow>,
        private val onMarkRead: (NotifRow) -> Unit,
        private val onDismiss: (NotifRow) -> Unit,
        private val onAction: (NotifRow) -> Unit
    ) : RecyclerView.Adapter<NotifAdapter.NotifVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotifVH {
            val v = layoutInflater.inflate(R.layout.item_doctor_notification, parent, false)
            return NotifVH(v)
        }

        override fun onBindViewHolder(holder: NotifVH, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class NotifVH(v: View) : RecyclerView.ViewHolder(v) {

            private val icType: ImageView = v.findViewById(R.id.icType)
            private val txtTitle: TextView = v.findViewById(R.id.txtTitle)
            private val txtMessage: TextView = v.findViewById(R.id.txtMessage)
            private val txtTime: TextView = v.findViewById(R.id.txtTime)
            private val txtAction: TextView = v.findViewById(R.id.txtAction)
            private val dotUnread: View = v.findViewById(R.id.dotUnread)
            private val btnRead: ImageView = v.findViewById(R.id.btnRead)
            private val btnClose: ImageView = v.findViewById(R.id.btnClose)

            fun bind(row: NotifRow) {
                txtTitle.text = row.title
                txtMessage.text = row.body
                txtTime.text = relativeTime(row.createdAt)
                txtAction.text = row.actionLabel

                dotUnread.visibility = if (!row.isRead) View.VISIBLE else View.INVISIBLE

                applyIconStyle(row, icType)

                btnRead.alpha = if (row.isRead) 0.35f else 1f
                btnRead.setOnClickListener { onMarkRead(row) }
                btnClose.setOnClickListener { onDismiss(row) }
                txtAction.setOnClickListener { onAction(row) }
            }
        }
    }

    private fun applyIconStyle(row: NotifRow, ic: ImageView) {
        val t = row.title.lowercase(Locale.getDefault())
        val b = row.body.lowercase(Locale.getDefault())

        when {
            t.contains("appointment") || b.contains("appointment") -> {
                ic.setImageResource(R.drawable.ic_calendar)
                ic.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_icon_blue)
            }
            t.contains("consultation") || b.contains("consultation") -> {
                ic.setImageResource(R.drawable.ic_clock)
                ic.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_icon_orange)
            }
            else -> {
                ic.setImageResource(R.drawable.ic_info)
                ic.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_icon_gray)
            }
        }
    }

    private fun relativeTime(createdAt: String): String {
        val ms = parseServerMs(createdAt) ?: return dfDateShort.format(Date())
        val now = System.currentTimeMillis()
        val diffMin = ((now - ms) / 60000L).toInt()

        if (diffMin < 0) return dfDateShort.format(Date(ms))

        return when {
            diffMin <= 0 -> getString(R.string.just_now)
            diffMin < 60 -> getString(R.string.minutes_ago_fmt, diffMin)
            diffMin < 24 * 60 -> {
                val hrs = (diffMin / 60).coerceAtLeast(1)
                getString(R.string.hours_ago_fmt, hrs)
            }
            diffMin < 48 * 60 -> getString(R.string.yesterday)
            else -> dfDateShort.format(Date(ms))
        }
    }

    private fun parseServerMs(s: String): Long? {
        val t = s.trim()
        if (t.isBlank()) return null
        return try { dfServer.parse(t)?.time } catch (_: Throwable) { null }
    }

    // ---------- API (kept INSIDE this Activity as you asked) ----------
    private object DoctorNotifApi {

        fun list(token: String, unreadOnly: Boolean): JSONObject {
            val url = BASE_URL +
                    "doctor/notifications_list.php?unread=" + (if (unreadOnly) "1" else "0") +
                    "&limit=200&_ts=" + System.currentTimeMillis()
            return httpGetJson(url, token)
        }

        fun markRead(token: String, id: Long): Boolean {
            val url = BASE_URL + "doctor/notifications_mark_read.php"
            val body = JSONObject().put("notification_id", id)
            val res = httpPostJson(url, token, body)
            return res.optBoolean("ok", false)
        }

        fun dismiss(token: String, id: Long): Boolean {
            val url = BASE_URL + "doctor/notifications_dismiss.php"
            val body = JSONObject().put("notification_id", id)
            val res = httpPostJson(url, token, body)
            return res.optBoolean("ok", false)
        }

        private fun httpGetJson(urlStr: String, token: String): JSONObject {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                runCatching { JSONObject(text) }
                    .getOrElse { JSONObject().put("ok", false).put("error", "Non-JSON response") }
            } catch (_: Throwable) {
                JSONObject().put("ok", false).put("error", "Network error")
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }

        private fun httpPostJson(urlStr: String, token: String, body: JSONObject): JSONObject {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                runCatching { JSONObject(text) }
                    .getOrElse { JSONObject().put("ok", false).put("error", "Non-JSON response") }
            } catch (_: Throwable) {
                JSONObject().put("ok", false).put("error", "Network error")
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }
}
