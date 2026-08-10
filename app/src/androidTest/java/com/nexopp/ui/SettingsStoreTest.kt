package com.nexopp.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device test of SettingsStore persistence: writes AppSettings to SharedPreferences and reads
 * them back, verifying that dynamicColor and other fields survive the round-trip.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    @Test
    fun dynamicColorPersistsAcrossLoadSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)

        val original = AppSettings(dynamicColor = true)
        store.save(original)

        val loaded = store.load()
        assertTrue("dynamicColor should be true after load", loaded.dynamicColor)
    }

    @Test
    fun dynamicColorFalsePersistsAcrossLoadSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)

        val original = AppSettings(dynamicColor = false)
        store.save(original)

        val loaded = store.load()
        assertEquals(false, loaded.dynamicColor)
    }

    @Test
    fun allAppearanceSettingsPersistTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)

        val original = AppSettings(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            pageCounterVertical = PageCounterVertical.TOP,
            pageCounterHorizontal = PageCounterHorizontal.LEFT,
        )
        store.save(original)

        val loaded = store.load()
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertEquals(false, loaded.dynamicColor)
        assertEquals(PageCounterVertical.TOP, loaded.pageCounterVertical)
        assertEquals(PageCounterHorizontal.LEFT, loaded.pageCounterHorizontal)
    }
}
