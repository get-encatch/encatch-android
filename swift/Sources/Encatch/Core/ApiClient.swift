import Foundation

/// Thrown for non-2xx responses, mirroring the RN SDK's `[Encatch API] <endpoint> failed with
/// status <n>: <body>`.
public struct EncatchApiException: Error, Sendable, CustomStringConvertible {
    public let endpoint: String
    public let status: Int
    public let responseBody: String

    public init(endpoint: String, status: Int, responseBody: String) {
        self.endpoint = endpoint
        self.status = status
        self.responseBody = responseBody
    }

    public var message: String {
        "[Encatch API] \(endpoint) failed with status \(status): \(responseBody)"
    }

    public var description: String { message }
}

/// One completed SDK HTTP call (request + response), emitted to `Encatch.onNetworkLog` for
/// host-app debugging tools. Covers all JSON POST endpoints; the multipart upload and the
/// Q&A-with-AI SSE stream are not logged (binary/streaming payloads).
public struct EncatchNetworkLogEntry: Sendable {
    public let timestamp: Date
    public let method: String
    public let endpoint: String
    public let url: String
    public let requestHeaders: [String: String]
    public let requestBody: String
    public let status: Int
    public let responseBody: String
    public let durationMs: Int
    public let error: String?
}

/// Thrown when a public method is called before `Encatch.initialize`.
public struct EncatchNotInitializedException: Error, Sendable, CustomStringConvertible {
    public init() {}
    public var message: String { "[Encatch] SDK not initialized" }
    public var description: String { message }
}

enum Endpoints {
    static let identifyUser = "engage-product/encatch/api/v2/encatch/identify-user"
    static let trackEvent = "engage-product/encatch/api/v2/encatch/track-event"
    static let trackScreen = "engage-product/encatch/api/v2/encatch/track-screen"
    static let showForm = "engage-product/encatch/api/v2/encatch/show-form"
    static let dismissForm = "engage-product/encatch/api/v2/encatch/dismiss-form"
    static let ping = "engage-product/encatch/api/v2/encatch/ping"
    static let refineText = "engage-product/encatch/api/v2/encatch/refine-text"
    static let submitForm = "engage-product/encatch/api/v2/encatch/submit-form"
    static let upload = "engage-product/encatch/api/v2/encatch/upload"
    static let qnaWithAiStream = "engage-product/encatch/api/v2/encatch/qna-with-ai/stream"
}

/// Common response metadata present on most Encatch API responses.
struct ResponseMeta {
    let pingAgainIn: Double?
    let pingOnNextPageVisit: Bool?
    let feedbackTransactions: String?
    let userPendingRetryExhausted: Bool
}

extension JSONValue {
    func toResponseMeta() -> ResponseMeta {
        guard case .object(let object) = self else {
            return ResponseMeta(pingAgainIn: nil, pingOnNextPageVisit: nil, feedbackTransactions: nil, userPendingRetryExhausted: false)
        }
        var pingAgainIn: Double?
        if case .number(let value)? = object["pingAgainIn"] { pingAgainIn = value }
        var pingOnNextPageVisit: Bool?
        if case .bool(let value)? = object["pingOnNextPageVisit"] { pingOnNextPageVisit = value }
        var feedbackTransactions: String?
        if case .string(let value)? = object["$feedbackTransactions"] { feedbackTransactions = value }
        var userPendingRetryExhausted = false
        if case .bool(let value)? = object["user_pending_retry_exhausted"] { userPendingRetryExhausted = value }
        return ResponseMeta(
            pingAgainIn: pingAgainIn,
            pingOnNextPageVisit: pingOnNextPageVisit,
            feedbackTransactions: feedbackTransactions,
            userPendingRetryExhausted: userPendingRetryExhausted
        )
    }
}

/// Snapshot of auth-relevant SDK state, used to build request headers per call.
struct AuthState {
    let apiKey: String?
    let sessionId: String?
    let userName: String?
    let userId: String?
    let userSignature: String?
    let deviceId: String?
    let appPackageName: String?
}

