package com.simats.sehatsethu

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton

object ViewTree {

    fun <T : View> firstOfType(root: View, clazz: Class<T>): T? {
        if (clazz.isInstance(root)) return clazz.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = firstOfType(root.getChildAt(i), clazz)
                if (found != null) return found
            }
        }
        return null
    }

    fun <T : View> allOfType(root: View, clazz: Class<T>): List<T> {
        val out = ArrayList<T>()
        collect(root, clazz, out)
        return out
    }

    private fun <T : View> collect(v: View, clazz: Class<T>, out: MutableList<T>) {
        if (clazz.isInstance(v)) out.add(clazz.cast(v))
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collect(v.getChildAt(i), clazz, out)
        }
    }

    fun findFirstClickableButton(root: View): View {
        // Prefer AppCompatButton then Button
        val ab = firstOfType(root, AppCompatButton::class.java)
        if (ab != null) return ab
        val b = firstOfType(root, Button::class.java)
        if (b != null) return b
        throw IllegalStateException("No Button/AppCompatButton found in layout")
    }
}
