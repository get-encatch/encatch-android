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
                            HStack(alignment: .top) {
                                Image(systemName: "hand.raised.fill")
                                    .font(.subheadline)
                                    .foregroundColor(TesterTheme.accent)
                                Text(item.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                Spacer()
                                Button(action: { onDismiss(item.formId) }) {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(Color(.systemGray3))
                                }
                            }
                            Text("Blocked by interceptor — tap to open custom UI")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Button(action: { onOpen(item) }) {
                                Text("Open")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(TesterTheme.accent)
                                    .foregroundColor(.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                            }
                        }
                        .padding(12)
                        .frame(width: 230)
                        .background(Color(.secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: TesterTheme.cornerRadius, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: TesterTheme.cornerRadius, style: .continuous)
                                .stroke(TesterTheme.accent.opacity(0.25))
                        )
                        .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
                    }
                }
                .padding(12)
            }
            .background(.thinMaterial)
            .overlay(Divider(), alignment: .top)
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
            Image(systemName: "sparkles")
                .font(.system(size: 40))
                .foregroundColor(TesterTheme.accent)
            Text(item.title).font(.title2.weight(.bold)).multilineTextAlignment(.center)
            Text("A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
            Button("Start") { step = 1 }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 8)
            Spacer()
        }
    }

    private var thankYouStep: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 44))
                .foregroundColor(.green)
            Text("Thank you!").font(.title2.weight(.bold))
            Text("Your answers are ready to submit.")
                .font(.subheadline)
                .foregroundColor(.secondary)
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
            .buttonStyle(PrimaryButtonStyle())
            .padding(.top, 8)
            Spacer()
        }
    }

    @ViewBuilder
    private func questionStep(_ question: NativeFormQuestion) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Question \(step) of \(questions.count)")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(TesterTheme.accent)
                Text(question.title).font(.title3.weight(.bold))
                switch question.type {
                case "rating":
                    HStack(spacing: 10) {
                        ForEach(1...5, id: \.self) { star in
                            Button(action: { answers[question.id] = String(star) }) {
                                Image(systemName: (Double(answers[question.id] ?? "0") ?? 0) >= Double(star) ? "star.fill" : "star")
                                    .foregroundColor(.yellow)
                            }
                            .font(.title)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                case "long_text":
                    TextEditor(text: Binding(get: { answers[question.id] ?? "" }, set: { answers[question.id] = $0 }))
                        .frame(height: 120)
                        .padding(6)
                        .background(Color(.tertiarySystemFill))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                default:
                    TextField("Answer", text: Binding(get: { answers[question.id] ?? "" }, set: { answers[question.id] = $0 }))
                        .textFieldStyle(FilledFieldStyle())
                }
                Button("Next") { step += 1 }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, 8)
            }
            .padding(.top, 4)
        }
    }
}