/// Reports upload progress via `URLSessionTaskDelegate`, since Foundation's async `upload(for:from:)`
/// only exposes byte-level progress through the delegate callback, not a return value.
private final class UploadProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let onProgress: (Int) -> Void
    private var lastReported = -1

    init(onProgress: @escaping (Int) -> Void) {
        self.onProgress = onProgress
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard totalBytesExpectedToSend > 0 else { return }
        let pct = min(100, max(0, Int((Double(totalBytesSent) / Double(totalBytesExpectedToSend) * 100).rounded())))
        if pct != lastReported {
            lastReported = pct
            onProgress(pct)
        }
    }
}

/// Thin `URLSession`-backed HTTP layer mirroring `_post`/`_buildHeaders` from the RN SDK's
/// `encatch.ts`, plus the SSE (`streamQnaWithAi`) and multipart (`uploadFile`) calls.
final class EncatchApiClient: @unchecked Sendable {
    private let session: URLSession
    private let baseUrlProvider: @Sendable () -> String
    private let authStateProvider: @Sendable () -> AuthState
    private let onUserPendingRetryExhausted: @Sendable () async -> Void
    private let logger: EncatchLogger
    private let networkLogSink: @Sendable (EncatchNetworkLogEntry) -> Void

    init(
        session: URLSession = .shared,
        baseUrlProvider: @escaping @Sendable () -> String,
        authStateProvider: @escaping @Sendable () -> AuthState,
        onUserPendingRetryExhausted: @escaping @Sendable () async -> Void = {},
        logger: EncatchLogger = DefaultEncatchLogger(debugMode: { false }),
        networkLogSink: @escaping @Sendable (EncatchNetworkLogEntry) -> Void = { _ in }
    ) {
        self.session = session
        self.baseUrlProvider = baseUrlProvider
        self.authStateProvider = authStateProvider
        self.onUserPendingRetryExhausted = onUserPendingRetryExhausted
        self.logger = logger
        self.networkLogSink = networkLogSink
    }

    private func buildAuthHeaders(signatureTime: String? = nil) throws -> [String: String] {
        let auth = authStateProvider()
        guard let apiKey = auth.apiKey else { throw EncatchNotInitializedException() }
        var headers: [String: String] = ["X-Api-Key": apiKey]
        if let value = auth.sessionId { headers["X-Session-Id"] = value }
        if let value = auth.userName { headers["X-User-Name"] = value }
        if let value = auth.userId { headers["X-User-Id"] = value }
        if let value = auth.userSignature { headers["X-User-Signature"] = value }
        if let value = auth.deviceId { headers["X-Device-Id"] = value }
        if let signatureTime { headers["X-User-Signature-Time"] = signatureTime }
        if let value = auth.appPackageName { headers["Referer"] = value }
        return headers
    }

    private func redactedHeadersForLog(_ headers: [String: String]) -> String {
        headers.map { key, value in "\(key): \(key == "X-Api-Key" ? "***" : value)" }.joined(separator: "\n")
    }

