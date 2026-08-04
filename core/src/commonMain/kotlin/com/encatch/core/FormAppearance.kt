package com.encatch.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Appearance/layout resolution, ported from `form-webview-helpers.ts`. Pure functions operating
 * on the form's `appearanceProperties` JSON — shared by every UI layer (`:android`, `:compose`)
 * so position/corner/animation semantics can't drift between them.
 */

// ============================================================================
// Corner / size / position enums
// ============================================================================

enum class CornerStyle { SHARP, SOFT, ROUND }

enum class InAppSize { COMPACT, STANDARD, SPACIOUS }

/** Matches iframe-manager mobile breakpoint (window width < 600dp). */
const val IN_APP_MOBILE_BREAKPOINT_DP = 600

data class PopupBorderRadii(
    val topLeftDp: Int,
    val topRightDp: Int,
    val bottomLeftDp: Int,
    val bottomRightDp: Int,
)

enum class VerticalAnchor { TOP, CENTER, BOTTOM }
enum class HorizontalAnchor { START, CENTER, END }

/** Platform-neutral alignment — `:android` maps this to `Gravity`, `:compose` to `Alignment`. */
data class PositionAlignment(val vertical: VerticalAnchor, val horizontal: HorizontalAnchor)

data class AnimationConfig(val type: String, val txFractionPercent: Int, val tyFractionPercent: Int)

private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Maps corners preset to dp (24dp = 1.5rem for round), aligned with App.svelte resolveRadius(). */
fun resolveCornerRadiusDp(corners: CornerStyle): Int = when (corners) {
    CornerStyle.SHARP -> 2
    CornerStyle.ROUND -> 24
    CornerStyle.SOFT -> 10
}

/**
 * These 5 functions take the raw [JsonElement] (not a pre-downcast [JsonObject]) and downcast
 * internally, rather than expecting the caller to pass an already-downcast [JsonObject] — a
 * Kotlin/Native-bridged [JsonObject] value crossing the Swift boundary a second time (once as a
 * return value from a helper like the old `asJsonObjectOrNull`, then again as a parameter into
 * one of these) triggered a native crash (EXC_BAD_ACCESS deep in `Map#get`) under real network-
 * sourced data, even though the identical pattern worked fine with directly-constructed test
 * data. Doing the downcast fresh, in the same Kotlin call, from the original [JsonElement]
 * every time sidesteps whatever Kotlin/Native interop edge case that was.
 */

/** Reads appearance.appearance.corners with legacy featureSettings.corners fallback. */
fun resolveCornersFromFormConfig(appearanceProperties: JsonElement?): CornerStyle {
    val obj = appearanceProperties as? JsonObject
    val value = obj?.obj("appearance")?.str("corners")
        ?: obj?.obj("featureSettings")?.str("corners")
    return when (value) {
        "sharp" -> CornerStyle.SHARP
        "round" -> CornerStyle.ROUND
        else -> CornerStyle.SOFT
    }
}

/** Reads inApp.size with legacy featureSettings.inAppSize fallback. */
fun resolveInAppSizeFromFormConfig(appearanceProperties: JsonElement?): InAppSize {
    val obj = appearanceProperties as? JsonObject
    val value = obj?.obj("inApp")?.str("size")
        ?: obj?.obj("featureSettings")?.str("inAppSize")
    return when (value) {
        "compact" -> InAppSize.COMPACT
        "spacious" -> InAppSize.SPACIOUS
        else -> InAppSize.STANDARD
    }
}

/** Reads inApp.position with legacy selectedPosition fallback. */
fun resolveSelectedPositionFromFormConfig(appearanceProperties: JsonElement?): String {
    val obj = appearanceProperties as? JsonObject
    return obj?.obj("inApp")?.str("position")
        ?: obj?.str("selectedPosition")
        ?: "middle-center"
}

/** Reads inApp.darkOverlay with legacy featureSettings.darkOverlay fallback. */
fun resolveDarkOverlayFromFormConfig(appearanceProperties: JsonElement?): Boolean {
    val obj = appearanceProperties as? JsonObject
    return (obj?.obj("inApp")?.bool("darkOverlay")
        ?: obj?.obj("featureSettings")?.bool("darkOverlay")) == true
}

/** Reads inApp.closeButton with legacy featureSettings.closeButton fallback. When enabled, the
 * form shows a close affordance — native hosts additionally treat a tap on the overlay area
 * outside the card as a close. */
