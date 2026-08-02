import Foundation

/// Wire-format answer types, ported 1:1 from `@encatch/schema`'s
/// `answer-schema.ts` (the source of truth shared by web/RN/Android SDKs).
/// Field names are the exact JSON keys sent to the Encatch backend.

public struct AnnotationMarker: Codable, Sendable, Equatable {
    public var markerNo: String
    public var timeline: String
    public var comment: String

    public init(markerNo: String, timeline: String, comment: String) {
        self.markerNo = markerNo
        self.timeline = timeline
        self.comment = comment
    }
}

public struct Annotation: Codable, Sendable, Equatable {
    public var fileType: String
    public var fileName: String
    public var markers: [AnnotationMarker]

    public init(fileType: String, fileName: String, markers: [AnnotationMarker] = []) {
        self.fileType = fileType
        self.fileName = fileName
        self.markers = markers
    }
}

/// mode: "type" | "draw" | "upload"
public struct SignatureAnswer: Codable, Sendable, Equatable {
    public var mode: String
    public var fileUrl: String?
    public var typedName: String?

    public init(mode: String, fileUrl: String? = nil, typedName: String? = nil) {
        self.mode = mode
        self.fileUrl = fileUrl
        self.typedName = typedName
    }
}

public struct FileUploadAnswerItem: Codable, Sendable, Equatable {
    public var fileUrl: String
    public var fileName: String
    public var fileSizeMb: Double
    public var mimeType: String?

    public init(fileUrl: String, fileName: String, fileSizeMb: Double, mimeType: String? = nil) {
        self.fileUrl = fileUrl
        self.fileName = fileName
        self.fileSizeMb = fileSizeMb
        self.mimeType = mimeType
    }
}

public struct PhoneNumberAnswer: Codable, Sendable, Equatable {
    public var countryCode: String
    public var number: String
    public var e164: String?

    public init(countryCode: String, number: String, e164: String? = nil) {
        self.countryCode = countryCode
        self.number = number
        self.e164 = e164
    }
}

public struct AddressAnswer: Codable, Sendable, Equatable {
    public var addressLine1: String?
    public var addressLine2: String?
    public var city: String?
    public var stateProvince: String?
    public var postalCode: String?
    public var country: String?

    public init(
        addressLine1: String? = nil,
        addressLine2: String? = nil,
        city: String? = nil,
        stateProvince: String? = nil,
        postalCode: String? = nil,
        country: String? = nil
    ) {
        self.addressLine1 = addressLine1
        self.addressLine2 = addressLine2
        self.city = city
        self.stateProvince = stateProvince
        self.postalCode = postalCode
        self.country = country
    }
}

/// mode: "video" | "audio" | "photo" | "text"
public struct VideoAudioAnswer: Codable, Sendable, Equatable {
    public var mode: String
    public var fileUrl: String?
    public var text: String?
    public var durationSeconds: Double?
    public var transcriptText: String?

    public init(
        mode: String,
        fileUrl: String? = nil,
        text: String? = nil,
        durationSeconds: Double? = nil,
        transcriptText: String? = nil
    ) {
        self.mode = mode
        self.fileUrl = fileUrl
        self.text = text
        self.durationSeconds = durationSeconds
        self.transcriptText = transcriptText
    }
}

/// provider: "google_calendar" | "calendly" — discriminated union, matches the schema's
/// `z.discriminatedUnion`. Encodes/decodes with a top-level `"provider"` field rather than Swift's
/// default nested-enum encoding, so the wire JSON matches exactly what the backend expects.
public enum SchedulerAnswer: Codable, Sendable, Equatable {
    case googleCalendar(bookedAt: String)
    case calendly(slotStart: String, slotEnd: String, eventId: String?, bookedAt: String)

    public var bookedAt: String {
        switch self {
        case .googleCalendar(let bookedAt): return bookedAt
        case .calendly(_, _, _, let bookedAt): return bookedAt
        }
    }