    /// POSTs `body` to `endpoint`, returns the parsed JSON response.
    func post(endpoint: String, body: JSONValue, signatureTime: String? = nil) async throws -> JSONValue {
        let urlString = "\(baseUrlProvider())/\(endpoint)"
        guard let url = URL(string: urlString) else {
            throw EncatchApiException(endpoint: endpoint, status: 0, responseBody: "Invalid URL: \(urlString)")
        }
        let authHeaders = try buildAuthHeaders(signatureTime: signatureTime)

        logger.debug("POST \(endpoint) -> \(urlString)")
        logger.debug("Request headers:\n\(redactedHeadersForLog(authHeaders))")
        logger.debug("Request body:\n\(body.toJSONString())")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in authHeaders { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let startedAt = Date()
        var logHeaders = authHeaders
        logHeaders["Content-Type"] = "application/json"
        // Even in debugMode, never expose the full API key — keep just the last 5 characters
        // so entries can be matched to a key without being usable as one.
        if let apiKey = logHeaders["X-Api-Key"] {
            logHeaders["X-Api-Key"] = "•••\(apiKey.suffix(5))"
        }
        func emitLog(status: Int, responseBody: String, error: String?) {
            networkLogSink(EncatchNetworkLogEntry(
                timestamp: startedAt,
                method: "POST",
                endpoint: endpoint,
                url: urlString,
                requestHeaders: logHeaders,
                requestBody: body.toJSONString(),
                status: status,
                responseBody: responseBody,
                durationMs: Int(Date().timeIntervalSince(startedAt) * 1000),
                error: error
            ))
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            emitLog(status: 0, responseBody: "", error: error.localizedDescription)
            let apiError = EncatchApiException(endpoint: endpoint, status: 0, responseBody: error.localizedDescription)
            logger.warn(apiError.message)
            throw apiError
        }

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        let responseText = String(data: data, encoding: .utf8) ?? ""
        let parsed = JSONValue.parse(responseText)
        emitLog(status: statusCode, responseBody: responseText, error: nil)

        logger.debug("POST \(endpoint) <- \(statusCode)")
        logger.debug("Response body:\n\(parsed?.toJSONString() ?? responseText)")

        if let parsed, parsed.toResponseMeta().userPendingRetryExhausted {
            await onUserPendingRetryExhausted()
        }

        let isSuccess = (200...299).contains(statusCode)
        guard isSuccess, let parsed else {
            let error = EncatchApiException(endpoint: endpoint, status: statusCode, responseBody: responseText)
            logger.warn(error.message)
            throw error
        }

        return parsed
    }

    /// Multipart file upload with progress, mirrors the RN SDK's XHR-based `uploadFile`.
    func uploadFile(
        feedbackConfigurationId: String,
        questionId: String,
        fileBytes: Data,
        fileName: String,
        mimeType: String?,
        onProgress: ((Int) -> Void)? = nil
    ) async throws -> UploadFileResponse {
        let authHeaders = try buildAuthHeaders()
        let urlString = "\(baseUrlProvider())/\(Endpoints.upload)"
        guard let url = URL(string: urlString) else {
            throw EncatchApiException(endpoint: Endpoints.upload, status: 0, responseBody: "Invalid URL: \(urlString)")
        }

        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        func appendField(name: String, value: String) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        appendField(name: "formId", value: feedbackConfigurationId)
        appendField(name: "questionId", value: questionId)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append(
            "Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!
        )
        body.append("Content-Type: \(mimeType ?? "application/octet-stream")\r\n\r\n".data(using: .utf8)!)
        body.append(fileBytes)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in authHeaders { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        let delegate = onProgress.map { UploadProgressDelegate(onProgress: $0) }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.upload(for: request, from: body, delegate: delegate)
        } catch {
            throw EncatchApiException(endpoint: Endpoints.upload, status: 0, responseBody: error.localizedDescription)
        }

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            var message = "Upload failed (\(statusCode))"
            if case .object(let object)? = JSONValue.parse(text) {
                if case .string(let value)? = object["message"] {
                    message = value
                } else if case .string(let value)? = object["error"] {
                    message = value
                }
            }
            throw EncatchApiException(endpoint: Endpoints.upload, status: statusCode, responseBody: message)
        }

        guard case .object(let object)? = JSONValue.parse(text), case .string(let fileUrl)? = object["fileUrl"] else {
            throw EncatchApiException(endpoint: Endpoints.upload, status: statusCode, responseBody: "Failed to parse upload response")
        }
        return UploadFileResponse(fileUrl: fileUrl)
    }

