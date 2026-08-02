import Foundation

/// Helpers for custom native forms (when using `BeforeShowFormInterceptor`).
/// Use these to build a `SubmitFormRequest` from your native form responses,
/// mirroring `form-helpers.ts`'s `buildSubmitRequest`.

/// A single native-form answer to convert. `value`'s expected shape depends on `type`:
/// - Numeric scales (rating/nps/csat/opinionScale): `Double`/`Int`/`String` (numeric string).
/// - Text (shortAnswer/longText/email/number/website/date): `String` (or anything — coerced via
///   `String(describing:)`).
/// - Single/multi choice, ranking, picture choice, nestedSelection: `String` or `[String]`.
/// - Boolean (yesNo/consent): `Bool`, or `"true"`/`1` (matches the RN helper's loose coercion).
/// - Matrix types: `[String: Any]` (ratingMatrix values may be numeric or `String`).
/// - Complex structured types: pass the matching type directly — `SignatureAnswer`,
///   `[FileUploadAnswerItem]`, `PhoneNumberAnswer`, `AddressAnswer`, `VideoAudioAnswer`,
///   `SchedulerAnswer`, `[QnaWithAiPair]`, `PaymentsUpiAnswer`, `Annotation`.
/// - Display-only types (welcome/thank_you/message_panel/exit_form): `value` is ignored.
public struct NativeFormResponse {
    public var questionId: String
    public var type: String
    public var value: Any?

    public init(questionId: String, type: String, value: Any?) {
        self.questionId = questionId
        self.type = type
        self.value = value
    }
}

public struct BuildSubmitRequestOptions {
    public var formConfigurationId: String
    public var triggerType: String
    public var responseLanguageCode: String?
    public var completionTimeInSeconds: Double?
    public var isPartialSubmit: Bool?
    public var feedbackIdentifier: String?
    /// Arbitrary caller-provided metadata attached to this submission.
    public var context: JSONValue?

    public init(
        formConfigurationId: String,
        triggerType: String = "manual",
        responseLanguageCode: String? = nil,
        completionTimeInSeconds: Double? = nil,
        isPartialSubmit: Bool? = nil,
        feedbackIdentifier: String? = nil,
        context: JSONValue? = nil
    ) {
        self.formConfigurationId = formConfigurationId
        self.triggerType = triggerType
        self.responseLanguageCode = responseLanguageCode
        self.completionTimeInSeconds = completionTimeInSeconds
        self.isPartialSubmit = isPartialSubmit
        self.feedbackIdentifier = feedbackIdentifier
        self.context = context
    }
}

private func toNum(_ v: Any?) -> Double {
    switch v {
    case let n as Double: return n
    case let n as Int: return Double(n)
    case let n as Float: return Double(n)
    case let n as NSNumber: return n.doubleValue
    default:
        if let v { return Double(String(describing: v)) ?? 0.0 }
        return 0.0
    }
}

private func toStr(_ v: Any?) -> String {
    guard let v else { return "" }
    if let s = v as? String { return s }
    return String(describing: v)
}

private func toStrList(_ v: Any?) -> [String] {
    if let list = v as? [Any?] {
        return list.map { toStr($0) }
    }
    if let list = v as? [String] {
        return list
    }
    return [toStr(v)]
}

private func toBool(_ v: Any?) -> Bool {
    if let b = v as? Bool { return b }
    if let s = v as? String { return s == "true" }
    if let n = v as? Int { return n == 1 }
    if let n = v as? Double { return n == 1.0 }
    return false
}

private func toStringMap(_ v: Any?) -> [String: String] {
    guard let map = v as? [String: Any?] else { return [:] }
    return map.mapValues { toStr($0) }
}

private func toStringListMap(_ v: Any?) -> [String: [String]] {
    guard let map = v as? [String: Any?] else { return [:] }
    return map.mapValues { toStrList($0) }
}

private func toRatingMatrix(_ v: Any?) -> [String: JSONValue] {
    guard let map = v as? [String: Any?] else { return [:] }
    return map.mapValues { JSONValue.from(any: $0) }
}