    private enum CodingKeys: String, CodingKey {
        case provider
        case slotStart
        case slotEnd
        case eventId
        case bookedAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let provider = try container.decode(String.self, forKey: .provider)
        let bookedAt = try container.decode(String.self, forKey: .bookedAt)
        switch provider {
        case "calendly":
            let slotStart = try container.decode(String.self, forKey: .slotStart)
            let slotEnd = try container.decode(String.self, forKey: .slotEnd)
            let eventId = try container.decodeIfPresent(String.self, forKey: .eventId)
            self = .calendly(slotStart: slotStart, slotEnd: slotEnd, eventId: eventId, bookedAt: bookedAt)
        case "google_calendar":
            self = .googleCalendar(bookedAt: bookedAt)
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .provider,
                in: container,
                debugDescription: "Unknown scheduler provider: \(provider)"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .googleCalendar(let bookedAt):
            try container.encode("google_calendar", forKey: .provider)
            try container.encode(bookedAt, forKey: .bookedAt)
        case .calendly(let slotStart, let slotEnd, let eventId, let bookedAt):
            try container.encode("calendly", forKey: .provider)
            try container.encode(slotStart, forKey: .slotStart)
            try container.encode(slotEnd, forKey: .slotEnd)
            try container.encodeIfPresent(eventId, forKey: .eventId)
            try container.encode(bookedAt, forKey: .bookedAt)
        }
    }
}

public struct QnaWithAiPair: Codable, Sendable, Equatable {
    public var question: String
    public var answer: String

    public init(question: String, answer: String) {
        self.question = question
        self.answer = answer
    }
}

public struct PaymentsUpiAnswer: Codable, Sendable, Equatable {
    public var transactionId: String
    public var encatchPaymentReference: String
    public var amount: String
    public var currency: String
    public var payeeVpa: String
    public var payeeName: String?
    public var sourceEmail: String?
    public var upiIntentUri: String?
    public var selfReported: Bool

    public init(
        transactionId: String,
        encatchPaymentReference: String,
        amount: String,
        currency: String = "INR",
        payeeVpa: String,
        payeeName: String? = nil,
        sourceEmail: String? = nil,
        upiIntentUri: String? = nil,
        selfReported: Bool = true
    ) {
        self.transactionId = transactionId
        self.encatchPaymentReference = encatchPaymentReference
        self.amount = amount
        self.currency = currency
        self.payeeVpa = payeeVpa
        self.payeeName = payeeName
        self.sourceEmail = sourceEmail
        self.upiIntentUri = upiIntentUri
        self.selfReported = selfReported
    }
}

/// Flexible answer item — matches `AnswerItemSchema`. Only the field(s) matching the
/// question's `QuestionType` are populated. `ratingMatrix` values are number-or-string
/// per the schema (`z.union([z.number(), z.string()])`), modeled as `JSONValue`.
public struct Answer: Codable, Sendable, Equatable {
    public var nps: Double?
    public var nestedSelection: [String]?
    public var longText: String?
    public var shortAnswer: String?
    public var singleChoice: String?
    public var rating: Double?
    public var yesNo: Bool?
    public var consent: Bool?
    public var multipleChoiceMultiple: [String]?
    public var singleChoiceOther: String?
    public var multipleChoiceOther: String?
    public var annotation: Annotation?
    public var ratingMatrix: [String: JSONValue]?
    public var matrixSingleChoice: [String: String]?
    public var matrixMultipleChoice: [String: [String]]?
    public var others: String?
    public var date: String?
    public var csat: Double?
    public var opinionScale: Double?
    public var ranking: [String]?
    public var pictureChoice: [String]?
    public var pictureChoiceOther: String?
    public var signature: SignatureAnswer?
    public var fileUpload: [FileUploadAnswerItem]?
    public var email: String?
    /// Submitted numeric value as a string, matching the schema.
    public var number: String?
    public var website: String?
    public var phoneNumber: PhoneNumberAnswer?
    public var address: AddressAnswer?
    public var videoAudio: VideoAudioAnswer?
    public var scheduler: SchedulerAnswer?
    public var qnaWithAi: [QnaWithAiPair]?
    public var paymentsUpi: PaymentsUpiAnswer?

