import XCTest
@testable import Encatch
import EncatchCore

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
}
