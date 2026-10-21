package com.simats.sehatsethu.roles.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simats.sehatsethu.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class AdminUsersAdapter(
    private val onClick: (AdminUserRow) -> Unit
) : RecyclerView.Adapter<AdminUsersAdapter.VH>() {

    private val items = ArrayList<AdminUserRow>()

    fun submit(list: List<AdminUserRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_admin_user, p, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val u = items[pos]
        val ctx = h.itemView.context

        h.tvName.text = u.fullName

        //  Subtitle like images (specialization or village)
        h.tvSubtitle.text = u.subtitle?.takeIf { it.isNotBlank() } ?: "—"

        //  Role pill
        val role = u.role.trim().uppercase(Locale.US)
        if (role == "PHARMACIST") {
            h.tvRolePill.text = ctx.getString(R.string.pharmacists).dropLast(1) // "Pharmacist"
            h.tvRolePill.setBackgroundResource(R.drawable.bg_role_pill_purple)
            h.ivAvatar.setImageResource(R.drawable.ic_pill) // use your pill icon
            h.avatarBox.setBackgroundResource(R.drawable.bg_avatar_purple)
        } else {
            h.tvRolePill.text = ctx.getString(R.string.doctors).dropLast(1) // "Doctor"
            h.tvRolePill.setBackgroundResource(R.drawable.bg_role_pill_green)
            h.ivAvatar.setImageResource(R.drawable.ic_doctor_face)
            h.avatarBox.setBackgroundResource(R.drawable.bg_avatar_mint)
        }

        //  time ago (from appliedAt)
        h.tvTime.text = timeAgo(ctx, u.appliedAt.orEmpty())

        //  docs count (NO status shown here)
        h.tvDocs.text = ctx.getString(R.string.docs_count, u.docsCount)

        h.itemView.setOnClickListener { onClick(u) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatarBox: View = v.findViewById(R.id.avatarBox)
        val ivAvatar: ImageView = v.findViewById(R.id.ivAvatar)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvRolePill: TextView = v.findViewById(R.id.tvRolePill)
        val tvSubtitle: TextView = v.findViewById(R.id.tvSubtitle)
        val tvTime: TextView = v.findViewById(R.id.tvTimeAgo)
        val tvDocs: TextView = v.findViewById(R.id.tvDocs)
    }

    private fun timeAgo(ctx: android.content.Context, db: String): String {
        // expects "yyyy-MM-dd HH:mm:ss"
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            val d = fmt.parse(db) ?: return ctx.getString(R.string.time_just_now)
            val now = System.currentTimeMillis()
            val diff = abs(now - d.time)

            val min = diff / (60_000)
            val hr = diff / (3_600_000)
            val day = diff / (86_400_000)

            when {
                min < 1 -> ctx.getString(R.string.time_just_now)
                hr < 1 -> ctx.getString(R.string.time_minutes_ago, min.toInt())
                day < 1 -> ctx.getString(R.string.time_hours_ago, hr.toInt())
                else -> ctx.getString(R.string.time_days_ago, day.toInt())
            }
        } catch (_: Throwable) {
            ctx.getString(R.string.time_just_now)
        }
    }
}
