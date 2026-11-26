import XCTest
@testable import Encatch

/// The SwiftUI context (`EncatchContext`, React Native's `useEncatch()` counterpart): it publishes
/// the SDK's state as it changes and passes its calls on. Each test drives its own SDK instance.
@MainActor
final class EncatchContextTests: XCTestCase {
    private let config = EncatchConfig(apiBaseUrl: "https://api.test", debugMode: true)

    private func makeContext(_ server: FakeServer, defaults: UserDefaults = makeTestDefaults(), logger: RecordingLogger = RecordingLogger()) -> (Encatch, EncatchContext) {
        let sdk = Encatch()
        sdk.transportForTesting = server.transport
        sdk.storageDefaultsForTesting = defaults
        sdk.loggerForTesting = logger
        return (sdk, EncatchContext(encatch: sdk))
    }

    private func replyIds(_ server: FakeServer, delayMs: UInt64 = 0) {
        server.respond { call in
            call.endpoint == "identify-user"
                ? FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#, delayMs: delayMs)
                : .init()
        }
    }

    func testBeforeInitializeNothingIsPublished() {
        let (sdk, context) = makeContext(FakeServer())
        defer { sdk.stop() }
        XCTAssertFalse(context.isInitialized)
        XCTAssertFalse(context.isIdentified)
        XCTAssertNil(context.userName)
    }

    func testInitializePublishesTheStateAndRunsOncePerApiKey() async {
        let logger = RecordingLogger()
        let (sdk, context) = makeContext(FakeServer(), logger: logger)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key-1", config: config)
        XCTAssertTrue(context.isInitialized)
        XCTAssertTrue(sdk.isInitialized)

        // the provider appearing again: no second initialize
        await context.initialize(apiKey: "key-1", config: config)
        XCTAssertFalse(logger.debugs.contains { $0.contains("reconfiguring") }, "initialized twice for the same key")

        // another key: initialized again with it
        await context.initialize(apiKey: "key-2", config: config)
        XCTAssertTrue(logger.debugs.contains { $0.contains("reconfiguring") })
        XCTAssertEqual(sdk.apiKey, "key-2")
    }