    public init(
        nps: Double? = nil,
        nestedSelection: [String]? = nil,
        longText: String? = nil,
        shortAnswer: String? = nil,
        singleChoice: String? = nil,
        rating: Double? = nil,
        yesNo: Bool? = nil,
        consent: Bool? = nil,
        multipleChoiceMultiple: [String]? = nil,
        singleChoiceOther: String? = nil,
        multipleChoiceOther: String? = nil,
        annotation: Annotation? = nil,
        ratingMatrix: [String: JSONValue]? = nil,
        matrixSingleChoice: [String: String]? = nil,
        matrixMultipleChoice: [String: [String]]? = nil,
        others: String? = nil,
        date: String? = nil,
        csat: Double? = nil,
        opinionScale: Double? = nil,
        ranking: [String]? = nil,
        pictureChoice: [String]? = nil,
        pictureChoiceOther: String? = nil,
        signature: SignatureAnswer? = nil,
        fileUpload: [FileUploadAnswerItem]? = nil,
        email: String? = nil,
        number: String? = nil,
        website: String? = nil,
        phoneNumber: PhoneNumberAnswer? = nil,
        address: AddressAnswer? = nil,
        videoAudio: VideoAudioAnswer? = nil,
        scheduler: SchedulerAnswer? = nil,
        qnaWithAi: [QnaWithAiPair]? = nil,
        paymentsUpi: PaymentsUpiAnswer? = nil
    ) {
        self.nps = nps
        self.nestedSelection = nestedSelection
        self.longText = longText
        self.shortAnswer = shortAnswer
        self.singleChoice = singleChoice
        self.rating = rating
        self.yesNo = yesNo
        self.consent = consent
        self.multipleChoiceMultiple = multipleChoiceMultiple
        self.singleChoiceOther = singleChoiceOther
        self.multipleChoiceOther = multipleChoiceOther
        self.annotation = annotation
        self.ratingMatrix = ratingMatrix
        self.matrixSingleChoice = matrixSingleChoice
        self.matrixMultipleChoice = matrixMultipleChoice
        self.others = others
        self.date = date
        self.csat = csat
        self.opinionScale = opinionScale
        self.ranking = ranking
        self.pictureChoice = pictureChoice
        self.pictureChoiceOther = pictureChoiceOther
        self.signature = signature
        self.fileUpload = fileUpload
        self.email = email
        self.number = number
        self.website = website
        self.phoneNumber = phoneNumber
        self.address = address
        self.videoAudio = videoAudio
        self.scheduler = scheduler
        self.qnaWithAi = qnaWithAi
        self.paymentsUpi = paymentsUpi
    }
}

public struct QuestionResponse: Codable, Sendable, Equatable {
    public var questionId: String
    public var answer: Answer?
    public var type: String?
    public var error: String?
    public var isOnPath: Bool?
    public var timeSpentMs: Int64?
    public var isPathTraversed: Bool?

    public init(
        questionId: String,
        answer: Answer? = nil,
        type: String? = nil,
        error: String? = nil,
        isOnPath: Bool? = nil,
        timeSpentMs: Int64? = nil,
        isPathTraversed: Bool? = nil
    ) {
        self.questionId = questionId
        self.answer = answer
        self.type = type
        self.error = error
        self.isOnPath = isOnPath
        self.timeSpentMs = timeSpentMs
        self.isPathTraversed = isPathTraversed
    }
}

public struct FormResponse: Codable, Sendable, Equatable {
    public var questions: [QuestionResponse]?
    public var context: JSONValue?
    public var contact: JSONValue?
    public var sourceTrackingFieldValues: [String: String]?

    public init(
        questions: [QuestionResponse]? = nil,
        context: JSONValue? = nil,
        contact: JSONValue? = nil,
        sourceTrackingFieldValues: [String: String]? = nil
    ) {
        self.questions = questions
        self.context = context
        self.contact = contact
        self.sourceTrackingFieldValues = sourceTrackingFieldValues
    }
}

public struct FormDetails: Codable, Sendable, Equatable {
    public var formConfigurationId: String
    public var feedbackIdentifier: String?
    public var responseLanguageCode: String?
    public var isPartialSubmit: Bool?
    public var completionTimeInSeconds: Double?
    public var response: FormResponse?
    public var visitedQuestionIds: [String]?
    public var context: JSONValue?

    public init(
        formConfigurationId: String,
        feedbackIdentifier: String? = nil,
        responseLanguageCode: String? = nil,
        isPartialSubmit: Bool? = nil,
        completionTimeInSeconds: Double? = nil,
        response: FormResponse? = nil,
        visitedQuestionIds: [String]? = nil,
        context: JSONValue? = nil
    ) {
        self.formConfigurationId = formConfigurationId
        self.feedbackIdentifier = feedbackIdentifier
        self.responseLanguageCode = responseLanguageCode
        self.isPartialSubmit = isPartialSubmit
        self.completionTimeInSeconds = completionTimeInSeconds
        self.response = response
        self.visitedQuestionIds = visitedQuestionIds
        self.context = context
    }
}

public struct SubmitFormRequest: Codable, Sendable, Equatable {
    /// "automatic" | "manual"
    public var triggerType: String?
    public var formDetails: FormDetails

    public init(triggerType: String? = nil, formDetails: FormDetails) {
        self.triggerType = triggerType
        self.formDetails = formDetails
    }
}
