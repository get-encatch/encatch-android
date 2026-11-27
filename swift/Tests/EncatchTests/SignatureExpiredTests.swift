import XCTest
@testable import Encatch

/// EncatchConfig.onSignatureExpired and the narrowed expiry rules, as the React Native SDK
/// 1.5.0-beta.3 (docs/HANDOVER-ON-SIGNATURE-EXPIRED.md, §8 cases 1–22). The fake gateway answers
/// identify-user by the signature it carries, as the user gateway does. Mirrors the Kotlin
/// `SignatureExpiredTest`.
final class SignatureExpiredTests: XCTestCase {
    private final class Locked<T>: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: T
        init(_ value: T) { stored = value }
        var value: T {
            get { lock.lock(); defer { lock.unlock() }; return stored }
            set { lock.lock(); stored = newValue; lock.unlock() }
        }
    }

    /// Signatures the gateway rejects as expired, and as wrong (no reason).
    private let expired = Locked<Set<String>>([])
    private let wrong = Locked<Set<String>>([])
    /// An older gateway: no reason in any 401.
    private let oldGateway = Locked(false)
    /// An SV8-style gateway: the signature is checked on every request, not only identify-user.
    private let checksEveryRequest = Locked(false)
    /// A 401 without the reason on track-event (a key or referer problem).
    private let trackEventRejected = Locked(false)
    /// Signatures whose rejected replies are slow, and a delay on every rejected reply.
    private let slowSignatures = Locked<Set<String>>([])
    private let rejectDelayMs = Locked<UInt64>(0)

    private let notified = Locked<[String]>([])

    private var server: FakeServer!
    private var logger: RecordingLogger!
    private var defaults: UserDefaults!
    private var sdk: Encatch!

    override func setUp() async throws {
        server = FakeServer()
        logger = RecordingLogger()
        defaults = makeTestDefaults()
        sdk = Encatch()
        sdk.transportForTesting = server.transport
        sdk.storageDefaultsForTesting = defaults
        sdk.loggerForTesting = logger
        sdk.timings.retryBackoffBaseMs = 200
        server.respond { [self] call in gateway(call) }
    }

    override func tearDown() async throws {
        sdk.stop()
        try await sdk.clearAll()
    }

    private func rejected(reason: Bool, signature: String?) -> FakeServer.Reply {
        let body = reason && !oldGateway.value
            ? #"{"error":"invalid api key","reason":"user_signature_expired"}"#
            : #"{"error":"invalid api key"}"#
        let delay = signature.map { slowSignatures.value.contains($0) } == true ? 1000 : rejectDelayMs.value
        return FakeServer.Reply(status: 401, body: body, delayMs: delay)
    }

    private func gateway(_ call: FakeServer.Call) -> FakeServer.Reply {
        let signature = call.header("X-User-Signature")
        if call.endpoint == "identify-user" {
            if let signature, expired.value.contains(signature) { return rejected(reason: true, signature: signature) }
            if let signature, wrong.value.contains(signature) { return rejected(reason: false, signature: signature) }
            return FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#)
        }
        if checksEveryRequest.value, let signature, expired.value.contains(signature) {
            return rejected(reason: true, signature: signature)
        }
        if call.endpoint == "track-event" && trackEventRejected.value { return rejected(reason: false, signature: signature) }
        return .init()
    }

    /// initialize with a callback that records the user and then runs `then`.
    private func start(configured: Bool = true, then: (@Sendable (String) async throws -> Void)? = nil) async throws {
        let notified = notified
        var listener: SignatureExpiredListener?
        if configured {
            listener = { @Sendable (info: SignatureExpiredInfo) async throws in
                notified.value.append(info.userName)
                try await then?(info.userName)
            }
        }
        var config = EncatchConfig(apiBaseUrl: "https://api.test")
        config.onSignatureExpired = listener
        try await sdk.initialize(apiKey: "test-api-key-12345", config: config)
    }

    private func secure(_ signature: String, time: String? = "1763500000000") -> IdentifyOptions {
        IdentifyOptions(secure: SecureOptions(signature: signature, generatedDateTimeInUtc: time))
    }

    private func identify(_ name: String, _ signature: String? = nil, traits: UserTraits? = nil, time: String? = "1763500000000") async throws {
        let before = server.count("identify-user")
        try await sdk.identifyUser(userName: name, traits: traits, options: signature.map { secure($0, time: time) })
        await waitFor("identify of \(name) sent") { self.server.count("identify-user") > before }
        await sleepMs(200)
    }

    private func update(_ name: String = "alice") async throws {
        let before = server.count("identify-user")
        try await sdk.setUserProperties(userName: name, traits: UserTraits(set: ["plan": .string("pro")]))
        await waitFor("update sent") { self.server.count("identify-user") > before }
        await sleepMs(200)
    }

    private func eventSent(_ name: String) async throws -> Bool {
        let before = server.count("track-event")
        try await sdk.trackEvent(name)
        await sleepMs(300)
        return server.count("track-event") > before
    }

    private func submit() async throws { try await sdk.submitForm(submitRequest()) }

    private var expiryWarnings: [String] {
        logger.warnings.filter { $0.contains("the secure signature has expired") || $0.contains("Secure identify signature expired") }
    }

    private func storedSignature(_ name: String = "alice") -> String? { defaults.string(forKey: "@encatch/user_signature_\(name)") }

    // 1
    func testAStoredSignatureFoundExpiredBySetUserPropertiesCallsOnceAndHoldsSubmitsUntilAFreshIdentify() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        try await update()
        await waitFor { self.notified.value == ["alice"] }
        let sent = try await eventSent("skipped")
        XCTAssertFalse(sent, "the expired state skips events")
        try await submit()
        await sleepMs(200)
        XCTAssertEqual(server.count("submit-form"), 0, "held")
        try await identify("alice", "sig-2")
        await waitFor { self.server.count("submit-form") == 1 }
        XCTAssertEqual(server.calls("submit-form")[0].header("X-User-Signature"), "sig-2")
        await sleepMs(200)
        XCTAssertEqual(notified.value, ["alice"], "no second call")
    }

    // 2
    func testIdentifyWithAnAlreadyExpiredSignatureCallsOnceWithoutTheExpiredState() async throws {
        try await start()
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value == ["alice"] }
        let sent = try await eventSent("still-sent")
        XCTAssertTrue(sent, "not in the expired state")
        XCTAssertEqual(expiryWarnings.count, 1, "\(expiryWarnings)")
    }

    // 3
    func testAStaleSignatureForTheSameUserLeavesTheValidStoredOne() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-stale"]
        try await identify("alice", "sig-stale")
        await waitFor { self.notified.value == ["alice"] }
        XCTAssertTrue(storedSignature()?.contains("sig-1") == true, "storage unchanged")
        let sent = try await eventSent("after")
        XCTAssertTrue(sent)
        XCTAssertEqual(server.calls("track-event").last?.header("X-User-Signature"), "sig-1", "memory unchanged")
    }

    // 4
    func testASwitchWithAStaleSignatureCallsForTheNewUserAndTrackingGoesOn() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-bob-stale"]
        try await identify("bob", "sig-bob-stale")
        await waitFor { self.notified.value == ["bob"] }
        let sent = try await eventSent("after")
        XCTAssertTrue(sent)
    }

    // 5
    func testANewSignatureThatKeepsBeingRejectedCallsOnceThenAgainAfterASuccess() async throws {
        let sdk = sdk!
        try await start { name in try await sdk.identifyUser(userName: name, options: IdentifyOptions(secure: SecureOptions(signature: "sig-bad-again", generatedDateTimeInUtc: "1763500000000"))) }
        expired.value = ["sig-bad", "sig-bad-again"]
        try await identify("alice", "sig-bad")
        await waitFor { self.notified.value.count == 1 }
        await sleepMs(800)
        XCTAssertEqual(notified.value, ["alice"], "no loop")
        XCTAssertEqual(server.count("identify-user"), 2, "the callback's identify, then nothing more")
        try await identify("alice", "sig-good")
        expired.value.insert("sig-good")
        try await update()
        await waitFor { self.notified.value.count == 2 }
    }

    // 6
    func testTheAppIdentifyingAgainFromTheCallbackAfterADelaySendsTheHeldSubmitAndEndsTheState() async throws {
        let sdk = sdk!
        try await start { name in
            try await Task.sleep(nanoseconds: 800_000_000) // a fetch from the app's server
            try await sdk.identifyUser(userName: name, options: IdentifyOptions(secure: SecureOptions(signature: "sig-fresh", generatedDateTimeInUtc: "1763500000000")))
        }
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        try await update()
        await waitFor { self.notified.value.count == 1 } // the app is fetching a fresh signature
        try await submit()
        XCTAssertEqual(server.count("submit-form"), 0, "held while the app fetches")
        await waitFor { self.server.count("submit-form") == 1 }
        XCTAssertEqual(server.calls("submit-form")[0].header("X-User-Signature"), "sig-fresh")
        let sent = try await eventSent("state-ended")
        XCTAssertTrue(sent)
    }

    // 7
    func testCalledOncePerExpiry() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1", "sig-2"]
        try await update()
        await waitFor { self.notified.value.count == 1 }
        try await identify("alice", "sig-2") // a fresh signature still rejected
        await sleepMs(300)
        XCTAssertEqual(notified.value.count, 1)
        try await identify("alice", "sig-3")
        expired.value.insert("sig-3")
        try await update()
        await waitFor { self.notified.value.count == 2 }
    }

    // 8
    func testAnOlderGatewayWithoutTheReasonStillCountsOnSetUserProperties() async throws {
        try await start()
        try await identify("alice", "sig-1")
        oldGateway.value = true
        expired.value = ["sig-1"]
        try await update()
        await waitFor { self.notified.value == ["alice"] }
        let sent = try await eventSent("skipped")
        XCTAssertFalse(sent, "the expired state")
    }

    // 9
    func testA401WithoutTheReasonOnAnotherRequestIsNotAnExpiry() async throws {
        try await start()
        try await identify("alice", "sig-1")
        trackEventRejected.value = true
        try await sdk.trackEvent("rejected")
        await waitFor { self.server.count("track-event") == 1 }
        await sleepMs(200)
        trackEventRejected.value = false
        let sent = try await eventSent("next")
        XCTAssertTrue(sent)
        XCTAssertEqual(server.calls("track-event").last?.header("X-User-Signature"), "sig-1")
        XCTAssertTrue(notified.value.isEmpty)
        XCTAssertTrue(storedSignature()?.contains("sig-1") == true)
    }

    // 10
    func testAnIdentifyRejectedWithoutTheReasonIsAWrongSignature() async throws {
        try await start()
        wrong.value = ["sig-wrong"]
        try await identify("alice", "sig-wrong")
        await sleepMs(200)
        XCTAssertTrue(notified.value.isEmpty)
        XCTAssertTrue(expiryWarnings.isEmpty)
        let sent = try await eventSent("after")
        XCTAssertTrue(sent)
    }

    // 11
    func testTheReasonOnANonIdentifyRequestCounts() async throws {
        try await start()
        try await identify("alice", "sig-1")
        checksEveryRequest.value = true
        expired.value = ["sig-1"]
        try await sdk.trackEvent("rejected")
        await waitFor { self.notified.value == ["alice"] }
        await sleepMs(200)
        checksEveryRequest.value = false
        let sent = try await eventSent("skipped")
        XCTAssertFalse(sent, "the expired state")
    }

    // 12
    func testAnExpiryReplyForAUserResetSinceIsIgnored() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        rejectDelayMs.value = 400
        let sdk = sdk!
        Task { try? await sdk.setUserProperties(userName: "alice", traits: UserTraits(set: ["plan": .string("pro")])) }
        await waitFor { self.server.count("identify-user") == 2 }
        try await sdk.resetUser()
        await sleepMs(800)
        XCTAssertTrue(notified.value.isEmpty)
        let sent = try await eventSent("as-visitor")
        XCTAssertTrue(sent)
    }

    // 13
    func testALateExpiryReplyForAReplacedSignatureIsIgnored() async throws {
        try await start()
        try await identify("alice", "sig-1")
        checksEveryRequest.value = true
        expired.value = ["sig-1"]
        slowSignatures.value = ["sig-1"]
        let sdk = sdk!
        Task { await sdk.trackFormEvent("form:show", "f-1") } // not queued
        await waitFor { self.server.count("track-event") == 1 }
        try await identify("alice", "sig-2")
        await sleepMs(1200) // the late 401 for sig-1 has arrived
        XCTAssertTrue(notified.value.isEmpty)
        let sent = try await eventSent("after")
        XCTAssertTrue(sent)
        XCTAssertEqual(server.calls("track-event").last?.header("X-User-Signature"), "sig-2")
    }

    // 14
    func testClearAllWhileAnExpiryReplyIsOnItsWayMeansNoCall() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        rejectDelayMs.value = 400
        let sdk = sdk!
        Task { try? await sdk.setUserProperties(userName: "alice", traits: UserTraits(set: ["plan": .string("pro")])) }
        await waitFor { self.server.count("identify-user") == 2 }
        try await sdk.clearAll()
        await sleepMs(800)
        XCTAssertTrue(notified.value.isEmpty)
    }

    // 15
    func testACallbackThatThrowsLeavesTheSdkWorking() async throws {
        struct AppBug: Error {}
        try await start { _ in throw AppBug() }
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 1 }
        await sleepMs(200)
        try await identify("alice", "sig-good")
        await waitFor { self.sdk.userId == "uid-alice" }
        XCTAssertTrue(logger.warnings.contains { $0.contains("onSignatureExpired threw") })
    }

    // 16
    func testWithoutACallbackItBehavesAsBefore() async throws {
        try await start(configured: false)
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        try await update()
        await sleepMs(200)
        XCTAssertEqual(expiryWarnings.count, 1, "\(expiryWarnings)")
        let skipped = try await eventSent("skipped")
        XCTAssertFalse(skipped, "waits for a fresh identify")
        try await identify("alice", "sig-2")
        let sent = try await eventSent("after")
        XCTAssertTrue(sent)
    }

    // 17
    func testToldAboutAStaleNewSignatureThenTheStoredOneExpiresToldAgain() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-stale"]
        try await identify("alice", "sig-stale")
        await waitFor { self.notified.value.count == 1 }
        expired.value = ["sig-stale", "sig-1"]
        try await update()
        await waitFor { self.notified.value.count == 2 }
        let sent = try await eventSent("skipped")
        XCTAssertFalse(sent, "the expired state, never entered silently")
    }

    // 18
    func testAnIdentifyResendingTheStoredSignatureRejectedAsExpiredEntersTheExpiredState() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        try await identify("alice", "sig-1", traits: UserTraits(set: ["plan": .string("team")]))
        await waitFor { self.notified.value == ["alice"] }
        XCTAssertNil(storedSignature(), "the stored signature is cleared")
        let sent = try await eventSent("skipped")
        XCTAssertFalse(sent, "the expired state")
    }

    // 19
    func testResetUserLetsItBeCalledAgainForTheSameUser() async throws {
        try await start()
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 1 }
        try await sdk.resetUser()
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 2 }
    }

    // 20
    func testClearAllRemovesTheCallbackAndResetsTheOnceOnlyRule() async throws {
        try await start()
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 1 }
        try await sdk.clearAll()
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        try await identify("alice", "sig-old")
        await sleepMs(300)
        XCTAssertEqual(notified.value.count, 1, "init without the option: not called")
        try await sdk.clearAll()
        try await start()
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 2 }
    }

    // 21
    func testStopBetweenTheReplyAndTheDeferredCallMeansNoCall() async throws {
        try await start()
        sdk.timings.signatureExpiredCallDelayMs = 500
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old") // the reply is handled; the call waits
        sdk.stop()
        await sleepMs(700)
        XCTAssertTrue(notified.value.isEmpty)
    }

    // 22
    func testWithAKeyWithoutASessionTimeA401WithoutTheReasonOnSetUserPropertiesIsNotAnExpiry() async throws {
        try await start()
        try await identify("alice", "sig-1", time: nil)
        oldGateway.value = true
        expired.value = ["sig-1"]
        try await update()
        await sleepMs(200)
        XCTAssertTrue(notified.value.isEmpty)
        let sent = try await eventSent("still-sent")
        XCTAssertTrue(sent)
    }

    func testAnIdentifyExpiryReplyAfterAResetIsIgnoredWithNoWarning() async throws {
        try await start()
        expired.value = ["sig-old"]
        rejectDelayMs.value = 400
        let sdk = sdk!
        Task { try? await sdk.identifyUser(userName: "alice", options: IdentifyOptions(secure: SecureOptions(signature: "sig-old", generatedDateTimeInUtc: "1763500000000"))) }
        await waitFor { self.server.count("identify-user") == 1 }
        try await sdk.resetUser()
        await sleepMs(800)
        XCTAssertTrue(notified.value.isEmpty)
        XCTAssertTrue(expiryWarnings.isEmpty, "\(expiryWarnings)")
    }

    func testAfterASuccessfulIdentifyARejectedNewSignatureIsToldAgain() async throws {
        try await start()
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 1 }
        try await identify("alice", "sig-good")
        try await identify("alice", "sig-old")
        await waitFor { self.notified.value.count == 2 }
    }

    func testTheDeferredCallStillComesWhenNothingChangesMeanwhile() async throws {
        try await start()
        sdk.timings.signatureExpiredCallDelayMs = 1500
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        XCTAssertTrue(notified.value.isEmpty, "not yet")
        await waitFor { self.notified.value == ["alice"] }
    }

    func testASwitchOrAReInitBeforeTheDeferredCallMeansNoCall() async throws {
        try await start()
        sdk.timings.signatureExpiredCallDelayMs = 1500
        expired.value = ["sig-old"]
        try await identify("alice", "sig-old")
        try await identify("bob")
        await sleepMs(1600)
        XCTAssertTrue(notified.value.isEmpty, "another user is current")
        try await sdk.resetUser()
        try await identify("alice", "sig-old")
        try await start() // re-init: the same user is restored
        await sleepMs(1600)
        XCTAssertTrue(notified.value.isEmpty, "re-initialized meanwhile")
    }

    func testASubmitRejectedAsExpiredIsHeldAndSentAfterAFreshIdentify() async throws {
        try await start()
        try await identify("alice", "sig-1")
        checksEveryRequest.value = true
        expired.value = ["sig-1"]
        try await submit()
        await waitFor { self.notified.value == ["alice"] }
        XCTAssertEqual(server.count("submit-form"), 1)
        try await identify("alice", "sig-2")
        await waitFor { self.server.count("submit-form") == 2 }
        XCTAssertEqual(server.calls("submit-form")[1].header("X-User-Signature"), "sig-2")
    }

    func testASubmitWhose401ArrivesAfterASwitchIsNeverHeldForTheNextUser() async throws {
        try await start()
        try await identify("alice", "sig-a")
        checksEveryRequest.value = true
        expired.value = ["sig-a"]
        slowSignatures.value = ["sig-a"]
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest()) } // alice's answers, rejected late
        await waitFor { self.server.count("submit-form") == 1 }
        try await identify("bob", "sig-b")
        expired.value = ["sig-a", "sig-b"]
        try await sdk.trackEvent("bob-expires") // bob's signature expires too: the expired state
        await waitFor { self.notified.value.contains("bob") }
        await waitFor("alice's late 401") { self.server.calls("submit-form").count == 1 && self.logger.warnings.contains { $0.contains("no longer the current user") } }
        try await identify("bob", "sig-b2")
        await sleepMs(400)
        XCTAssertEqual(server.count("submit-form"), 1, "alice's answers are never sent as bob")
        XCTAssertTrue(logger.warnings.contains { $0.contains("no longer the current user") })
    }

    func testATrailingClosureStillMeansOnBeforeShowForm() {
        let blocking = EncatchConfig(apiBaseUrl: "https://api.test") { _ in false }
        XCTAssertNotNil(blocking.onBeforeShowForm)
        XCTAssertNil(blocking.onSignatureExpired)
        let bare = EncatchConfig { _ in false }
        XCTAssertNotNil(bare.onBeforeShowForm)
        XCTAssertNil(bare.onSignatureExpired)
        let labeled = EncatchConfig(apiBaseUrl: "https://api.test", onSignatureExpired: { _ in })
        XCTAssertNil(labeled.onBeforeShowForm)
        XCTAssertNotNil(labeled.onSignatureExpired)
    }

    func testTheReasonIsReadOnlyAsAString() {
        XCTAssertEqual(errorReason(JSONValue.parse(#"{"error":"invalid api key","reason":"user_signature_expired"}"#)), "user_signature_expired")
        XCTAssertNil(errorReason(JSONValue.parse(#"{"reason":42}"#)))
        XCTAssertNil(errorReason(JSONValue.parse(#"{"reason":null}"#)))
        XCTAssertNil(errorReason(JSONValue.parse("not json")))
        XCTAssertNil(errorReason(JSONValue.parse(#"{"error":{"reason":"user_signature_expired"}}"#)))
    }

    func testAfterASwitchTheNewUsersSubmitIsSentNotHeld() async throws {
        try await start()
        try await identify("alice", "sig-1")
        expired.value = ["sig-1"]
        try await update()
        await waitFor { self.notified.value == ["alice"] }
        try await identify("bob")
        try await submit()
        await waitFor { self.server.count("submit-form") == 1 }
        XCTAssertEqual(server.calls("submit-form")[0].header("X-User-Name"), "bob")
    }

    func testASubmitWaitingOutA429IsNotSentAsAUserIdentifiedMeanwhile() async throws {
        try await start()
        try await identify("alice")
        let submits = Locked(0)
        server.respond { [self] call in
            if call.endpoint == "submit-form" {
                submits.value += 1
                if submits.value == 1 { return FakeServer.Reply(status: 429, headers: ["Retry-After": "1"]) }
            }
            return gateway(call)
        }
        let sdk = sdk!
        Task { try? await sdk.submitForm(submitRequest()) }
        await waitFor { self.server.count("submit-form") == 1 } // paused for a second
        try await identify("bob")
        await sleepMs(1500)
        XCTAssertEqual(server.count("submit-form"), 1, "alice's answers are not sent as bob")
        XCTAssertTrue(logger.warnings.contains { $0.contains("no longer the current user") })
    }
}
