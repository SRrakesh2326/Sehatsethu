package com.simats.sehatsethu.notifications

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NotificationsAdapter(
    private val items: MutableList<JSONObject>,
    private val timeText: (JSONObject) -> String = { o -> defaultTimeText(o) },
    private val onRowClick: (JSONObject) -> Unit = {},
    private val onActionClick: (JSONObject) -> Unit = {},
    private val onDismissClick: (JSONObject) -> Unit = {}
) : RecyclerView.Adapter<NotificationsAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification_row, parent, false)
        return VH(v, timeText, onRowClick, onActionClick, onDismissClick)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    fun replaceAll(newItems: List<JSONObject>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeById(id: Long): Int {
        val idx = items.indexOfFirst { readId(it) == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
        return idx
    }

    fun markReadById(id: Long) {
        val idx = items.indexOfFirst { readId(it) == id }
        if (idx >= 0) {
            items[idx].put("is_read", 1)
            notifyItemChanged(idx)
        }
    }

    class VH(
        v: View,
        private val timeText: (JSONObject) -> String,
        private val onRowClick: (JSONObject) -> Unit,
        private val onActionClick: (JSONObject) -> Unit,
        private val onDismissClick: (JSONObject) -> Unit
    ) : RecyclerView.ViewHolder(v) {

        private val icon = v.findViewById<ImageView>(R.id.ivIcon)
        private val dismiss = v.findViewById<ImageView>(R.id.ivDismiss)
        private val tvTitle = v.findViewById<TextView>(R.id.tvNTitle)
        private val tvBody = v.findViewById<TextView>(R.id.tvNBody)
        private val tvTime = v.findViewById<TextView>(R.id.tvNTime)
        private val tvAction = v.findViewById<TextView>(R.id.tvAction)
        private val dot = v.findViewById<View>(R.id.vUnreadDot)

        fun bind(o: JSONObject) {
            tvTitle.text = o.optString("title", "")
            tvBody.text = o.optString("body", "")
            tvTime.text = timeText(o)

            val isRead = o.optInt("is_read", 0) == 1
            dot.visibility = if (isRead) View.INVISIBLE else View.VISIBLE

            val actionText = o.optString("action_text", "").trim()
            if (actionText.isNotEmpty()) {
                tvAction.visibility = View.VISIBLE
                tvAction.text = actionText
            } else {
                tvAction.visibility = View.VISIBLE
                tvAction.text = itemView.context.getString(R.string.view)
            }

            // Optional icon type mapping
            val type = o.optString("type", "").uppercase(Locale.US)
            when (type) {
                "APPOINTMENT", "UPCOMING_CONSULTATION" -> icon.setImageResource(R.drawable.ic_notif_calendar)
                "PRESCRIPTION" -> icon.setImageResource(R.drawable.ic_notif_pill)
                "MEDICINE_REMINDER" -> icon.setImageResource(R.drawable.ic_notif_clock)
                else -> icon.setImageResource(R.drawable.ic_notif_bell)
            }

            itemView.setOnClickListener { onRowClick(o) }
            tvAction.setOnClickListener { onActionClick(o) }
            dismiss.setOnClickListener { onDismissClick(o) }
        }
    }

    companion object {
        private fun readId(o: JSONObject): Long {
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

        private fun defaultTimeText(o: JSONObject): String {
            val x = o.optString("time_ago", "").trim()
            if (x.isNotEmpty()) return x

            val raw = o.optString("created_at", "").trim()
            if (raw.isEmpty()) return ""

            // parse common: "yyyy-MM-dd HH:mm:ss"
            return runCatching {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val dt = sdf.parse(raw) ?: return raw
                // show original if parsing works but you want simple
                raw
            }.getOrElse { raw }
        }
    }
}
