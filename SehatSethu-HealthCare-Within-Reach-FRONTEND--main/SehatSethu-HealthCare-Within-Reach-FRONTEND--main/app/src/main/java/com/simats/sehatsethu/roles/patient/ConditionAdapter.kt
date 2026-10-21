package com.simats.sehatsethu.roles.patient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.simats.sehatsethu.R

class ConditionAdapter(
    private val items: List<Row>
) : RecyclerView.Adapter<ConditionAdapter.VH>() {

    data class Row(
        val name: String,
        val pct: Int,      // 0..100
        val note: String
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_condition_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<TextView>(R.id.tvName)
        private val tvMatch = itemView.findViewById<TextView>(R.id.tvMatch)
        private val tvDesc = itemView.findViewById<TextView>(R.id.tvDesc)
        private val prog = itemView.findViewById<LinearProgressIndicator>(R.id.prog)

        fun bind(r: Row) {
            tvName.text = r.name
            tvDesc.text = r.note

            val pct = r.pct.coerceIn(0, 100)
            tvMatch.text = "$pct% match"

            prog.max = 100
            prog.progress = pct
        }
    }
}
