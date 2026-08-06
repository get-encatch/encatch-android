import XCTest
@testable import Encatch

// MARK: - FormAppearance (ported from core/src/commonTest/.../FormAppearanceTest.kt)

final class FormAppearanceTests: XCTestCase {
    private func appearance(_ json: String) -> JSONValue? { JSONValue.parse(json) }

    func testResolveCornersFromFormConfig_readsAppearanceThenFeatureSettingsThenDefaultsSoft() {
        XCTAssertEqual(resolveCornersFromFormConfig(appearance(#"{"appearance":{"corners":"sharp"}}"#)), .sharp)
        XCTAssertEqual(resolveCornersFromFormConfig(appearance(#"{"featureSettings":{"corners":"round"}}"#)), .round)
        XCTAssertEqual(resolveCornersFromFormConfig(appearance("{}")), .soft)
        XCTAssertEqual(resolveCornersFromFormConfig(nil), .soft)
    }

    func testResolveInAppSizeFromFormConfig_readsInAppThenFeatureSettingsThenDefaultsStandard() {
        XCTAssertEqual(resolveInAppSizeFromFormConfig(appearance(#"{"inApp":{"size":"compact"}}"#)), .compact)
        XCTAssertEqual(resolveInAppSizeFromFormConfig(appearance(#"{"featureSettings":{"inAppSize":"spacious"}}"#)), .spacious)
        XCTAssertEqual(resolveInAppSizeFromFormConfig(appearance("{}")), .standard)
    }

    func testResolveSelectedPositionFromFormConfig_readsInAppThenLegacyThenDefaultsMiddleCenter() {
        XCTAssertEqual(resolveSelectedPositionFromFormConfig(appearance(#"{"inApp":{"position":"top-left"}}"#)), "top-left")
        XCTAssertEqual(resolveSelectedPositionFromFormConfig(appearance(#"{"selectedPosition":"bottom-right"}"#)), "bottom-right")
        XCTAssertEqual(resolveSelectedPositionFromFormConfig(appearance("{}")), "middle-center")
    }

    func testResolveDarkOverlayFromFormConfig_readsInAppThenFeatureSettings() {
        XCTAssertTrue(resolveDarkOverlayFromFormConfig(appearance(#"{"inApp":{"darkOverlay":true}}"#)))
        XCTAssertTrue(resolveDarkOverlayFromFormConfig(appearance(#"{"featureSettings":{"darkOverlay":true}}"#)))
        XCTAssertFalse(resolveDarkOverlayFromFormConfig(appearance("{}")))
    }

    func testResolveMaxDialogHeightFraction_convertsPercentOrDefaults80Percent() {
        XCTAssertEqual(resolveMaxDialogHeightFraction(appearance(#"{"featureSettings":{"maxDialogHeightPercentInApp":60}}"#)), 0.6)
        XCTAssertEqual(resolveMaxDialogHeightFraction(appearance("{}")), 0.8)
    }

    func testNormalizePosition_collapsesLeftRightToCenterOnMobile() {
        XCTAssertEqual(normalizePosition("top-left", screenWidthDp: 400), "top-center")
        XCTAssertEqual(normalizePosition("bottom-right", screenWidthDp: 400), "bottom-center")
        XCTAssertEqual(normalizePosition("middle-left", screenWidthDp: 400), "middle-center")
        XCTAssertEqual(normalizePosition("top-left", screenWidthDp: 800), "top-left")
    }

    func testNormalizePosition_fullCollapsesToFullCenterRegardlessOfWidth() {
        XCTAssertEqual(normalizePosition("full", screenWidthDp: 800), "full-center")
        XCTAssertEqual(normalizePosition("full-center", screenWidthDp: 400), "full-center")
    }

    func testResolveInAppMaxWidthDp_fullCenterFillsAvailableWidth() {
        XCTAssertEqual(resolveInAppMaxWidthDp(size: .standard, position: "full-center", screenWidthDp: 400), 400)
    }

    func testResolveInAppMaxWidthDp_centeredPresetsAreWiderThanEdgeAnchored() {
        let centered = resolveInAppMaxWidthDp(size: .standard, position: "middle-center", screenWidthDp: 1000)
        let edgeAnchored = resolveInAppMaxWidthDp(size: .standard, position: "top-right", screenWidthDp: 1000)
        XCTAssertEqual(centered, 600)
        XCTAssertEqual(edgeAnchored, 400)
    }

    func testResolveInAppMaxWidthDp_cappedByAvailableViewport() {
        XCTAssertEqual(resolveInAppMaxWidthDp(size: .spacious, position: "middle-center", screenWidthDp: 150), 150)
    }

    func testGetBorderRadii_fullCenterIsAlwaysSquare() {
        let radii = getBorderRadii("full-center", corners: .round)
        XCTAssertEqual(radii, PopupBorderRadii(topLeftDp: 0, topRightDp: 0, bottomLeftDp: 0, bottomRightDp: 0))
    }

    func testGetBorderRadii_edgesTouchingScreenStaySquare() {
        let radii = getBorderRadii("top-left", corners: .round)
        // top-left touches both top and left edges -> square; bottom-right is the free corner.
        XCTAssertEqual(radii.topLeftDp, 0)
        XCTAssertEqual(radii.topRightDp, 0) // touches top
        XCTAssertEqual(radii.bottomLeftDp, 0) // touches left
        XCTAssertEqual(radii.bottomRightDp, 24)
    }

    func testGetInlineBorderRadii_isUniform() {
        let radii = getInlineBorderRadii(corners: .sharp)
        XCTAssertEqual(radii, PopupBorderRadii(topLeftDp: 2, topRightDp: 2, bottomLeftDp: 2, bottomRightDp: 2))
    }

    func testGetAnimationConfig_slidesFromEdgePositionsAndScalesFromCenter() {
        XCTAssertEqual(getAnimationConfig("top-center").type, "slide")
        XCTAssertEqual(getAnimationConfig("bottom-center").type, "slide")
        XCTAssertEqual(getAnimationConfig("middle-left").type, "slide")
        XCTAssertEqual(getAnimationConfig("middle-right").type, "slide")
        XCTAssertEqual(getAnimationConfig("middle-center").type, "scale")
    }

    func testGetPositionLayout_resolvesVerticalAndHorizontalAnchors() {
        XCTAssertEqual(getPositionLayout("top-left"), PositionAlignment(vertical: .top, horizontal: .start))
        XCTAssertEqual(getPositionLayout("bottom-right"), PositionAlignment(vertical: .bottom, horizontal: .end))
        XCTAssertEqual(getPositionLayout("middle-center"), PositionAlignment(vertical: .center, horizontal: .center))
    }
}

// MARK: - FormThemeColor (ported from core/src/commonTest/.../FormThemeColorTest.kt)

final class FormThemeColorTests: XCTestCase {
    func testHexWithAlpha_appendsOrReplacesAlphaChannel() {
        XCTAssertEqual(hexWithAlpha("#ff0000"), "#ff00004D")
        XCTAssertEqual(hexWithAlpha("#ff0000", alphaHex: "80"), "#ff000080")
        XCTAssertEqual(hexWithAlpha("#fff"), "#ffffff4D")
        XCTAssertEqual(hexWithAlpha("#aabbccdd", alphaHex: "80"), "#aabbccdd") // 8-digit hex already has alpha
    }

    func testNormalizeColorForNative_acceptsHexAndRgbFunctions_rejectsUnsupportedFormats() {
        XCTAssertEqual(normalizeColorForNative("#ff0000", fallback: "#000000"), "#ff0000")
        XCTAssertEqual(normalizeColorForNative("rgb(255, 0, 0)", fallback: "#000000"), "rgb(255, 0, 0)")
        XCTAssertEqual(normalizeColorForNative("rgba(255, 0, 0, 0.5)", fallback: "#000000"), "rgba(255, 0, 0, 0.5)")
        // oklch(...) (or any unrecognized function) isn't renderable -> falls back
        XCTAssertEqual(normalizeColorForNative("oklch(0.5 0.2 30)", fallback: "#000000"), "#000000")
        XCTAssertEqual(normalizeColorForNative(nil, fallback: "#000000"), "#000000")
        XCTAssertEqual(normalizeColorForNative("", fallback: "#000000"), "#000000")
    }

    func testGetBackgroundColor_extractsBackgroundThenPopoverVariable() {
        XCTAssertEqual(getBackgroundColor("{\"--background\":\"#ffffff\"}", fallback: "#000000"), "#ffffff")
        XCTAssertEqual(getBackgroundColor("{\"--popover\":\"#eeeeee\"}", fallback: "#000000"), "#eeeeee")
        XCTAssertEqual(getBackgroundColor("{}", fallback: "#000000"), "#000000")
        XCTAssertEqual(getBackgroundColor(nil, fallback: "#000000"), "#000000")
        XCTAssertEqual(getBackgroundColor("not json", fallback: "#000000"), "#000000")
    }

    func testGetBackgroundColor_fallsBackWhenStoredColorIsUnrenderable() {
        XCTAssertEqual(getBackgroundColor("{\"--background\":\"oklch(1 0 0)\"}", fallback: "#000000"), "#000000")
    }

    func testResolveSystemColorScheme_mapsIsSystemDarkToModeString() {
        XCTAssertEqual(resolveSystemColorScheme(isSystemDark: true), "dark")
        XCTAssertEqual(resolveSystemColorScheme(isSystemDark: false), "light")
    }

    func testResolveActiveMode_prefersExplicitShareableModeOverSystemScheme() {
        XCTAssertEqual(resolveActiveMode(shareableMode: "light", systemScheme: "dark"), "light")
        XCTAssertEqual(resolveActiveMode(shareableMode: "dark", systemScheme: "light"), "dark")
        XCTAssertEqual(resolveActiveMode(shareableMode: nil, systemScheme: "dark"), "dark")
        XCTAssertEqual(resolveActiveMode(shareableMode: nil, systemScheme: "light"), "light")
    }

    func testColorWithAlpha_preservesExplicitAlpha_appliesFallbackOtherwise() {
        XCTAssertEqual(colorWithAlpha("rgba(1, 2, 3, 0.9)"), "rgba(1, 2, 3, 0.9)")
        XCTAssertEqual(colorWithAlpha("rgb(1, 2, 3)", fallbackAlpha: 0.4), "rgba(1, 2, 3, 0.4)")
        XCTAssertEqual(colorWithAlpha("not-a-color", fallbackAlpha: 0.4), "rgba(0, 0, 0, 0.4)")
    }

    func testColorWithAlpha_hex8PreservesEmbeddedAlpha() {
        let result = colorWithAlpha("#ff000080", fallbackAlpha: 0.4)
        XCTAssertEqual(result, "rgba(255, 0, 0, \(128.0 / 255.0))")
    }

    func testResolveModalOverlayBackgroundColor_isTransparentWhenDarkOverlayDisabled() {
        XCTAssertEqual(resolveModalOverlayBackgroundColor(appearanceProperties: nil, activeMode: "light", darkOverlay: false), "transparent")
    }

    func testResolveModalOverlayBackgroundColor_defaultsWhenDarkOverlayEnabledButNoThemeConfig() {
        let result = resolveModalOverlayBackgroundColor(appearanceProperties: nil, activeMode: "light", darkOverlay: true)
        XCTAssertEqual(result, DEFAULT_OVERLAY_RGBA)
    }

    func testParseCssColorToArgb_hex6IsFullyOpaque() {
        XCTAssertEqual(parseCssColorToArgb("#ff0000", fallbackArgb: 0), Int32(bitPattern: 0xFFFF0000))
    }

    func testParseCssColorToArgb_hex8HonorsAlphaChannel() {
        XCTAssertEqual(parseCssColorToArgb("#ff000080", fallbackArgb: 0), Int32(bitPattern: 0x80FF0000))
    }

    func testParseCssColorToArgb_rgbaHonorsAlpha() {
        let argb = UInt32(bitPattern: parseCssColorToArgb("rgba(255, 0, 0, 0.5)", fallbackArgb: 0))
        XCTAssertEqual((argb >> 16) & 0xFF, 0xFF) // red
        XCTAssertEqual((argb >> 8) & 0xFF, 0x00) // green
        XCTAssertEqual((argb >> 24) & 0xFF, 128) // round(0.5 * 255)
    }

    func testParseCssColorToArgb_transparentIsZero() {
        XCTAssertEqual(parseCssColorToArgb("transparent", fallbackArgb: Int32(bitPattern: 0xFFFFFFFF)), 0)
    }

    func testParseCssColorToArgb_unparseableFallsBackToProvidedDefault() {
        XCTAssertEqual(parseCssColorToArgb("not-a-color", fallbackArgb: 0x11223344), 0x11223344)
    }

    func testParseCssColorToArgb_hslConvertsToRgb() {
        // hsl(0, 100%, 50%) is pure red.
        let argb = UInt32(bitPattern: parseCssColorToArgb("hsl(0, 100%, 50%)", fallbackArgb: 0))
        XCTAssertEqual((argb >> 16) & 0xFF, 0xFF)
        XCTAssertEqual((argb >> 8) & 0xFF, 0x00)
        XCTAssertEqual(argb & 0xFF, 0x00)
    }
}

// MARK: - InlineSlotRegistry (ported from core/src/commonTest/.../InlineSlotRegistryTest.kt)

final class InlineSlotRegistryTests: XCTestCase {
    override func setUp() {
        super.setUp()
        InlineSlotRegistry.shared.clearSlots()
    }

    override func tearDown() {
        InlineSlotRegistry.shared.clearSlots()
        super.tearDown()
    }

    func testResolve_noSlots_returnsModal() {
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .modal)
    }

    func testResolve_exactMatchOnFormId_returnsInline() {
        let slotId = InlineSlotRegistry.shared.registerInlineSlot(formId: "form-1")
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .inline(slotId: slotId))
    }

    func testResolve_exactMatchOnFeedbackConfigurationId_returnsInline() {
        let slotId = InlineSlotRegistry.shared.registerInlineSlot(formId: "cfg-1")
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .inline(slotId: slotId))
    }

    func testResolve_wildcardSlot_catchesUnmatchedForm() {
        let wildcardSlotId = InlineSlotRegistry.shared.registerInlineSlot(formId: nil)
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .inline(slotId: wildcardSlotId))
    }

    func testResolve_exactMatchWinsOverWildcard() {
        _ = InlineSlotRegistry.shared.registerInlineSlot(formId: nil)
        let exactSlotId = InlineSlotRegistry.shared.registerInlineSlot(formId: "form-1")
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .inline(slotId: exactSlotId))
    }

    func testResolve_nonMatchingExactSlot_fallsBackToWildcard() {
        _ = InlineSlotRegistry.shared.registerInlineSlot(formId: "some-other-form")
        let wildcardSlotId = InlineSlotRegistry.shared.registerInlineSlot(formId: nil)
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .inline(slotId: wildcardSlotId))
    }

    func testResolve_noMatchAndNoWildcard_returnsModal() {
        _ = InlineSlotRegistry.shared.registerInlineSlot(formId: "some-other-form")
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .modal)
    }

    func testUnregister_removesSlotFromResolution() {
        let slotId = InlineSlotRegistry.shared.registerInlineSlot(formId: "form-1")
        InlineSlotRegistry.shared.unregisterInlineSlot(slotId)
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: "cfg-1")
        XCTAssertEqual(target, .modal)
    }

    func testUpdateInlineSlot_changesFormIdWithoutReordering() {
        let first = InlineSlotRegistry.shared.registerInlineSlot(formId: "form-a")
        let second = InlineSlotRegistry.shared.registerInlineSlot(formId: "form-b")

        InlineSlotRegistry.shared.updateInlineSlot(first, formId: "form-b")

        // First-registered still wins for the now-shared formId.
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-b", feedbackConfigurationId: nil)
        XCTAssertEqual(target, .inline(slotId: first))
        let snapshot = InlineSlotRegistry.shared.slotsSnapshot()
        XCTAssertEqual(snapshot.count, 2)
        XCTAssertEqual(snapshot[0].formId, "form-b")
        XCTAssertEqual(snapshot[1].slotId, second)
    }

    func testRegistrationOrder_firstWildcardWins() {
        let firstWildcard = InlineSlotRegistry.shared.registerInlineSlot(formId: nil)
        _ = InlineSlotRegistry.shared.registerInlineSlot(formId: nil)
        let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: "form-1", feedbackConfigurationId: nil)
        XCTAssertEqual(target, .inline(slotId: firstWildcard))
    }
}

// MARK: - RetryQueue (ported from core/src/commonTest/.../RetryQueueTest.kt)
//
// The Kotlin tests run on `kotlinx.coroutines.test`'s virtual-time scheduler
// (`runTest`/`advanceUntilIdle`), which Swift Concurrency has no equivalent for — these use real
// `Task.sleep`-driven backoff instead (max total wait ~7s across the retry-exhaustion cases,
// matching `BASE_BACKOFF_MS=1000` doubling), with a generous polling timeout.

final class RetryQueueTests: XCTestCase {
    private func waitUntil(timeout: TimeInterval = 10, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    func testEnqueue_successfulCall_isRemovedFromQueue() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeEphemeralDefaults()))
        await queue.enqueue(label: "trackEvent") { /* succeeds */ }
        await waitUntil { await queue.queueSize() == 0 }
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testEnqueue_clientError_isDroppedWithoutRetry() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeEphemeralDefaults()))
        let attempts = Counter()

        await queue.enqueue(label: "identifyUser") {
            await attempts.increment()
            throw EncatchApiException(endpoint: "identify-user", status: 404, responseBody: "not found")
        }
        await waitUntil { await queue.queueSize() == 0 }

        let count = await attempts.value
        XCTAssertEqual(count, 1)
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testEnqueue_serverError_retriesUpToMaxThenDrops() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeEphemeralDefaults()))
        let attempts = Counter()

        await queue.enqueue(label: "trackScreen") {
            await attempts.increment()
            throw EncatchApiException(endpoint: "track-screen", status: 500, responseBody: "server error")
        }
        await waitUntil(timeout: 15) {
            let size = await queue.queueSize()
            let count = await attempts.value
            return size == 0 && count >= RetryQueue.maxRetries
        }

        // maxRetries (3) is the total attempt count before dropping (matches retry-queue.ts semantics).
        let count = await attempts.value
        XCTAssertEqual(count, RetryQueue.maxRetries)
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testEnqueue_succeedsAfterTransientServerErrors() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeEphemeralDefaults()))
        let attempts = Counter()

        await queue.enqueue(label: "trackEvent") {
            let current = await attempts.increment()
            if current < 3 {
                throw EncatchApiException(endpoint: "track-event", status: 503, responseBody: "unavailable")
            }
        }
        await waitUntil(timeout: 15) { (await attempts.value) >= 3 }

        let count = await attempts.value
        XCTAssertEqual(count, 3)
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }
}

/// Actor-based counter for concurrency-safe increments inside async test closures.
private actor Counter {
    private(set) var value = 0

    @discardableResult
    func increment() -> Int {
        value += 1
        return value
    }
}

/// A fresh, isolated `UserDefaults` suite per test — avoids cross-test/cross-run pollution of
/// `UserDefaults.standard` (the Kotlin tests use `InMemorySettings`, a from-scratch in-memory map;
/// this is the closest Foundation equivalent).
private func makeEphemeralDefaults() -> UserDefaults {
    let suiteName = "com.encatch.tests.\(UUID().uuidString)"
    return UserDefaults(suiteName: suiteName)!
}

// MARK: - MessageProtocol wire-value round trips (ported from core/src/commonTest/.../MessageProtocolTest.kt)

final class MessageProtocolTests: XCTestCase {
    func testFormMessageTypeFromWireRoundTrips() {
        for type in FormMessageType.allCases {
            XCTAssertEqual(FormMessageType.fromWire(type.wireValue), type)
        }
    }

    func testFormMessageTypeFromWireUnknownReturnsNil() {
        XCTAssertNil(FormMessageType.fromWire("not-a-real-type"))
    }
}

// MARK: - Answer / SchedulerAnswer JSON round trip (ported from core/src/commonTest/.../AnswerSerializationTest.kt)

final class AnswerSerializationTests: XCTestCase {
    func testSchedulerAnswerGoogleCalendarEncodesProviderDiscriminator() throws {
        let answer = SchedulerAnswer.googleCalendar(bookedAt: "2024-01-01T00:00:00Z")
        let data = try JSONEncoder().encode(answer)
        let json = try XCTUnwrap(JSONValue.parse(String(data: data, encoding: .utf8)!))
        guard case .object(let object) = json else { return XCTFail("expected object") }
        XCTAssertEqual(object["provider"], .string("google_calendar"))
        XCTAssertEqual(object["bookedAt"], .string("2024-01-01T00:00:00Z"))
    }

    func testSchedulerAnswerCalendlyRoundTrips() throws {
        let answer = SchedulerAnswer.calendly(slotStart: "10:00", slotEnd: "10:30", eventId: "evt-1", bookedAt: "2024-01-01T00:00:00Z")
        let data = try JSONEncoder().encode(answer)
        let decoded = try JSONDecoder().decode(SchedulerAnswer.self, from: data)
        XCTAssertEqual(decoded, answer)
    }

    func testAnswerOmitsNilFieldsWhenEncoded() throws {
        let answer = Answer(rating: 5)
        let data = try JSONEncoder().encode(answer)
        let json = try XCTUnwrap(JSONValue.parse(String(data: data, encoding: .utf8)!))
        guard case .object(let object) = json else { return XCTFail("expected object") }
        XCTAssertEqual(object["rating"], .number(5))
        XCTAssertNil(object["longText"])
        XCTAssertNil(object["scheduler"])
    }
}

#if canImport(UIKit)
import UIKit

// MARK: - Modal presentation smoke test (ported from swift/Tests/EncatchTests/EncatchTests.swift)

final class EncatchFormViewControllerTests: XCTestCase {
    /// Drives the whole native UI stack (WKWebView + JS-bridge shim creation, appearance/theme
    /// resolution, layout constraints, entrance animation) with a synthetic payload — no live
    /// network/backend involved, per this project's fully-offline testing rule. Doesn't verify the
    /// hosted form page actually renders (that needs a live URL), but proves nothing in the native
    /// chrome crashes or fails to lay out when driven with real data shapes.
    @MainActor
    func testModalFormViewControllerPresentsWithSyntheticPayloadWithoutCrashing() async throws {
        let hostWindow = UIWindow(frame: UIScreen.main.bounds)
        let hostController = UIViewController()
        hostWindow.rootViewController = hostController
        hostWindow.makeKeyAndVisible()

        let formConfig = ShowFormResponse(feedbackConfigurationId: "test-config", triggerType: .manual)
        let payload = ShowFormPayload(
            formId: "test-form",
            formConfig: formConfig,
            resetMode: .always,
            triggerType: .manual,
            prefillResponses: [:],
            locale: nil,
            theme: .light,
            context: nil,
            presentation: "modal",
            inlineSlotId: nil
        )

        let controller = EncatchFormViewController()
        let presented = expectation(description: "modal presented")
        controller.present(payload: payload, from: hostController)

        // present(from:) itself calls UIKit's async present(_:animated:completion:); give the
        // run loop a beat to actually finish presenting before asserting.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { presented.fulfill() }
        await fulfillment(of: [presented], timeout: 2)

        XCTAssertNotNil(hostController.presentedViewController)
        controller.dismiss(animated: false)
    }
}
#endif

final class FormWebViewBridgeTests: XCTestCase {
    private struct NoopRedirectOpener: RedirectOpener {
        func openInternal(url: String) async {}
    }

    /// form:layout carries fullHeight as a JSON BOOL (see apps/shareable: sendToParent("form:layout",
    /// { fullHeight: schedulerDialogOpen || qnaWithAiDialogOpen })) — decoding it as a string made
    /// the QnA/scheduler full-height overlays dead on iOS.
    func testFormLayoutBooleanFullHeightReachesCallback() {
        nonisolated(unsafe) var received: [Bool] = []
        let bridge = FormWebViewBridge(
            onClose: { _ in },
            onHeightChange: { _ in },
            onForceFullHeight: { received.append($0) },
            onReady: {},
            sendToWebView: { _ in },
            redirectOpener: NoopRedirectOpener(),
            openExternal: { _ in }
        )
        bridge.handleMessage(#"{"type":"form:layout","data":{"fullHeight":true}}"#)
        bridge.handleMessage(#"{"type":"form:layout","data":{"fullHeight":false}}"#)
        bridge.handleMessage(#"{"type":"form:layout","data":{"fullHeight":"true"}}"#)
        XCTAssertEqual(received, [true, false, true])
    }
}
