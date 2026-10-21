package com.simats.sehatsethu.roles.patient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.BaseActivity
import com.simats.sehatsethu.R
import com.simats.sehatsethu.notifications.NotificationsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PatientNotificationsActivity : BaseActivity() {

    private val items = ArrayList<JSONObject>()

    private lateinit var rv: RecyclerView
    private lateinit var emptyWrap: View

    private lateinit var btnAll: LinearLayout
    private lateinit var btnUnread: LinearLayout
    private lateinit var tvAll: TextView
    private lateinit var tvUnread: TextView

    private var unreadOnly: Boolean = true

    private lateinit var adapter: NotificationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_notifications)
        supportActionBar?.hide()

        findViewById<View>(R.id.ivBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        rv = findViewById(R.id.rvNotifs)
        emptyWrap = findViewById(R.id.emptyWrap)

        btnAll = findViewById(R.id.btnAll)
        btnUnread = findViewById(R.id.btnUnread)
        tvAll = findViewById(R.id.tvAll)
        tvUnread = findViewById(R.id.tvUnread)

        rv.layoutManager = LinearLayoutManager(this)

        adapter = NotificationsAdapter(
            items,
            onRowClick = { o -> onOpen(o, markReadIfNeeded = true) },
            onActionClick = { o -> onOpen(o, markReadIfNeeded = true) },
            onDismissClick = { o -> onDismiss(o) }
        )
        rv.adapter = adapter

        btnAll.setOnClickListener {
            if (unreadOnly) {
                unreadOnly = false
                applyTabUi()
                load()
            }
        }

        btnUnread.setOnClickListener {
            if (!unreadOnly) {
                unreadOnly = true
                applyTabUi()
                load()
            }
        }

        applyTabUi()
        load()
    }

    private fun applyTabUi() {
        // Your XML uses bg_tab_selected/bg_tab_unselected and colors in TextViews
        if (unreadOnly) {
            btnUnread.setBackgroundResource(R.drawable.bg_tab_selected)
            btnAll.setBackgroundResource(R.drawable.bg_tab_unselected)
            tvUnread.setTextColor(getColorCompat(R.color.tab_selected_text))
            tvAll.setTextColor(getColorCompat(R.color.tab_unselected_text))
        } else {
            btnAll.setBackgroundResource(R.drawable.bg_tab_selected)
            btnUnread.setBackgroundResource(R.drawable.bg_tab_unselected)
            tvAll.setTextColor(getColorCompat(R.color.tab_selected_text))
            tvUnread.setTextColor(getColorCompat(R.color.tab_unselected_text))
        }
    }

    private fun getColorCompat(id: Int): Int {
        return try { resources.getColor(id, theme) } catch (_: Throwable) { resources.getColor(id) }
    }

    private fun load() {
        lifecycleScope.launch {
            val arr = withContext(Dispatchers.IO) {
                PatientApi.listNotifications(this@PatientNotificationsActivity, unreadOnly = unreadOnly)
            }

            if (arr == null) {
                Toast.makeText(
                    this@PatientNotificationsActivity,
                    PatientApi.lastError ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
                items.clear()
                adapter.notifyDataSetChanged()
                updateEmpty()
                return@launch
            }

            val newItems = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { newItems.add(it) }

            items.clear()
            items.addAll(newItems)
            adapter.notifyDataSetChanged()
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        val empty = items.isEmpty()
        emptyWrap.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }

    private fun onDismiss(o: JSONObject) {
        val id = readNotifId(o)
        if (id <= 0L) return

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                PatientApi.dismissNotification(this@PatientNotificationsActivity, id)
            }
            if (ok) {
                adapter.removeById(id)
                updateEmpty()
            } else {
                Toast.makeText(
                    this@PatientNotificationsActivity,
                    PatientApi.lastError ?: getString(R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onOpen(o: JSONObject, markReadIfNeeded: Boolean) {
        val id = readNotifId(o)
        val isRead = o.optInt("is_read", 0) == 1

        if (markReadIfNeeded && id > 0L && !isRead) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    PatientApi.markNotificationRead(this@PatientNotificationsActivity, id)
                }
                if (ok) {
                    adapter.markReadById(id)
                    if (unreadOnly) {
                        // remove from list in Unread tab after marking read
                        adapter.removeById(id)
                        updateEmpty()
                    }
                }
            }
        }

        // Optional deep link: appointment details
        openDeepLinkIfAny(o)
    }

    private fun openDeepLinkIfAny(o: JSONObject) {
        val data = o.optJSONObject("data_json")
            ?: runCatching { JSONObject(o.optString("data_json", "")) }.getOrNull()

        val apptId = data?.optString("appointment_id", "").orEmpty()
            .ifBlank { data?.optString("appointmentId", "").orEmpty() }
            .ifBlank { data?.optString("id", "").orEmpty() }

        val code = data?.optString("public_code", "").orEmpty()
            .ifBlank { data?.optString("publicCode", "").orEmpty() }

        if (apptId.isBlank() && code.isBlank()) return

        runCatching {
            val itn = Intent(this, PatientAppointmentDetailsActivity::class.java).apply {
                if (apptId.isNotBlank()) {
                    putExtra("appointmentId", apptId)
                    putExtra("appointment_id", apptId)
                    putExtra("id", apptId)
                }
                if (code.isNotBlank()) {
                    putExtra("public_code", code)
                    putExtra("publicCode", code)
                }
            }
            startActivity(itn)
        }
    }

    private fun readNotifId(o: JSONObject): Long {
        val a = o.optLong("id", 0L)
        if (a > 0L) return a
        val b = o.optLong("notification_id", 0L)
        if (b > 0L) return b
        val s = o.optString("id", "").trim().toLongOrNull()
        if (s != null && s > 0L) return s
        val s2 = o.optString("notification_id", "").trim().toLongOrNull()
        if (s2 != null && s2 > 0L) return s2
        return 0L
    }
}
