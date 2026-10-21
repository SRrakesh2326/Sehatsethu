package com.simats.sehatsethu

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

object ViewTools {

    fun allEditTexts(root: View): List<EditText> {
        val out = ArrayList<EditText>()
        walk(root) { v -> if (v is EditText) out.add(v) }
        return out
    }

    fun allButtons(root: View): List<Button> {
        val out = ArrayList<Button>()
        walk(root) { v -> if (v is Button) out.add(v) }
        return out
    }

    fun allTextViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        walk(root) { v -> if (v is TextView) out.add(v) }
        return out
    }

    private fun walk(view: View, onEach: (View) -> Unit) {
        onEach(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), onEach)
            }
        }
    }
}