fun resolveCloseButtonFromFormConfig(appearanceProperties: JsonElement?): Boolean {
    val obj = appearanceProperties as? JsonObject
    return (obj?.obj("inApp")?.bool("closeButton")
        ?: obj?.obj("featureSettings")?.bool("closeButton")) == true
}

/** Reads featureSettings.maxDialogHeightPercentInApp, defaulting to 0.8 (80%). */
fun resolveMaxDialogHeightFraction(appearanceProperties: JsonElement?): Double {
    val obj = appearanceProperties as? JsonObject
    val raw = (obj?.obj("featureSettings")?.get("maxDialogHeightPercentInApp") as? JsonPrimitive)?.doubleOrNull
    return if (raw != null) raw / 100.0 else 0.8
}

fun isMobileLayout(screenWidthDp: Int): Boolean = screenWidthDp < IN_APP_MOBILE_BREAKPOINT_DP

/** Collapse left/right anchors to center on mobile — matches iframe-manager normalizePosition(). */
fun normalizePosition(position: String, screenWidthDp: Int): String {
    if (position == "full-center" || position == "full") return "full-center"
    if (!isMobileLayout(screenWidthDp)) return position
    if (position.startsWith("top")) return "top-center"
    if (position.startsWith("bottom")) return "bottom-center"
    return "middle-center"
}

fun isCenterAlignedPosition(position: String): Boolean = position.endsWith("-center") || position == "center"

/** Popup shell max-width in dp — aligned with iframe-manager getInAppMaxWidth(). */
fun resolveInAppMaxWidthDp(size: InAppSize, position: String, screenWidthDp: Int, horizontalInsetDp: Int = 0): Int {
    val available = max(screenWidthDp - horizontalInsetDp * 2, 100)
    if (position == "full-center") return available

    val centered = isCenterAlignedPosition(position)
    val presetWidth = if (centered) {
        when (size) {
            InAppSize.COMPACT -> 480
            InAppSize.SPACIOUS -> 720
            InAppSize.STANDARD -> 600
        }
    } else {
        when (size) {
            InAppSize.COMPACT -> 320
            InAppSize.SPACIOUS -> 500
            InAppSize.STANDARD -> 400
        }
    }
    return min(presetWidth, available)
}

fun getPositionLayout(position: String): PositionAlignment {
    var vertical = VerticalAnchor.CENTER
    var horizontal = HorizontalAnchor.CENTER

    if (position.startsWith("top")) vertical = VerticalAnchor.TOP
    else if (position.startsWith("bottom")) vertical = VerticalAnchor.BOTTOM

    if (position.endsWith("left")) horizontal = HorizontalAnchor.START
    else if (position.endsWith("right")) horizontal = HorizontalAnchor.END

    return PositionAlignment(vertical, horizontal)
}

/**
 * Per-corner radii for the modal shell. Edges that touch the screen stay square (0);
 * other corners use the resolved corners preset radius.
 */
fun getBorderRadii(position: String, corners: CornerStyle = CornerStyle.SOFT): PopupBorderRadii {
    if (position == "full-center" || position == "full") {
        return PopupBorderRadii(0, 0, 0, 0)
    }

    val radius = resolveCornerRadiusDp(corners)
    val touchesTop = position.contains("top")
    val touchesBottom = position.contains("bottom")
    val touchesLeft = position.endsWith("left")
    val touchesRight = position.endsWith("right")

    return PopupBorderRadii(
        topLeftDp = if (touchesTop || touchesLeft) 0 else radius,
        topRightDp = if (touchesTop || touchesRight) 0 else radius,
        bottomLeftDp = if (touchesBottom || touchesLeft) 0 else radius,
        bottomRightDp = if (touchesBottom || touchesRight) 0 else radius,
    )
}

/** Uniform radii for inline embeds — matches web-sdk iframe-manager inline innerWrapper. */
fun getInlineBorderRadii(corners: CornerStyle = CornerStyle.SOFT): PopupBorderRadii {
    val radius = resolveCornerRadiusDp(corners)
    return PopupBorderRadii(radius, radius, radius, radius)
}

fun getAnimationConfig(position: String): AnimationConfig {
    if (position.startsWith("top")) return AnimationConfig("slide", 0, -100)
    if (position.startsWith("bottom")) return AnimationConfig("slide", 0, 100)
    if (position.endsWith("left")) return AnimationConfig("slide", -100, 0)
    if (position.endsWith("right")) return AnimationConfig("slide", 100, 0)
    return AnimationConfig("scale", 0, 0)
}

fun dpToPxInt(dp: Int, density: Float): Int = (dp * density).roundToInt()
