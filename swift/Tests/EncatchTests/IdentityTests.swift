import XCTest
@testable import Encatch

/// Pass 4: identity headers, identify, reset/identify ordering, secure mode (RN 1.4.4 items 3, 11–17).
final class IdentityTests: XCTestCase {
    private func identifyReply(_ userId: String) -> FakeServer.Reply {
        FakeServer.Reply(body: #"{"userId":"\#(userId)"}"#)
    }

    /// Identifies `name`, waits for the reply to be applied.
    private func identify(_ sdk: Encatch, _ server: FakeServer, _ name: String, options: IdentifyOptions? = nil, traits: UserTraits? = nil) async throws {
        let before = server.count("identify-user")
        try await sdk.identifyUser(userName: name, traits: traits, options: options)
        await waitFor("identify of \(name) sent") { server.count("identify-user") > before }
        await waitFor("identify of \(name) applied") { sdk.userName == name && sdk.userId != nil }
        await sleepMs(30)
    }

    // MARK: both-or-neither identity headers

    func testVisitorRequestsCarryNoIdentityHeaders() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.trackEvent("visit")
        await waitFor { server.count("track-event") == 1 }
        let call = server.calls("track-event")[0]
        XCTAssertNil(call.header("X-User-Name"))
        XCTAssertNil(call.header("X-User-Id"))
        XCTAssertEqual(call.header("X-Api-Key"), "test-api-key-12345")
    }

