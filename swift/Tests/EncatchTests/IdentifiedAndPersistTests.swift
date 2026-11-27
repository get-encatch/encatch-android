import XCTest
@testable import Encatch

/// RN 1.5.0 items 2 and 4: `isIdentified()` and `EncatchConfig.persistUserAcrossRestarts`
/// (react-native-sdk docs/SDK-1.5.0-CHANGES-FOR-OTHER-SDKS.md). A "restart" is a new SDK instance
/// on the same `UserDefaults` (a new app run: nothing in memory carries over).
final class IdentifiedAndPersistTests: XCTestCase {
    private let secure = IdentifyOptions(secure: SecureOptions(signature: "sig-abc", generatedDateTimeInUtc: "1763500000000"))

    private func replyIds(_ server: FakeServer, delayMs: UInt64 = 0) {
        server.respond { call in
            call.endpoint == "identify-user"
                ? FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#, delayMs: delayMs)
                : .init()
        }
    }

    /// Identifies `name` and waits for the reply to be applied (its id is `uid-<name>`).
    private func identify(_ sdk: Encatch, _ server: FakeServer, _ name: String, options: IdentifyOptions? = nil) async throws {
        replyIds(server)
        let before = server.count("identify-user")
        try await sdk.identifyUser(userName: name, options: options)
        await waitFor("identify of \(name) sent") { server.count("identify-user") > before }
        await waitFor("identify of \(name) applied") { sdk.userName == name && sdk.userId == "uid-\(name)" }
        await sleepMs(30)
    }

    private func userKeys(_ defaults: UserDefaults) -> [String] {
        defaults.dictionaryRepresentation().keys.filter {
            $0 == "@encatch/user_name" || $0.hasPrefix("@encatch/user_id_")
                || $0.hasPrefix("@encatch/user_signature_") || $0.hasPrefix("@encatch/ft_")
        }
    }

    private func trackEvent(_ sdk: Encatch, _ server: FakeServer, _ name: String) async throws -> FakeServer.Call {
        let before = server.count("track-event")
        try await sdk.trackEvent(name)
        await waitFor("track-event \(name)") { server.count("track-event") > before }
        return server.calls("track-event").last!
    }

    // MARK: isIdentified()

    func testNotIdentifiedBeforeInit() {
        XCTAssertFalse(Encatch().isIdentified())
    }

    func testAVisitorIsNotIdentified() async throws {
        let sdk = try await makeTestSDK(server: FakeServer())
        defer { sdk.stop() }
        XCTAssertFalse(sdk.isIdentified())
    }

