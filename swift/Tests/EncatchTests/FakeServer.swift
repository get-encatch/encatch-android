import Foundation
import XCTest
@testable import Encatch

/// An in-memory stand-in for the Encatch API, plugged in through `Encatch.transportForTesting`.
/// Records every request (endpoint, headers, body) and answers with what `respond` returns.
final class FakeServer: @unchecked Sendable {
    struct Call {
        let endpoint: String
        let headers: [String: String]
        let body: [String: JSONValue]

        func header(_ name: String) -> String? { headers[name] }
        func string(_ key: String) -> String? {
            if case .string(let value)? = body[key] { return value }
            return nil
        }
    }

    struct Reply {
        var status: Int = 200
        var headers: [String: String] = [:]
        var body: String = "{}"
        /// Held this long before answering.
        var delayMs: UInt64 = 0
    }

    private let lock = NSLock()
    private var recorded: [Call] = []
    private var responder: (Call) -> Reply = { _ in Reply() }

    /// Sets how the server answers (any thread).
    func respond(_ responder: @escaping (Call) -> Reply) {
        lock.lock()
        self.responder = responder
        lock.unlock()
    }

    var calls: [Call] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func calls(_ endpoint: String) -> [Call] { calls.filter { $0.endpoint == endpoint } }

    func count(_ endpoint: String) -> Int { calls(endpoint).count }

    /// Records the call, then asks the responder (outside the lock: it may read `count`).
    private func record(_ call: Call) -> Reply {
        lock.lock()
        recorded.append(call)
        let responder = self.responder
        lock.unlock()
        return responder(call)
    }

    var transport: HttpTransport {
        { [self] request in
            let url = request.url!.absoluteString
            let endpoint = url.components(separatedBy: "/encatch/api/v2/encatch/").last ?? url
            var body: [String: JSONValue] = [:]
            if let data = request.httpBody, case .object(let object)? = try? JSONDecoder().decode(JSONValue.self, from: data) {
                body = object
            }
            let call = Call(endpoint: endpoint, headers: request.allHTTPHeaderFields ?? [:], body: body)
            let reply = record(call)
            if reply.delayMs > 0 { try await Task.sleep(nanoseconds: reply.delayMs * 1_000_000) }
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: reply.headers)!
            return (Data(reply.body.utf8), response)
        }
    }
}

/// Collects the SDK's log lines.
final class RecordingLogger: EncatchLogger, @unchecked Sendable {
    private let lock = NSLock()
    private var debugLines: [String] = []
    private var warnLines: [String] = []

    func debug(_ message: String) {
        lock.lock()
        debugLines.append(message)
        lock.unlock()
    }

    func warn(_ message: String) {
        lock.lock()
        warnLines.append(message)
        lock.unlock()
    }

    var debugs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return debugLines
    }

    var warnings: [String] {
        lock.lock()
        defer { lock.unlock() }
        return warnLines
    }
}

func makeTestDefaults() -> UserDefaults {
    UserDefaults(suiteName: "com.encatch.tests.\(UUID().uuidString)")!
}

/// A fresh SDK instance on the fake server (never the app's `Encatch.shared`).
func makeTestSDK(
    server: FakeServer,
    defaults: UserDefaults = makeTestDefaults(),
    logger: RecordingLogger = RecordingLogger(),
    debugMode: Bool = false,
    persistUserAcrossRestarts: Bool = true,
    configure: (Encatch) -> Void = { _ in }
) async throws -> Encatch {
    let sdk = Encatch()
    sdk.transportForTesting = server.transport
    sdk.storageDefaultsForTesting = defaults
    sdk.loggerForTesting = logger
    configure(sdk)
    try await sdk.initialize(apiKey: "test-api-key-12345", config: EncatchConfig(apiBaseUrl: "https://api.test", debugMode: debugMode, persistUserAcrossRestarts: persistUserAcrossRestarts))
    return sdk
}

/// Polls `condition` until it holds or `timeout` passes; fails the test on timeout.
func waitFor(
    _ what: String = "condition",
    timeout: TimeInterval = 5,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ condition: () -> Bool
) async {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return }
        try? await Task.sleep(nanoseconds: 20_000_000)
    }
    XCTFail("timed out waiting for \(what)", file: file, line: line)
}

func sleepMs(_ ms: UInt64) async {
    try? await Task.sleep(nanoseconds: ms * 1_000_000)
}

func submitRequest(_ formId: String = "11111111-1111-1111-1111-111111111111") -> SubmitFormRequest {
    SubmitFormRequest(triggerType: "manual", formDetails: FormDetails(formConfigurationId: formId))
}
