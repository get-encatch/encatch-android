import XCTest
@testable import Encatch

/// Pass 5: ping scheduling and stopSession (RN 1.4.4 items 18, 19, 19b).
final class PingTests: XCTestCase {
    private func sdk(_ server: FakeServer, defaults: UserDefaults = makeTestDefaults(), intervalMs: Int64) async throws -> Encatch {
        try await makeTestSDK(server: server, defaults: defaults) { $0.timings.pingIntervalMs = intervalMs }
    }

    func testEveryRepliesPingAgainInIsHonoured() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "ping" ? .init(body: #"{"pingAgainIn":0.15}"#) : .init() }
        let sdk = try await sdk(server, intervalMs: 60_000) // only server-scheduled pings can happen
        defer { sdk.stop() }
        try await sdk.startSession()
        await sleepMs(1_000)
        // 0, 150, 300, … ms: the scheduled ping's own reply schedules the next one (the interval is 60 s)
        XCTAssertGreaterThanOrEqual(server.count("ping"), 3)
        XCTAssertTrue(sdk.pingActive)
    }

    func testTheIntervalResumesAfterAScheduledPingWhoseReplySchedulesNothing() async throws {
        let server = FakeServer()
        let first = LockedFlag()
        server.respond { call in
            guard call.endpoint == "ping" else { return .init() }
            if !first.value {
                first.value = true
                return .init(body: #"{"pingAgainIn":0.05}"#)
            }
            return .init()
        }
        let sdk = try await sdk(server, intervalMs: 200)
        defer { sdk.stop() }
        try await sdk.startSession()
        await sleepMs(700)
        // immediate, scheduled (+50 ms), then the 200 ms interval again
        XCTAssertGreaterThanOrEqual(server.count("ping"), 4)
    }

    func testNoPingWhileASurveyIsOpen() async throws {
        let server = FakeServer()
        let sdk = try await sdk(server, intervalMs: 80)
        defer { sdk.stop() }
        try await sdk.startSession(StartSessionOptions(skipImmediatePing: true, skipImmediateTrackScreen: true))
        sdk.setFormVisible(true)
        await sleepMs(400)
        XCTAssertEqual(server.count("ping"), 0)
        sdk.setFormVisible(false)
        await waitFor("pings resume") { server.count("ping") >= 1 }
    }

    func testPingOnNextPageVisitFalseStopsTheInterval() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "ping" ? .init(body: #"{"pingOnNextPageVisit":false}"#) : .init() }
        let sdk = try await sdk(server, intervalMs: 80)
        defer { sdk.stop() }
        try await sdk.startSession()
        await waitFor { server.count("ping") == 1 }
        await sleepMs(400)
        XCTAssertEqual(server.count("ping"), 1)
        XCTAssertFalse(sdk.pingActive)
    }

    func testStoppingWhileAScheduledPingIsInFlightDoesNotRestartPings() async throws {
        let server = FakeServer()
        server.respond { call in
            guard call.endpoint == "ping" else { return .init() }
            // the scheduled ping answers late, and asks for another
            return .init(body: #"{"pingAgainIn":0.05}"#, delayMs: server.count("ping") >= 2 ? 300 : 0)
        }
        let sdk = try await sdk(server, intervalMs: 100)
        defer { sdk.stop() }
        try await sdk.startSession()
        await waitFor("the scheduled ping in flight") { server.count("ping") == 2 }
        try await sdk.stopSession()
        await sleepMs(800)
        XCTAssertEqual(server.count("ping"), 2, "neither the reply's pingAgainIn nor the interval may restart pings")
        XCTAssertFalse(sdk.pingActive)
    }

    func testStopSessionIsSavedAndRespectedUntilStartSession() async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "track-event": return .init(body: #"{"pingAgainIn":0.05,"nextFeedbackId":"form-1","onPageDelay":0}"#)
            case "show-form": return .init(status: 404, body: "not eligible")
            default: return .init()
            }
        }
        let defaults = makeTestDefaults()
        let first = try await sdk(server, defaults: defaults, intervalMs: 80)
        try await first.stopSession()
        first.stop()

        // a restart: still stopped
        let sdk = try await sdk(server, defaults: defaults, intervalMs: 80)
        defer { sdk.stop() }
        XCTAssertTrue(sdk.sessionStopped)
        // the app's own calls still work; a reply's survey still shows; its pingAgainIn starts nothing
        try await sdk.trackEvent("purchase")
        await waitFor { server.count("track-event") == 1 }
        await waitFor("the reply's survey requested") { server.count("show-form") == 1 }
        await sleepMs(300)
        XCTAssertEqual(server.count("ping"), 0)
        XCTAssertFalse(sdk.pingActive)

        try await sdk.startSession()
        XCTAssertFalse(sdk.sessionStopped)
        await waitFor { server.count("ping") >= 1 }
        let restarted = try await self.sdk(server, defaults: defaults, intervalMs: 80)
        defer { restarted.stop() }
        XCTAssertFalse(restarted.sessionStopped, "startSession() cleared the saved stop")
    }

