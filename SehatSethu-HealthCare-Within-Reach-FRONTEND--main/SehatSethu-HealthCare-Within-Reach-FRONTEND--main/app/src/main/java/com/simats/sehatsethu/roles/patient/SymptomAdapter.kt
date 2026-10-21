package com.simats.sehatsethu.roles.patient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.simats.sehatsethu.R

class SymptomAdapter(
    private val items: List<Item>,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<SymptomAdapter.VH>() {

    data class Item(val key: String, val emoji: String, val labelRes: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_symptom_chip, parent, false)
        return VH(v, isSelected, onToggle)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val card = itemView.findViewById<MaterialCardView>(R.id.cardSymptom)
        private val tvEmoji = itemView.findViewById<TextView>(R.id.tvEmoji)
        private val tvLabel = itemView.findViewById<TextView>(R.id.tvLabel)

        fun bind(item: Item) {
            val selected = isSelected(item.key)

            tvEmoji.text = item.emoji
            tvLabel.setText(item.labelRes)

            if (selected) {
                card.setCardBackgroundColor(0xFFECFDF5.toInt())
                card.strokeColor = 0xFF10B981.toInt()
                tvLabel.setTextColor(0xFF065F46.toInt())
            } else {
                card.setCardBackgroundColor(0xFFFFFFFF.toInt())
                card.strokeColor = 0xFFE5EAF0.toInt()
                tvLabel.setTextColor(0xFF0F172A.toInt())
            }

            card.setOnClickListener { onToggle(item.key) }
        }
    }
}
