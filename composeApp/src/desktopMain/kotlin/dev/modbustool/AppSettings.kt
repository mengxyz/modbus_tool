package dev.modbustool

import java.util.Locale
import java.util.prefs.Preferences

enum class AppLanguage(val languageTag: String, val displayName: String) {
    ENGLISH("en", "English"),
    THAI("th", "ไทย"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.languageTag == tag } ?: ENGLISH
    }
}

internal class AppSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node("dev/modbustool/settings"),
) {
    fun loadLanguage(): AppLanguage = AppLanguage.fromTag(preferences.get(LANGUAGE_KEY, null))

    fun saveLanguage(language: AppLanguage) {
        preferences.put(LANGUAGE_KEY, language.languageTag)
        runCatching { preferences.flush() }
    }

    companion object {
        private const val LANGUAGE_KEY = "language"
    }
}

internal fun applyAppLanguage(language: AppLanguage) {
    Locale.setDefault(Locale.forLanguageTag(language.languageTag))
}
