package com.simats.sehatsethu

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object UiVisibility {

    fun fixEditText(et: EditText, activity: Activity) {
        // Safe defaults (works on both light/dark even if theme is inconsistent)
        // We avoid touching background to respect your bg drawables.
        et.setTextColor(resolvePrimaryText(activity))
        et.setHintTextColor(resolveHintText(activity))
    }

    fun fixTextView(tv: TextView, activity: Activity, isLink: Boolean = false, isPill: Boolean = false) {
        tv.setTextColor(
            when {
                isLink -> resolveBrand(activity)
                isPill -> resolveBrand(activity)
                else -> resolvePrimaryText(activity)
            }
        )
    }

    fun addBlockingLoader(activity: Activity, root: View, label: String): View {
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
            setBackgroundColor(resolveSurfaceAlt(activity))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }

        val pb = ProgressBar(activity)
        val tv = TextView(activity).apply {
            text = label
            setPadding(0, dp(activity, 10), 0, 0)
            setTextColor(resolvePrimaryText(activity))
            textSize = 14f
        }

        pb.id = R.id.progress
        tv.id = R.id.tv_loading

        card.addView(pb)
        card.addView(tv)
        container.addView(card)

        // attach
        val vg = root as? ViewGroup ?: (activity.findViewById(android.R.id.content) as ViewGroup)
        vg.addView(container)

        return container
    }

    fun setBlockingLoaderText(overlay: View, label: String) {
        val tv = overlay.findViewById<TextView>(R.id.tv_loading)
        tv?.text = label
    }

    private fun resolvePrimaryText(activity: Activity): Int {
        // Use your app colors if they exist, fallback safe.
        return runCatching { activity.getColor(R.color.ss_text_primary) }.getOrElse { Color.parseColor("#0F172A") }
    }

    private fun resolveHintText(activity: Activity): Int {
        return runCatching { activity.getColor(R.color.ss_text_secondary) }.getOrElse { Color.parseColor("#64748B") }
    }

    private fun resolveBrand(activity: Activity): Int {
        return runCatching { activity.getColor(R.color.ss_brand) }.getOrElse { Color.parseColor("#059669") }
    }

    private fun resolveSurfaceAlt(activity: Activity): Int {
        return runCatching { activity.getColor(R.color.ss_surface_alt) }.getOrElse { Color.WHITE }
    }

    private fun dp(activity: Activity, v: Int): Int {
        val d = activity.resources.displayMetrics.density
        return (v * d).toInt()
    }
}
