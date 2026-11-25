package com.encatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locale and country share one saved entry: no save may overwrite another's value, whatever the
 * order or the thread (setLocale, setCountry and identifyUser's locale/country).
 */
class PreferencesSaveTest {

    private lateinit var api: MockApi

    @BeforeTest
    fun setUp() {
        api = MockApi()
        api.install()
    }

    @AfterTest
    fun tearDown() = runTest { api.uninstall() }

    private fun saved() = EncatchStorage(api.settings).getPreferences()

    @Test
    fun set_locale_then_identify_with_a_country_keeps_both() = runTest {
        Encatch.init("test-key")
        Encatch.setLocale("fr")
        Encatch.identifyUser("alice", options = IdentifyOptions(country = "FR"))
        eventually { api.called("identify-user").isNotEmpty() }
        assertEquals(EncatchStorage.Preferences(locale = "fr", country = "FR"), saved())
    }

    @Test
    fun set_locale_and_country_are_saved_by_the_time_they_return() = runTest {
        Encatch.init("test-key")
        repeat(50) { i ->
            Encatch.setLocale("l$i")
            assertEquals("l$i", saved().locale, "setLocale round $i")
            Encatch.setCountry("c$i")
            assertEquals("c$i", saved().country, "setCountry round $i")
        }
    }

    @Test
    fun the_last_of_quick_set_locale_calls_is_saved() = runTest {
        Encatch.init("test-key")
        Encatch.setLocale("fr")
        Encatch.setLocale("de")
        Encatch.setCountry("FR")
        Encatch.setCountry("DE")
        assertEquals(EncatchStorage.Preferences(locale = "de", country = "DE"), saved())
    }

    @Test
    fun concurrent_saves_never_lose_a_value() = runTest {
        val storage = EncatchStorage(api.settings)
        withContext(Dispatchers.Default) {
            repeat(300) { round ->
                storage.clearPreferences()
                listOf(
                    async { storage.setPreferences(locale = "l$round") },
                    async { storage.setPreferences(country = "c$round") },
                ).awaitAll()
                assertEquals(EncatchStorage.Preferences(locale = "l$round", country = "c$round"), storage.getPreferences(), "round $round")
            }
        }
    }

    @Test
    fun set_locale_and_country_before_init_do_not_throw() {
        Encatch.setLocale("fr")
        Encatch.setCountry("FR")
    }
}
