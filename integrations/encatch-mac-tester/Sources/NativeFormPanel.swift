import SwiftUI
import Encatch

/// A fully custom, non-WebView native form renderer: welcome -> questions (rating/short-answer/
/// long-text) -> thank-you, driving the SDK manually via `Encatch.shared.emitEvent`/
/// `buildSubmitRequest`/`submitForm`/`dismissForm` — same logic as `encatch-ios-tester`'s
/// `NativeFormModal`, but embedded as `InterceptorView`'s detail pane instead of presented as a
/// `.sheet`, so the sidebar and the rest of the app stay reachable while answering it.
struct NativeFormPanel: View {
    let item: BlockedFormItem
    let onClose: () -> Void

    @State private var step = 0 // 0 = welcome, 1...questions.count = questions, last = thank you
    @State private var answers: [String: Any] = [:]
    private let questions: [NativeFormQuestion]

    init(item: BlockedFormItem, onClose: @escaping () -> Void) {
        self.item = item
        self.onClose = onClose
        self.questions = parseQuestionnaireFields(item.questionnaireFields).filter { renderableNativeFormTypes.contains($0.type) }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Custom Native Form").font(.headline)
                Spacer()
                Button("Close") {
                    Encatch.shared.emitEvent(.formClose, EventPayload(formId: item.formId, timestamp: 0))
                    Task { try? await Encatch.shared.dismissForm(item.formId) }
                    onClose()
                }
            }
            .padding()
            Divider()

            Group {
                if step == 0 {
                    welcomeStep
                } else if step <= questions.count {
                    questionStep(questions[step - 1])
                } else {
                    thankYouStep
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
        }
        .onAppear {
            Encatch.shared.emitEvent(.formShow, EventPayload(formId: item.formId, timestamp: 0))
            Encatch.shared.emitEvent(.formStarted, EventPayload(formId: item.formId, timestamp: 0))
        }
    }

    private var welcomeStep: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "sparkles").font(.system(size: 40)).foregroundColor(.accentColor)
            Text(item.title).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
            Text("A custom-rendered form, built entirely from the interceptor payload's questionnaireFields — not the SDK's WebView.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .frame(maxWidth: 380)
            Button("Start") { step = 1 }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 8)
            Spacer()
        }
    }

    private var thankYouStep: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "checkmark.seal.fill").font(.system(size: 44)).foregroundColor(.green)
            Text("Thank you!").font(.title2.weight(.semibold))
            Text("Your answers are ready to submit.").font(.subheadline).foregroundColor(.secondary)
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
            .controlSize(.large)
            .padding(.top, 8)
            Spacer()
        }
    }

    @ViewBuilder
    private func questionStep(_ question: NativeFormQuestion) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Question \(step) of \(questions.count)")
                .font(.caption.weight(.semibold))
                .foregroundColor(.accentColor)
            Text(question.title).font(.title3.weight(.semibold))
            switch question.type {
            case "rating":
                HStack(spacing: 10) {
                    ForEach(1...5, id: \.self) { star in
                        Button(action: { answers[question.id] = Double(star) }) {
                            Image(systemName: (answers[question.id] as? Double ?? 0) >= Double(star) ? "star.fill" : "star")
                                .foregroundColor(.yellow)
                        }
                        .buttonStyle(.plain)
                        .font(.title)
                    }
                }
                .padding(.vertical, 8)
            case "long_text":
                TextEditor(text: Binding(
                    get: { answers[question.id] as? String ?? "" },
                    set: { answers[question.id] = $0 }
                ))
                .frame(height: 120)
                .padding(6)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.separator))
            default:
                TextField("Answer", text: Binding(
                    get: { answers[question.id] as? String ?? "" },
                    set: { answers[question.id] = $0 }
                ))
                .textFieldStyle(.roundedBorder)
            }
            Button("Next") { step += 1 }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 8)
            Spacer()
        }
        .frame(maxWidth: 420, alignment: .leading)
    }
}
