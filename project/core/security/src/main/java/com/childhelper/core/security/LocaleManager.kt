package com.childhelper.core.security

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Manages in-app language switching independent of the device's system locale.
 * Calls [applyLocale] from Activity.attachBaseContext before any UI inflation.
 *
 * Supported languages: English (en), Bulgarian (bg)
 */
object LocaleManager {

    const val PREF_KEY_LANGUAGE = "app_language"
    const val LANG_ENGLISH = "en"
    const val LANG_BULGARIAN = "bg"

    @Volatile
    var selectedLanguageCache: String? = null
        private set

    fun cacheLanguage(languageCode: String?) {
        selectedLanguageCache = languageCode
    }

    fun applyLocale(context: Context): Context {
        val langCode = selectedLanguageCache ?: return context
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        return context.createConfigurationContext(config)
    }

    fun isLanguageSelected(languageCode: String): Boolean {
        return selectedLanguageCache == languageCode
    }
}
