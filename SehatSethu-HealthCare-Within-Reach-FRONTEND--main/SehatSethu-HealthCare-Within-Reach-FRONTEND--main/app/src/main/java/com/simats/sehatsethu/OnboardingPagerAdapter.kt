package com.simats.sehatsethu

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class OnboardingPagerAdapter(
    private val activity: OnboardingActivity
) : RecyclerView.Adapter<OnboardingPagerAdapter.VH>() {

    private val layouts = intArrayOf(
        R.layout.item_onboarding_page_1,
        R.layout.item_onboarding_page_2,
        R.layout.item_onboarding_page_3
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return VH(view as ViewGroup)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Nothing required because texts are already from @string (auto language)
    }

    override fun getItemCount(): Int = layouts.size

    override fun getItemViewType(position: Int): Int = layouts[position]

    class VH(val root: ViewGroup) : RecyclerView.ViewHolder(root)
}
