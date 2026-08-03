import SwiftUI
import Encatch

/// A blocked form, queued for the tester to open in the `InterceptorCarousel`.
struct BlockedFormItem: Identifiable {
    let formId: String
    let title: String
    let questionnaireFields: JSONValue?
    var id: String { formId }
}

/// A single question extracted from `ShowFormResponse.questionnaireFields`. The real schema nests
/// questions under sections in a tree this tester doesn't have a typed model for — so this walks
/// the whole tree and treats any object carrying both a recognizable `type` and `id` key as a
/// question, which is robust to the real `{questions: {id: Question}, sections: [...]}` shape
/// without needing to hard-code it.
struct NativeFormQuestion: Identifiable {
    let id: String
    let type: String
    let title: String
}

func parseQuestionnaireFields(_ questionnaireFields: JSONValue?) -> [NativeFormQuestion] {
    var results: [NativeFormQuestion] = []
    func stringField(_ object: [String: JSONValue], _ key: String) -> String? {
        if case .string(let s)? = object[key] { return s }
        return nil
    }
    func walk(_ element: JSONValue) {
        switch element {
        case .object(let object):
            let type = stringField(object, "type") ?? stringField(object, "questionType")
            let id = stringField(object, "id") ?? stringField(object, "questionId")
            if let type, let id {
                let title = stringField(object, "title") ?? stringField(object, "label") ?? stringField(object, "question") ?? id
                results.append(NativeFormQuestion(id: id, type: type, title: title))
            }
            object.values.forEach(walk)
        case .array(let array):
            array.forEach(walk)
        default:
            break
        }
    }
    if let questionnaireFields { walk(questionnaireFields) }
    return results
}

/// Answerable question types this demo form knows how to draw; everything else falls back to a
/// plain text field. `welcome`/`thank_you` are deliberately excluded — those are display-only
/// markers rendered by `WelcomeStep`/`ThankYouStep`, not answerable questions.
private let renderableTypes: Set<String> = ["rating", "short_answer", "long_text"]

struct InterceptorCarousel: View {
    let items: [BlockedFormItem]
    let onOpen: (BlockedFormItem) -> Void
    let onDismiss: (String) -> Void

    var body: some View {
        if !items.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(items) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(item.title).font(.headline).lineLimit(2)
                                Spacer()
                                Button(action: { onDismiss(item.formId) }) {
                                    Image(systemName: "xmark.circle.fill")
                                }
                            }
                            Text("Blocked by interceptor — tap to open custom UI")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Button("Open") { onOpen(item) }
                                .buttonStyle(.borderedProminent)
                        }
                        .padding(12)
                        .frame(width: 220)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(12)
                    }
                }
                .padding(12)
            }
            .background(Color(.systemBackground).shadow(radius: 4))
        }
    }
}

/// A fully custom, non-WebView native form renderer: welcome -> questions (rating/short-answer/
/// long-text) -> thank-you, driving the SDK manually via `Encatch.shared.emitEvent`/
/// `buildSubmitRequest`/`submitForm`/`dismissForm` instead of the SDK's own modal. Demonstrates the
/// pattern a host app follows after `EncatchConfig.onBeforeShowForm` returns `false`.
struct NativeFormModal: View {
    let item: BlockedFormItem
    let onClose: () -> Void

    @State private var step = 0 // 0 = welcome, 1...questions.count = questions, last = thank you
    @State private var answers: [String: Any] = [:]
    private let questions: [NativeFormQuestion]

    init(item: BlockedFormItem, onClose: @escaping () -> Void) {
        self.item = item
        self.onClose = onClose
        self.questions = parseQuestionnaireFields(item.questionnaireFields).filter { renderableTypes.contains($0.type) }
    }

    var body: some View {
        NavigationView {
            VStack {
                if step == 0 {
                    welcomeStep
                } else if step <= questions.count {
                    questionStep(questions[step - 1])
                } else {
                    thankYouStep
                }
            }
            .padding()
            .navigationTitle("Custom native form")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Close") {
                        Encatch.shared.emitEvent(.formClose, EventPayload(formId: item.formId, timestamp: 0))
                        Task { try? await Encatch.shared.dismissForm(item.formId) }
                        onClose()
                    }
                }
            }
        }
        .onAppear {
            Encatch.shared.emitEvent(.formShow, EventPayload(formId: item.formId, timestamp: 0))
            Encatch.shared.emitEvent(.formStarted, EventPayload(formId: item.formId, timestamp: 0))
        }
    }

    private var welcomeStep: some View {
        VStack(spacing: 16) {
            Spacer()
            Text(item.title).font(.title2).bold()
            Text("A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
            Button("Start") { step = 1 }
                .buttonStyle(.borderedProminent)
            Spacer()
        }
    }

    private var thankYouStep: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("Thank you!").font(.title2).bold()
            Button("Submit") {
                let responses = questions.map { NativeFormResponse(questionId: $0.id, type: $0.type, value: answers[$0.id]) }
                let request = buildSubmitRequest(BuildSubmitRequestOptions(formConfigurationId: item.formId), responses: responses)
                Task {
                    Encatch.shared.emitEvent(.formSubmit, EventPayload(formId: item.formId, timestamp: 0))
                    try? await Encatch.shared.submitForm(request)
                    Encatch.shared.emitEvent(.formComplete, EventPayload(formId: item.formId, timestamp: 0))
                    try? await Encatch.shared.dismissForm(item.formId)
                    await MainActor.run { onClose() }
                }
            }
            .buttonStyle(.borderedProminent)
            Spacer()
        }
    }

    @ViewBuilder
    private func questionStep(_ question: NativeFormQuestion) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(question.title).font(.title3).bold()
            switch question.type {
            case "rating":
                HStack {
                    ForEach(1...5, id: \.self) { star in
                        Button(action: { answers[question.id] = Double(star) }) {
                            Image(systemName: (answers[question.id] as? Double ?? 0) >= Double(star) ? "star.fill" : "star")
                        }
                        .font(.title2)
                    }
                }
            case "long_text":
                TextEditor(text: Binding(
                    get: { answers[question.id] as? String ?? "" },
                    set: { answers[question.id] = $0 }
                ))
                .frame(height: 120)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
            default:
                TextField("Answer", text: Binding(
                    get: { answers[question.id] as? String ?? "" },
                    set: { answers[question.id] = $0 }
                ))
                .textFieldStyle(.roundedBorder)
            }
            Spacer()
            Button("Next") { step += 1 }
                .buttonStyle(.borderedProminent)
        }
    }
}
