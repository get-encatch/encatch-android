import Foundation

private extension JSONValue {
    // asObject/asArray/asString/asDouble live publicly on JSONValue (JSONValue.swift).

    /// True for JSON `true` and the string `"true"` — bridge messages are loose about which
    /// they send (form:layout's fullHeight is a real bool).
    var asBool: Bool {
        if case .bool(let value) = self { return value }
        if case .string(let value) = self { return value == "true" }
        return false
    }
}

private extension Dictionary where Key == String, Value == JSONValue {
    func string(_ key: String) -> String? { self[key]?.asString }
    func double(_ key: String) -> Double? { self[key]?.asDouble }
    func bool(_ key: String) -> Bool { self[key]?.asBool ?? false }
    func object(_ key: String) -> [String: JSONValue]? { self[key]?.asObject }
    func array(_ key: String) -> [JSONValue]? { self[key]?.asArray }
}

/// Handles the `form:*`/`sdk:*` WebView postMessage bridge, mirroring
/// `useEncatchFormWebView.ts`. UI-agnostic — driven by any WebView host (`EncatchWebView`) via
/// `onHeightChange`/`onForceFullHeight`/`onReady`/`onClose`/`sendToWebView`.
public final class FormWebViewBridge: @unchecked Sendable {
    private static let redirectInternalAfterCloseDelayMs: Int64 = 400
    private static let redirectAfterImmediateCloseDelayMs: Int64 = 50

    private let logTag: String
    let presentation: String
    private let onClose: @Sendable (_ immediate: Bool) -> Void
    private let onHeightChange: @Sendable (Int) -> Void
    private let onForceFullHeight: @Sendable (Bool) -> Void
    private let onReady: @Sendable () -> Void
    private let sendToWebView: @Sendable (SDKMessage) -> Void
    private let redirectOpener: RedirectOpener
    private let openExternal: @Sendable (String) -> Void

    private let lock = NSLock()
    private var _formPayload: ShowFormPayload?
    public var formPayload: ShowFormPayload? {
        lock.lock()
        defer { lock.unlock() }
        return _formPayload
    }

    private var webViewReady = false
    private var formAnsweredTracked = Set<String>()
    private var pendingCompletionCtaCache: [String: JSONValue]?

    public init(
        logTag: String = "Encatch",
        presentation: String = "modal",
        onClose: @escaping @Sendable (_ immediate: Bool) -> Void,
        onHeightChange: @escaping @Sendable (Int) -> Void,
        onForceFullHeight: @escaping @Sendable (Bool) -> Void,
        onReady: @escaping @Sendable () -> Void,
        sendToWebView: @escaping @Sendable (SDKMessage) -> Void,
        redirectOpener: RedirectOpener,
        openExternal: @escaping @Sendable (String) -> Void
    ) {
        self.logTag = logTag
        self.presentation = presentation
        self.onClose = onClose
        self.onHeightChange = onHeightChange
        self.onForceFullHeight = onForceFullHeight
        self.onReady = onReady
        self.sendToWebView = sendToWebView
        self.redirectOpener = redirectOpener
        self.openExternal = openExternal
    }

    public func setFormPayload(_ payload: ShowFormPayload?) {
        lock.lock()
        _formPayload = payload
        lock.unlock()
        webViewReady = false
        pendingCompletionCtaCache = nil
    }