/// Maps a native form question type + value to the `Answer` format. Covers all 33 question
/// types defined in the schema. Unknown types are stored as `shortAnswer` for
/// forward-compatibility, matching the RN helper's fallback.
public func toQuestionAnswer(type: String, value: Any?) -> Answer {
    switch QuestionType.fromWire(type) {
    case .rating: return Answer(rating: toNum(value))
    case .nps: return Answer(nps: toNum(value))
    case .csat: return Answer(csat: toNum(value))
    case .opinionScale: return Answer(opinionScale: toNum(value))

    case .shortAnswer: return Answer(shortAnswer: toStr(value))
    case .longText: return Answer(longText: toStr(value))
    case .email: return Answer(email: toStr(value))
    case .number: return Answer(number: toStr(value))
    case .website: return Answer(website: toStr(value))

    case .singleChoice: return Answer(singleChoice: toStr(value))
    case .multipleChoiceMultiple: return Answer(multipleChoiceMultiple: toStrList(value))
    case .pictureChoice: return Answer(pictureChoice: toStrList(value))
    case .ranking: return Answer(ranking: toStrList(value))

    case .yesNo: return Answer(yesNo: toBool(value))
    case .consent: return Answer(consent: toBool(value))

    case .date: return Answer(date: toStr(value))

    case .ratingMatrix: return Answer(ratingMatrix: toRatingMatrix(value))
    case .matrixSingleChoice: return Answer(matrixSingleChoice: toStringMap(value))
    case .matrixMultipleChoice: return Answer(matrixMultipleChoice: toStringListMap(value))

    case .nestedSelection: return Answer(nestedSelection: toStrList(value))

    case .annotation: return Answer(annotation: value as? Annotation)

    case .signature: return Answer(signature: value as? SignatureAnswer)
    case .fileUpload: return Answer(fileUpload: value as? [FileUploadAnswerItem])
    case .phoneNumber: return Answer(phoneNumber: value as? PhoneNumberAnswer)
    case .address: return Answer(address: value as? AddressAnswer)
    case .videoAudio: return Answer(videoAudio: value as? VideoAudioAnswer)
    case .scheduler: return Answer(scheduler: value as? SchedulerAnswer)
    case .qnaWithAi: return Answer(qnaWithAi: value as? [QnaWithAiPair])
    case .paymentsUpi: return Answer(paymentsUpi: value as? PaymentsUpiAnswer)

    case .welcome, .thankYou, .messagePanel, .exitForm: return Answer()

    case nil: return Answer(shortAnswer: toStr(value))
    }
}

/// Builds a `SubmitFormRequest` from native form responses. Use when you have a custom native
/// form (shown after `Encatch`'s `onBeforeShowForm` interceptor returns `false`) and need to
/// submit to the Encatch API.
///
/// Example:
/// ```swift
/// let responses = [
///     NativeFormResponse(questionId: "q1", type: "rating", value: 5),
///     NativeFormResponse(questionId: "q2", type: "short_answer", value: "Great product!"),
/// ]
/// let request = buildSubmitRequest(
///     BuildSubmitRequestOptions(formConfigurationId: formConfig.feedbackConfigurationId),
///     responses: responses
/// )
/// try await Encatch.shared.submitForm(request)
/// ```
public func buildSubmitRequest(_ options: BuildSubmitRequestOptions, responses: [NativeFormResponse]) -> SubmitFormRequest {
    let questions = responses.map { response in
        QuestionResponse(
            questionId: response.questionId,
            answer: toQuestionAnswer(type: response.type, value: response.value),
            type: response.type
        )
    }

    let formDetails = FormDetails(
        formConfigurationId: options.formConfigurationId,
        feedbackIdentifier: options.feedbackIdentifier,
        responseLanguageCode: options.responseLanguageCode,
        isPartialSubmit: options.isPartialSubmit,
        completionTimeInSeconds: options.completionTimeInSeconds,
        response: FormResponse(questions: questions),
        context: options.context
    )

    return SubmitFormRequest(triggerType: options.triggerType, formDetails: formDetails)
}