    func testResetUserAndASuccessfulIdentifyClearTheSavedStop() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "identify-user" ? .init(body: #"{"userId":"uid-a"}"#) : .init() }
        let defaults = makeTestDefaults()
        let sdk = try await sdk(server, defaults: defaults, intervalMs: 60_000)
        defer { sdk.stop() }
        try await sdk.stopSession()
        try await sdk.resetUser()
        XCTAssertFalse(sdk.sessionStopped)
        XCTAssertNil(defaults.string(forKey: "@encatch/session_stopped"))

        try await sdk.stopSession()
        try await sdk.identifyUser(userName: "alice")
        await waitFor("identify cleared the stop") { !sdk.sessionStopped }
        XCTAssertNil(defaults.string(forKey: "@encatch/session_stopped"))
        XCTAssertTrue(sdk.pingActive)
    }

    func testPauseThenResumeDoesNotRestartPingsWhileStopped() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "ping" ? .init(body: #"{"pingAgainIn":0.05}"#) : .init() }
        let sdk = try await sdk(server, intervalMs: 60)
        defer { sdk.stop() }
        try await sdk.startSession()
        await waitFor { server.count("ping") >= 1 }
        try await sdk.stopSession()
        sdk.pauseSession()
        sdk.resumeSession()
        await sleepMs(100)
        let pings = server.count("ping")
        await sleepMs(400)
        XCTAssertEqual(server.count("ping"), pings, "stopped: resume must not restart pings")
        XCTAssertFalse(sdk.pingActive)
    }

    func testAScheduledPingNeedsActivePinging() async throws {
        let server = FakeServer()
        let sdk = try await sdk(server, intervalMs: 60_000)
        defer { sdk.stop() }
        sdk.sessionManagerForTesting.scheduleNextPing(delayMs: 30) // never started (as in full screen)
        await sleepMs(300)
        XCTAssertEqual(server.count("ping"), 0)
    }

    func testNoPingDuringA429Pause() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "track-event" ? .init(status: 429, headers: ["Retry-After": "0.4"]) : .init() }
        let sdk = try await sdk(server, intervalMs: 60)
        defer { sdk.stop() }
        try await sdk.startSession(StartSessionOptions(skipImmediatePing: true, skipImmediateTrackScreen: true))
        try await sdk.trackEvent("burst")
        await waitFor { server.count("track-event") == 1 }
        let pingsAtPause = server.count("ping")
        await sleepMs(250)
        XCTAssertEqual(server.count("ping"), pingsAtPause, "no ping while tracking is paused")
        await waitFor("pings after the pause") { server.count("ping") > pingsAtPause }
    }
}

/// Pass 5: 429 pause, retry queue, timeout, collapse (RN 1.4.4 items 1, 2, 4).
final class LoadTests: XCTestCase {
    // MARK: 429 pause