    public func handleFormReady() {
        if webViewReady { return }
        guard let payload = formPayload else { return }

        var configData = payload.formConfig.toJSONObject()
        configData["triggerType"] = .string(payload.triggerType.wireValue)
        if let context = payload.context {
            configData["context"] = .object(context)
        }
        sendToWebView(SDKMessage(type: .formConfig, dataJson: JSONValue.object(configData).toJSONString()))

        if payload.resetMode == .always {
            sendToWebView(SDKMessage(type: .resetData))
        }

        let prefill = !payload.prefillResponses.isEmpty ? payload.prefillResponses : Encatch.shared.getPendingResponses()
        if !prefill.isEmpty {
            sendToWebView(SDKMessage(
                type: .prefillResponses,
                dataJson: JSONValue.object(["responses": .object(prefill)]).toJSONString()
            ))
            if payload.prefillResponses.isEmpty {
                Encatch.shared.clearPendingResponses()
            }
        }

        if let theme = payload.theme {
            sendToWebView(SDKMessage(type: .theme, dataJson: JSONValue.object(["theme": .string(theme.wireValue)]).toJSONString()))
        }
        if let locale = payload.locale {
            sendToWebView(SDKMessage(type: .locale, dataJson: JSONValue.object(["locale": .string(locale)]).toJSONString()))
        }

        webViewReady = true
        onReady()
    }