    func testIdentifyPublishesTheUserAndResetClearsIt() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)

        context.identifyUser(userName: "alice")
        await waitFor("published as identified") { context.isIdentified && context.userName == "alice" }

        context.resetUser()
        await waitFor("published as a visitor") { !context.isIdentified && context.userName == nil }
    }

    func testASwitchKeepsThePreviousUserUntilTheNewIdentifySucceeds() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.userName == "alice" }

        replyIds(server, delayMs: 800)
        context.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 2 }
        await sleepMs(100)
        XCTAssertTrue(context.isIdentified)
        XCTAssertEqual(context.userName, "alice", "still the previous user while bob's identify is pending")

        await waitFor("bob published") { context.userName == "bob" }
        XCTAssertTrue(context.isIdentified)
    }

    func testClearAllPublishesAVisitor() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.isIdentified }
        try await sdk.clearAll()
        await waitFor("published as cleared") { !context.isIdentified && context.userName == nil }
    }

    func testARestoredUserIsPublishedAtInitialize() async throws {
        let server = FakeServer()
        replyIds(server)
        let defaults = makeTestDefaults()
        let (first, firstContext) = makeContext(server, defaults: defaults)
        await firstContext.initialize(apiKey: "key", config: config)
        firstContext.identifyUser(userName: "alice")
        await waitFor { firstContext.isIdentified }
        first.stop()

        let (sdk, context) = makeContext(server, defaults: defaults)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        XCTAssertTrue(context.isIdentified)
        XCTAssertEqual(context.userName, "alice")
    }

    func testCallsReachTheSdkInTheOrderTheyWereMade() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        for round in 0..<100 {
            // sign out, then the next user in: never left as a visitor
            context.resetUser()
            context.identifyUser(userName: "u\(round)")
            await waitFor("round \(round)") { sdk.userName == "u\(round)" && context.userName == "u\(round)" }
        }
        XCTAssertTrue(context.isIdentified)
    }

    func testASubmitMadeBeforeSigningOutGoesAsTheUser() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.userName == "alice" }

        context.submitForm(submitRequest())
        context.resetUser()
        await waitFor("submit sent") { server.count("submit-form") == 1 }
        XCTAssertEqual(server.calls("submit-form")[0].header("X-User-Name"), "alice")
        await waitFor("signed out") { !context.isIdentified }
    }

    func testASlowFormDoesNotHoldUpSigningOutOrOtherCalls() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.isIdentified }
        server.respond { call in
            call.endpoint == "show-form"
                ? FakeServer.Reply(body: #"{"feedbackConfigurationId":"cfg","appearanceProperties":{}}"#, delayMs: 3_000)
                : .init()
        }
        let start = Date()
        context.showForm("slow")
        context.resetUser()
        context.trackEvent("after")
        await waitFor("signed out and the event sent", timeout: 2) { !context.isIdentified && server.count("track-event") == 1 }
        XCTAssertLessThan(Date().timeIntervalSince(start), 2, "held up by the slow form")
        XCTAssertNil(server.calls("track-event")[0].header("X-User-Name"), "the event goes as a visitor")
    }

    func testSigningOutWaitsForASlowSubmitOnlyUpToTheLimit() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        let limit = EncatchContext.submitWaitLimitMs
        EncatchContext.submitWaitLimitMs = 300
        defer { EncatchContext.submitWaitLimitMs = limit }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.isIdentified }
        server.respond { call in
            call.endpoint == "submit-form" ? FakeServer.Reply(delayMs: 3_000) : .init()
        }
        let start = Date()
        context.submitForm(submitRequest())
        context.resetUser()
        await waitFor("signed out", timeout: 2) { !context.isIdentified }
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.5, "waited for the whole submit, not up to the limit")
        XCTAssertEqual(server.calls("submit-form").first?.header("X-User-Name"), "alice", "the submit went as the user who answered")
    }

    func testFormsAreShownInTheOrderAsked() async throws {
        let server = FakeServer()
        server.respond { call in
            call.endpoint == "show-form"
                ? FakeServer.Reply(body: #"{"feedbackConfigurationId":"cfg","appearanceProperties":{}}"#, delayMs: 50)
                : .init()
        }
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        for round in 0..<20 {
            let before = server.count("show-form")
            context.showForm("a\(round)")
            context.showForm("b\(round)")
            await waitFor { server.count("show-form") == before + 2 }
            let ids = server.calls("show-form").suffix(2).map { $0.string("formSlugOrId") ?? "" }
            XCTAssertEqual(ids, ["a\(round)", "b\(round)"], "round \(round)")
        }
    }

    func testALocaleSetAfterSigningOutIsKept() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        for round in 0..<20 {
            context.identifyUser(userName: "u\(round)")
            context.resetUser()
            context.setLocale("fr")
            await waitFor("round \(round)") { !context.isIdentified && sdk.locale == "fr" }
            await sleepMs(20)
            XCTAssertEqual(sdk.locale, "fr", "round \(round): the reset ran after setLocale")
        }
    }

    func testAnotherContextOnTheSameSdkAndKeyDoesNotInitializeAgain() async {
        let logger = RecordingLogger()
        let (sdk, first) = makeContext(FakeServer(), logger: logger)
        defer { sdk.stop() }
        let second = EncatchContext(encatch: sdk)
        // two windows' providers at once, then a third later
        async let a: Void = first.initialize(apiKey: "key", config: config)
        async let b: Void = second.initialize(apiKey: "key", config: config)
        _ = await (a, b)
        await EncatchContext(encatch: sdk).initialize(apiKey: "key", config: config)
        XCTAssertFalse(logger.debugs.contains { $0.contains("reconfiguring") }, "initialized more than once")
        XCTAssertTrue(first.isInitialized && second.isInitialized)
    }

    func testAfterClearAllTheProviderInitializesAgain() async throws {
        let (sdk, context) = makeContext(FakeServer())
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        try await sdk.clearAll()
        await waitFor { !context.isInitialized }
        await context.initialize(apiKey: "key", config: config)
        XCTAssertTrue(sdk.isInitialized)
        XCTAssertTrue(context.isInitialized)
    }

    func testAScreenShownBeforeInitializeIsTrackedOnceInitialized() async {
        let server = FakeServer()
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        let tracking = Task { await context.trackScreenWhenInitialized("Home") }
        await sleepMs(100)
        XCTAssertEqual(server.count("track-screen"), 0)
        await context.initialize(apiKey: "key", config: config)
        await tracking.value
        await waitFor("tracked after initialize") { server.count("track-screen") == 1 }
    }

    func testAScreenThatDisappearsBeforeInitializeIsNotTracked() async {
        let server = FakeServer()
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        let tracking = Task { await context.trackScreenWhenInitialized("Gone") }
        await sleepMs(50)
        tracking.cancel()
        await tracking.value
        await context.initialize(apiKey: "key", config: config)
        await sleepMs(200)
        XCTAssertEqual(server.count("track-screen"), 0)
    }

    func testASwitchReplyWithoutAUserIdKeepsThePreviousName() async throws {
        let server = FakeServer()
        replyIds(server)
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)
        context.identifyUser(userName: "alice")
        await waitFor { context.userName == "alice" }

        server.respond { $0.endpoint == "identify-user" ? FakeServer.Reply(body: "{}") : .init() }
        context.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 2 }
        await sleepMs(300)
        XCTAssertTrue(context.isIdentified)
        XCTAssertEqual(context.userName, "alice", "bob has no confirmed identity yet")
    }

    func testCallsReachTheSdk() async throws {
        let server = FakeServer()
        server.respond { call in
            call.endpoint == "show-form"
                ? FakeServer.Reply(body: #"{"feedbackConfigurationId":"cfg-1","appearanceProperties":{}}"#)
                : .init()
        }
        let (sdk, context) = makeContext(server)
        defer { sdk.stop() }
        await context.initialize(apiKey: "key", config: config)

        context.trackEvent("clicked")
        await waitFor { server.count("track-event") == 1 }
        context.trackScreen("Home")
        await waitFor { server.count("track-screen") == 1 }
        context.showForm("form-1")
        await waitFor { server.count("show-form") == 1 }
        context.setLocale("fr")
        await waitFor { sdk.locale == "fr" }
        context.addToResponse(questionId: "q1", value: "yes")
        await waitFor { sdk.getPendingResponses()["q1"] == .string("yes") }

        var events: [EventType] = []
        let unsubscribe = context.on { type, _ in events.append(type) }
        context.emitEvent(.formShow, EventPayload(formId: "form-1", timestamp: 0))
        XCTAssertEqual(events, [.formShow])
        context.emitEvent(.formClose, formId: "form-1")
        XCTAssertEqual(events, [.formShow, .formClose], "React Native's form, without a timestamp")
        unsubscribe()
        context.emitEvent(.formShow, EventPayload(formId: "form-1", timestamp: 0))
        XCTAssertEqual(events, [.formShow, .formClose], "no event after unsubscribing")
    }
}
