package com.encatch.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FormThemeColorTest {

    @Test
    fun hexWithAlpha_appendsOrReplacesAlphaChannel() {
        assertEquals("#ff0000" + "4D", hexWithAlpha("#ff0000"))
        assertEquals("#ff000080", hexWithAlpha("#ff0000", "80"))
        assertEquals("#ffffff4D", hexWithAlpha("#fff"))
        assertEquals("#aabbccdd", hexWithAlpha("#aabbccdd", "80")) // 8-digit hex already has alpha, kept as-is
    }

    @Test
    fun normalizeColorForNative_acceptsHexAndRgbFunctions_rejectsUnsupportedFormats() {
        assertEquals("#ff0000", normalizeColorForNative("#ff0000", "#000000"))
        assertEquals("rgb(255, 0, 0)", normalizeColorForNative("rgb(255, 0, 0)", "#000000"))
        assertEquals("rgba(255, 0, 0, 0.5)", normalizeColorForNative("rgba(255, 0, 0, 0.5)", "#000000"))
        // oklch(...) (or any unrecognized function) isn't renderable -> falls back
        assertEquals("#000000", normalizeColorForNative("oklch(0.5 0.2 30)", "#000000"))
        assertEquals("#000000", normalizeColorForNative(null, "#000000"))
        assertEquals("#000000", normalizeColorForNative("", "#000000"))
    }

    @Test
    fun getBackgroundColor_extractsBackgroundThenPopoverVariable() {
        assertEquals("#ffffff", getBackgroundColor("""{"--background":"#ffffff"}""", "#000000"))
        assertEquals("#eeeeee", getBackgroundColor("""{"--popover":"#eeeeee"}""", "#000000"))
        assertEquals("#000000", getBackgroundColor("{}", "#000000"))
        assertEquals("#000000", getBackgroundColor(null, "#000000"))
        assertEquals("#000000", getBackgroundColor("not json", "#000000"))
    }

    @Test
    fun getBackgroundColor_fallsBackWhenStoredColorIsUnrenderable() {
        // --background stored as an oklch() token (shadcn default) -> can't render, use fallback.
        assertEquals("#000000", getBackgroundColor("""{"--background":"oklch(1 0 0)"}""", "#000000"))
    }

    @Test
    fun resolveSystemColorScheme_mapsIsSystemDarkToModeString() {
        assertEquals("dark", resolveSystemColorScheme(isSystemDark = true))
        assertEquals("light", resolveSystemColorScheme(isSystemDark = false))
    }

    @Test
    fun resolveActiveMode_prefersExplicitShareableModeOverSystemScheme() {
        assertEquals("light", resolveActiveMode("light", systemScheme = "dark"))
        assertEquals("dark", resolveActiveMode("dark", systemScheme = "light"))
        assertEquals("dark", resolveActiveMode(null, systemScheme = "dark"))
        assertEquals("light", resolveActiveMode(null, systemScheme = "light"))
    }

    @Test
    fun colorWithAlpha_preservesExplicitAlpha_appliesFallbackOtherwise() {
        assertEquals("rgba(1, 2, 3, 0.9)", colorWithAlpha("rgba(1, 2, 3, 0.9)"))
        assertEquals("rgba(1, 2, 3, 0.4)", colorWithAlpha("rgb(1, 2, 3)", fallbackAlpha = 0.4))
        assertEquals("rgba(0, 0, 0, 0.4)", colorWithAlpha("not-a-color", fallbackAlpha = 0.4))
    }

    @Test
    fun colorWithAlpha_hex8PreservesEmbeddedAlpha() {
        val result = colorWithAlpha("#ff000080", fallbackAlpha = 0.4)
        assertEquals("rgba(255, 0, 0, ${128 / 255.0})", result)
    }

    @Test
    fun resolveModalOverlayBackgroundColor_isTransparentWhenDarkOverlayDisabled() {
        assertEquals("transparent", resolveModalOverlayBackgroundColor(null, "light", darkOverlay = false))
    }

    @Test
    fun resolveModalOverlayBackgroundColor_defaultsWhenDarkOverlayEnabledButNoThemeConfig() {
        val result = resolveModalOverlayBackgroundColor(null, "light", darkOverlay = true)
        assertEquals(DEFAULT_OVERLAY_RGBA, result)
    }

    @Test
    fun parseCssColorToArgb_hex6IsFullyOpaque() {
        assertEquals(0xFFFF0000.toInt(), parseCssColorToArgb("#ff0000", 0))
    }

    @Test
    fun parseCssColorToArgb_hex8HonorsAlphaChannel() {
        assertEquals(0x80FF0000.toInt(), parseCssColorToArgb("#ff000080", 0))
    }

    @Test
    fun parseCssColorToArgb_rgbaHonorsAlpha() {
        val argb = parseCssColorToArgb("rgba(255, 0, 0, 0.5)", 0)
        assertEquals(0xFF, (argb ushr 16) and 0xFF) // red
        assertEquals(0x00, (argb ushr 8) and 0xFF) // green
        val alpha = (argb ushr 24) and 0xFF
        assertEquals(128, alpha) // round(0.5 * 255)
    }

    @Test
    fun parseCssColorToArgb_transparentIsZero() {
        assertEquals(0, parseCssColorToArgb("transparent", 0xFFFFFFFF.toInt()))
    }

    @Test
    fun parseCssColorToArgb_unparseableFallsBackToProvidedDefault() {
        assertEquals(0x11223344, parseCssColorToArgb("not-a-color", 0x11223344))
    }

    @Test
    fun parseCssColorToArgb_hslConvertsToRgb() {
        // hsl(0, 100%, 50%) is pure red.
        val argb = parseCssColorToArgb("hsl(0, 100%, 50%)", 0)
        assertEquals(0xFF, (argb ushr 16) and 0xFF)
        assertEquals(0x00, (argb ushr 8) and 0xFF)
        assertEquals(0x00, argb and 0xFF)
    }
}