    public func handleMessage(_ raw: String) {
        guard let rawData = raw.data(using: .utf8),
              let parsed = try? JSONDecoder().decode(FormMessage.self, from: rawData)
        else { return }
        let data = parsed.data?.asObject

        switch parsed.messageType {
        case .ready:
            handleFormReady()

        case .resize:
            if let height = data?.double("height"), height > 0 {
                onHeightChange(Int(height))
            }

        case .layout:
            onForceFullHeight(data?.bool("fullHeight") ?? false)

        case .submit:
            guard let data else { return }
            if let cta = data.object("pendingCompletionCta") {
                pendingCompletionCtaCache = cta
            }
            let response: FormResponse? = data["response"]?.decode(as: FormResponse.self)
            let submitReq = SubmitFormRequest(
                triggerType: data.string("triggerType") ?? "manual",
                formDetails: FormDetails(
                    formConfigurationId: data.string("feedbackConfigurationId") ?? "",
                    feedbackIdentifier: data.string("feedbackIdentifier"),
                    responseLanguageCode: data.string("responseLanguageCode"),
                    isPartialSubmit: parseStrictBool(data.string("isPartialSubmit")) ?? false,
                    // Rounded: the web form may report fractional seconds, but the backend field is i32.
                    completionTimeInSeconds: data.double("completionTimeInSeconds").map { Int($0.rounded()) },
                    response: response,
                    visitedQuestionIds: data.array("visitedQuestionIds")?.compactMap { $0.asString },
                    context: data["context"]?.asObject != nil ? data["context"] : nil
                )
            )
            Task { try? await Encatch.shared.submitForm(submitReq) }
            Encatch.shared.emitEvent(.formSubmit, EventPayload(formId: parsed.formId, timestamp: 0, data: data))

        case .complete:
            let completeFormId = (parsed.formId?.isEmpty == false ? parsed.formId : nil)
                ?? data?.string("feedbackConfigurationId") ?? ""
            Encatch.shared.emitEvent(.formComplete, EventPayload(formId: completeFormId, timestamp: 0, data: data))
            Task { await Encatch.shared.trackFormEvent("form:complete", data?.string("feedbackConfigurationId")) }
            formAnsweredTracked.remove(completeFormId)

            let pending = parsePendingCompletionCta(
                data?.object("pendingCompletionCta").map { JSONValue.object($0) } ?? pendingCompletionCtaCache.map { JSONValue.object($0) }
            )
            pendingCompletionCtaCache = nil
            let closeImmediately = pending != nil && pending?.action != "dismiss"
            onClose(closeImmediately)
            if let pending, !completeFormId.isEmpty {
                Encatch.shared.pendingCtaScheduler?.schedule(formId: completeFormId, pending: pending)
            }

        case .close:
            Encatch.shared.emitEvent(.formClose, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            formAnsweredTracked.remove(data?.string("feedbackConfigurationId") ?? parsed.formId ?? "")
            onClose(false)

        case .started:
            Encatch.shared.emitEvent(.formStarted, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            Task { await Encatch.shared.trackFormEvent("form:started", data?.string("feedbackConfigurationId")) }

        case .answered:
            Encatch.shared.emitEvent(.formAnswered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            let key = data?.string("feedbackConfigurationId") ?? parsed.formId ?? ""
            if !key.isEmpty, !formAnsweredTracked.contains(key) {
                formAnsweredTracked.insert(key)
                Task { await Encatch.shared.trackFormEvent("form:answered", data?.string("feedbackConfigurationId")) }
            }

        case .sectionChange:
            Encatch.shared.emitEvent(.formSectionChange, EventPayload(formId: parsed.formId, timestamp: 0, data: data))

        case .show:
            Encatch.shared.emitEvent(.formShow, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            Task { await Encatch.shared.trackFormEvent("form:show", data?.string("feedbackConfigurationId")) }

        case .refineTextRequest:
            guard let data else { return }
            let requestId = data.string("requestId")
            Task {
                var payload: [String: JSONValue] = [:]
                do {
                    let response = try await Encatch.shared.refineText(RefineTextRequest(
                        questionId: data.string("questionId") ?? "",
                        feedbackConfigurationId: data.string("feedbackConfigurationId") ?? "",
                        userText: data.string("userText") ?? ""
                    ))
                    if let requestId { payload["requestId"] = .string(requestId) }
                    if let refinedText = response.refinedText { payload["refinedText"] = .string(refinedText) }
                    if let message = response.message { payload["message"] = .string(message) }
                } catch {
                    if let requestId { payload["requestId"] = .string(requestId) }
                    payload["error"] = .string("Refine text request failed")
                }
                self.sendToWebView(SDKMessage(type: .refineTextResponse, dataJson: JSONValue.object(payload).toJSONString()))
            }

        case .error:
            Encatch.shared.emitEvent(.formError, EventPayload(formId: parsed.formId, timestamp: 0, data: data))

        case .uploadFileRequest:
            guard let data else { return }
            let requestId = data.string("requestId")
            let feedbackConfigurationId = data.string("feedbackConfigurationId") ?? ""
            let questionId = data.string("questionId") ?? ""
            let fileDataBase64 = data.string("fileData")
            let fileName = data.string("fileName") ?? "upload"
            let mimeType = uploadMimeType(data.string("mimeType"))

            guard let fileDataBase64, !fileDataBase64.isEmpty else {
                var payload: [String: JSONValue] = [:]
                if let requestId { payload["requestId"] = .string(requestId) }
                payload["error"] = .string("Missing file data")
                sendToWebView(SDKMessage(type: .uploadFileResponse, dataJson: JSONValue.object(payload).toJSONString()))
                return
            }

            Task {
                var payload: [String: JSONValue] = [:]
                guard let bytes = Data(base64Encoded: fileDataBase64) else {
                    if let requestId { payload["requestId"] = .string(requestId) }
                    payload["error"] = .string("Upload failed")
                    self.sendToWebView(SDKMessage(type: .uploadFileResponse, dataJson: JSONValue.object(payload).toJSONString()))
                    return
                }
                do {
                    let result = try await Encatch.shared.uploadFile(UploadFileRequest(
                        feedbackConfigurationId: feedbackConfigurationId,
                        questionId: questionId,
                        file: .bytes(bytes, mimeType: mimeType),
                        fileName: fileName,
                        onProgress: { percent in
                            var progressPayload: [String: JSONValue] = [:]
                            if let requestId { progressPayload["requestId"] = .string(requestId) }
                            progressPayload["percent"] = .number(Double(percent))
                            self.sendToWebView(SDKMessage(type: .uploadFileProgress, dataJson: JSONValue.object(progressPayload).toJSONString()))
                        }
                    ))
                    if let requestId { payload["requestId"] = .string(requestId) }
                    payload["fileUrl"] = .string(result.fileUrl)
                } catch {
                    if let requestId { payload["requestId"] = .string(requestId) }
                    payload["error"] = .string((error as? EncatchApiException)?.message ?? "Upload failed")
                }
                self.sendToWebView(SDKMessage(type: .uploadFileResponse, dataJson: JSONValue.object(payload).toJSONString()))
            }

        case .qnaWithAiRequest:
            guard let data else { return }
            let requestId = data.string("requestId")
            let conversation: [QnaWithAiConversationTurn] = (data.array("conversation") ?? []).compactMap { turn in
                guard let obj = turn.asObject else { return nil }
                return QnaWithAiConversationTurn(question: obj.string("question") ?? "", answer: obj.string("answer") ?? "")
            }

            Task {
                do {
                    try await Encatch.shared.streamQnaWithAi(
                        QnaWithAiRequest(
                            feedbackConfigurationId: data.string("feedbackConfigurationId") ?? "",
                            questionId: data.string("questionId") ?? "",
                            conversation: conversation
                        ),
                        onChunk: { delta in
                            var chunkPayload: [String: JSONValue] = [:]
                            if let requestId { chunkPayload["requestId"] = .string(requestId) }
                            chunkPayload["delta"] = .string(delta)
                            self.sendToWebView(SDKMessage(type: .qnaWithAiChunk, dataJson: JSONValue.object(chunkPayload).toJSONString()))
                        },
                        onDone: { answer in
                            var donePayload: [String: JSONValue] = [:]
                            if let requestId { donePayload["requestId"] = .string(requestId) }
                            donePayload["answer"] = .string(answer)
                            self.sendToWebView(SDKMessage(type: .qnaWithAiDone, dataJson: JSONValue.object(donePayload).toJSONString()))
                        }
                    )
                } catch {
                    var errorPayload: [String: JSONValue] = [:]
                    if let requestId { errorPayload["requestId"] = .string(requestId) }
                    errorPayload["error"] = .string((error as? EncatchApiException)?.message ?? "Q&A with AI stream failed")
                    self.sendToWebView(SDKMessage(type: .qnaWithAiResponse, dataJson: JSONValue.object(errorPayload).toJSONString()))
                }
            }

        case .remindMeLater:
            Encatch.shared.emitEvent(.formRemindMeLater, EventPayload(formId: parsed.formId ?? "", timestamp: 0, data: data))
            onClose(false)

        case .ctaTriggered:
            let action = data?.string("action")
            let url = data?.string("url")
            switch action {
            case "app_navigate":
                Encatch.shared.emitEvent(.formCtaTriggered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
                onClose(false)
            case "redirect_internal":
                if let url {
                    onClose(true)
                    Task {
                        try? await Task.sleep(nanoseconds: UInt64(Self.redirectInternalAfterCloseDelayMs) * 1_000_000)
                        await self.redirectOpener.openInternal(url: url)
                        Encatch.shared.emitEvent(.formCtaTriggered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
                    }
                }
            case "redirect_external":
                if let url {
                    onClose(true)
                    Task {
                        try? await Task.sleep(nanoseconds: UInt64(Self.redirectAfterImmediateCloseDelayMs) * 1_000_000)
                        self.openExternal(url)
                        Encatch.shared.emitEvent(.formCtaTriggered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
                    }
                }
            default:
                break
            }

        case .closeButton, .themeData, .readyToDismiss, nil:
            break
        }
    }

    /// Restricts in-WebView navigation to the loaded form's origin+path; everything else opens externally.
    public func shouldAllowNavigation(requestUrl: String, formWebViewUrl: String, isTopFrame: Bool = true) -> Bool {
        if !isTopFrame { return true }
        if requestUrl.hasPrefix("about:blank") || requestUrl.hasPrefix("data:") || requestUrl.hasPrefix("blob:") { return true }
        if formWebViewUrl.isEmpty { return true }
        let requested = parseUrlHostAndPath(requestUrl)
        let form = parseUrlHostAndPath(formWebViewUrl)
        return requested == form
    }
}

private func parseStrictBool(_ value: String?) -> Bool? {
    switch value {
    case "true": return true
    case "false": return false
    default: return nil
    }
}

private struct HostAndPath: Equatable {
    let host: String?
    let path: String?
}

/// Minimal, dependency-free URL host+path extraction (no `URL(string:)` needed — the Kotlin
/// version is deliberately lenient about malformed URLs, which `URL(string:)`'s stricter RFC 3986
/// parsing would reject).
private func parseUrlHostAndPath(_ url: String) -> HostAndPath {
    let schemeSplit: String
    if let range = url.range(of: "://") {
        schemeSplit = String(url[range.upperBound...])
    } else {
        schemeSplit = url
    }
    let authorityAndRest = schemeSplit
    let pathStartIndex = authorityAndRest.firstIndex { $0 == "/" || $0 == "?" || $0 == "#" }
    let authority: String
    let rest: String
    if let pathStartIndex {
        authority = String(authorityAndRest[authorityAndRest.startIndex..<pathStartIndex])
        rest = String(authorityAndRest[pathStartIndex...])
    } else {
        authority = authorityAndRest
        rest = ""
    }
    let path = rest.components(separatedBy: "?")[0].components(separatedBy: "#")[0]
    let hostWithPort = authority.contains("@") ? String(authority[authority.index(after: authority.range(of: "@", options: .backwards)!.lowerBound)...]) : authority
    let host = hostWithPort.components(separatedBy: ":")[0]
    return HostAndPath(host: host.isEmpty ? nil : host, path: path.isEmpty ? nil : path)
}

private extension ShowFormResponse {
    func toJSONObject() -> [String: JSONValue] {
        var result: [String: JSONValue] = ["feedbackConfigurationId": .string(feedbackConfigurationId)]
        if let feedbackIdentifier { result["feedbackIdentifier"] = .string(feedbackIdentifier) }
        if let triggerType { result["triggerType"] = .string(triggerType.wireValue) }
        if let formConfiguration { result["formConfiguration"] = .object(formConfiguration) }
        if let questionnaireFields { result["questionnaireFields"] = questionnaireFields }
        if let otherConfigurationProperties { result["otherConfigurationProperties"] = otherConfigurationProperties }
        if let appearanceProperties { result["appearanceProperties"] = appearanceProperties }
        if let partialResponseEnabled { result["partialResponseEnabled"] = .bool(partialResponseEnabled) }
        if let contact { result["contact"] = .object(contact) }
        if let projectI18nFileUrl { result["projectI18nFileUrl"] = .string(projectI18nFileUrl) }
        if let pingAgainIn { result["pingAgainIn"] = .number(pingAgainIn) }
        if let pingOnNextPageVisit { result["pingOnNextPageVisit"] = .bool(pingOnNextPageVisit) }
        return result
    }
}

/// Normalizes a MIME type string, mirrors `uploadMimeType` from `form-webview-helpers.ts`.
public func uploadMimeType(_ mimeType: String?) -> String {
    let base = mimeType?.components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces)
    return (base?.isEmpty ?? true) ? "application/octet-stream" : base!
}

/// Native -> WebView: builds the JS snippet that injects an `sdk:*` message, mirrors
/// `injectSDKMessage` in `useEncatchFormWebView.ts`.
public func buildSdkMessageInjectionScript(message: SDKMessage) -> String {
    var envelope = "{\"type\":\"\(message.type.wireValue)\""
    if let dataJson = message.dataJson {
        envelope += ",\"data\":\(dataJson)"
    }
    envelope += "}"
    return """
        (function () {
            var message = \(envelope);
            if (typeof window.__encatchReceiveSDKMessage === 'function') {
                window.__encatchReceiveSDKMessage(message);
                return true;
            }
            window.__encatchSDKMessageQueue = window.__encatchSDKMessageQueue || [];
            window.__encatchSDKMessageQueue.push(message);
            window.dispatchEvent(new MessageEvent('message', { data: message }));
            return true;
        })();
        true;
        """
}
