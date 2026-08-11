import SwiftUI

/// One captured SDK HTTP call, flattened by TesterController's setOnNetworkLog passthrough.
struct NetworkLogItem: Identifiable {
    let id = UUID()
    let status: Int
    let name: String
    let durationMs: Int
    let fullText: String

    var isSuccess: Bool { (200...299).contains(status) }
    var statusLabel: String { status == 0 ? "ERR" : "\(status)" }
}

struct LogsTabView: View {
    @ObservedObject var state: TesterState
    @State private var selected: NetworkLogItem?

    var body: some View {
        Group {
            if state.networkLogs.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "list.bullet.rectangle")
                        .font(.system(size: 36))
                        .foregroundColor(.secondary)
                    Text("No SDK requests yet")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(spacing: 8) {
                        HStack {
                            Text("\(state.networkLogs.count) requests · newest first")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Button("Copy all") {
                                UIPasteboard.general.string = state.networkLogs
                                    .map(\.fullText)
                                    .joined(separator: "\n\n============\n\n")
                            }
                            .font(.caption.weight(.semibold))
                            Button("Clear") { state.networkLogs.removeAll() }
                                .font(.caption.weight(.semibold))
                                .foregroundColor(.red)
                        }
                        .padding(.horizontal, 4)

                        ForEach(state.networkLogs) { item in
                            Button(action: { selected = item }) {
                                HStack(spacing: 10) {
                                    Text(item.statusLabel)
                                        .font(.caption.weight(.bold).monospaced())
                                        .foregroundColor(item.isSuccess ? TesterTheme.green : .red)
                                        .frame(width: 38, alignment: .leading)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name).font(.subheadline.weight(.semibold))
                                        Text("\(item.durationMs)ms")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Button(action: { UIPasteboard.general.string = item.fullText }) {
                                        Image(systemName: "doc.on.doc")
                                            .font(.subheadline)
                                            .foregroundColor(.secondary)
                                    }
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundColor(Color(.systemGray3))
                                }
                                .padding(12)
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding()
                }
            }
        }
        .screenBackground()
        .sheet(item: $selected) { item in
            NetworkLogDetailView(item: item)
        }
    }
}

private struct NetworkLogDetailView: View {
    let item: NetworkLogItem
    @Environment(\.presentationMode) private var presentationMode
    @State private var copied = false

    var body: some View {
        NavigationView {
            ScrollView {
                Text(item.fullText)
                    .font(.caption.monospaced())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .textSelection(.enabled)
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: {
                        UIPasteboard.general.string = item.fullText
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                    }) {
                        Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { presentationMode.wrappedValue.dismiss() }
                }
            }
        }
    }
}
