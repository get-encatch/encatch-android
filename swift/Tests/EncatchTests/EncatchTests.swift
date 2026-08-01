import XCTest
@testable import Encatch
import EncatchCore
import UIKit

final class EncatchTests: XCTestCase {
    func testSharedInstanceIsReachable() {
        XCTAssertNotNil(EncatchCoreSDK.shared)
    }

    func testThemeRoundTripsThroughKotlinBridge() {
        for theme: EncatchTheme in [.light, .dark, .system] {
            XCTAssertEqual(theme, EncatchTheme(kotlin: theme.kotlin))
        }
    }

    func testResetModeRoundTripsThroughKotlinBridge() {
        for mode: EncatchResetMode in [.always, .onComplete, .never] {
            XCTAssertEqual(mode, EncatchResetMode(kotlin: mode.kotlin))
        }
    }

    func testTriggerTypeRoundTripsThroughKotlinBridge() {
        for type: EncatchTriggerType in [.automatic, .manual] {
            XCTAssertEqual(type, EncatchTriggerType(kotlin: type.kotlin))
        }
    }

    func testEventTypeRoundTripsThroughKotlinBridge() {
        for type in EncatchEventType.allCases where type != .unknown {
            let match = EventType.entries.first { EncatchEventType(kotlin: $0) == type }
            XCTAssertNotNil(match, "no Kotlin EventType round-trips to \(type)")
        }
    }

    func testUploadFileSourceBytesConvertsToKotlin() {
        let data = Data([0x01, 0x02, 0x03])
        let source = EncatchUploadFileSource.bytes(data, mimeType: "image/png")
        guard let bridged = source.kotlin as? UploadFileSource.Bytes else {
            return XCTFail("expected UploadFileSource.Bytes")
        }
        XCTAssertEqual(bridged.bytes.size, 3)
        XCTAssertEqual(bridged.mimeType, "image/png")
    }

    /// `refineText` throws EncatchNotInitializedException before `initialize(apiKey:)` is called
    /// (unlike e.g. `trackEvent`/`showForm`, which intentionally no-op instead of throwing) —
    /// verifies EncatchError correctly unwraps it via the "KotlinException" userInfo key rather
    /// than falling back to the generic `.other` case.
    func testUninitializedCallSurfacesTypedNotInitializedError() async {
        do {
            _ = try await Encatch.shared.refineText(params: RefineTextRequest(questionId: "q", feedbackConfigurationId: "f", userText: "hi"))
            XCTFail("expected EncatchNotInitializedException")
        } catch {
            let encatchError = EncatchError(error)
            guard case .notInitialized = encatchError else {
                return XCTFail("expected .notInitialized, got \(encatchError)")
            }
        }
    }

    /// Drives the whole native UI stack (WKWebView + JS-bridge shim creation, appearance/theme
    /// resolution, layout constraints, entrance animation) with a synthetic payload — no live
    /// network/backend involved, per this project's fully-offline testing rule. Doesn't verify
    /// the hosted form page actually renders (that needs a live URL), but proves nothing in the
    /// native chrome crashes or fails to lay out when driven with real data shapes.
    @MainActor
    func testModalFormViewControllerPresentsWithSyntheticPayloadWithoutCrashing() async throws {
        let hostWindow = UIWindow(frame: UIScreen.main.bounds)
        let hostController = UIViewController()
        hostWindow.rootViewController = hostController
        hostWindow.makeKeyAndVisible()

        let formConfig = ShowFormResponse(
            feedbackConfigurationId: "test-config",
            feedbackIdentifier: nil,
            triggerType: TriggerType.manual,
            formConfiguration: nil,
            questionnaireFields: nil,
            otherConfigurationProperties: nil,
            appearanceProperties: nil,
            partialResponseEnabled: nil,
            contact: nil,
            projectI18nFileUrl: nil,
            pingAgainIn: nil,
            pingOnNextPageVisit: nil,
            feedbackTransactions: nil,
        )
        let payload = ShowFormPayload(
            formId: "test-form",
            formConfig: formConfig,
            resetMode: ResetMode.always,
            triggerType: TriggerType.manual,
            prefillResponses: [:],
            locale: nil,
            theme: EncatchTheme.light.kotlin,
            context: nil,
            presentation: "modal",
            inlineSlotId: nil,
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
