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
///
/// **Not public API.** Used only inside the SDK; it may change in any release, including
/// minor ones.
public final class FormWebViewBridge: @unchecked Sendable {
    private static let redirectInternalAfterCloseDelayMs: Int64 = 400
    private static let redirectAfterImmediateCloseDelayMs: Int64 = 50
    /// How long a dismissal waits for the page to save a partial answer (web SDK: 500 ms).
    static let partialBeforeDismissWaitMs: Int64 = 500

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

    // The open survey's session (RN 1.4.4 item 9, Flutter 1.2.0 FormSession, the Kotlin bridge): a
    // close before the final submit is a dismissal; after it (the thank-you screen), after "remind me
    // later" or a call-to-action that closes, it is not. One close per survey.
    private var finalSubmitDone = false
    private var closingForAnotherReason = false
    private var closed = false
    private var readyToDismiss: CheckedContinuation<Void, Never>?
    private var readyToDismissArrived = false

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
        // a dismissal still waiting for the page ends its wait now (it then records, without closing)
        resumeReadyToDismiss()
        webViewReady = false
        pendingCompletionCtaCache = nil
        finalSubmitDone = false
        closingForAnotherReason = false
        closed = false
        readyToDismiss = nil
        readyToDismissArrived = false
    }

    /// The survey's form id for the server: the page's when it sends one, else the UUID of the
    /// show-form reply, else the id it was shown by (RN 1.4.4 item 10: the page may send "").
    private func formIdOf(_ fromPage: String?) -> String? {
        if let fromPage, !fromPage.isEmpty { return fromPage }
        if let uuid = formUuid() { return uuid }
        if let shownBy = formPayload?.formId, !shownBy.isEmpty { return shownBy }
        return nil
    }

    /// The form's UUID, as dismiss-form needs it: the show-form reply's `feedbackConfigurationId`.
    private func formUuid() -> String? {
        guard let id = formPayload?.formConfig.feedbackConfigurationId, !id.isEmpty else { return nil }
        return id
    }

    /// A call-to-action closes the survey: never a dismissal. Only for the actions that do close it.
    private func markClosedByCta() {
        closingForAnotherReason = true
        closed = true
    }

    /// The user closes the survey — the page's ✕, a tap outside it. Before the final submit it is a
    /// dismissal: the page is asked to save a partial answer (`sdk:submitPartialBeforeDismiss`, at most
    /// 500 ms for `form:readyToDismiss`; no wait while it has not loaded), the survey closes and the
    /// dismissal is recorded. Otherwise the survey just closes. Called on the main thread.
    public func requestUserClose() {
        if closed {
            onClose(false)
            return
        }
        closed = true
        guard let payload = formPayload, !finalSubmitDone, !closingForAnotherReason else {
            onClose(false)
            return
        }
        let id = formUuid()
        let waitForPage = webViewReady
        if waitForPage { sendToWebView(SDKMessage(type: .submitPartialBeforeDismiss)) }
        Task { @MainActor [weak self] in
            if waitForPage { await self?.waitForReadyToDismiss() }
            guard let self else { return }
            // still this survey on screen (a second close may have closed it, another may have opened)
            if self.formPayload?.formId == payload.formId && self.formPayload?.formConfig.feedbackConfigurationId == payload.formConfig.feedbackConfigurationId {
                self.onClose(false)
            }
            await Encatch.shared.recordDismissal(id)
        }
    }

    /// Waits for `form:readyToDismiss`, at most [partialBeforeDismissWaitMs].
    @MainActor
    private func waitForReadyToDismiss() async {
        if readyToDismissArrived { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            readyToDismiss = continuation
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(Self.partialBeforeDismissWaitMs) * 1_000_000)
                self?.resumeReadyToDismiss()
            }
        }
    }

    private func resumeReadyToDismiss() {
        readyToDismissArrived = true
        let continuation = readyToDismiss
        readyToDismiss = nil
        continuation?.resume()
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
            // a JSON boolean from the survey page (a string "true"/"false" from older pages); read as a
            // string only, a partial save was sent as a final submit (and counted as the final submit here)
            let isPartial = strictBool(data["isPartialSubmit"]) ?? false
            // the final submit comes before the thank-you screen: a close after it is not a dismissal
            if !isPartial { finalSubmitDone = true }
            let submitReq = SubmitFormRequest(
                triggerType: data.string("triggerType") ?? "manual",
                formDetails: FormDetails(
                    formConfigurationId: formIdOf(data.string("feedbackConfigurationId")) ?? "",
                    feedbackIdentifier: data.string("feedbackIdentifier"),
                    responseLanguageCode: data.string("responseLanguageCode"),
                    isPartialSubmit: isPartial,
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
            finalSubmitDone = true
            closed = true
            let completeFormId = (parsed.formId?.isEmpty == false ? parsed.formId : nil)
                ?? formIdOf(data?.string("feedbackConfigurationId")) ?? ""
            Encatch.shared.emitEvent(.formComplete, EventPayload(formId: completeFormId, timestamp: 0, data: data))
            let completeId = formIdOf(data?.string("feedbackConfigurationId"))
            Task { await Encatch.shared.trackFormEvent("form:complete", completeId) }
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
            Encatch.shared.emitEvent(.formClose, EventPayload(formId: formIdOf(parsed.formId), timestamp: 0, data: data))
            formAnsweredTracked.remove(data?.string("feedbackConfigurationId") ?? parsed.formId ?? "")
            requestUserClose()

        case .readyToDismiss:
            resumeReadyToDismiss()

        case .started:
            Encatch.shared.emitEvent(.formStarted, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            let eventId = formIdOf(data?.string("feedbackConfigurationId"))
            Task { await Encatch.shared.trackFormEvent("form:started", eventId) }

        case .answered:
            Encatch.shared.emitEvent(.formAnswered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            let key = data?.string("feedbackConfigurationId") ?? parsed.formId ?? ""
            if !key.isEmpty, !formAnsweredTracked.contains(key) {
                formAnsweredTracked.insert(key)
                let eventId = formIdOf(data?.string("feedbackConfigurationId"))
            Task { await Encatch.shared.trackFormEvent("form:answered", eventId) }
            }

        case .sectionChange:
            Encatch.shared.emitEvent(.formSectionChange, EventPayload(formId: parsed.formId, timestamp: 0, data: data))

        case .show:
            Encatch.shared.emitEvent(.formShow, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
            let eventId = formIdOf(data?.string("feedbackConfigurationId"))
            Task { await Encatch.shared.trackFormEvent("form:show", eventId) }

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
                guard let bytes = Data(base64Encoded: extractBase64Payload(fileDataBase64)) else {
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
            // not a dismissal: the server re-offers the survey after its wait (RN 1.4.4 item 10b)
            closingForAnotherReason = true
            closed = true
            let id = formIdOf(data?.string("feedbackConfigurationId") ?? parsed.formId)
            Encatch.shared.emitEvent(.formRemindMeLater, EventPayload(formId: id ?? "", timestamp: 0, data: data))
            Task { await Encatch.shared.trackFormEvent("form:remindmelater", id) }
            onClose(false)

        case .ctaTriggered:
            let action = data?.string("action")
            let url = data?.string("url")
            switch action {
            case "app_navigate":
                markClosedByCta()
                Encatch.shared.emitEvent(.formCtaTriggered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
                onClose(false)
            case "redirect_internal":
                if let url {
                    markClosedByCta()
                    onClose(true)
                    Task {
                        try? await Task.sleep(nanoseconds: UInt64(Self.redirectInternalAfterCloseDelayMs) * 1_000_000)
                        await self.redirectOpener.openInternal(url: url)
                        Encatch.shared.emitEvent(.formCtaTriggered, EventPayload(formId: parsed.formId, timestamp: 0, data: data))
                    }
                }
            case "redirect_external":
                if let url {
                    markClosedByCta()
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

        case .closeButton, .themeData, nil:
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

/// A boolean from the survey page: a JSON boolean, or the string "true"/"false"; anything else is no answer.
func strictBool(_ value: JSONValue?) -> Bool? {
    switch value {
    case .bool(let flag): return flag
    case .string("true"): return true
    case .string("false"): return false
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

/// Extracts the raw base64 payload from an upload's `fileData`, mirroring the Flutter SDK's
/// `_extractBase64Payload`. The web engine sends bare base64 for engine-generated content
/// (e.g. a drawn signature, where it strips the canvas data URL itself) but a full
/// `data:<mime>;base64,<payload>` data URL for user-picked files (signature upload mode reads
/// them via `FileReader.readAsDataURL`). `Data(base64Encoded:)` rejects the `data:` header —
/// strip it when present, drop any whitespace, and pass bare base64 through unchanged.
public func extractBase64Payload(_ fileData: String) -> String {
    var payload = fileData.trimmingCharacters(in: .whitespacesAndNewlines)
    if let commaIndex = payload.range(of: ",", options: .backwards)?.lowerBound {
        let header = payload[..<commaIndex].lowercased()
        if header.hasPrefix("data:"), header.contains(";base64") {
            payload = String(payload[payload.index(after: commaIndex)...])
        }
    }
    return payload.filter { !$0.isWhitespace }
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
