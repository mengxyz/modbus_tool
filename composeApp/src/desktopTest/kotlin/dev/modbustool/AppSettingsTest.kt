package dev.modbustool

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun defaultsUnknownOrMissingLanguageToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("unknown"))
    }

    @Test
    fun restoresSupportedLanguage() {
        assertEquals(AppLanguage.THAI, AppLanguage.fromTag("th"))
    }
}
