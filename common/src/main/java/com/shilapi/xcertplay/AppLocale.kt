// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Platform app locales on Android 13+, with a persisted context override on older Android. */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    const val TRADITIONAL_CHINESE = "zh-TW"
    const val ARABIC = "ar"
    const val RUSSIAN = "ru"
    const val SPANISH = "es"
    const val UKRAINIAN = "uk"

    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ARABIC, RUSSIAN, SPANISH, UKRAINIAN)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    private const val KEY_MIGRATED = "app_language_platform_migrated"

    fun preference(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            val selected = locales[0]
            if (selected.language != "zh") return selected.language
            // An explicit BCP 47 script is more specific than the region's usual script.
            return when (selected.script) {
                "Hant" -> TRADITIONAL_CHINESE
                "Hans" -> SIMPLIFIED_CHINESE
                else -> if (selected.country in setOf("TW", "HK", "MO")) {
                    TRADITIONAL_CHINESE
                } else {
                    SIMPLIFIED_CHINESE
                }
            }
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in ALL } ?: SYSTEM
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                locale(language)?.let { LocaleList(it) } ?: LocaleList.getEmptyLocaleList()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /** On Android 13+, the OS is the single source of truth for the app language. */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_MIGRATED, false)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                val previous = locale(prefs.getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM)
                // Never overwrite a language already chosen through Android Settings.
                if (manager.applicationLocales.isEmpty && previous != null) {
                    manager.applicationLocales = LocaleList(previous)
                }
                prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
            }
            return context
        }
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * Before Android 13, some head units run apps in a compatibility mode that puts the system language
     * back into the activity's resources after [wrap]. Writes the chosen language again; returns true
     * if it had to. System default writes the system language back, because the process keeps the
     * resources that an earlier choice changed.
     */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val locale = locale(preference(context)) ?: Resources.getSystem().configuration.locales[0]
        val resources = context.resources
        if (resources.configuration.locales[0] == locale) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        activity.appDialogBuilder()
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    // LocaleManager recreates activities itself on Android 13+.
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Names stay in their native form for every language; only "system default" is localized. */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        TRADITIONAL_CHINESE -> "繁體中文"
        ARABIC -> "العربية"
        RUSSIAN -> "Русский"
        SPANISH -> "Español"
        UKRAINIAN -> "Українська"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
        ARABIC -> Locale("ar")
        RUSSIAN -> Locale("ru")
        SPANISH -> Locale("es")
        UKRAINIAN -> Locale("uk")
        else -> null
    }
}
