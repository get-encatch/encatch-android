import XCTest
@testable import Encatch

/// setUserProperties(userName, traits), as the React Native SDK 1.5.0 (docs/HANDOVER-IDENTIFY-DEDUPE-
/// AND-SET-USER-PROPERTIES.md, part 2). Mirrors the Kotlin `SetUserPropertiesTest`.
final class SetUserPropertiesTests: XCTestCase {
    private var server: FakeServer!
    private var logger: RecordingLogger!
    private var defaults: UserDefaults!
    private var sdk: Encatch!
    private var surveyPollMs: Int64 = 500

    override func setUp() async throws {
        server = FakeServer()
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call) : .init() }
        logger = RecordingLogger()
        defaults = makeTestDefaults()
        sdk = try await newSDK()
    }

    override func tearDown() async throws {
        sdk.stop()
        try await sdk.clearAll()
    }

    private func newSDK() async throws -> Encatch {
        let poll = surveyPollMs
        return try await makeTestSDK(server: server, defaults: defaults, logger: logger) {
            $0.timings.retryBackoffBaseMs = 200
            $0.timings.surveyIdlePollMs = poll
        }
    }

    static func idReply(_ call: FakeServer.Call, delayMs: UInt64 = 0) -> FakeServer.Reply {
        FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#, delayMs: delayMs)
    }

    static func isUpdate(_ call: FakeServer.Call) -> Bool {
        guard case .object(let attrs)? = call.body["userAttributes"] else { return false }
        if case .object(let set)? = attrs["$set"], set["props"] != nil { return true }
        return attrs["$increment"] != nil
    }

    private var identifies: [FakeServer.Call] { server.calls("identify-user") }
    private var updates: [FakeServer.Call] { identifies.filter(Self.isUpdate) }
    private func props(_ v: String) -> UserTraits { UserTraits(set: ["props": .string(v)]) }
    private func propsValue(_ call: FakeServer.Call) -> String? {
        guard case .object(let attrs)? = call.body["userAttributes"], case .object(let set)? = attrs["$set"],
              case .string(let v)? = set["props"] else { return nil }
        return v
    }

    private func identify(_ name: String, _ traits: UserTraits? = nil, _ options: IdentifyOptions? = nil) async throws {
        try await sdk.identifyUser(userName: name, traits: traits, options: options)
        await sleepMs(150)
    }

    /// Starts setUserProperties without waiting for it (it may wait for an identify or a survey).
    private func setLater(_ name: String, _ traits: UserTraits) {
        let sdk = sdk!
        Task { try? await sdk.setUserProperties(userName: name, traits: traits) }
    }

    private let sig = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
    private let formReply = FakeServer.Reply(body: #"{"feedbackConfigurationId":"0000-form-uuid","questionnaireFields":{}}"#)

    private func pauseTracking(retryAfterSec: Int = 1) async throws {
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(status: 429, headers: ["Retry-After": "\(retryAfterSec)"])
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("pause-me")
        await waitFor { self.server.count("track-event") > 0 }
        await sleepMs(100)
    }

    func testAConfirmedUserSendsNameIdTraitsAndTheStoredSignature() async throws {
        try await identify("alice", nil, sig)
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        let u = updates[0]
        XCTAssertEqual(u.string("userName"), "alice")
        XCTAssertEqual(u.string("userId"), "uid-alice")
        XCTAssertEqual(propsValue(u), "a")
        XCTAssertEqual(u.header("X-User-Name"), "alice")
        XCTAssertNil(u.header("X-User-Id"))
        XCTAssertEqual(u.header("X-User-Signature"), "sig-1")
        XCTAssertEqual(u.header("X-User-Session-Time"), "1763500000000")
        XCTAssertNil(u.body["userSignature"], "the signature goes in the headers, not the body")
        try await sdk.trackEvent("after")
        await waitFor { self.server.count("track-event") == 1 }
        XCTAssertEqual(server.calls("track-event")[0].header("X-User-Signature"), "sig-1", "the stored signature is kept")
    }

    func testNeverDeduped() async throws {
        try await identify("alice")
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 2 }
        await sleepMs(200)
        XCTAssertEqual(updates.count, 2)
    }

    /// The core API answers a byte-identical identify-user body for the same user within 60 s without
    /// storing it again, so two equal updates must be equal bytes (as from the other SDKs): keys sorted
    /// at every level, whatever order the app built its dictionaries in.
    func testTwoEqualUpdatesAreByteIdenticalWithKeysSortedAtEveryLevel() async throws {
        try await identify("alice")
        var set: [String: JSONValue] = [:]
        for key in ["zeta", "alpha", "plan", "mid", "beta", "omega", "gamma", "kappa"] { set[key] = .string(key) }
        set["props"] = .string("bytes")
        set["address"] = .object(["zip": .string("400001"), "city": .string("Mumbai"), "country": .string("IN"), "line1": .string("1 Road")])
        let first = UserTraits(
            set: set,
            setOnce: ["signupSource": .string("ios"), "firstSeen": .string("2026-11-27"), "cohort": .number(7)],
            increment: ["logins": 1, "sessions": 2, "visits": 3],
            unset: ["legacyA", "legacyB"]
        )
        // the same traits, each dictionary built in another order
        var setAgain: [String: JSONValue] = [:]
        for (key, value) in set.sorted(by: { $0.key > $1.key }) { setAgain[key] = value }
        let second = UserTraits(
            set: setAgain,
            setOnce: ["cohort": .number(7), "firstSeen": .string("2026-11-27"), "signupSource": .string("ios")],
            increment: ["visits": 3, "sessions": 2, "logins": 1],
            unset: ["legacyA", "legacyB"]
        )
        try await sdk.setUserProperties(userName: "alice", traits: first)
        try await sdk.setUserProperties(userName: "alice", traits: second)
        await waitFor { self.updates.count == 2 }
        let bodies = updates.map { $0.rawBody }
        guard bodies.count == 2 else { return XCTFail("expected 2 updates, got \(bodies.count)") }
        let a = try XCTUnwrap(bodies[0]), b = try XCTUnwrap(bodies[1])
        XCTAssertEqual(String(decoding: a, as: UTF8.self), String(decoding: b, as: UTF8.self), "two equal updates are the same bytes")
        let sorted = JSONEncoder()
        sorted.outputFormatting = [.sortedKeys]
        for body in [a, b] {
            let reEncoded = try sorted.encode(JSONDecoder().decode(JSONValue.self, from: body))
            XCTAssertEqual(String(decoding: body, as: UTF8.self), String(decoding: reEncoded, as: UTF8.self), "keys sorted at every level")
        }
    }

    func testAVisitorOrAnotherUserIsIgnored() async throws {
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        try await identify("alice")
        try await sdk.setUserProperties(userName: "bob", traits: props("b"))
        await sleepMs(300)
        XCTAssertEqual(updates.count, 0)
        XCTAssertTrue(logger.warnings.contains { $0.contains("setUserProperties(\"bob\") ignored: the current user is \"alice\".") })
    }

    func testDuringASwitchTheOldUserIsWrongAndTheNewOneWaitsForItsIdentify() async throws {
        try await identify("alice")
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: Self.isUpdate(call) ? 0 : 400) : .init() }
        try await sdk.identifyUser(userName: "bob")
        setLater("alice", props("for-alice"))
        setLater("bob", props("for-bob"))
        await waitFor { !self.updates.isEmpty }
        await sleepMs(300)
        XCTAssertEqual(updates.map(propsValue), ["for-bob"])
        let all = identifies
        let bobIdentify = all.firstIndex { $0.string("userName") == "bob" && !Self.isUpdate($0) }!
        let update = all.firstIndex(where: Self.isUpdate)!
        XCTAssertLessThan(bobIdentify, update, "sent after bob's identify")
        XCTAssertEqual(updates[0].string("userId"), "uid-bob")
    }

    func testAFailedSwitchLeavesTheNewUserUnidentified() async throws {
        try await identify("alice")
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return call.string("userName") == "bob" ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#) : Self.idReply(call)
        }
        try await sdk.identifyUser(userName: "bob")
        try await sdk.setUserProperties(userName: "bob", traits: props("b"))
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await sleepMs(400)
        XCTAssertEqual(updates.count, 0)
    }

    func testASwitchBackWhoseIdentifyFailsIsNotConfirmed() async throws {
        try await identify("alice")
        let aliceCalls = CallCounter()
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            if Self.isUpdate(call) { return Self.idReply(call) }
            if call.string("userName") == "bob" { return Self.idReply(call, delayMs: 300) }
            return aliceCalls.next() == 0 ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#) : Self.idReply(call)
        }
        try await sdk.identifyUser(userName: "bob")
        try await sdk.identifyUser(userName: "alice") // back to alice: this identify fails
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await sleepMs(800)
        XCTAssertEqual(updates.count, 0, "alice's earlier confirmation doesn't count after a switch")
    }

    func testANewUsersUpdateWaitsForTheirFirstIdentify() async throws {
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: Self.isUpdate(call) ? 0 : 300) : .init() }
        try await sdk.identifyUser(userName: "bob")
        try await sdk.setUserProperties(userName: "bob", traits: props("b"))
        await waitFor { self.updates.count == 1 }
        XCTAssertEqual(updates[0].string("userId"), "uid-bob")
    }

    func testResetWhileItIsQueuedBehindASlowRequestStopsIt() async throws {
        try await identify("alice")
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(delayMs: 500)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("slow")
        await waitFor { self.server.count("track-event") > 0 }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        try await sdk.resetUser()
        await sleepMs(800)
        XCTAssertEqual(updates.count, 0)
    }

    func testResetIsNotHeldUpWhileAnUpdateWaitsForAHeldIdentify() async throws {
        try await identify("alice")
        try await pauseTracking(retryAfterSec: 2)
        try await sdk.identifyUser(userName: "bob") // held by the pause
        setLater("bob", props("b"))
        await sleepMs(100)
        let start = Date()
        try await sdk.resetUser()
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.5, "resetUser waited")
        await sleepMs(2_500)
        XCTAssertEqual(updates.count, 0, "bob was reset: his update isn't sent")
    }

    func testAnUpdateWaitingForAHeldIdentifyStillLetsOnlyTheLatestIdentifyGo() async throws {
        try await identify("alice")
        try await pauseTracking()
        try await sdk.identifyUser(userName: "bob", traits: UserTraits(set: ["t": .number(1)]))
        setLater("bob", props("b"))
        await sleepMs(100)
        try await sdk.identifyUser(userName: "bob", traits: UserTraits(set: ["t": .number(2)]))
        await waitFor(timeout: 4) { self.updates.count == 1 }
        let all = identifies
        let bobs = all.filter { $0.string("userName") == "bob" && !Self.isUpdate($0) }
        XCTAssertEqual(bobs.count, 1, "only the latest held identify")
        XCTAssertLessThan(all.firstIndex { $0.string("userName") == "bob" && !Self.isUpdate($0) }!, all.firstIndex(where: Self.isUpdate)!)
    }

    func testItDoesntRestartAStoppedSessionOrChangeTheIdentity() async throws {
        try await identify("alice")
        try await sdk.stopSession()
        let identified = CallCounter()
        let off = EncatchInternalEmitter.shared.on { event in
            if case .userIdentified = event { _ = identified.next() }
        }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        await sleepMs(200)
        off()
        XCTAssertTrue(sdk.sessionStopped)
        XCTAssertEqual(identified.current, 0, "no \"user identified\"")
        XCTAssertTrue(sdk.isIdentified())
    }

    func testItClearsIdentifysTraitsMemoryButNotTheUser() async throws {
        let t = UserTraits(set: ["plan": .string("pro")])
        try await identify("alice", t)
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits(set: ["plan": .string("team")]))
        await sleepMs(200)
        try await identify("alice", t)
        XCTAssertEqual(identifies.count, 3, "identify, the update, the same identify again")
        try await identify("alice")
        XCTAssertEqual(identifies.count, 3, "a no-traits identify is still a repeat")
    }

    func testDuringA429PauseItIsIgnored() async throws {
        try await identify("alice")
        try await pauseTracking()
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await sleepMs(1_500)
        XCTAssertEqual(updates.count, 0)
        XCTAssertTrue(logger.warnings.contains("setUserProperties ignored: rate limited, try again later."))
    }

    func testOneWaitingForAHeldIdentifyOfItsUserIsSentAfterIt() async throws {
        try await identify("alice")
        try await pauseTracking()
        try await sdk.identifyUser(userName: "bob")
        setLater("bob", props("b"))
        await waitFor(timeout: 4) { self.updates.count == 1 }
        let all = identifies
        let bob = all.firstIndex { $0.string("userName") == "bob" && !Self.isUpdate($0) }!
        XCTAssertLessThan(bob, all.firstIndex(where: Self.isUpdate)!)
    }

    func testWithAnExpiredSignatureItIsIgnored() async throws {
        try await identify("alice", nil, sig)
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(status: 401, body: #"{"error":"invalid api key","reason":"user_signature_expired"}"#)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("expires")
        await waitFor { self.sdk.isSignatureExpired }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await sleepMs(300)
        XCTAssertEqual(updates.count, 0)
    }

    func testA401OnAnUpdateIsASignatureExpiry() async throws {
        try await identify("alice", nil, sig)
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return Self.isUpdate(call) ? FakeServer.Reply(status: 401, body: #"{"message":"expired"}"#) : Self.idReply(call)
        }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        await waitFor { self.sdk.isSignatureExpired }
    }

    func testA401ForThePreviousUserAfterAResetIsNotAnExpiry() async throws {
        try await identify("alice", nil, sig)
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return Self.isUpdate(call) ? FakeServer.Reply(status: 401, body: #"{"message":"expired"}"#, delayMs: 300) : Self.idReply(call)
        }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        try await sdk.resetUser()
        await sleepMs(500)
        XCTAssertFalse(sdk.isSignatureExpired)
        try await sdk.trackEvent("visitor-event")
        await waitFor { self.server.count("track-event") == 1 }
    }

    func testIncrementsAreNeverRetriedAndSetsAre() async throws {
        try await identify("alice")
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return Self.isUpdate(call) ? FakeServer.Reply(status: 500, body: #"{"message":"oops"}"#) : Self.idReply(call)
        }
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits(increment: ["logins": 1]))
        await sleepMs(1_200)
        XCTAssertEqual(updates.count, 1, "an increment goes once")
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor(timeout: 4) { self.updates.filter { self.propsValue($0) == "a" }.count == 2 }
    }

    func testUpdatesGoInCallOrderEvenWhenTheFirstIsRetried() async throws {
        try await identify("alice")
        let failed = CallCounter()
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            if Self.isUpdate(call), failed.next() == 0 { return FakeServer.Reply(status: 500, body: #"{"message":"oops"}"#) }
            return Self.idReply(call)
        }
        try await sdk.setUserProperties(userName: "alice", traits: props("old"))
        try await sdk.setUserProperties(userName: "alice", traits: props("new"))
        await waitFor(timeout: 5) { self.updates.count == 3 }
        XCTAssertEqual(updates.map(propsValue), ["old", "old", "new"])
    }

    func testItWaitsWhileASurveyIsOpenAndShowsASurveyInItsReply() async throws {
        // a long poll: only the close itself can wake the update in time
        sdk.stop()
        surveyPollMs = 10_000
        sdk = try await newSDK()
        try await identify("alice")
        let form = formReply
        server.respond { call in
            switch call.endpoint {
            case "identify-user": return Self.isUpdate(call) ? FakeServer.Reply(body: #"{"nextFeedbackId":"form-x"}"#) : Self.idReply(call)
            case "show-form": return form
            default: return .init()
            }
        }
        sdk.setFormVisible(true)
        setLater("alice", props("a"))
        await sleepMs(800)
        XCTAssertEqual(updates.count, 0, "not while the survey is open")
        let closed = Date()
        sdk.setFormVisible(false)
        await waitFor { self.updates.count == 1 }
        XCTAssertLessThan(Date().timeIntervalSince(closed), 1, "woken by the close, not the poll")
        await waitFor { self.server.count("show-form") > 0 }
    }

    func testItWaitsWhileAnAutomaticShowIsPending() async throws {
        try await identify("alice")
        let form = formReply
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(body: #"{"nextFeedbackId":"form-d","onPageDelay":1}"#)
            case "show-form": return form
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("offer")
        await waitFor { self.server.count("track-event") > 0 }
        await sleepMs(100)
        setLater("alice", props("a"))
        await waitFor(timeout: 3) { self.server.count("show-form") > 0 }
        await sleepMs(300)
        XCTAssertEqual(updates.count, 0, "not before the survey shown after the delay is closed")
        sdk.setFormVisible(false)
        await waitFor { self.updates.count == 1 }
    }

    func testAReplyAfterAResetIsIgnored() async throws {
        try await identify("alice")
        let form = formReply
        server.respond { call in
            switch call.endpoint {
            case "identify-user":
                return Self.isUpdate(call)
                    ? FakeServer.Reply(body: #"{"nextFeedbackId":"form-x","$feedbackTransactions":"tx-1"}"#, delayMs: 300)
                    : Self.idReply(call)
            case "show-form": return form
            default: return .init()
            }
        }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        try await sdk.resetUser()
        await sleepMs(600)
        XCTAssertEqual(server.count("show-form"), 0, "no survey for the visitor")
        try await sdk.trackEvent("visitor")
        await waitFor { self.server.count("track-event") == 1 }
        XCTAssertNil(server.calls("track-event")[0].body["$feedbackTransactions"], "no transactions for the visitor")
    }

    func testClearAllWhileOneWaitsForASurveySendsNothingAfterwards() async throws {
        try await identify("alice")
        sdk.setFormVisible(true)
        setLater("alice", props("a"))
        await sleepMs(200)
        try await sdk.clearAll()
        sdk = try await newSDK()
        try await identify("alice")
        await sleepMs(800)
        XCTAssertEqual(updates.count, 0)
    }

    func testEmptyOrUnsendableTraitsSendNothing() async throws {
        try await identify("alice")
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits())
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits(set: [:], unset: []))
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits(set: ["props": .number(.nan)]))
        try await sdk.setUserProperties(userName: "alice", traits: UserTraits(increment: ["n": .infinity]))
        await sleepMs(300)
        XCTAssertEqual(identifies.count, 1)
    }

    func testStopReleasesAnUpdateWaitingForASurvey() async throws {
        try await identify("alice")
        sdk.setFormVisible(true)
        let returned = CallCounter()
        let sdk = self.sdk!
        Task {
            try? await sdk.setUserProperties(userName: "alice", traits: self.props("a"))
            _ = returned.next()
        }
        await sleepMs(200)
        sdk.stop()
        await waitFor { returned.current > 0 }
        sdk.setFormVisible(false)
        await sleepMs(600)
        XCTAssertEqual(updates.count, 0)
    }

    func testASurveyOpenedWhileItWasQueuedHoldsItUntilClosed() async throws {
        try await identify("alice")
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(delayMs: 400)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("slow")
        await waitFor { self.server.count("track-event") > 0 }
        try await sdk.setUserProperties(userName: "alice", traits: props("a")) // queued behind the slow event
        sdk.setFormVisible(true) // a survey opens before it is sent
        await sleepMs(800)
        XCTAssertEqual(updates.count, 0)
        sdk.setFormVisible(false)
        await waitFor { self.updates.count == 1 }
    }

    func testARestoredUserWithAnIdIsConfirmed() async throws {
        try await identify("alice")
        sdk.stop()
        sdk = try await newSDK() // a new app run: alice restored with her id
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        await waitFor { self.updates.count == 1 }
        XCTAssertEqual(identifies.count, 2, "no identify needed first")
    }

    func testClearAllWithAHeldIdentifyDoesntLeaveALaterUpdateWaitingForever() async throws {
        try await identify("alice")
        try await pauseTracking(retryAfterSec: 2)
        try await sdk.identifyUser(userName: "bob") // held by the pause
        try await sdk.clearAll()
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call) : .init() }
        // the same SDK initialized again (as an app does after clearAll)
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        try await identify("bob")
        let returned = CallCounter()
        let sdk = self.sdk!
        Task {
            try? await sdk.setUserProperties(userName: "bob", traits: self.props("b"))
            _ = returned.next()
        }
        await waitFor(timeout: 3) { returned.current > 0 }
        await waitFor { self.updates.count == 1 }
    }

    func testTwoCallsWaitingAtOnceGoOutInCallOrder() async throws {
        try await identify("alice")
        for round in 0..<8 {
            sdk.setFormVisible(true)
            setLater("alice", props("first-\(round)"))
            await sleepMs(30)
            setLater("alice", props("second-\(round)"))
            await sleepMs(100)
            sdk.setFormVisible(false)
            await waitFor { self.updates.count == 2 * (round + 1) }
        }
        XCTAssertEqual(updates.map(propsValue), (0..<8).flatMap { ["first-\($0)", "second-\($0)"] })
    }

    func testAReInitReleasesAnUpdateWaitingForAnIdentify() async throws {
        try await identify("alice")
        try await pauseTracking(retryAfterSec: 2)
        try await sdk.identifyUser(userName: "bob") // held
        let returned = CallCounter()
        let sdk = self.sdk!
        Task {
            try? await sdk.setUserProperties(userName: "bob", traits: self.props("b"))
            _ = returned.next()
        }
        await sleepMs(100)
        try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test"))
        await waitFor { returned.current > 0 }
        await sleepMs(2_500)
        XCTAssertEqual(updates.count, 0)
    }

    func testStopReleasesAnUpdateWaitingForAnIdentify() async throws {
        try await identify("alice")
        try await pauseTracking(retryAfterSec: 1)
        try await sdk.identifyUser(userName: "bob") // held
        let returned = CallCounter()
        let sdk = self.sdk!
        Task {
            try? await sdk.setUserProperties(userName: "bob", traits: self.props("b"))
            _ = returned.next()
        }
        await sleepMs(100)
        sdk.stop()
        await waitFor { returned.current > 0 }
        await sleepMs(1_800)
        XCTAssertEqual(updates.count, 0, "not sent once bob's identify goes out after the pause")
    }

    func testASignatureExpiringWhileItIsQueuedStopsIt() async throws {
        try await identify("alice", nil, sig)
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(status: 401, body: #"{"error":"invalid api key","reason":"user_signature_expired"}"#, delayMs: 400)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("expires-soon")
        await waitFor { self.server.count("track-event") > 0 }
        try await sdk.setUserProperties(userName: "alice", traits: props("a")) // passes the first check, waits in the queue
        await sleepMs(900)
        XCTAssertEqual(updates.count, 0)
    }

    func testStopWhileItIsQueuedBehindASlowRequestStopsIt() async throws {
        try await identify("alice")
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(delayMs: 500)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("slow")
        await waitFor { self.server.count("track-event") > 0 }
        try await sdk.setUserProperties(userName: "alice", traits: props("a"))
        sdk.stop()
        await sleepMs(900)
        XCTAssertEqual(updates.count, 0)
    }

    func testManyUpdatesGoOutInCallOrderUnderLoad() async throws {
        try await identify("alice")
        // calls keep arriving while earlier updates finish, so a finish and a new call race the pump
        // the race is narrow: three rounds of 3000
        let n = 3000
        for round in 0..<3 {
            for i in 0..<n {
                sdk.startSetUserProperties(userName: "alice", traits: props("\(round * n + i)"))
                if i % 5 == 0 { await Task.yield() }
            }
            await waitFor(timeout: 60) { self.updates.count == (round + 1) * n }
        }
        let values = updates.compactMap { propsValue($0).flatMap(Int.init) }
        let inversions = zip(values, values.dropFirst()).filter { $0 > $1 }.count
        XCTAssertEqual(inversions, 0, "every update in call order")
        XCTAssertEqual(values.count, 3 * n)
    }

    func testAnUpdateWaitingItsTurnSeesAnIdentifyMadeMeanwhile() async throws {
        try await identify("bob")
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: Self.isUpdate(call) ? 0 : 400) : .init() }
        sdk.setFormVisible(true)
        setLater("carol", props("for-carol")) // waits for the survey
        await sleepMs(50)
        setLater("bob", props("for-bob")) // waits its turn behind carol's
        await sleepMs(50)
        try await sdk.identifyUser(userName: "alice")
        try await sdk.identifyUser(userName: "bob") // in flight when bob's turn comes
        sdk.setFormVisible(false)
        await waitFor(timeout: 3) { self.updates.count == 1 }
        XCTAssertEqual(updates.first.flatMap(propsValue), "for-bob", "sent once bob's identify succeeded")
    }

    func testStopWithManyUpdatesWaitingDropsThemWithoutCrashing() async throws {
        try await identify("alice")
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return Self.isUpdate(call) ? FakeServer.Reply(delayMs: 600) : Self.idReply(call)
        }
        sdk.startSetUserProperties(userName: "alice", traits: props("in-flight"))
        await waitFor { self.updates.count == 1 }
        for i in 0..<3000 { sdk.startSetUserProperties(userName: "alice", traits: props("waiting-\(i)")) }
        await sleepMs(200) // they all reach the pump
        sdk.stop()
        await sleepMs(1_200)
        XCTAssertEqual(updates.count, 1, "only the one already in flight")
    }
}
