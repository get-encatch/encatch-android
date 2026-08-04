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
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
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
                    .font(.caption)
                    Button("Clear") { state.networkLogs.removeAll() }
                        .font(.caption)
                        .foregroundColor(.red)
                }

                if state.networkLogs.isEmpty {
                    Text("No SDK requests yet")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .padding(.top, 24)
                }

                ForEach(state.networkLogs) { item in
                    Button(action: { selected = item }) {
                        HStack(spacing: 10) {
                            Text(item.statusLabel)
                                .font(.caption.weight(.bold).monospaced())
                                .foregroundColor(item.isSuccess ? .green : .red)
                                .frame(width: 38, alignment: .leading)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name).font(.subheadline.weight(.semibold))
                                Text("\(item.durationMs)ms").font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(action: { UIPasteboard.general.string = item.fullText }) {
                                Image(systemName: "doc.on.doc").font(.subheadline).foregroundColor(.secondary)
                            }
                        }
                        .padding(10)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .sheet(item: $selected) { item in
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
                        Button("Copy") { UIPasteboard.general.string = item.fullText }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Done") { selected = nil }
                    }
                }
            }
        }
    }
}
