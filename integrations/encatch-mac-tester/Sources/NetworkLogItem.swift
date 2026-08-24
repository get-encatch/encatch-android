import Foundation
import Encatch

/// One captured SDK HTTP call for the Logs destination, wrapping the SDK's
/// `EncatchNetworkLogEntry`. Ported verbatim (data layer only — no UIKit/AppKit) from
/// `encatch-ios-tester`'s `NetworkLogs.swift`.
struct NetworkLogItem: Identifiable {
    let id = UUID()
    let entry: EncatchNetworkLogEntry

    /// Short endpoint name, e.g. "show-form".
    var name: String { entry.endpoint.components(separatedBy: "/").last ?? entry.endpoint }

    var isSuccess: Bool { (200...299).contains(entry.status) }

    var statusLabel: String { entry.status == 0 ? (entry.error != nil ? "ERR" : "—") : "\(entry.status)" }

    var timeLabel: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: entry.timestamp)
    }

    /// Full plain-text dump used by the copy actions and the detail inspector.
    var fullText: String {
        let iso = ISO8601DateFormatter()
        var lines: [String] = []
        lines.append("\(entry.method) \(name) — \(statusLabel) in \(entry.durationMs)ms")
        lines.append("Time: \(iso.string(from: entry.timestamp))")
        lines.append("URL: \(entry.url)")
        lines.append("")
        lines.append("--- Request headers ---")
        for (key, value) in entry.requestHeaders.sorted(by: { $0.key < $1.key }) {
            lines.append("\(key): \(value)")
        }
        lines.append("")
        lines.append("--- Request body ---")
        lines.append(prettyJson(entry.requestBody))
        lines.append("")
        if !entry.responseHeaders.isEmpty {
            lines.append("--- Response headers ---")
            for (key, value) in entry.responseHeaders.sorted(by: { $0.key < $1.key }) {
                lines.append("\(key): \(value)")
            }
            lines.append("")
        }
        lines.append("--- Response (\(statusLabel)) ---")
        lines.append(entry.responseBody.isEmpty ? (entry.error ?? "(empty)") : prettyJson(entry.responseBody))
        return lines.joined(separator: "\n")
    }

    private func prettyJson(_ raw: String) -> String {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: pretty, encoding: .utf8)
        else { return raw }
        return text
    }
}