    /// SSE stream for Q&A with AI: `event: chunk|done|error` frames, `data:` payload
    /// `{delta}`/`{answer}`/`{message}`. Foundation has no built-in SSE client, so frames are parsed
    /// manually from the byte stream — same wire contract as the Ktor `sse` plugin used on Kotlin.
    func streamQnaWithAi(
        feedbackConfigurationId: String,
        questionId: String,
        conversation: [QnaWithAiConversationTurn],
        onChunk: @escaping (String) -> Void,
        onDone: @escaping (String) -> Void
    ) async throws {
        let authHeaders = try buildAuthHeaders()
        let urlString = "\(baseUrlProvider())/\(Endpoints.qnaWithAiStream)"
        guard let url = URL(string: urlString) else {
            throw EncatchApiException(endpoint: Endpoints.qnaWithAiStream, status: 0, responseBody: "Invalid URL: \(urlString)")
        }

        let bodyObject: JSONValue = .object([
            "feedbackConfigurationId": .string(feedbackConfigurationId),
            "questionId": .string(questionId),
            "conversation": .array(conversation.map { turn in
                .object(["question": .string(turn.question), "answer": .string(turn.answer)])
            }),
        ])

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in authHeaders { request.setValue(value, forHTTPHeaderField: key) }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Ktor's SSE plugin (the Android SDK) sends these automatically; SSE backends and
        // intermediaries key on Accept to stream rather than buffer — without it the
        // response can come back non-SSE/empty and the stream "ends without a done event".
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONEncoder().encode(bodyObject)

        var accumulatedAnswer = ""
        var doneOrErrorReceived = false
        var streamError: Error?

        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await session.bytes(for: request)
        } catch {
            throw EncatchApiException(endpoint: Endpoints.qnaWithAiStream, status: 0, responseBody: error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            // Surface the real failure (auth, 4xx/5xx) instead of parsing a non-SSE body to
            // nothing and reporting a bogus "ended without done/error".
            var errorBody = ""
            for try await line in bytes.lines {
                errorBody += line
                if errorBody.count > 2048 { break }
            }
            throw EncatchApiException(endpoint: Endpoints.qnaWithAiStream, status: http.statusCode, responseBody: errorBody)
        }

        var currentEvent: String?
        var currentData = ""

        func processEvent() {
            defer {
                currentEvent = nil
                currentData = ""
            }
            guard let eventName = currentEvent else { return }
            let dataRaw = currentData.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !dataRaw.isEmpty else { return }
            guard case .object(let payload)? = JSONValue.parse(dataRaw) else { return }

            switch eventName {
            case "chunk":
                var delta = ""
                if case .string(let value)? = payload["delta"] { delta = value }
                accumulatedAnswer += delta
                onChunk(delta)
            case "done":
                doneOrErrorReceived = true
                var answer = accumulatedAnswer
                if case .string(let value)? = payload["answer"] { answer = value }
                onDone(answer)
            case "error":
                doneOrErrorReceived = true
                var message = "Stream error"
                if case .string(let value)? = payload["message"] { message = value }
                streamError = EncatchApiException(endpoint: Endpoints.qnaWithAiStream, status: 0, responseBody: message)
            default:
                break
            }
        }

        func handleLine(_ line: String) {
            if line.isEmpty {
                processEvent()
                return
            }
            if line.hasPrefix("event:") {
                currentEvent = line.dropFirst("event:".count).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("data:") {
                let chunk = line.dropFirst("data:".count).trimmingCharacters(in: .whitespaces)
                if !currentData.isEmpty { currentData += "\n" }
                currentData += chunk
            }
        }

        do {
            // Hand-rolled line splitting: `bytes.lines` never emits EMPTY lines (consecutive
            // newlines collapse into one separator), and empty lines are exactly how SSE
            // delimits frames — with .lines, processEvent() would never fire mid-stream and
            // every frame's data would concatenate into one unparseable blob. (Ktor's SSE
            // plugin on Android does its own framing, which is why only iOS broke.)
            var lineBuf = [UInt8]()
            for try await byte in bytes {
                if byte == 0x0A { // \n
                    var line = String(decoding: lineBuf, as: UTF8.self)
                    if line.hasSuffix("\r") { line.removeLast() }
                    handleLine(line)
                    lineBuf.removeAll(keepingCapacity: true)
                } else {
                    lineBuf.append(byte)
                }
            }
            if !lineBuf.isEmpty { handleLine(String(decoding: lineBuf, as: UTF8.self)) }
            // Servers may close the connection right after the final `data:` line without the
            // blank-line frame terminator — flush the pending event or the done frame is lost.
            processEvent()
        } catch {
            throw EncatchApiException(endpoint: Endpoints.qnaWithAiStream, status: 0, responseBody: error.localizedDescription)
        }

        if let streamError { throw streamError }
        if !doneOrErrorReceived {
            throw EncatchApiException(
                endpoint: Endpoints.qnaWithAiStream,
                status: 0,
                responseBody: "[Encatch] Q&A with AI stream ended without a done/error event"
            )
        }
    }
}