    func testA429IsNotRetriedAndPausesTrackingWithOneWarning() async throws {
        let server = FakeServer()
        let limited = LockedFlag()
        limited.value = true
        server.respond { call in
            call.endpoint == "track-event" && limited.value ? .init(status: 429, headers: ["Retry-After": "0.4"]) : .init()
        }
        let logger = RecordingLogger()
        let sdk = try await makeTestSDK(server: server, logger: logger)
        defer { sdk.stop() }
        try await sdk.trackEvent("e1")
        await waitFor { server.count("track-event") == 1 }
        limited.value = false
        for name in ["e2", "e3", "e4"] { try await sdk.trackEvent(name) }
        try await sdk.trackScreen("Home")
        await sdk.trackFormEvent("form:show", "f1")
        await sleepMs(150)
        XCTAssertEqual(server.count("track-event"), 1, "the 429'd call is not retried; calls in the pause are skipped")
        XCTAssertEqual(server.count("track-screen"), 0)
        XCTAssertEqual(logger.warnings.filter { $0.contains("Rate limited (429)") }.count, 1, "one warning per pause")

        await sleepMs(400)
        try await sdk.trackEvent("after")
        await waitFor("sent after the pause") { server.count("track-event") == 2 }
        await sleepMs(100)
        XCTAssertEqual(server.calls("track-event").compactMap { $0.string("eventName") }, ["e1", "after"])
    }

