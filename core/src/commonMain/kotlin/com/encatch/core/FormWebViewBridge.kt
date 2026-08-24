package com.encatch.core

import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Handles the `form:*`/`sdk:*` WebView postMessage bridge, mirroring
 * `useEncatchFormWebView.ts`. UI-agnostic — driven by any WebView host (`:android`'s
 * `EncatchWebView`, `:compose`'s WebView content) via [onHeightChange]/[onForceFullHeight]/
 * [onReady]/[onClose]/[sendToWebView].
 */
class FormWebViewBridge(
    private val scope: CoroutineScope,
    private val logTag: String = "Encatch",
    val presentation: String = "modal",
    private val onClose: (immediate: Boolean) -> Unit,
    private val onHeightChange: (Int) -> Unit,
    private val onForceFullHeight: (Boolean) -> Unit,
    private val onReady: () -> Unit,
    private val sendToWebView: (SDKMessage) -> Unit,
    private val redirectOpener: RedirectOpener,
    private val openExternal: (String) -> Unit,
) {
    companion object {
        private const val REDIRECT_INTERNAL_AFTER_CLOSE_DELAY_MS = 400L
        private const val REDIRECT_AFTER_IMMEDIATE_CLOSE_DELAY_MS = 50L
    }

    var formPayload: ShowFormPayload? = null
        private set

    private var webViewReady = false
    private val formAnsweredTracked = mutableSetOf<String>()
    private var pendingCompletionCtaCache: JsonObject? = null

    fun setFormPayload(payload: ShowFormPayload?) {
        formPayload = payload
        webViewReady = false
        pendingCompletionCtaCache = null
    }

    fun handleFormReady() {
        if (webViewReady) return
        val payload = formPayload ?: return

        sendToWebView(
            SDKMessage(
                SDKMessageType.FORM_CONFIG,
                dataJson = buildJsonObject {
                    payload.formConfig.toJson().forEach { (k, v) -> put(k, v) }
                    put("triggerType", payload.triggerType.wireValue)
                    payload.context?.let { ctx -> put("context", buildJsonObject { ctx.forEach { (k, v) -> put(k, v) } }) }
                }.toString(),
            ),
        )

        if (payload.resetMode == ResetMode.ALWAYS) {
            sendToWebView(SDKMessage(SDKMessageType.RESET_DATA))
        }

        val prefill = if (payload.prefillResponses.isNotEmpty()) payload.prefillResponses else Encatch.getPendingResponses()
        if (prefill.isNotEmpty()) {
            sendToWebView(
                SDKMessage(
                    SDKMessageType.PREFILL_RESPONSES,
                    dataJson = buildJsonObject {
                        put(
                            "responses",
                            buildJsonObject {
                                prefill.forEach { (k, v) -> put(k, anyToJsonElement(v)) }
                            },
                        )
                    }.toString(),
                ),
            )
            if (payload.prefillResponses.isEmpty()) Encatch.clearPendingResponses()
        }

        payload.theme?.let {
            sendToWebView(SDKMessage(SDKMessageType.THEME, dataJson = buildJsonObject { put("theme", it.wireValue) }.toString()))
        }
        payload.locale?.let {
            sendToWebView(SDKMessage(SDKMessageType.LOCALE, dataJson = buildJsonObject { put("locale", it) }.toString()))
        }

        webViewReady = true
        onReady()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessage(raw: String) {
        val parsed = runCatching { EncatchJson.decodeFromString(FormMessage.serializer(), raw) }.getOrNull() ?: return
        val data = parsed.data

        when (parsed.messageType) {
            FormMessageType.READY -> handleFormReady()

            FormMessageType.RESIZE -> {
                val height = data?.get("height")?.jsonPrimitive?.doubleOrNull
                if (height != null && height > 0) onHeightChange(height.toInt())
            }

            FormMessageType.LAYOUT -> {
                onForceFullHeight(data?.get("fullHeight")?.jsonPrimitive?.contentOrNull == "true")
            }

            FormMessageType.SUBMIT -> {
                if (data == null) return
                data["pendingCompletionCta"]?.let { pendingCompletionCtaCache = it as? JsonObject }
                val submitReq = SubmitFormRequest(
                    triggerType = data["triggerType"]?.jsonPrimitive?.contentOrNull ?: "manual",
                    formDetails = FormDetails(
                        formConfigurationId = data["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull ?: "",
                        isPartialSubmit = data["isPartialSubmit"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        feedbackIdentifier = data["feedbackIdentifier"]?.jsonPrimitive?.contentOrNull,
                        responseLanguageCode = data["responseLanguageCode"]?.jsonPrimitive?.contentOrNull,
                        response = data["response"]?.let { runCatching { EncatchJson.decodeFromJsonElement(FormResponse.serializer(), it) }.getOrNull() },
                        // Round rather than intOrNull: the web form may report fractional seconds, but the
                        // backend field is i32 — intOrNull would silently drop e.g. "1.5".
                        completionTimeInSeconds = data["completionTimeInSeconds"]?.jsonPrimitive?.doubleOrNull?.roundToInt(),
                        context = data["context"] as? JsonObject,
                        visitedQuestionIds = (data["visitedQuestionIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
                    ),
                )
                scope.launch { runCatching { Encatch.submitForm(submitReq) } }
                Encatch.emitEvent(EventType.FORM_SUBMIT, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
            }

            FormMessageType.COMPLETE -> {
                val completeFormId = parsed.formId?.takeIf { it.isNotEmpty() }
                    ?: data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull ?: ""
                Encatch.emitEvent(EventType.FORM_COMPLETE, EventPayload(formId = completeFormId, timestamp = 0L, data = data))
                scope.launch { Encatch.trackFormEvent("form:complete", data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull) }
                formAnsweredTracked.remove(completeFormId)

                val pending = parsePendingCompletionCta((data?.get("pendingCompletionCta") as? JsonObject) ?: pendingCompletionCtaCache)
                pendingCompletionCtaCache = null
                val closeImmediately = pending != null && pending.action != "dismiss"
                onClose(closeImmediately)
                if (pending != null && completeFormId.isNotEmpty()) {
                    Encatch.pendingCtaScheduler?.schedule(completeFormId, pending)
                }
            }

            FormMessageType.CLOSE -> {
                Encatch.emitEvent(EventType.FORM_CLOSE, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                formAnsweredTracked.remove(data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull ?: parsed.formId ?: "")
                onClose(false)
            }

            FormMessageType.STARTED -> {
                Encatch.emitEvent(EventType.FORM_STARTED, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                scope.launch { Encatch.trackFormEvent("form:started", data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull) }
            }

            FormMessageType.ANSWERED -> {
                Encatch.emitEvent(EventType.FORM_ANSWERED, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                val key = data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull ?: parsed.formId ?: ""
                if (key.isNotEmpty() && formAnsweredTracked.add(key)) {
                    scope.launch { Encatch.trackFormEvent("form:answered", data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull) }
                }
            }

            FormMessageType.SECTION_CHANGE -> {
                Encatch.emitEvent(EventType.FORM_SECTION_CHANGE, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
            }

            FormMessageType.SHOW -> {
                Encatch.emitEvent(EventType.FORM_SHOW, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                scope.launch { Encatch.trackFormEvent("form:show", data?.get("feedbackConfigurationId")?.jsonPrimitive?.contentOrNull) }
            }

            FormMessageType.REFINE_TEXT_REQUEST -> {
                if (data == null) return
                val requestId = data["requestId"]?.jsonPrimitive?.contentOrNull
                scope.launch {
                    val response = runCatching {
                        Encatch.refineText(
                            RefineTextRequest(
                                questionId = data["questionId"]?.jsonPrimitive?.contentOrNull ?: "",
                                feedbackConfigurationId = data["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull ?: "",
                                userText = data["userText"]?.jsonPrimitive?.contentOrNull ?: "",
                            ),
                        )
                    }
                    val payload = if (response.isSuccess) {
                        buildJsonObject {
                            requestId?.let { put("requestId", it) }
                            response.getOrNull()?.refinedText?.let { put("refinedText", it) }
                            response.getOrNull()?.message?.let { put("message", it) }
                        }
                    } else {
                        buildJsonObject {
                            requestId?.let { put("requestId", it) }
                            put("error", "Refine text request failed")
                        }
                    }
                    sendToWebView(SDKMessage(SDKMessageType.REFINE_TEXT_RESPONSE, dataJson = payload.toString()))
                }
            }

            FormMessageType.ERROR -> {
                Encatch.emitEvent(EventType.FORM_ERROR, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
            }

            FormMessageType.UPLOAD_FILE_REQUEST -> {
                if (data == null) return
                val requestId = data["requestId"]?.jsonPrimitive?.contentOrNull
                val feedbackConfigurationId = data["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull ?: ""
                val questionId = data["questionId"]?.jsonPrimitive?.contentOrNull ?: ""
                val fileDataBase64 = data["fileData"]?.jsonPrimitive?.contentOrNull
                val fileName = data["fileName"]?.jsonPrimitive?.contentOrNull ?: "upload"
                val mimeType = uploadMimeType(data["mimeType"]?.jsonPrimitive?.contentOrNull)

                if (fileDataBase64.isNullOrEmpty()) {
                    sendToWebView(
                        SDKMessage(
                            SDKMessageType.UPLOAD_FILE_RESPONSE,
                            dataJson = buildJsonObject { requestId?.let { put("requestId", it) }; put("error", "Missing file data") }.toString(),
                        ),
                    )
                    return
                }

                scope.launch {
                    val result = runCatching {
                        val bytes = Base64.decode(extractBase64Payload(fileDataBase64))
                        Encatch.uploadFile(
                            UploadFileRequest(
                                feedbackConfigurationId = feedbackConfigurationId,
                                questionId = questionId,
                                file = UploadFileSource.Bytes(bytes, mimeType),
                                fileName = fileName,
                                onProgress = { percent ->
                                    sendToWebView(
                                        SDKMessage(
                                            SDKMessageType.UPLOAD_FILE_PROGRESS,
                                            dataJson = buildJsonObject { requestId?.let { put("requestId", it) }; put("percent", percent) }.toString(),
                                        ),
                                    )
                                },
                            ),
                        )
                    }
                    val payload = result.fold(
                        onSuccess = { res -> buildJsonObject { requestId?.let { put("requestId", it) }; put("fileUrl", res.fileUrl) } },
                        onFailure = { err -> buildJsonObject { requestId?.let { put("requestId", it) }; put("error", err.message ?: "Upload failed") } },
                    )
                    sendToWebView(SDKMessage(SDKMessageType.UPLOAD_FILE_RESPONSE, dataJson = payload.toString()))
                }
            }

            FormMessageType.QNA_WITH_AI_REQUEST -> {
                if (data == null) return
                val requestId = data["requestId"]?.jsonPrimitive?.contentOrNull
                val conversation = (data["conversation"] as? JsonArray)?.mapNotNull { turn ->
                    val obj = turn as? JsonObject ?: return@mapNotNull null
                    QnaWithAiConversationTurn(
                        question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                        answer = obj["answer"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } ?: emptyList()

                scope.launch {
                    runCatching {
                        Encatch.streamQnaWithAi(
                            QnaWithAiRequest(
                                feedbackConfigurationId = data["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull ?: "",
                                questionId = data["questionId"]?.jsonPrimitive?.contentOrNull ?: "",
                                conversation = conversation,
                            ),
                            onChunk = { delta ->
                                sendToWebView(
                                    SDKMessage(
                                        SDKMessageType.QNA_WITH_AI_CHUNK,
                                        dataJson = buildJsonObject { requestId?.let { put("requestId", it) }; put("delta", delta) }.toString(),
                                    ),
                                )
                            },
                            onDone = { answer ->
                                sendToWebView(
                                    SDKMessage(
                                        SDKMessageType.QNA_WITH_AI_DONE,
                                        dataJson = buildJsonObject { requestId?.let { put("requestId", it) }; put("answer", answer) }.toString(),
                                    ),
                                )
                            },
                        )
                    }.onFailure { err ->
                        sendToWebView(
                            SDKMessage(
                                SDKMessageType.QNA_WITH_AI_RESPONSE,
                                dataJson = buildJsonObject { requestId?.let { put("requestId", it) }; put("error", err.message ?: "Q&A with AI stream failed") }.toString(),
                            ),
                        )
                    }
                }
            }

            FormMessageType.REMIND_ME_LATER -> {
                Encatch.emitEvent(EventType.FORM_REMIND_ME_LATER, EventPayload(formId = parsed.formId ?: "", timestamp = 0L, data = data))
                onClose(false)
            }

            FormMessageType.CTA_TRIGGERED -> {
                val action = data?.get("action")?.jsonPrimitive?.contentOrNull
                val url = data?.get("url")?.jsonPrimitive?.contentOrNull
                when (action) {
                    "app_navigate" -> {
                        Encatch.emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                        onClose(false)
                    }
                    "redirect_internal" -> if (url != null) {
                        onClose(true)
                        scope.launch {
                            delay(REDIRECT_INTERNAL_AFTER_CLOSE_DELAY_MS)
                            redirectOpener.openInternal(url)
                            Encatch.emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                        }
                    }
                    "redirect_external" -> if (url != null) {
                        onClose(true)
                        scope.launch {
                            delay(REDIRECT_AFTER_IMMEDIATE_CLOSE_DELAY_MS)
                            openExternal(url)
                            Encatch.emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = parsed.formId, timestamp = 0L, data = data))
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    /** Restricts in-WebView navigation to the loaded form's origin+path; everything else opens externally. */
    fun shouldAllowNavigation(requestUrl: String, formWebViewUrl: String, isTopFrame: Boolean = true): Boolean {
        if (!isTopFrame) return true
        if (requestUrl.startsWith("about:blank") || requestUrl.startsWith("data:") || requestUrl.startsWith("blob:")) return true
        if (formWebViewUrl.isEmpty()) return true
        return runCatching {
            val requested = parseUrlHostAndPath(requestUrl)
            val form = parseUrlHostAndPath(formWebViewUrl)
            requested == form
        }.getOrDefault(true)
    }
}

private data class HostAndPath(val host: String?, val path: String?)

/** Minimal, dependency-free URL host+path extraction (no android.net.Uri / java.net.URL needed). */
private fun parseUrlHostAndPath(url: String): HostAndPath {
    val schemeSplit = url.substringAfter("://", missingDelimiterValue = url)
    val authorityAndRest = schemeSplit
    val pathStart = authorityAndRest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (pathStart >= 0) authorityAndRest.substring(0, pathStart) else authorityAndRest
    val rest = if (pathStart >= 0) authorityAndRest.substring(pathStart) else ""
    val path = rest.substringBefore('?').substringBefore('#')
    val host = authority.substringAfter('@').substringBefore(':')
    return HostAndPath(host.ifEmpty { null }, path.ifEmpty { null })
}

private fun ShowFormResponse.toJson(): JsonObject = buildJsonObject {
    put("feedbackConfigurationId", feedbackConfigurationId)
    feedbackIdentifier?.let { put("feedbackIdentifier", it) }
    triggerType?.let { put("triggerType", it.wireValue) }
    formConfiguration?.let { put("formConfiguration", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    questionnaireFields?.let { put("questionnaireFields", it) }
    otherConfigurationProperties?.let { put("otherConfigurationProperties", it) }
    appearanceProperties?.let { put("appearanceProperties", it) }
    partialResponseEnabled?.let { put("partialResponseEnabled", it) }
    contact?.let { put("contact", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    projectI18nFileUrl?.let { put("projectI18nFileUrl", it) }
    pingAgainIn?.let { put("pingAgainIn", it) }
    pingOnNextPageVisit?.let { put("pingOnNextPageVisit", it) }
}

/**
 * Extracts the raw base64 payload from an upload's `fileData`, mirroring the Flutter SDK's
 * `_extractBase64Payload`. The web engine sends bare base64 for engine-generated content
 * (e.g. a drawn signature, where it strips the canvas data URL itself) but a full
 * `data:<mime>;base64,<payload>` data URL for user-picked files (signature upload mode reads
 * them via `FileReader.readAsDataURL`). Strict base64 decoders throw on the `data:` header —
 * strip it when present, drop any whitespace, and pass bare base64 through unchanged.
 */
fun extractBase64Payload(fileData: String): String {
    var payload = fileData.trim()
    val commaIndex = payload.lastIndexOf(',')
    if (commaIndex >= 0) {
        val header = payload.substring(0, commaIndex).lowercase()
        if (header.startsWith("data:") && header.contains(";base64")) {
            payload = payload.substring(commaIndex + 1)
        }
    }
    return payload.replace(Regex("\\s+"), "")
}

/** Normalizes a MIME type string, mirrors `uploadMimeType` from `form-webview-helpers.ts`. */
fun uploadMimeType(mimeType: String?): String {
    val base = mimeType?.substringBefore(';')?.trim()
    return if (base.isNullOrEmpty()) "application/octet-stream" else base
}

/** Native -> WebView: builds the JS snippet that injects an `sdk:*` message, mirrors `injectSDKMessage` in `useEncatchFormWebView.ts`. */
fun buildSdkMessageInjectionScript(message: SDKMessage): String {
    val envelope = buildString {
        append("{\"type\":\"")
        append(message.type.wireValue)
        append("\"")
        if (message.dataJson != null) {
            append(",\"data\":")
            append(message.dataJson)
        }
        append("}")
    }
    return """
        (function () {
            var message = $envelope;
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
    """.trimIndent()
}
