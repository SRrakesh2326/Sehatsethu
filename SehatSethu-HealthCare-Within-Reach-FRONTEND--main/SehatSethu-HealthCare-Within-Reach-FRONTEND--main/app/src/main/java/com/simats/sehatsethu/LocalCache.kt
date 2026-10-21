package com.simats.sehatsethu

import android.content.Context

object LocalCache {
    private const val PREF = "local_cache_v1"

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun putString(c: Context, key: String, value: String?) {
        p(c).edit().putString(key, value).apply()
    }

    fun getString(c: Context, key: String): String? = p(c).getString(key, null)

    fun putInt(c: Context, key: String, value: Int) {
        p(c).edit().putInt(key, value).apply()
    }

    fun getInt(c: Context, key: String, def: Int = 0): Int = p(c).getInt(key, def)

    fun putLong(c: Context, key: String, value: Long) {
        p(c).edit().putLong(key, value).apply()
    }

    fun getLong(c: Context, key: String, def: Long = 0L): Long = p(c).getLong(key, def)

    fun remove(c: Context, key: String) {
        p(c).edit().remove(key).apply()
    }

    fun key(prefix: String, id: Long): String = "${prefix}_${id}"
}
