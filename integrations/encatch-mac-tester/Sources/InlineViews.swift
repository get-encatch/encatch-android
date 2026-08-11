import SwiftUI
import Encatch

/// Bridges `EncatchInlineFormView` (the SDK's UIKit inline view) into SwiftUI — same
/// `UIViewRepresentable` mechanism as `encatch-ios-tester`'s equivalent, unchanged for Catalyst.
private struct InlineFormRepresentable: UIViewRepresentable {
    let formId: String?
    @Binding var height: CGFloat

    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        view.formId = formId
        view.onHeightChange = { [binding = $height] newHeight in
            DispatchQueue.main.async { binding.wrappedValue = newHeight }
        }
        return view
    }

    func updateUIView(_ uiView: EncatchInlineFormView, context: Context) {}
}

/// Container for the inline SDK view — auto-height, dashed drop-zone when idle. Ported from
/// `encatch-ios-tester`'s `InlineFormSlot`, recolored to the system accent/separator colors.
private struct InlineFormSlot: View {
    let formId: String?
    @State private var height: CGFloat = 0

    var body: some View {
        InlineFormRepresentable(formId: formId, height: $height)
            .frame(height: max(height, 64))
            .background(.quaternary.opacity(0.2))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    .foregroundColor(Color.accentColor.opacity(height > 64 ? 0 : 0.4))
            )
            .animation(.easeOut(duration: 0.2), value: height)
    }
}

struct InlineExactView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Label {
                    Text("Claims \"\(state.prefs.formId ?? "")\" — only renders inline when that exact form id is shown.")
                        .foregroundColor(.secondary)
                } icon: {
                    Image(systemName: "info.circle").foregroundColor(.accentColor)
                }
                .font(.callout)

                Button(action: { state.showModalForm() }) {
                    Label("Show Exact Form (renders inline below)", systemImage: "arrow.down.doc.fill")
                }
                .buttonStyle(.borderedProminent)

                InlineFormSlot(formId: state.prefs.formId)
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .onAppear { state.trackScreen("InlineExact") }
    }
}

struct InlineAnyView: View {
    @ObservedObject var state: TesterState
    @State private var wildcardFormId = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Label {
                    Text("Catches any form id not exactly claimed elsewhere.")
                        .foregroundColor(.secondary)
                } icon: {
                    Image(systemName: "info.circle").foregroundColor(.accentColor)
                }
                .font(.callout)

                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        TextField("Form id", text: $wildcardFormId)
                            .textFieldStyle(.roundedBorder)
                        Button(action: { state.showForm(wildcardFormId.trimmed) }) {
                            Label("Show Form (renders inline below)", systemImage: "arrow.down.doc.fill")
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(wildcardFormId.trimmed.isEmpty)
                        Button("Trigger unmatched form → modal fallback") { state.showForm("modal-fallback-demo") }
                            .buttonStyle(.plain)
                            .foregroundColor(.accentColor)
                    }
                    .padding(.top, 4)
                }

                InlineFormSlot(formId: nil)
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .onAppear { state.trackScreen("InlineAny") }
    }
}
