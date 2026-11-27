import XCTest
@testable import Encatch

/// Survey answers go only to the user they belong to (React Native handover
/// HANDOVER-ON-SIGNATURE-EXPIRED.md §6b): who the survey was shown to, else the current user; a
/// signed-in owner's answers are dropped once they are no longer current (checked again after a
/// rate-limit wait); a visitor's answers are sent whoever is current; clearAll() drops a waiting one.
/// Mirrors the Kotlin `SubmitOwnerTest`.
final class SubmitOwnerTests: XCTestCase {
    private final class Locked<T>: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: T
        init(_ value: T) { stored = value }
        var value: T {
            get { lock.lock(); defer { lock.unlock() }; return stored }
            set { lock.lock(); stored = newValue; lock.unlock() }
        }
    }

    /// submit-form answers 429 (Retry-After 1 s) to its first request when set.
    private let firstSubmitPaused = Locked(false)
    private let submits = Locked(0)
    /// show-form answers after this long.
    private let showDelayMs = Locked<UInt64>(0)
    private let shows = Locked(0)

    private var server: FakeServer!
    private var logger: RecordingLogger!
    private var sdk: Encatch!
    private var offShows: (() -> Void)?

    override func setUp() async throws {
        server = FakeServer()
        server.respond { [self] call in
            switch call.endpoint {
            case "identify-user": return FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#)
            case "show-form": return FakeServer.Reply(body: #"{"feedbackConfigurationId":"fc-1","questionnaireFields":{}}"#, delayMs: showDelayMs.value)
            case "submit-form":
                submits.value += 1
                if submits.value == 1 && firstSubmitPaused.value { return FakeServer.Reply(status: 429, headers: ["Retry-After": "1"]) }
                return .init()
            default: return .init()
            }
        }
        logger = RecordingLogger()
        sdk = try await makeTestSDK(server: server, logger: logger)
        let shows = shows
        offShows = EncatchInternalEmitter.shared.on { event in
            if case .showForm = event { shows.value += 1 }
        }
    }

    override func tearDown() async throws {
        offShows?()
        sdk.stop()
        try await sdk.clearAll()
    }

    private func identify(_ name: String) async throws {
        try await sdk.identifyUser(userName: name)
        await waitFor("\(name) identified") { self.sdk.userId == "uid-\(name)" }
        await sleepMs(100)
    }

    private func show() async throws {
        let before = shows.value
        try await sdk.showForm("form-1")
        await waitFor("shown") { self.shows.value > before }
    }

    private func submit() async throws { try await sdk.submitForm(submitRequest("fc-1")) }

    private var sent: [FakeServer.Call] { server.calls("submit-form") }
    private var dropWarnings: [String] { logger.warnings.filter { $0.contains("A survey submit was dropped") } }

    func testShownToAliceThenASwitchToBobDropsIt() async throws {
        try await identify("alice")
        try await show()
        try await identify("bob")
        try await submit()
        await sleepMs(200)
        XCTAssertTrue(sent.isEmpty, "never sent as bob")
        XCTAssertEqual(dropWarnings.count, 1)
    }

    func testAliceSubmitsItIsSentAsAlice() async throws {
        try await identify("alice")
        try await show()
        try await submit()
        await waitFor { self.sent.count == 1 }
        XCTAssertEqual(sent.first?.header("X-User-Name"), "alice")
    }

    func testShownToAliceThenResetUserDropsIt() async throws {
        try await identify("alice")
        try await show()
        try await sdk.resetUser()
        try await submit()
        await sleepMs(200)
        XCTAssertTrue(sent.isEmpty, "never sent as a visitor")
    }

    func testAlicesSubmitInA429PauseThenResetUserDropsIt() async throws {
        try await identify("alice")
        try await show()
        firstSubmitPaused.value = true
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest("fc-1")) }
        await waitFor { self.sent.count == 1 } // the 429 attempt
        try await sdk.resetUser()
        await sleepMs(1500)
        XCTAssertEqual(sent.count, 1, "only the 429 attempt")
        XCTAssertEqual(dropWarnings.count, 1)
    }

    func testAVisitorShownThenSignsInItIsSent() async throws {
        try await show()
        try await identify("alice")
        try await submit()
        await waitFor { self.sent.count == 1 }
        XCTAssertEqual(sent.first?.header("X-User-Name"), "alice")
    }

    func testAVisitorsSubmitInA429PauseIsSentAfterTheVisitorSignsIn() async throws {
        try await show()
        firstSubmitPaused.value = true
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest("fc-1")) }
        await waitFor { self.sent.count == 1 }
        try await identify("alice")
        await waitFor(timeout: 3) { self.sent.count == 2 }
        XCTAssertTrue(dropWarnings.isEmpty)
    }

    func testTheSameSurveyShownAgainToBobIsSentAsBob() async throws {
        try await identify("alice")
        try await show()
        try await identify("bob")
        sdk.setFormVisible(false, formId: "form-1") // alice's closed, then shown again for bob
        try await show()
        try await submit()
        await waitFor { self.sent.count == 1 }
        XCTAssertEqual(sent.first?.header("X-User-Name"), "bob")
    }

    func testAliceBobAliceWithinAPauseIsSentAsAlice() async throws {
        try await identify("alice")
        try await show()
        firstSubmitPaused.value = true
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest("fc-1")) }
        await waitFor { self.sent.count == 1 }
        try await identify("bob")
        try await identify("alice")
        await waitFor(timeout: 3) { self.sent.count == 2 }
        XCTAssertEqual(sent.dropFirst().first?.header("X-User-Name"), "alice")
    }

    func testClearAllWhileASubmitWaitsDropsIt() async throws {
        try await show() // a visitor's: only clearAll can drop it
        firstSubmitPaused.value = true
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest("fc-1")) }
        await waitFor { self.sent.count == 1 }
        try await sdk.clearAll()
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        await sleepMs(1500)
        XCTAssertEqual(sent.count, 1)
        XCTAssertTrue(logger.warnings.contains { $0.contains("cleared or re-initialized while it waited") })
    }

    func testARepeatedFinalSubmitResolvesTheSameWay() async throws {
        try await identify("alice")
        try await show()
        try await submit()
        try await submit()
        await waitFor { self.sent.count == 2 }
        XCTAssertTrue(sent.allSatisfy { $0.header("X-User-Name") == "alice" })
    }

    func testOnceTheSurveyClosesALaterSubmitGoesAsTheCurrentUser() async throws {
        try await identify("alice")
        try await show()
        sdk.setFormVisible(false, formId: "form-1")
        try await identify("bob")
        try await submit()
        await waitFor { self.sent.count == 1 }
        XCTAssertEqual(sent.first?.header("X-User-Name"), "bob")
    }

    func testASurveyAskedForAliceWhoseReplyArrivesAfterASwitchIsNotShown() async throws {
        try await identify("alice")
        showDelayMs.value = 400
        let sdk = sdk!
        Task { try? await sdk.showForm("form-1") }
        await waitFor { self.server.count("show-form") == 1 }
        try await identify("bob")
        await sleepMs(600)
        XCTAssertEqual(shows.value, 0)
    }

    func testAVisitorsSurveyWhoseReplyArrivesAfterSignInIsStillShown() async throws {
        showDelayMs.value = 400
        let sdk = sdk!
        Task { try? await sdk.showForm("form-1") }
        await waitFor { self.server.count("show-form") == 1 }
        try await identify("alice")
        await waitFor { self.shows.value == 1 }
    }

    func testAReInitWhileASubmitWaitsDropsIt() async throws {
        try await show()
        firstSubmitPaused.value = true
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest("fc-1")) }
        await waitFor { self.sent.count == 1 }
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        await sleepMs(1500)
        XCTAssertEqual(sent.count, 1)
    }

    func testTheSurveyPagesSubmitTakesItsOwnerBeforeTheCloseThatFollowsIt() async throws {
        try await identify("alice")
        try await show()
        try await identify("bob")
        // the page sends the final submit and its close back to back
        let req = submitRequest("fc-1")
        let owner = sdk.ownerOf(req)
        sdk.setFormVisible(false, formId: "form-1")
        await sdk.submitForm(req, owner: owner)
        await sleepMs(200)
        XCTAssertTrue(sent.isEmpty, "alice's answers are never sent as bob")
    }
}
