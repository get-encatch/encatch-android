import SwiftUI
import EncatchKmpTester

/// A blocked form, queued for the tester to open in the `InterceptorCarousel`.
struct BlockedFormItem: Identifiable {
    let formId: String
    let title: String
    let formConfigJson: String?
    var id: String { formId }
}

struct NativeFormQuestion: Identifiable {
    let id: String
    let type: String
    let title: String
}

/// Walks the whole `questionnaireFields` JSON tree (parsed via `JSONSerialization`) and treats any
/// object carrying both a recognizable `type` and `id` key as a question — robust to the real
/// `{questions: {id: Question}, sections: [...]}` shape without needing to hard-code it (see
/// `encatch-android-tester`'s `NativeForm.kt` for the same approach).
func parseQuestionnaireFields(_ formConfigJson: String?) -> [NativeFormQuestion] {
    guard let formConfigJson, let data = formConfigJson.data(using: .utf8) else { return [] }
    guard let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
    var results: [NativeFormQuestion] = []
    func walk(_ element: Any) {
        if let object = element as? [String: Any] {
            let type = (object["type"] as? String) ?? (object["questionType"] as? String)
            let id = (object["id"] as? String) ?? (object["questionId"] as? String)
            if let type, let id {
                let title = (object["title"] as? String) ?? (object["label"] as? String) ?? (object["question"] as? String) ?? id
                results.append(NativeFormQuestion(id: id, type: type, title: title))
            }
            object.values.forEach(walk)
        } else if let array = element as? [Any] {
            array.forEach(walk)
        }
    }
    walk(root)
    return results
}

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
/// long-text) -> thank-you, driving the SDK manually via `TesterController.shared.emitEvent`/
/// `submitNativeForm`/`dismissForm` instead of the SDK's own modal.
struct NativeFormModal: View {
    let item: BlockedFormItem
    let onClose: () -> Void

    @State private var step = 0
    @State private var answers: [String: String] = [:]
    private let questions: [NativeFormQuestion]

    init(item: BlockedFormItem, onClose: @escaping () -> Void) {
        self.item = item
        self.onClose = onClose
        self.questions = parseQuestionnaireFields(item.formConfigJson).filter { renderableTypes.contains($0.type) }
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
                        TesterController.shared.emitEvent(eventWireValue: "form:close", formId: item.formId)
                        Task { try? await TesterController.shared.dismissForm(formId: item.formId) }
                        onClose()
                    }
                }
            }
        }
        .onAppear {
            TesterController.shared.emitEvent(eventWireValue: "form:show", formId: item.formId)
            TesterController.shared.emitEvent(eventWireValue: "form:started", formId: item.formId)
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
                let questionIds = questions.map { $0.id }
                let types = questions.map { $0.type }
                let values = questions.map { answers[$0.id] ?? "" }
                Task {
                    TesterController.shared.emitEvent(eventWireValue: "form:submit", formId: item.formId)
                    try? await TesterController.shared.submitNativeForm(formId: item.formId, questionIds: questionIds, types: types, values: values)
                    TesterController.shared.emitEvent(eventWireValue: "form:complete", formId: item.formId)
                    try? await TesterController.shared.dismissForm(formId: item.formId)
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
                        Button(action: { answers[question.id] = String(star) }) {
                            Image(systemName: (Double(answers[question.id] ?? "0") ?? 0) >= Double(star) ? "star.fill" : "star")
                        }
                        .font(.title2)
                    }
                }
            case "long_text":
                TextEditor(text: Binding(get: { answers[question.id] ?? "" }, set: { answers[question.id] = $0 }))
                    .frame(height: 120)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
            default:
                TextField("Answer", text: Binding(get: { answers[question.id] ?? "" }, set: { answers[question.id] = $0 }))
                    .textFieldStyle(.roundedBorder)
            }
            Spacer()
            Button("Next") { step += 1 }
                .buttonStyle(.borderedProminent)
        }
    }
}