    func testHalfIdentifiedDeviceGoesAsVisitorUntilNextIdentify() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        defaults.set("alice", forKey: "@encatch/user_name") // a name without its id (1.4.3 left these)
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await sdk.trackEvent("visit")
        await waitFor { server.count("track-event") == 1 }
        XCTAssertNil(server.calls("track-event")[0].header("X-User-Name"))
        XCTAssertNil(server.calls("track-event")[0].header("X-User-Id"))

        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-alice") : .init() }
        try await identify(sdk, server, "alice")
        let identifyCall = server.calls("identify-user")[0]
        XCTAssertNil(identifyCall.string("userId"), "no id known for alice yet")
        try await sdk.trackEvent("after")
        await waitFor { server.count("track-event") == 2 }
        let after = server.calls("track-event")[1]
        XCTAssertEqual(after.header("X-User-Name"), "alice")
        XCTAssertEqual(after.header("X-User-Id"), "uid-alice")
    }

    func testIdentifyHeaderIsTheCallsNameAndNeverCarriesUserId() async throws {
        let server = FakeServer()
        let defaults = makeTestDefaults()
        defaults.set("alice", forKey: "@encatch/user_name")
        defaults.set("uid-alice", forKey: "@encatch/user_id_alice")
        server.respond { call in
            call.endpoint == "identify-user" ? self.identifyReply("uid-\(call.string("userName") ?? "")") : .init()
        }
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }

        try await identify(sdk, server, "bob")
        var call = server.calls("identify-user")[0]
        XCTAssertEqual(call.header("X-User-Name"), "bob")
        XCTAssertEqual(call.string("userName"), "bob")
        XCTAssertNil(call.header("X-User-Id"), "identify never sends X-User-Id")
        XCTAssertNil(call.string("userId"), "bob's id is not known: alice's must not be sent")

        try await identify(sdk, server, "alice")
        call = server.calls("identify-user")[1]
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertNil(call.header("X-User-Id"))
        XCTAssertEqual(call.string("userId"), "uid-alice", "the id known for that name goes in the body")
    }

    func testIdentifiedRequestsCarryBothHeaders() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        try await sdk.trackScreen("Home")
        await waitFor { server.count("track-screen") == 1 }
        let call = server.calls("track-screen")[0]
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertEqual(call.header("X-User-Id"), "uid-a")
        XCTAssertNil(call.header("X-User-Signature"))
    }

    // MARK: reset / identify ordering

    func testLateIdentifyReplyForAResetUserIsIgnored() async throws {
        let server = FakeServer()
        server.respond { call in
            var reply = call.endpoint == "identify-user" ? self.identifyReply("uid-a") : FakeServer.Reply()
            if call.endpoint == "identify-user" { reply.delayMs = 300 }
            return reply
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 1 }
        try await sdk.resetUser()
        await sleepMs(500)
        XCTAssertNil(sdk.userName)
        XCTAssertNil(sdk.userId, "the late reply must not bring alice back")
        try await sdk.trackEvent("after-reset")
        await waitFor { server.count("track-event") == 1 }
        XCTAssertNil(server.calls("track-event")[0].header("X-User-Name"))
    }

    func testResetThenIdentifyWithAnOlderReplyStillInFlight() async throws {
        let server = FakeServer()
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            let name = call.string("userName") ?? ""
            var reply = self.identifyReply("uid-\(name)")
            if name == "alice" { reply.delayMs = 300 } // alice's reply comes back last
            return reply
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 1 }
        try await sdk.resetUser()
        try await sdk.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 2 }
        await sleepMs(500)
        XCTAssertEqual(sdk.userName, "bob")
        XCTAssertEqual(sdk.userId, "uid-bob", "alice's late reply must not replace bob")
        XCTAssertTrue(server.calls("identify-user").allSatisfy { $0.header("X-User-Name") == $0.string("userName") })
        XCTAssertTrue(server.calls("identify-user").allSatisfy { $0.header("X-User-Id") == nil })
    }

    func testSerialOpsRunInCallOrderWithoutOverlap() async {
        let ops = SerialOps()
        let log = LockedLog()
        await withTaskGroup(of: Void.self) { group in
            for index in 0..<5 {
                group.addTask {
                    await ops.run {
                        log.append("start \(index)")
                        await sleepMs(index == 0 ? 80 : 5)
                        log.append("end \(index)")
                    }
                }
                await sleepMs(5) // enqueue in index order
            }
        }
        let expected = (0..<5).flatMap { ["start \($0)", "end \($0)"] }
        XCTAssertEqual(log.entries, expected)
    }

    // MARK: userPendingRetryExhausted

    func testUserPendingRetryExhaustedCamelCaseResetsTheUser() async throws {
        try await assertExhaustedResets(flag: "userPendingRetryExhausted")
    }

    func testUserPendingRetryExhaustedSnakeCaseResetsTheUser() async throws {
        try await assertExhaustedResets(flag: "user_pending_retry_exhausted")
    }

    private func assertExhaustedResets(flag: String) async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "identify-user": return self.identifyReply("uid-a")
            case "track-event": return .init(body: #"{"pingAgainIn":900,"\#(flag)":true}"#)
            default: return .init()
            }
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice")
        try await sdk.trackEvent("e")
        await waitFor("user reset") { sdk.userName == nil && sdk.userId == nil }
        let pings = server.count("ping")
        await sleepMs(200)
        XCTAssertEqual(server.count("ping"), pings, "pings stopped")
    }

    // MARK: secure identify

    func testSecureIdentifySendsSignatureHeadersAndBodyField() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        let secure = SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000")
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: secure))
        let call = server.calls("identify-user")[0]
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertEqual(call.header("X-User-Signature"), "sig-1")
        XCTAssertEqual(call.header("X-User-Session-Time"), "1763500000000")
        XCTAssertEqual(call.header("X-User-Signature-Time"), "1763500000000")
        XCTAssertEqual(call.string("userSignature"), "sig-1")
        XCTAssertNil(call.header("X-User-Id"))
    }

    func testSignatureGoesOnEveryRequestOfThatUserAndSurvivesARestart() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000")))
        try await sdk.trackEvent("e")
        await waitFor { server.count("track-event") == 1 }
        var call = server.calls("track-event")[0]
        XCTAssertEqual(call.header("X-User-Name"), "alice")
        XCTAssertEqual(call.header("X-User-Id"), "uid-a")
        XCTAssertEqual(call.header("X-User-Signature"), "sig-1")
        XCTAssertEqual(call.header("X-User-Session-Time"), "1763500000000")
        XCTAssertEqual(call.header("X-User-Signature-Time"), "1763500000000")

        // a restart: the same storage, a new instance
        let restarted = try await makeTestSDK(server: server, defaults: defaults)
        defer { restarted.stop() }
        try await restarted.trackScreen("Home")
        await waitFor { server.count("track-screen") == 1 }
        call = server.calls("track-screen")[0]
        XCTAssertEqual(call.header("X-User-Signature"), "sig-1")
        XCTAssertEqual(call.header("X-User-Session-Time"), "1763500000000")
    }

    func testIdentifyWithoutSecureClearsTheSignature() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1")))
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 2 }
        await sleepMs(100)
        XCTAssertNil(server.calls("identify-user")[1].header("X-User-Signature"))
        try await sdk.trackEvent("e")
        await waitFor { server.count("track-event") == 1 }
        XCTAssertNil(server.calls("track-event")[0].header("X-User-Signature"))
        XCTAssertNil(defaults.string(forKey: "@encatch/user_signature_alice"))
    }

    func testResetClearsTheSignatureAndItNeverGoesOnVisitorRequests() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1")))
        try await sdk.resetUser()
        try await sdk.trackEvent("visitor")
        await waitFor { server.count("track-event") == 1 }
        let call = server.calls("track-event")[0]
        XCTAssertNil(call.header("X-User-Signature"))
        XCTAssertNil(call.header("X-User-Name"))
        XCTAssertNil(defaults.string(forKey: "@encatch/user_signature_alice"))
    }

    func testUnsuccessfulSecureIdentifyStoresNoSignature() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? .init(status: 401, body: "bad signature") : .init() }
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(secure: SecureOptions(signature: "bad")))
        await waitFor { server.count("identify-user") == 1 }
        await sleepMs(100)
        XCTAssertNil(defaults.string(forKey: "@encatch/user_signature_alice"))
        XCTAssertFalse(sdk.isSignatureExpired, "a rejected identify is not an expiry")
    }

    // MARK: expiry

    func testA401OnATimedSignatureExpiresItPausesBackgroundTrafficAndHoldsSubmits() async throws {
        let server = FakeServer()
        let expired = LockedFlag()
        server.respond { call in
            switch call.endpoint {
            case "identify-user": return self.identifyReply("uid-a")
            default:
                if call.header("X-User-Signature") == "sig-1" && expired.value { return .init(status: 401, body: "expired") }
                return .init()
            }
        }
        let logger = RecordingLogger()
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults, logger: logger)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000")))
        expired.value = true

        try await sdk.trackEvent("first")
        await waitFor("expiry") { sdk.isSignatureExpired }
        XCTAssertNil(defaults.string(forKey: "@encatch/user_signature_alice"), "the stored signature is dropped")

        // background traffic paused; a survey submit is held
        try await sdk.trackEvent("second")
        try await sdk.trackScreen("Home")
        await sdk.trackFormEvent("form:started", "f1")
        try await sdk.submitForm(submitRequest())
        await sleepMs(200)
        XCTAssertEqual(server.count("track-event"), 1)
        XCTAssertEqual(server.count("track-screen"), 0)
        XCTAssertEqual(server.count("submit-form"), 0)
        XCTAssertEqual(logger.warnings.filter { $0.contains("signature expired") }.count, 1, "one warning")

        // the next successful identify ends it and sends the held submit
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-2", generatedDateTimeInUtc: "1763600000000")))
        await waitFor("held submit sent") { server.count("submit-form") == 1 }
        XCTAssertFalse(sdk.isSignatureExpired)
        XCTAssertEqual(server.calls("submit-form")[0].header("X-User-Signature"), "sig-2")
        try await sdk.trackEvent("third")
        await waitFor { server.count("track-event") == 2 }
    }

    func testA401WithoutASignatureTimeIsNotAnExpiry() async throws {
        let server = FakeServer()
        server.respond { call in
            call.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init(status: 401, body: "nope")
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1")))
        try await sdk.trackEvent("e")
        await waitFor { server.count("track-event") == 1 }
        await sleepMs(100)
        XCTAssertFalse(sdk.isSignatureExpired)
    }

    func testALate401ForAnOlderSignatureDoesNotUndoAFreshSecureIdentify() async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "identify-user": return self.identifyReply("uid-a")
            case "submit-form" where call.header("X-User-Signature") == "sig-1":
                return .init(status: 401, body: "expired", delayMs: 300) // answers after sig-2 is in place
            default: return .init()
            }
        }
        let defaults = makeTestDefaults()
        let sdk = try await makeTestSDK(server: server, defaults: defaults)
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000")))
        let submit = Task { try await sdk.submitForm(submitRequest()) }
        await waitFor { server.count("submit-form") == 1 }
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-2", generatedDateTimeInUtc: "1763600000000")))
        await waitFor("sig-2 stored") { defaults.string(forKey: "@encatch/user_signature_alice")?.contains("sig-2") == true }
        try await submit.value
        await sleepMs(100)
        XCTAssertFalse(sdk.isSignatureExpired, "the 401 was for sig-1, not the current sig-2")
        XCTAssertTrue(defaults.string(forKey: "@encatch/user_signature_alice")?.contains("sig-2") == true)
        try await sdk.trackEvent("e")
        await waitFor { server.count("track-event") == 1 }
        XCTAssertEqual(server.calls("track-event")[0].header("X-User-Signature"), "sig-2")
    }

    func testAfterAnExpiryTheSameSignedIdentifyIsSentAgain() async throws {
        let server = FakeServer()
        server.respond { call in
            call.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init(status: 401, body: "expired")
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        let options = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
        try await identify(sdk, server, "alice", options: options)
        try await sdk.trackEvent("e")
        await waitFor("expiry") { sdk.isSignatureExpired }
        try await sdk.identifyUser(userName: "alice", options: options)
        await waitFor("the same signed identify re-sent") { server.count("identify-user") == 2 }
    }

    func testReinitializeForgetsIdentifyExpiryAndCollapseMemory() async throws {
        let server = FakeServer()
        server.respond { call in
            call.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init(status: 401, body: "expired")
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        let options = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
        let traits = UserTraits(set: ["plan": .string("pro")])
        try await identify(sdk, server, "alice", options: options, traits: traits)
        try await sdk.trackEvent("e")
        await waitFor("expiry") { sdk.isSignatureExpired }
        try await sdk.submitForm(submitRequest()) // held

        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        try await sdk.initialize(apiKey: "another-key-67890", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        XCTAssertFalse(sdk.isSignatureExpired)
        try await sdk.trackEvent("e") // not collapsed against the event before re-init
        await waitFor { server.count("track-event") == 2 }
        XCTAssertEqual(server.calls("track-event")[1].header("X-Api-Key"), "another-key-67890")
        try await sdk.identifyUser(userName: "alice", traits: traits, options: options)
        await waitFor("same identify sent after re-init") { server.count("identify-user") == 2 }
        await sleepMs(150)
        XCTAssertEqual(server.count("submit-form"), 0, "submits held under the old key are not sent with the new one")
    }

    func testAQueuedIdentifyOfThePreviousUserIsDroppedOnResetAndOnASwitch() async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "track-screen": return .init(delayMs: 200) // keeps the queue busy
            case "identify-user": return self.identifyReply("uid-\(call.string("userName") ?? "")")
            default: return .init()
            }
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        // a switch: alice's queued identify never goes out after bob's
        try await sdk.trackScreen("Home")
        await waitFor { server.count("track-screen") == 1 }
        try await sdk.identifyUser(userName: "alice")
        try await sdk.identifyUser(userName: "bob")
        await waitFor { server.count("identify-user") == 1 }
        await sleepMs(150)
        XCTAssertEqual(server.calls("identify-user").map { $0.string("userName") }, ["bob"])
        await waitFor { sdk.userId == "uid-bob" }

        // a reset: carol's queued identify is dropped
        try await sdk.trackScreen("Cart")
        await waitFor { server.count("track-screen") == 2 }
        try await sdk.identifyUser(userName: "carol")
        try await sdk.resetUser()
        await sleepMs(400)
        XCTAssertEqual(server.count("identify-user"), 1)
        XCTAssertNil(sdk.userName)
    }

    // MARK: masking

    func testSignatureIsMaskedInDebugAndNetworkLogs() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let logger = RecordingLogger()
        let entries = LockedLog()
        let sdk = try await makeTestSDK(server: server, logger: logger, debugMode: true) { sdk in
            sdk.onNetworkLog = { entry in
                entries.append(entry.requestHeaders.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ";") + " " + entry.requestBody)
            }
        }
        defer { sdk.stop() }
        try await identify(sdk, server, "alice", options: IdentifyOptions(secure: SecureOptions(signature: "secret-sig-value", generatedDateTimeInUtc: "1763500000000")))
        try await sdk.trackEvent("e")
        await waitFor { server.count("track-event") == 1 }
        await waitFor { entries.entries.count >= 2 }

        XCTAssertEqual(server.calls("track-event")[0].header("X-User-Signature"), "secret-sig-value", "sent unmasked")
        let everything = (logger.debugs + logger.warnings + entries.entries).joined(separator: "\n")
        XCTAssertFalse(everything.contains("secret-sig-value"), "signature leaked into a log")
        XCTAssertFalse(everything.contains("test-api-key-12345"), "API key leaked into a log")
        XCTAssertTrue(entries.entries.contains { $0.contains("X-User-Signature=***") })
        XCTAssertTrue(entries.entries.contains { $0.contains(#""userSignature":"***""#) })
        XCTAssertTrue(logger.debugs.contains { $0.contains("X-User-Signature: ***") })
    }

    // MARK: identify dedupe

    func testIdenticalIdentifyIsSentOnce() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? self.identifyReply("uid-a") : .init() }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        let traits = UserTraits(set: ["plan": .string("pro")])
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", traits: traits, options: IdentifyOptions(locale: "en")) }
        await waitFor { server.count("identify-user") == 1 }
        await sleepMs(150)
        XCTAssertEqual(server.count("identify-user"), 1)

        // anything different is sent
        try await sdk.identifyUser(userName: "alice", traits: UserTraits(set: ["plan": .string("free")]), options: IdentifyOptions(locale: "en"))
        try await sdk.identifyUser(userName: "alice", traits: UserTraits(set: ["plan": .string("free")]), options: IdentifyOptions(locale: "fr"))
        try await sdk.identifyUser(userName: "alice", traits: UserTraits(set: ["plan": .string("free")]), options: IdentifyOptions(locale: "fr", secure: SecureOptions(signature: "s")))
        await waitFor { server.count("identify-user") == 4 }

        // after a reset the same identify goes again
        try await sdk.resetUser()
        try await sdk.identifyUser(userName: "alice", traits: UserTraits(set: ["plan": .string("free")]), options: IdentifyOptions(locale: "fr", secure: SecureOptions(signature: "s")))
        await waitFor { server.count("identify-user") == 5 }
    }

    func testAFailedIdentifyCanBeSentAgain() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? .init(status: 400, body: "bad") : .init() }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.identifyUser(userName: "alice")
        await waitFor { server.count("identify-user") == 1 }
        await sleepMs(100)
        try await sdk.identifyUser(userName: "alice")
        await waitFor("identify re-sent after a drop") { server.count("identify-user") == 2 }
    }
}

final class LockedLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func append(_ entry: String) {
        lock.lock()
        items.append(entry)
        lock.unlock()
    }
    var entries: [String] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return flag }
        set { lock.lock(); flag = newValue; lock.unlock() }
    }
}
