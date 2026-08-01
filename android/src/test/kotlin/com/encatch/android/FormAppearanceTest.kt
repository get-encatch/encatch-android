package com.encatch.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FormAppearanceTest {

    private fun appearance(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun resolveCornersFromFormConfig_readsAppearanceThenFeatureSettingsThenDefaultsSoft() {
        assertEquals(CornerStyle.SHARP, resolveCornersFromFormConfig(appearance("""{"appearance":{"corners":"sharp"}}""")))
        assertEquals(CornerStyle.ROUND, resolveCornersFromFormConfig(appearance("""{"featureSettings":{"corners":"round"}}""")))
        assertEquals(CornerStyle.SOFT, resolveCornersFromFormConfig(appearance("""{}""")))
        assertEquals(CornerStyle.SOFT, resolveCornersFromFormConfig(null))
    }

    @Test
    fun resolveInAppSizeFromFormConfig_readsInAppThenFeatureSettingsThenDefaultsStandard() {
        assertEquals(InAppSize.COMPACT, resolveInAppSizeFromFormConfig(appearance("""{"inApp":{"size":"compact"}}""")))
        assertEquals(InAppSize.SPACIOUS, resolveInAppSizeFromFormConfig(appearance("""{"featureSettings":{"inAppSize":"spacious"}}""")))
        assertEquals(InAppSize.STANDARD, resolveInAppSizeFromFormConfig(appearance("""{}""")))
    }

    @Test
    fun resolveSelectedPositionFromFormConfig_readsInAppThenLegacyThenDefaultsMiddleCenter() {
        assertEquals("top-left", resolveSelectedPositionFromFormConfig(appearance("""{"inApp":{"position":"top-left"}}""")))
        assertEquals("bottom-right", resolveSelectedPositionFromFormConfig(appearance("""{"selectedPosition":"bottom-right"}""")))
        assertEquals("middle-center", resolveSelectedPositionFromFormConfig(appearance("""{}""")))
    }

    @Test
    fun resolveDarkOverlayFromFormConfig_readsInAppThenFeatureSettings() {
        assertTrue(resolveDarkOverlayFromFormConfig(appearance("""{"inApp":{"darkOverlay":true}}""")))
        assertTrue(resolveDarkOverlayFromFormConfig(appearance("""{"featureSettings":{"darkOverlay":true}}""")))
        assertEquals(false, resolveDarkOverlayFromFormConfig(appearance("""{}""")))
    }

    @Test
    fun resolveMaxDialogHeightFraction_convertsPercentOrDefaults80Percent() {
        assertEquals(0.6, resolveMaxDialogHeightFraction(appearance("""{"featureSettings":{"maxDialogHeightPercentInApp":60}}""")))
        assertEquals(0.8, resolveMaxDialogHeightFraction(appearance("""{}""")))
    }

    @Test
    fun normalizePosition_collapsesLeftRightToCenterOnMobile() {
        assertEquals("top-center", normalizePosition("top-left", screenWidthDp = 400))
        assertEquals("bottom-center", normalizePosition("bottom-right", screenWidthDp = 400))
        assertEquals("middle-center", normalizePosition("middle-left", screenWidthDp = 400))
        assertEquals("top-left", normalizePosition("top-left", screenWidthDp = 800))
    }

    @Test
    fun normalizePosition_fullCollapsesToFullCenterRegardlessOfWidth() {
        assertEquals("full-center", normalizePosition("full", screenWidthDp = 800))
        assertEquals("full-center", normalizePosition("full-center", screenWidthDp = 400))
    }

    @Test
    fun resolveInAppMaxWidthDp_fullCenterFillsAvailableWidth() {
        assertEquals(400, resolveInAppMaxWidthDp(InAppSize.STANDARD, "full-center", screenWidthDp = 400))
    }

    @Test
    fun resolveInAppMaxWidthDp_centeredPresetsAreWiderThanEdgeAnchored() {
        val centered = resolveInAppMaxWidthDp(InAppSize.STANDARD, "middle-center", screenWidthDp = 1000)
        val edgeAnchored = resolveInAppMaxWidthDp(InAppSize.STANDARD, "top-right", screenWidthDp = 1000)
        assertEquals(600, centered)
        assertEquals(400, edgeAnchored)
    }

    @Test
    fun resolveInAppMaxWidthDp_cappedByAvailableViewport() {
        assertEquals(150, resolveInAppMaxWidthDp(InAppSize.SPACIOUS, "middle-center", screenWidthDp = 150))
    }

    @Test
    fun getBorderRadii_fullCenterIsAlwaysSquare() {
        val radii = getBorderRadii("full-center", CornerStyle.ROUND)
        assertEquals(0, radii.topLeftDp)
        assertEquals(0, radii.topRightDp)
        assertEquals(0, radii.bottomLeftDp)
        assertEquals(0, radii.bottomRightDp)
    }

    @Test
    fun getBorderRadii_edgesTouchingScreenStaySquare() {
        val radii = getBorderRadii("top-left", CornerStyle.ROUND)
        // top-left touches both top and left edges -> square; bottom-right is the free corner.
        assertEquals(0, radii.topLeftDp)
        assertEquals(0, radii.topRightDp) // touches top
        assertEquals(0, radii.bottomLeftDp) // touches left
        assertEquals(24, radii.bottomRightDp)
    }

    @Test
    fun getInlineBorderRadii_isUniform() {
        val radii = getInlineBorderRadii(CornerStyle.SHARP)
        assertEquals(2, radii.topLeftDp)
        assertEquals(2, radii.topRightDp)
        assertEquals(2, radii.bottomLeftDp)
        assertEquals(2, radii.bottomRightDp)
    }

    @Test
    fun getAnimationConfig_slidesFromEdgePositionsAndScalesFromCenter() {
        assertEquals("slide", getAnimationConfig("top-center").type)
        assertEquals("slide", getAnimationConfig("bottom-center").type)
        assertEquals("slide", getAnimationConfig("middle-left").type)
        assertEquals("slide", getAnimationConfig("middle-right").type)
        assertEquals("scale", getAnimationConfig("middle-center").type)
    }
}