    func testQueuedCallsAreHeldDuringAPauseThenSentOnce() async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "track-screen": return .init(status: 429, headers: ["Retry-After": "1"], delayMs: 120)
            case "identify-user": return .init(body: #"{"userId":"uid-a"}"#)
            default: return .init()
            }
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.trackScreen("Home")
        await waitFor { server.count("track-screen") == 1 }
        try await sdk.identifyUser(userName: "alice") // queued behind the track-screen in flight
        await sleepMs(250)
        XCTAssertEqual(server.count("identify-user"), 0, "held while paused")
        await waitFor("sent when the pause ends") { server.count("identify-user") == 1 }
        await sleepMs(300)
        XCTAssertEqual(server.count("identify-user"), 1, "sent once")
        XCTAssertEqual(server.count("track-screen"), 1, "the 429'd call was dropped, not retried")
    }

    func testAnIdentifyDuringAPauseIsHeldLatestOnly() async throws {
        let server = FakeServer()
        server.respond { call in
            switch call.endpoint {
            case "track-event": return .init(status: 429, headers: ["Retry-After": "1"])
            case "identify-user": return .init(body: #"{"userId":"uid-\#(call.string("userName") ?? "")"}"#)
            default: return .init()
            }
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.trackEvent("burst")
        await waitFor { server.count("track-event") == 1 }
        try await sdk.identifyUser(userName: "alice")
        try await sdk.identifyUser(userName: "bob")
        await sleepMs(150)
        XCTAssertEqual(server.count("identify-user"), 0)
        await waitFor("the held identify") { server.count("identify-user") == 1 }
        await sleepMs(200)
        XCTAssertEqual(server.calls("identify-user").map { $0.string("userName") }, ["bob"])
        await waitFor { sdk.userId == "uid-bob" }
    }

    func testAShowForm429PausesShowFormOnly() async throws {
        let server = FakeServer()
        server.respond { $0.endpoint == "show-form" ? .init(status: 429, headers: ["Retry-After": "1"]) : .init() }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        try await sdk.showForm("form-1")
        try await sdk.showForm("form-2")
        XCTAssertEqual(server.count("show-form"), 1)
        try await sdk.trackEvent("still-tracked")
        await waitFor { server.count("track-event") == 1 }
    }

    func testSubmitFormWaitsOutThePauseAndRetriesUpTo3Times() async throws {
        let server = FakeServer()
        server.respond { call in
            guard call.endpoint == "submit-form" else { return .init() }
            return server.count("submit-form") < 3 ? .init(status: 429, headers: ["Retry-After": "0.15"]) : .init()
        }
        let sdk = try await makeTestSDK(server: server)
        defer { sdk.stop() }
        let started = Date()
        try await sdk.submitForm(submitRequest())
        XCTAssertEqual(server.count("submit-form"), 3, "two 429s, then sent")
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(started), 0.28, "waited out both pauses")

        server.respond { $0.endpoint == "submit-form" ? .init(status: 429, headers: ["Retry-After": "0.05"]) : .init() }
        try await sdk.submitForm(submitRequest())
        XCTAssertEqual(server.count("submit-form"), 6, "at most 3 attempts in all")
    }

    func testRetryAfterParsing() {
        XCTAssertEqual(parseRetryAfterMs("120"), 120_000)
        XCTAssertEqual(parseRetryAfterMs(" 1.5 "), 1_500)
        XCTAssertNil(parseRetryAfterMs(nil))
        XCTAssertNil(parseRetryAfterMs(""))
        XCTAssertNil(parseRetryAfterMs("soon"))
        let now = Date(timeIntervalSince1970: 1_763_500_000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        let date = formatter.string(from: now.addingTimeInterval(10))
        XCTAssertEqual(parseRetryAfterMs(date, now: now), 10_000)
        XCTAssertEqual(parseRetryAfterMs(formatter.string(from: now.addingTimeInterval(-5)), now: now), 0)
    }

    func testPauseLengthDefaultsTo60sAndIsCappedAt5Minutes() {
        let logger = RecordingLogger()
        let limiter = RateLimiter(defaultPauseMs: 60_000, maxPauseMs: 300_000, logger: logger)
        defer { limiter.clear() }
        limiter.pauseFor(.tracking, retryAfterMs: nil)
        XCTAssertTrue((59_000...60_000).contains(limiter.remainingMs(.tracking)))
        limiter.pauseFor(.showForm, retryAfterMs: 10 * 60_000)
        XCTAssertTrue((299_000...300_000).contains(limiter.remainingMs(.showForm)))
        limiter.pauseFor(.other, retryAfterMs: 1_000)
        XCTAssertFalse(limiter.isPaused(.other), "only tracking, show-form and submit-form pause")
        limiter.pauseFor(.tracking, retryAfterMs: 120_000) // extends: no new warning
        XCTAssertEqual(logger.warnings.count, 2)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.identifyUser), .tracking)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.ping), .tracking)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.trackScreen), .tracking)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.trackEvent), .tracking)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.showForm), .showForm)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.submitForm), .submitForm)
        XCTAssertEqual(RateLimitBucket.of(endpoint: Endpoints.dismissForm), .other)
    }

    // MARK: retry queue

    func testOneFlushAtATimeNeverSendsAnItemTwice() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeTestDefaults()))
        let attempts = LockedLog()
        await queue.enqueue(label: "trackEvent") {
            attempts.append("a")
            try await Task.sleep(nanoseconds: 150_000_000)
        }
        async let f1: Void = queue.flush()
        async let f2: Void = queue.flush()
        async let f3: Void = queue.flush()
        _ = await (f1, f2, f3)
        await sleepMs(100)
        XCTAssertEqual(attempts.entries.count, 1)
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testBackoffIs2xThen4xTheBaseAnd3AttemptsInAll() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeTestDefaults()), backoffBaseMs: 50)
        let times = LockedTimes()
        await queue.enqueue(label: "trackScreen") {
            times.append(monotonicNowMs())
            throw EncatchApiException(endpoint: "track-screen", status: 503, responseBody: "")
        }
        await waitFor { times.values.count == 3 }
        await sleepMs(400)
        XCTAssertEqual(times.values.count, 3)
        let gaps = zip(times.values.dropFirst(), times.values).map { $0 - $1 }
        XCTAssertGreaterThanOrEqual(gaps[0], 95) // 2 × 50
        XCTAssertGreaterThanOrEqual(gaps[1], 195) // 4 × 50
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testA4xxDropsThatItemAndTheFlushContinues() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeTestDefaults()))
        let log = LockedLog()
        let dropped = LockedFlag()
        await queue.enqueue(label: "trackEvent", onDrop: { dropped.value = true }) {
            log.append("bad")
            throw EncatchApiException(endpoint: "track-event", status: 400, responseBody: "bad")
        }
        await queue.enqueue(label: "trackEvent") { log.append("good") }
        await queue.flush()
        await waitFor { log.entries.contains("good") }
        XCTAssertEqual(log.entries, ["bad", "good"])
        XCTAssertTrue(dropped.value)
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testABackedOffItemDoesNotHoldUpTheItemsBehindIt() async {
        let queue = RetryQueue(storage: EncatchStorage(defaults: makeTestDefaults()), backoffBaseMs: 100)
        let log = LockedLog()
        let failed = LockedFlag()
        await queue.enqueue(label: "trackEvent") {
            log.append("first")
            if !failed.value {
                failed.value = true
                throw EncatchApiException(endpoint: "track-event", status: 0, responseBody: "offline")
            }
        }
        await queue.enqueue(label: "trackEvent") { log.append("second") }
        await waitFor { log.entries.count == 3 }
        XCTAssertEqual(log.entries, ["first", "second", "first"])
    }

    func testEveryRequestTimesOutAfterTheTimeoutAndIsRetried() async throws {
        let server = FakeServer()
        server.respond { call in
            guard call.endpoint == "track-event" else { return .init() }
            return .init(delayMs: server.count("track-event") == 1 ? 5_000 : 0)
        }
        let sdk = try await makeTestSDK(server: server) {
            $0.timings.requestTimeoutMs = 150
            $0.timings.retryBackoffBaseMs = 25
        }
        defer { sdk.stop() }
        let started = Date()
        try await sdk.trackEvent("slow")
        await waitFor("retried after the timeout", timeout: 3) { server.count("track-event") == 2 }
        XCTAssertLessThan(Date().timeIntervalSince(started), 2)
    }

    // MARK: collapse

    func testIdenticalEventsWithinTheWindowAreSentOnce() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server) { $0.timings.collapseWindowMs = 250 }
        defer { sdk.stop() }
        for _ in 0..<5 { try await sdk.trackEvent("tap") }
        try await sdk.trackEvent("other")
        await waitFor { server.count("track-event") == 2 }
        await sleepMs(300)
        try await sdk.trackEvent("tap")
        await waitFor { server.count("track-event") == 3 }
        XCTAssertEqual(server.calls("track-event").compactMap { $0.string("eventName") }, ["tap", "other", "tap"])
    }

    func testTheSameScreenWithinTheWindowIsSentOnceButABackAndForthIsNot() async throws {
        let server = FakeServer()
        let sdk = try await makeTestSDK(server: server) { $0.timings.collapseWindowMs = 1_000 }
        defer { sdk.stop() }
        for screen in ["Home", "Home", "Home", "Cart", "Home", "Home"] { try await sdk.trackScreen(screen) }
        await waitFor { server.count("track-screen") == 3 }
        await sleepMs(150)
        let screens = server.calls("track-screen").compactMap { call -> String? in
            if case .object(let info)? = call.body["$deviceInfo"], case .string(let name)? = info["$urlOrScreenName"] { return name }
            return nil
        }
        XCTAssertEqual(screens, ["Home", "Cart", "Home"])

        // a collapsed call still updates the current screen (it goes with the next ping)
        try await sdk.startSession()
        await waitFor { server.count("ping") >= 1 }
        if case .object(let info)? = server.calls("ping")[0].body["$deviceInfo"] {
            XCTAssertEqual(info["$urlOrScreenName"], .string("Home"))
        } else {
            XCTFail("ping without device info")
        }
    }
}

final class LockedTimes: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Int64] = []
    func append(_ value: Int64) {
        lock.lock()
        items.append(value)
        lock.unlock()
    }
    var values: [Int64] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}
