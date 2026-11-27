import XCTest
@testable import Encatch

/// Removing items while one is being sent (identify's user switch, hold-only-latest during a 429
/// pause, reset): the one in flight is left to finish and reported only if it fails, never retried.
final class RetryQueueInFlightTests: XCTestCase {
    private func newQueue() -> RetryQueue {
        RetryQueue(storage: EncatchStorage(defaults: makeTestDefaults()), backoffBaseMs: 50)
    }

    func testRemovingLeavesTheItemInFlightToFinishAndReportsItOnlyIfItFails() async {
        let queue = newQueue()
        let attempts = CallCounter()
        let droppedInFlight = CallCounter()
        let droppedQueued = CallCounter()
        await queue.enqueue(label: "identifyUser", onDrop: { _ = droppedInFlight.next() }) {
            _ = attempts.next()
            try await Task.sleep(nanoseconds: 300_000_000)
            throw EncatchApiException(endpoint: "identify-user", status: 503, responseBody: "unavailable")
        }
        await queue.enqueue(label: "identifyUser", onDrop: { _ = droppedQueued.next() }) {}
        await waitFor { attempts.current == 1 }
        let removed = await queue.removeQueued(label: "identifyUser")
        XCTAssertEqual(removed, 1, "only the one not yet sent counts")
        XCTAssertEqual(droppedQueued.current, 1)
        XCTAssertEqual(droppedInFlight.current, 0, "the one in flight may still succeed")
        await waitFor { droppedInFlight.current == 1 }
        await sleepMs(300)
        XCTAssertEqual(attempts.current, 1, "a removed item that fails is not retried")
        let size = await queue.queueSize()
        XCTAssertEqual(size, 0)
    }

    func testAnItemRemovedInFlightThatSucceedsIsNeverReportedDropped() async {
        let queue = newQueue()
        let dropped = CallCounter()
        let started = CallCounter()
        await queue.enqueue(label: "identifyUser", onDrop: { _ = dropped.next() }) {
            _ = started.next()
            try await Task.sleep(nanoseconds: 300_000_000)
        }
        await waitFor { started.current == 1 }
        let removed = await queue.removeQueued(label: "identifyUser")
        XCTAssertEqual(removed, 0)
        await sleepMs(500)
        XCTAssertEqual(dropped.current, 0)
    }

    func testAnItemInFlightWhenTheQueueIsClearedIsNotRetriedIfItFails() async {
        let queue = newQueue()
        let attempts = CallCounter()
        let dropped = CallCounter()
        await queue.enqueue(label: "identifyUser", onDrop: { _ = dropped.next() }) {
            _ = attempts.next()
            try await Task.sleep(nanoseconds: 300_000_000)
            throw EncatchApiException(endpoint: "identify-user", status: 503, responseBody: "unavailable")
        }
        await waitFor { attempts.current == 1 }
        await queue.clear()
        await waitFor { dropped.current == 1 }
        await sleepMs(300)
        XCTAssertEqual(attempts.current, 1, "not retried")
    }
}
