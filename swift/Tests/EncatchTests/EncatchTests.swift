import XCTest
@testable import Encatch

final class EncatchTests: XCTestCase {
    func testSharedInstanceIsReachable() {
        XCTAssertNotNil(EncatchCoreSDK.shared)
    }
}
