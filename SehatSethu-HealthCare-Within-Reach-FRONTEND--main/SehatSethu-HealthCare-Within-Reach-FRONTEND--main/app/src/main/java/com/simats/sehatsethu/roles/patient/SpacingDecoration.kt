package com.simats.sehatsethu.roles.patient

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SpacingDecoration(
    private val spacePx: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return

        val span = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 2
        val col = pos % span

        if (includeEdge) {
            outRect.left = spacePx - col * spacePx / span
            outRect.right = (col + 1) * spacePx / span
            if (pos < span) outRect.top = spacePx
            outRect.bottom = spacePx
        } else {
            outRect.left = col * spacePx / span
            outRect.right = spacePx - (col + 1) * spacePx / span
            if (pos >= span) outRect.top = spacePx
        }
    }
}
