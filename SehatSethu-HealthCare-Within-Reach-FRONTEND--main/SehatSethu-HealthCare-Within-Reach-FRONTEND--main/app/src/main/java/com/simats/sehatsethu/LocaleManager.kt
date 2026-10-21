package com.simats.sehatsethu

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    fun wrap(base: Context): Context {
        val lang = AppPrefs.getLang(base) ?: return base
        return setLocale(base, lang)
    }

    private fun setLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