    func testIdentifiedAfterAnIdentifySucceeds() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        XCTAssertTrue(sdk.isIdentified())
    }

    func testAReturningUserIsIdentifiedAsSoonAsInitFinishes() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let first = try await makeTestSDK(server: server, defaults: defaults)
        try await identify(first, server, "alice")
        first.stop()

        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        XCTAssertTrue(sdk.isIdentified())
        XCTAssertEqual(sdk.userName, "alice")
    }

    func testASavedNameWithoutAnIdIsNotIdentified() async throws {
        let defaults = makeTestDefaults()
        defaults.set("half", forKey: "@encatch/user_name")
        let sdk = try await makeTestSDK(server: FakeServer(), defaults: defaults)
        defer { sdk.stop() }
        XCTAssertFalse(sdk.isIdentified())
    }

    func testANewUserIsNotIdentifiedWhileTheFirstIdentifyIsPending() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        replyIds(server, delayMs: 800)
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 1 }
        XCTAssertFalse(sdk.isIdentified())
        await waitFor { sdk.userId == "uid-alice" }
        XCTAssertTrue(sdk.isIdentified())
    }

    func testANewUserWhoseFirstIdentifyFailedIsNotIdentified() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        server.respond { $0.endpoint == "identify-user" ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#) : .init() }
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 1 }
        await sleepMs(300)
        XCTAssertFalse(sdk.isIdentified())
    }

    func testSwitchingUsersKeepsThePreviousUserUntilTheNewIdentifySucceeds() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        replyIds(server, delayMs: 800)
        try await sdk.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 2 }
        XCTAssertTrue(sdk.isIdentified(), "pending: still alice's confirmed identity")
        await waitFor { sdk.userId == "uid-bob" }
        XCTAssertTrue(sdk.isIdentified())
        XCTAssertEqual(sdk.userName, "bob")
    }

    func testSwitchingUsersKeepsThePreviousUserWhenTheNewIdentifyFails() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        server.respond { $0.endpoint == "identify-user" ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#) : .init() }
        try await sdk.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 2 }
        await sleepMs(300)
        XCTAssertTrue(sdk.isIdentified())
    }

    func testResetAndClearAllEndTheIdentity() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        try await sdk.resetUser()
        XCTAssertFalse(sdk.isIdentified())

        try await identify(sdk, server, "bob")
        XCTAssertTrue(sdk.isIdentified())
        try await sdk.clearAll()
        XCTAssertFalse(sdk.isIdentified())
    }

    func testTheServersRetriesExhaustedResetEndsTheIdentity() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        server.respond { $0.endpoint == "track-event" ? FakeServer.Reply(body: #"{"pingAgainIn":900,"userPendingRetryExhausted":true}"#) : .init() }
        try await sdk.trackEvent("trigger")
        await waitFor { sdk.userName == nil }
        XCTAssertFalse(sdk.isIdentified())
    }

    func testStopSessionAndAnExpiredSignatureLeaveItUnchanged() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: secure)
        try await sdk.stopSession()
        XCTAssertTrue(sdk.isIdentified())
        try await sdk.startSession()
        server.respond { $0.endpoint == "track-event" ? FakeServer.Reply(status: 401, body: #"{"error":"invalid api key","reason":"user_signature_expired"}"#) : .init() }
        try await sdk.trackEvent("expires")
        await waitFor { server.count("track-event") == 1 }
        await sleepMs(300)
        XCTAssertTrue(sdk.isIdentified())
    }

    // MARK: persistUserAcrossRestarts = false

    func testMemoryOnlySavesNothingAboutTheUserButRequestsCarryTheIdentity() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: secure)
        XCTAssertEqual(userKeys(defaults), [])
        XCTAssertTrue(sdk.isIdentified())

        let call = try await trackEvent(sdk, server, "as_alice")
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertEqual(call.header("X-User-Id"), "uid-alice")
        XCTAssertEqual(call.header("X-User-Signature"), "sig-abc")
    }

    func testMemoryOnlyStartsTheNextLaunchAsAVisitor() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let first = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        try await identify(first, server, "alice", options: secure)
        first.stop()

        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { sdk.stop() }
        XCTAssertFalse(sdk.isIdentified())
        XCTAssertNil(sdk.userName)
        let call = try await trackEvent(sdk, server, "after_restart")
        XCTAssertNil(call.header("X-User-Name"))
        XCTAssertNil(call.header("X-User-Id"))
        XCTAssertNil(call.header("X-User-Signature"))
    }

    func testMemoryOnlyDeletesUserDataAnEarlierRunSaved() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let first = try await makeTestSDK(server: server, defaults: defaults)
        try await identify(first, server, "alice", options: secure)
        first.stop()
        XCTAssertFalse(userKeys(defaults).isEmpty)

        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { sdk.stop() }
        XCTAssertEqual(userKeys(defaults), [])
        XCTAssertFalse(sdk.isIdentified())
        XCTAssertNil(sdk.userName)
    }

    func testMemoryOnlyStillSavesTheDeviceIdAStopAndPreferences() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        let deviceId = sdk.deviceId
        try await identify(sdk, server, "alice", options: IdentifyOptions(country: "FR"))
        try await sdk.stopSession()
        sdk.stop()

        XCTAssertEqual(defaults.string(forKey: "@encatch/device_id"), deviceId)
        XCTAssertEqual(defaults.string(forKey: "@encatch/session_stopped"), "true")
        XCTAssertTrue(defaults.string(forKey: "@encatch/preferences")?.contains("FR") == true)

        let next = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { next.stop() }
        XCTAssertEqual(next.deviceId, deviceId)
    }

    func testMemoryOnlyReusesAnEarlierUsersIdWithinTheSameRun() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        try await identify(sdk, server, "bob")
        replyIds(server, delayMs: 800)
        try await sdk.identifyUser(userName: "alice")
        // straight away, before the reply: alice's id from this run
        await waitFor { sdk.userName == "alice" }
        XCTAssertEqual(sdk.userId, "uid-alice")
        XCTAssertEqual(userKeys(defaults), [])
    }

    func testMemoryOnlyClearAllForgetsTheInMemoryUser() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults, persistUserAcrossRestarts: false)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        try await sdk.clearAll()
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test", persistUserAcrossRestarts: false))
        XCTAssertNil(sdk.userName)
        XCTAssertFalse(sdk.isIdentified())
        replyIds(server, delayMs: 800)
        try await sdk.identifyUser(userName: "alice")
        await waitFor { sdk.userName == "alice" }
        XCTAssertNil(sdk.userId, "alice's id from before clearAll is forgotten")
    }

    func testByDefaultTheUserIsRestoredAfterARestart() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        let first = try await makeTestSDK(server: server, defaults: defaults)
        try await identify(first, server, "alice", options: secure)
        first.stop()

        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        XCTAssertTrue(sdk.isIdentified())
        let call = try await trackEvent(sdk, server, "restored")
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertEqual(call.header("X-User-Id"), "uid-alice")
        XCTAssertEqual(call.header("X-User-Signature"), "sig-abc")
    }
}
