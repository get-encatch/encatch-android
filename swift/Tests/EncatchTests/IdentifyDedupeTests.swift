import XCTest
@testable import Encatch

/// identifyUser() dedupe, as the React Native SDK 1.5.0 (docs/HANDOVER-IDENTIFY-DEDUPE-AND-SET-USER-
/// PROPERTIES.md, part 1): after the first identify of each app run, a repeat for the same user with
/// the same traits, or none, is not sent. Mirrors the Kotlin `IdentifyDedupeTest`.
final class IdentifyDedupeTests: XCTestCase {
    private var server: FakeServer!
    private var logger: RecordingLogger!
    private var defaults: UserDefaults!
    private var sdk: Encatch!

    override func setUp() async throws {
        server = FakeServer()
        server.respond { call in
            call.endpoint == "identify-user" ? Self.idReply(call) : .init()
        }
        logger = RecordingLogger()
        defaults = makeTestDefaults()
        sdk = try await makeTestSDK(server: server, defaults: defaults, logger: logger) { $0.timings.retryBackoffBaseMs = 50 }
    }

    override func tearDown() async throws {
        sdk.stop()
        try await sdk.clearAll()
    }

    static func idReply(_ call: FakeServer.Call, delayMs: UInt64 = 0) -> FakeServer.Reply {
        FakeServer.Reply(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#, delayMs: delayMs)
    }

    private var identifies: [FakeServer.Call] { server.calls("identify-user") }
    private func plan(_ p: String) -> UserTraits { UserTraits(set: ["plan": .string(p)]) }

    /// Identifies, then waits until the request went out (or long enough to be sure it didn't).
    private func identify(_ name: String, _ traits: UserTraits? = nil, _ options: IdentifyOptions? = nil) async throws {
        try await sdk.identifyUser(userName: name, traits: traits, options: options)
        await sleepMs(150)
    }

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

    func testARestoredUserSendsTheFirstIdentifyOfTheRunThenSkipsTheRepeat() async throws {
        try await identify("alice")
        XCTAssertEqual(identifies.count, 1)
        sdk.stop()
        sdk = try await makeTestSDK(server: server, defaults: defaults, logger: logger) // a new app run
        try await identify("alice")
        try await identify("alice")
        XCTAssertEqual(identifies.count, 2, "first of this run sent, the repeat not")
    }

    func testTheSameUserWithoutTraitsAfterTraitsIsADuplicate() async throws {
        try await identify("alice", plan("pro"))
        try await identify("alice")
        XCTAssertEqual(identifies.count, 1)
    }

    func testKeyOrderAndUnsetOrderDontMatterAndTheCallersListIsKept() async throws {
        let unset = ["b", "a"]
        try await identify("alice", UserTraits(set: ["x": .number(1), "y": .number(2)], unset: unset))
        try await identify("alice", UserTraits(set: ["y": .number(2), "x": .number(1)], unset: ["a", "b"]))
        XCTAssertEqual(identifies.count, 1)
        XCTAssertEqual(unset, ["b", "a"])
        // the request still carries the caller's order
        if case .object(let attrs)? = identifies[0].body["userAttributes"] {
            XCTAssertEqual(attrs["$unset"], .array([.string("b"), .string("a")]))
        } else { XCTFail("no userAttributes") }
    }

    func testEmptyOperationsCountAsNoTraits() async throws {
        try await identify("alice")
        try await identify("alice", UserTraits(set: [:], increment: [:], unset: []))
        XCTAssertEqual(identifies.count, 1)
    }

    func testChangedTraitsAndUsersAreSent() async throws {
        try await identify("alice", plan("a"))
        try await identify("alice", plan("b"))
        try await identify("alice", plan("a"))
        XCTAssertEqual(identifies.count, 3, "A, B, A traits: three sent")
        try await identify("bob")
        try await identify("alice")
        XCTAssertEqual(identifies.count, 5, "users A, B, A: each sent")
    }

    func testTheSameIncrementTwiceSendsOnce() async throws {
        try await identify("alice", UserTraits(increment: ["logins": 1]))
        try await identify("alice", UserTraits(increment: ["logins": 1]))
        XCTAssertEqual(identifies.count, 1)
    }

    func testABurstForANewUserSendsOneWhileTheFirstIsInFlight() async throws {
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: 500) : .init() }
        for _ in 0..<20 { try await sdk.identifyUser(userName: "bob") }
        await sleepMs(900)
        XCTAssertEqual(identifies.count, 1)
    }

    func testABurstDuringASwitchSendsOne() async throws {
        try await identify("alice")
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: 500) : .init() }
        for _ in 0..<20 { try await sdk.identifyUser(userName: "bob", traits: plan("pro")) }
        await sleepMs(900)
        XCTAssertEqual(identifies.count, 2)
    }

    func testRepeatsOfSentTraitsDuringA429PauseSendNothing() async throws {
        try await identify("alice", plan("pro"))
        try await pauseTracking()
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", traits: plan("pro")) }
        await sleepMs(1_800)
        XCTAssertEqual(identifies.count, 1, "all five are the identify already sent")
    }

    func testRepeatsOfNewTraitsDuringA429PauseSendOneWhenItEnds() async throws {
        try await identify("alice")
        try await pauseTracking()
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", traits: plan("pro")) }
        await sleepMs(1_800)
        XCTAssertEqual(identifies.count, 2, "the first identify, then one of the five after the pause")
    }

    func testAnIdentifyReplacedWhileHeldForgetsItsTraitsAndTheReplacementIsKept() async throws {
        try await identify("alice")
        try await pauseTracking()
        try await sdk.identifyUser(userName: "alice", traits: plan("pro")) // held
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(locale: "fr")) // replaces it
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(locale: "fr")) // a repeat
        await sleepMs(1_800)
        XCTAssertEqual(identifies.count, 2)
        XCTAssertNil(identifies.last?.body["userAttributes"])
        try await identify("alice", plan("pro"))
        XCTAssertEqual(identifies.count, 3, "the replaced identify's traits go again")
        try await identify("alice", nil, IdentifyOptions(locale: "fr"))
        XCTAssertEqual(identifies.count, 3, "the replacing identify's memory is kept")
    }

    func testAnIdentifyDroppedOnA429CanBeSentAgain() async throws {
        let first = CallCounter()
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return first.next() == 0 ? FakeServer.Reply(status: 429, headers: ["Retry-After": "1"]) : Self.idReply(call)
        }
        try await identify("alice", plan("pro"))
        XCTAssertEqual(identifies.count, 1)
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", traits: plan("pro")) }
        await sleepMs(1_800)
        XCTAssertEqual(identifies.count, 2, "one of the five goes out when the pause ends")
    }

    func testSignaturesChangedRepeatedAndOmitted() async throws {
        let sig1 = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
        try await identify("alice", nil, sig1)
        try await identify("alice", nil, sig1)
        XCTAssertEqual(identifies.count, 1, "the same signature again: not sent")
        try await identify("alice", nil, IdentifyOptions(secure: SecureOptions(signature: "sig-2", generatedDateTimeInUtc: "1763500000001")))
        XCTAssertEqual(identifies.count, 2, "a fresh signature: sent")
        try await identify("alice")
        XCTAssertEqual(identifies.count, 3, "secure omitted: sent")
    }

    func testTheSameSignatureWhileTheFirstIsInFlightSendsOne() async throws {
        server.respond { call in call.endpoint == "identify-user" ? Self.idReply(call, delayMs: 500) : .init() }
        let sig = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", options: sig) }
        await sleepMs(900)
        XCTAssertEqual(identifies.count, 1)
    }

    func testAnotherUserStartsWithNoTraitsOrLocaleRemembered() async throws {
        try await identify("alice", plan("pro"), IdentifyOptions(locale: "fr"))
        try await identify("bob")
        try await identify("bob", plan("pro"))
        XCTAssertEqual(identifies.count, 3, "bob's traits, the same as alice's: sent")
        try await identify("bob", nil, IdentifyOptions(locale: "fr"))
        XCTAssertEqual(identifies.count, 4, "bob's locale, the same as alice's: sent")
    }

    func testLocaleAndCountryCompareWithWhatTheLastIdentifyCarried() async throws {
        try await identify("alice", nil, IdentifyOptions(locale: "fr"))
        try await identify("alice", nil, IdentifyOptions(locale: "fr"))
        XCTAssertEqual(identifies.count, 1, "the same locale: not sent")
        try await identify("alice", nil, IdentifyOptions(locale: "de"))
        try await identify("alice", nil, IdentifyOptions(locale: "de"))
        XCTAssertEqual(identifies.count, 2, "different, then the same again")
        try await identify("alice", nil, IdentifyOptions(country: "IN"))
        XCTAssertEqual(identifies.count, 3, "a country the last identify didn't carry")
    }

    func testSetLocaleThenAnIdentifyWithThatLocaleIsSent() async throws {
        try await identify("alice")
        sdk.setLocale("fr")
        try await identify("alice", nil, IdentifyOptions(locale: "fr"))
        XCTAssertEqual(identifies.count, 2)
    }

    func testAfterASignatureExpiryTheSameIdentifyGoesOutOnce() async throws {
        let sig = IdentifyOptions(secure: SecureOptions(signature: "sig-1", generatedDateTimeInUtc: "1763500000000"))
        try await identify("alice", nil, sig)
        server.respond { call in
            switch call.endpoint {
            case "track-event": return FakeServer.Reply(status: 401, body: #"{"message":"expired"}"#)
            case "identify-user": return Self.idReply(call)
            default: return .init()
            }
        }
        try await sdk.trackEvent("expires")
        await waitFor { self.sdk.isSignatureExpired }
        for _ in 0..<5 { try await sdk.identifyUser(userName: "alice", options: sig) }
        await sleepMs(300)
        XCTAssertEqual(identifies.count, 2)
    }

    func testAfterStopSessionTheIdentifyGoesOutAndRepeatsCollapse() async throws {
        try await identify("alice")
        try await sdk.stopSession()
        for _ in 0..<20 { try await sdk.identifyUser(userName: "alice") }
        await sleepMs(300)
        XCTAssertEqual(identifies.count, 2)
        XCTAssertFalse(sdk.sessionStopped, "the identify turned the session back on")
    }

    func testAFailedIdentifyCanBeSentAgain() async throws {
        server.respond { call in call.endpoint == "identify-user" ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#) : .init() }
        try await identify("alice", plan("pro"))
        try await identify("alice", plan("pro"))
        XCTAssertEqual(identifies.count, 2)
    }

    func testADroppedIdentifyWithTraitsForgetsThemEvenAfterALaterIdentifyTookOver() async throws {
        try await identify("alice")
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            return call.body["userAttributes"] != nil
                ? FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#, delayMs: 300)
                : Self.idReply(call)
        }
        try await sdk.identifyUser(userName: "alice", traits: plan("pro")) // fails after 300 ms
        try await sdk.identifyUser(userName: "alice", options: IdentifyOptions(locale: "fr")) // takes over the memory
        await sleepMs(800)
        XCTAssertEqual(identifies.count, 3)
        try await identify("alice", plan("pro"))
        XCTAssertEqual(identifies.count, 4, "the failed identify's traits weren't sent, so they go again")
    }

    func testAnEarlierIdentifysDropKeepsALaterIdentifysMemory() async throws {
        try await identify("alice")
        server.respond { call in
            guard call.endpoint == "identify-user" else { return .init() }
            if case .object(let attrs)? = call.body["userAttributes"], case .object(let set)? = attrs["$set"], set["plan"] == .string("bad") {
                return FakeServer.Reply(status: 400, body: #"{"message":"bad"}"#, delayMs: 300)
            }
            return Self.idReply(call)
        }
        try await sdk.identifyUser(userName: "alice", traits: plan("bad"))
        try await sdk.identifyUser(userName: "alice", traits: plan("good"))
        await sleepMs(800)
        XCTAssertEqual(identifies.count, 3)
        try await identify("alice", plan("good"))
        try await identify("alice")
        XCTAssertEqual(identifies.count, 3, "the later identify's memory survives the earlier one's drop")
    }

    func testASkippedCallChangesNoMemory() async throws {
        try await identify("alice", plan("t1"))
        try await identify("alice")
        try await identify("alice", plan("t1"))
        XCTAssertEqual(identifies.count, 1)
    }

    func testResetAndClearAllClearTheMemory() async throws {
        try await identify("alice")
        try await sdk.resetUser()
        try await identify("alice")
        XCTAssertEqual(identifies.count, 2)
        try await sdk.clearAll()
        sdk = try await makeTestSDK(server: server, defaults: defaults, logger: logger)
        try await identify("alice")
        XCTAssertEqual(identifies.count, 3)
    }

    func testTraitsThatCantBeSentAreIgnoredAndChangeNoMemory() async throws {
        try await identify("alice", plan("pro"))
        try await identify("alice", UserTraits(set: ["score": .number(.nan)]))
        try await identify("alice", UserTraits(increment: ["n": .infinity]))
        try await identify("bob", UserTraits(decrement: ["n": -.infinity]))
        XCTAssertEqual(identifies.count, 1, "none of the three sent")
        XCTAssertTrue(logger.warnings.contains { $0.contains("can't be sent") })
        try await identify("alice", plan("pro"))
        XCTAssertEqual(identifies.count, 1, "the memory is still alice's plan: a repeat")
        try await identify("alice", UserTraits(set: ["nothing": .null]))
        XCTAssertEqual(identifies.count, 2, "null is a value a request can carry")
    }

    func testTheDuplicateMessageNamesSetUserProperties() async throws {
        try await identify("alice")
        try await identify("alice")
        XCTAssertTrue(logger.debugs.contains { $0.contains("skipped: same user and traits") && $0.contains("setUserProperties(\"alice\", traits)") })
    }

    func testTheFingerprintHelpers() {
        XCTAssertNil(traitsForDedupe(nil))
        XCTAssertNil(traitsForDedupe(UserTraits(set: [:], unset: [])))
        XCTAssertEqual(traitsForDedupe(UserTraits(unset: ["b", "a"])), .object(["$unset": .array([.string("a"), .string("b")])]))
        XCTAssertTrue(hasUnsendableValue(UserTraits(set: ["x": .object(["y": .number(.nan)])])))
        XCTAssertFalse(hasUnsendableValue(UserTraits(set: ["x": .string("NaN")])))
    }
}

/// A thread-safe counter for responders.
final class CallCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    /// The current value, then adds one.
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value - 1
    }

    /// The current value.
    var current: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
