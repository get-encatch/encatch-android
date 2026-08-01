package com.encatch.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/**
 * Color resolution for form theming, ported from `form-webview-helpers.ts`. The web form's
 * theme JSON stores shadcn CSS variables (hex/rgb/rgba/hsl/hsla, occasionally oklch which we
 * can't render and fall back from) — these functions extract and normalize them, then
 * [parseCssColorToArgb] converts the normalized string into a packed ARGB [Int] (compatible with
 * `android.graphics.Color`'s packing and with `androidx.compose.ui.graphics.Color(Int)`).
 */

private val hexRegex = Regex("^#[0-9A-Fa-f]{3,8}$")
private val rgbFnRegex = Regex("^(rgb|rgba|hsl|hsla)\\(", RegexOption.IGNORE_CASE)
private val rgbaMatchRegex = Regex("""^rgba\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)$""", RegexOption.IGNORE_CASE)
private val rgbMatchRegex = Regex("""^rgb\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$""", RegexOption.IGNORE_CASE)
private val hslaMatchRegex = Regex("""^hsla?\s*\(\s*([\d.]+)\s*,?\s*([\d.]+)%\s*,?\s*([\d.]+)%\s*(?:[,/]\s*([\d.]+))?\s*\)$""", RegexOption.IGNORE_CASE)

const val DEFAULT_OVERLAY_RGBA = "rgba(0, 0, 0, 0.5)"

/** Matches shareable encatch.ts OVERLAY_OPACITY fallback for colors without explicit alpha. */
private const val OVERLAY_FALLBACK_ALPHA = 0.4

fun hexWithAlpha(hex: String, alphaHex: String = "4D"): String {
    var h = hex.removePrefix("#")
    if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
    return when (h.length) {
        6 -> "#$h$alphaHex"
        8 -> "#$h"
        else -> "#000000$alphaHex"
    }
}

/** Only hex and rgb(a)/hsl(a) function colors are safe to hand to a real color renderer. */
private fun isRenderableColor(value: String): Boolean {
    val v = value.trim()
    if (hexRegex.matches(v)) return true
    return rgbFnRegex.find(v)?.range?.first == 0
}

fun normalizeColorForNative(value: String?, fallback: String): String {
    if (value.isNullOrBlank()) return fallback
    val trimmed = value.trim()
    return if (isRenderableColor(trimmed)) trimmed else fallback
}

/**
 * Extracts --background (falling back to --popover) from the shadcn-variables JSON string
 * stored in themes[mode].theme and normalizes it to a renderable color value.
 */
fun getBackgroundColor(themeJson: String?, fallback: String): String {
    if (themeJson.isNullOrEmpty() || themeJson == "{}") return fallback
    return runCatching {
        val vars = parseLenientJsonStringMap(themeJson) ?: return@runCatching fallback
        val value = vars["--background"] ?: vars["--popover"]
        if (value.isNullOrEmpty()) fallback else normalizeColorForNative(value, fallback)
    }.getOrDefault(fallback)
}

/** Resolves "dark"/"light" from the platform's current system appearance flag. */
fun resolveSystemColorScheme(isSystemDark: Boolean): String = if (isSystemDark) "dark" else "light"

/**
 * Reads `appearanceProperties.featureSettings.shareableMode`. Exposed as a standalone function
 * (rather than expecting UI layers to navigate the [JsonObject] themselves) so JSON-shape
 * knowledge stays in `:core` — Kotlin/Native-bridged Swift callers in particular would otherwise
 * need to downcast/navigate `JsonElement`/`JsonObject`/`JsonPrimitive` by hand.
 */
fun extractShareableMode(appearanceProperties: JsonObject?): String? =
    (appearanceProperties?.get("featureSettings") as? JsonObject)?.get("shareableMode")?.let { (it as? JsonPrimitive)?.contentOrNull }

/** Reads `appearanceProperties.themes[mode].theme` — see [extractShareableMode] for why this is a function, not inline JSON navigation. */
fun extractThemeJsonForMode(appearanceProperties: JsonObject?, mode: String): String? =
    (appearanceProperties?.get("themes") as? JsonObject)?.get(mode)?.let { (it as? JsonObject)?.get("theme") }
        ?.let { (it as? JsonPrimitive)?.contentOrNull }

/** Resolves which theme mode ("light" | "dark") is active for the form. */
fun resolveActiveMode(shareableMode: String?, systemScheme: String): String = when (shareableMode) {
    "light" -> "light"
    "dark" -> "dark"
    else -> if (systemScheme == "dark") "dark" else "light"
}

private data class ThemeModeConfig(val theme: String?, val overlayColor: String?)

/** Overlay base color from theme JSON — aligned with shareable encatch.ts getOverlayColorFromTheme(). */
private fun getOverlayColorFromTheme(themeConfig: ThemeModeConfig?): String {
    themeConfig?.overlayColor?.let { return it }
    val themeJson = themeConfig?.theme
    if (themeJson.isNullOrEmpty() || themeJson == "{}") return DEFAULT_OVERLAY_RGBA
    return runCatching {
        val vars = parseLenientJsonStringMap(themeJson) ?: return@runCatching DEFAULT_OVERLAY_RGBA
        vars["overlayColor"] ?: vars["--encatch-overlay-color"] ?: vars["--overlay"] ?: vars["--popover"] ?: DEFAULT_OVERLAY_RGBA
    }.getOrDefault(DEFAULT_OVERLAY_RGBA)
}

