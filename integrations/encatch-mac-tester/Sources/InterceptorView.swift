import SwiftUI
import Encatch

/// The Interceptor sidebar destination — a `List` of blocked forms (leading) + the native form
/// wizard embedded directly as the detail pane (trailing), NOT a `.sheet`/popover. Replaces the
/// iOS tester's `InterceptorCarousel` (a horizontal card strip docked above the bottom tab bar —
/// a touch/mobile pattern with no good Mac equivalent). A tester bouncing between "trigger an
/// interceptor on Home" and "answer it here" can see both the sidebar and the form at once, which
/// a modal window would prevent. See the plan file's rationale for why Interceptor is a
/// standalone sidebar row (with a badge) instead of staying tab-attached to Home.
struct InterceptorView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        HStack(spacing: 0) {
            List(state.blockedForms) { item in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.body)
                        Text("Blocked by interceptor").font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button(action: { state.dismissBlockedForm(item.formId) }) {
                        Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
                .listRowBackground(item.id == state.openedForm?.id ? Color.accentColor.opacity(0.15) : Color.clear)
                .onTapGesture { state.openedForm = item }
            }
            .frame(width: 260)
            .listStyle(.inset)
            .overlay {
                if state.blockedForms.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "hand.raised").font(.system(size: 28)).foregroundColor(.secondary)
                        Text("No blocked forms").font(.callout).foregroundColor(.secondary)
                        Text("Set an interceptor form id in Preferences, then trigger it from Home.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 200)
                    }
                }
            }

            Divider()

            if let item = state.openedForm {
                NativeFormPanel(item: item, onClose: {
                    state.dismissBlockedForm(item.formId)
                    state.openedForm = nil
                })
                .id(item.id)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Text("Select a blocked form to open its custom-rendered form.")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .onAppear { state.trackScreen("Interceptor") }
    }
}
