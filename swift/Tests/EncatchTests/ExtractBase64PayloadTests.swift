import XCTest
@testable import Encatch

/// Regression tests for signature upload mode, whose `fileData` arrives as a FileReader data
/// URL (draw mode sends bare base64, which must pass through unchanged). Mirrors the Kotlin
/// `FormWebViewBridgeTest` cases.
final class ExtractBase64PayloadTests: XCTestCase {

    func testBareBase64PassesThroughUnchanged() {
        XCTAssertEqual(extractBase64Payload("aGVsbG8="), "aGVsbG8=")
    }

    func testDataUrlHeaderIsStripped() {
        XCTAssertEqual(extractBase64Payload("data:image/jpeg;base64,aGVsbG8="), "aGVsbG8=")
        XCTAssertEqual(extractBase64Payload("DATA:IMAGE/PNG;BASE64,aGVsbG8="), "aGVsbG8=")
    }

    func testWhitespaceIsRemoved() {
        XCTAssertEqual(extractBase64Payload("  aGVs\nbG8= "), "aGVsbG8=")
        XCTAssertEqual(extractBase64Payload("data:image/png;base64,aGVs\r\nbG8="), "aGVsbG8=")
    }

    func testCommaWithoutDataHeaderIsKept() {
        // A comma alone must not trigger stripping — only a real data-URL header does.
        XCTAssertEqual(extractBase64Payload("abc,def"), "abc,def")
    }

    func testStrippedPayloadDecodes() {
        let payload = extractBase64Payload("data:image/png;base64,aGVsbG8=")
        XCTAssertEqual(Data(base64Encoded: payload), Data("hello".utf8))
    }
}