/**
 * Returns rgba(...) for the modal backdrop. Preserves explicit alpha in rgba(...)/#RRGGBBAA;
 * otherwise applies [fallbackAlpha] — same rules as shareable encatch.ts withAlpha().
 */
fun colorWithAlpha(color: String, fallbackAlpha: Double = OVERLAY_FALLBACK_ALPHA): String {
    val a = fallbackAlpha.coerceIn(0.0, 1.0)
    val s = color.trim()

    rgbaMatchRegex.find(s)?.let { m ->
        return "rgba(${m.groupValues[1]}, ${m.groupValues[2]}, ${m.groupValues[3]}, ${m.groupValues[4]})"
    }
    rgbMatchRegex.find(s)?.let { m ->
        return "rgba(${m.groupValues[1]}, ${m.groupValues[2]}, ${m.groupValues[3]}, $a)"
    }
    Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""").find(s)?.let { m ->
        var hex = m.groupValues[1]
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val alpha = if (hex.length == 8) hex.substring(6, 8).toInt(16) / 255.0 else a
        return "rgba($r, $g, $b, $alpha)"
    }
    return "rgba(0, 0, 0, $a)"
}

/**
 * Modal backdrop color when darkOverlay is enabled; transparent when disabled. Native UI layers
 * always keep touches captured by the modal shell regardless of overlay visibility.
 */
fun resolveModalOverlayBackgroundColor(
    appearanceProperties: JsonObject?,
    activeMode: String,
    darkOverlay: Boolean,
): String {
    if (!darkOverlay) return "transparent"
    val themes = appearanceProperties?.get("themes")?.let { it as? JsonObject }
    val modeConfig = themes?.get(activeMode)?.let { it as? JsonObject }
    val themeConfig = ThemeModeConfig(
        theme = (modeConfig?.get("theme") as? JsonPrimitive)?.contentOrNull,
        overlayColor = (modeConfig?.get("overlayColor") as? JsonPrimitive)?.contentOrNull,
    )
    val base = getOverlayColorFromTheme(themeConfig)
    return normalizeColorForNative(colorWithAlpha(base), DEFAULT_OVERLAY_RGBA)
}

/** Parses a hex/rgb/rgba/hsl/hsla CSS color string into a packed ARGB [Int]; [fallbackArgb] on failure. */
fun parseCssColorToArgb(color: String, fallbackArgb: Int): Int {
    if (color == "transparent") return 0x00000000
    val s = color.trim()

    if (hexRegex.matches(s)) {
        var hex = s.removePrefix("#")
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        return runCatching {
            when (hex.length) {
                6 -> (0xFF shl 24) or hex.toInt(16)
                8 -> {
                    val rgb = hex.substring(0, 6).toInt(16)
                    val alpha = hex.substring(6, 8).toInt(16)
                    (alpha shl 24) or rgb
                }
                else -> fallbackArgb
            }
        }.getOrDefault(fallbackArgb)
    }

    rgbaMatchRegex.find(s)?.let { m ->
        val r = m.groupValues[1].toInt().coerceIn(0, 255)
        val g = m.groupValues[2].toInt().coerceIn(0, 255)
        val b = m.groupValues[3].toInt().coerceIn(0, 255)
        val a = ((m.groupValues[4].toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0) * 255).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    rgbMatchRegex.find(s)?.let { m ->
        val r = m.groupValues[1].toInt().coerceIn(0, 255)
        val g = m.groupValues[2].toInt().coerceIn(0, 255)
        val b = m.groupValues[3].toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    hslaMatchRegex.find(s)?.let { m ->
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val sat = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val l = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val a = (m.groupValues.getOrNull(4)?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0)
        val (r, g, b) = hslToRgb(h, sat / 100.0, l / 100.0)
        val alpha = (a * 255).roundToInt()
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    return fallbackArgb
}

private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Int, Int, Int> {
    if (s == 0.0) {
        val v = (l * 255).roundToInt()
        return Triple(v, v, v)
    }
    val q = if (l < 0.5) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val hk = (h % 360) / 360.0
    fun hueToRgb(pp: Double, qq: Double, t0: Double): Double {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1.0 / 6 -> pp + (qq - pp) * 6 * t
            t < 1.0 / 2 -> qq
            t < 2.0 / 3 -> pp + (qq - pp) * (2.0 / 3 - t) * 6
            else -> pp
        }
    }
    val r = (hueToRgb(p, q, hk + 1.0 / 3) * 255).roundToInt()
    val g = (hueToRgb(p, q, hk) * 255).roundToInt()
    val b = (hueToRgb(p, q, hk - 1.0 / 3) * 255).roundToInt()
    return Triple(r, g, b)
}

/** Lenient `"--key": "value"` string-map parse of a shadcn CSS-variables JSON blob. Null on parse failure. */
private fun parseLenientJsonStringMap(themeJson: String): Map<String, String>? = runCatching {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(themeJson)
    val obj = element as? JsonObject ?: return@runCatching null
    obj.entries.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
    }.toMap()
}.getOrNull()
