import SwiftUI
import Encatch

/// Console.app-style `Table` (Status/Endpoint/Duration/Time, sortable, selection-driven) + a
/// detail inspector pane — replaces `encatch-ios-tester`'s `LogsTabView`, a plain scrolling
/// `List` of rows with a `.sheet` detail. The clearest "phone list of rows" → "Mac data table"
/// upgrade in the app. (`.inspector` was avoided in favor of a plain `HStack` + `Divider` split —
/// same reasoning as `InterceptorView`: this app has already hit several SwiftUI APIs that look
/// cross-platform but are hard-unavailable under Catalyst — `Settings{}`, `Window(_:id:)`,
/// `.menuStyle(.borderedButton)` — so unverified macOS-only-flavored modifiers are avoided here
/// rather than risking another one.)
struct LogsView: View {
    @ObservedObject var state: TesterState
    @State private var sortOrder: [KeyPathComparator<NetworkLogItem>] = [KeyPathComparator(\.timeLabel, order: .reverse)]
    @State private var selection: NetworkLogItem.ID?

    private var sortedLogs: [NetworkLogItem] {
        state.networkLogs.sorted(using: sortOrder)
    }

    private var selectedItem: NetworkLogItem? {
        sortedLogs.first { $0.id == selection }
    }

    var body: some View {
        VStack(spacing: 0) {
            actionsRow
            Divider()
            content
        }
        .onAppear { state.trackScreen("Logs") }
    }

    // `.toolbar` items on a per-destination view proved unreliable under Catalyst (see
    // RootShell's `header` doc comment) — these actions live in the view's own content instead.
    private var actionsRow: some View {
        HStack {
            Spacer()
            Button("Copy All") {
                copyToPasteboard(state.networkLogs.map(\.fullText).joined(separator: "\n\n============\n\n"))
            }
            .disabled(state.networkLogs.isEmpty)
            Button("Clear") { state.networkLogs.removeAll() }
                .disabled(state.networkLogs.isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var content: some View {
        HStack(spacing: 0) {
            if state.networkLogs.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "list.bullet.rectangle").font(.system(size: 28)).foregroundColor(.secondary)
                    Text("No SDK requests yet").font(.callout).foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Table(sortedLogs, selection: $selection, sortOrder: $sortOrder) {
                    TableColumn("Status", value: \.statusLabel) { item in
                        Text(item.statusLabel)
                            .font(.callout.monospaced().weight(.semibold))
                            .foregroundColor(item.isSuccess ? MacTheme.success : MacTheme.failure)
                    }
                    .width(60)
                    TableColumn("Endpoint", value: \.name)
                    TableColumn("Time", value: \.timeLabel).width(80)
                    TableColumn("Duration (ms)") { item in
                        Text("\(item.entry.durationMs)")
                    }
                    .width(110)
                }
                .frame(minWidth: 360)

                Divider()

                Group {
                    if let selectedItem {
                        VStack(alignment: .leading, spacing: 0) {
                            HStack {
                                Text(selectedItem.name).font(.headline)
                                Spacer()
                                Button("Copy") { copyToPasteboard(selectedItem.fullText) }
                            }
                            .padding()
                            Divider()
                            ScrollView {
                                Text(selectedItem.fullText)
                                    .font(.callout.monospaced())
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding()
                            }
                        }
                    } else {
                        Text("Select a request to inspect it.")
                            .foregroundColor(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(minWidth: 320, maxHeight: .infinity)
            }
        }
    }

    private func copyToPasteboard(_ text: String) {
        UIPasteboard.general.string = text
    }
}
