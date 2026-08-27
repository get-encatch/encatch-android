import SwiftUI

/// addToResponse question types, sample values, parsing, and the row-based prefill sheet —
/// ported from the web tester's `add-to-response-types.ts` / `AddToResponsePrefillRows`
/// (slash-admin-encatch), matching the Kotlin `PrefillSpec.kt` in the Android testers. Panel
/// types (welcome, thank_you, message_panel, exit_form) are excluded — no answer to prefill.
/// Kept in lockstep by hand with the copies in the other tester apps.

enum PrefillEditor { case bool, number, text, longText, json }

struct PrefillQuestionType {
    let wire: String
    let label: String
    let editor: PrefillEditor
    /// Canonical sample value (JSON text for JSON editors, plain text otherwise).
    let sample: String
    let hint: String
}

struct PrefillCategory {
    let label: String
    let types: [PrefillQuestionType]
}

private func t(_ wire: String, _ label: String, _ editor: PrefillEditor, _ sample: String, _ hint: String) -> PrefillQuestionType {
    PrefillQuestionType(wire: wire, label: label, editor: editor, sample: sample, hint: hint)
}

let PREFILL_CATEGORIES: [PrefillCategory] = [
    PrefillCategory(label: "Scale", types: [
        t("rating", "Rating", .number, "4", "Number 1–5 (or form max rating)"),
        t("csat", "CSAT", .number, "4", "Number 1–5 (scale size depends on form)"),
        t("nps", "NPS", .number, "9", "Number 0–10"),
        t("opinion_scale", "Opinion scale", .number, "7", "Numeric scale value"),
    ]),
    PrefillCategory(label: "Choice", types: [
        t("single_choice", "Single choice", .text, "option_1", "Option value string from the form schema"),
        t("yes_no", "Yes / No", .bool, "true", "true = Yes, false = No"),
        t("nested_selection", "Nested selection", .json, #"["category_a", "sub_option_1"]"#, "JSON array of option values"),
        t("picture_choice", "Picture choice", .json, #"["picture_option_1"]"#, "JSON array of option values"),
        t("multiple_choice_multiple", "Multiple choice", .json, #"["option_a", "option_b"]"#, "JSON array of option values"),
        t("consent", "Consent", .bool, "true", "true = agreed, false = not agreed"),
        t("ranking", "Ranking", .json, #"["option_a", "option_b", "option_c"]"#, "JSON array in ranked order"),
    ]),
    PrefillCategory(label: "Matrix", types: [
        t("rating_matrix", "Rating matrix", .json, #"{"statement_1": 4, "statement_2": 5}"#, "JSON object: row id -> rating"),
        t("matrix_single_choice", "Matrix (single per row)", .json, #"{"row_1": "column_a", "row_2": "column_b"}"#, "JSON object: row id -> column id"),
        t("matrix_multiple_choice", "Matrix (multiple per row)", .json, #"{"row_1": ["column_a"], "row_2": ["column_a", "column_b"]}"#, "JSON object: row id -> column ids"),
    ]),
    PrefillCategory(label: "Text", types: [
        t("short_answer", "Short answer", .text, "Sample short answer", "Plain text value"),
        t("long_text", "Long answer", .longText, "Sample long answer text for testing addToResponse.", "Long text value"),
        t("date", "Date", .text, "2024-06-15", "Plain text value (YYYY-MM-DD)"),
        t("number", "Number", .text, "42", "Plain text value (numeric string)"),
    ]),
    PrefillCategory(label: "Contact info", types: [
        t("email", "Email", .text, "test@example.com", "Plain text value"),
        t("phone_number", "Phone number", .json,
          #"{"countryCode": "+1", "number": "5551234567", "e164": "+15551234567"}"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("website", "Website", .text, "https://example.com", "Plain text value"),
        t("address", "Address", .json,
          #"{"addressLine1": "123 Main St", "city": "San Francisco", "stateProvince": "CA", "postalCode": "94105", "country": "US"}"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("signature", "Signature", .json,
          #"{"mode": "type", "typedName": "Jane Doe"}"#,
          "JSON matching the answer shape in @encatch/schema"),
    ]),
    PrefillCategory(label: "Advanced", types: [
        t("file_upload", "File upload", .json,
          #"[{"fileUrl": "https://example.com/uploads/sample.pdf", "fileName": "sample.pdf", "fileSizeMb": 0.5, "mimeType": "application/pdf"}]"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("video_audio", "Video / audio / photo", .json,
          #"{"mode": "text", "text": "Sample video/audio text response"}"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("scheduler", "Scheduler", .json,
          #"{"provider": "google_calendar", "bookedAt": "1710000000"}"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("qna_with_ai", "Q&A with AI", .json,
          #"[{"question": "What is your return policy?", "answer": "Returns are accepted within 30 days."}]"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("annotation", "Annotation", .json,
          #"{"fileType": "video/mp4", "fileName": "demo.mp4", "markers": [{"markerNo": "1", "timeline": "00:01:30", "comment": "Issue here"}]}"#,
          "JSON matching the answer shape in @encatch/schema"),
        t("payments_upi", "Payments UPI", .json,
          #"{"transactionId": "123456789012", "encatchPaymentReference": "enc_ref_sample_001", "amount": "99.00", "currency": "INR", "payeeVpa": "merchant@upi", "payeeName": "Sample Merchant", "selfReported": true}"#,
          "JSON matching the answer shape in @encatch/schema"),
    ]),
]

let ALL_PREFILL_TYPES: [PrefillQuestionType] = PREFILL_CATEGORIES.flatMap(\.types)

func prefillTypeByWire(_ wire: String) -> PrefillQuestionType {
    ALL_PREFILL_TYPES.first { $0.wire == wire } ?? ALL_PREFILL_TYPES.first { $0.wire == "short_answer" }!
}

/// One editable prefill row. Persisted as {questionId, typeWire, value} — the same JSON shape
/// the Android testers store, so the format stays interchangeable.
struct PrefillRow: Identifiable, Codable {
    var id = UUID()
    var questionId = ""
    var typeWire = "short_answer"
    var value = ""

    var type: PrefillQuestionType { prefillTypeByWire(typeWire) }

    private enum CodingKeys: String, CodingKey { case questionId, typeWire, value }
}

private let prefillRowsDefaultsKey = "encatch_prefill_rows"

func loadPrefillRows() -> [PrefillRow] {
    guard let raw = UserDefaults.standard.string(forKey: prefillRowsDefaultsKey),
          let data = raw.data(using: .utf8),
          let rows = try? JSONDecoder().decode([PrefillRow].self, from: data) else { return [] }
    return rows
}

func savePrefillRows(_ rows: [PrefillRow]) {
    guard let data = try? JSONEncoder().encode(rows), let raw = String(data: data, encoding: .utf8) else { return }
    UserDefaults.standard.set(raw, forKey: prefillRowsDefaultsKey)
}

struct PrefillParseError: Error { let message: String }

/// Strict per-editor parsing, mirroring the web tester's `parseAddToResponseValue`.
func parsePrefillValue(_ type: PrefillQuestionType, _ raw: String) throws -> Any? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    switch type.editor {
    case .bool:
        if trimmed == "true" { return true }
        if trimmed == "false" { return false }
        throw PrefillParseError(message: "Expected true or false for \(type.label)")
    case .number:
        guard let n = Int(trimmed) else { throw PrefillParseError(message: "Expected a whole number for \(type.label)") }
        return n
    case .json:
        guard let data = trimmed.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) else {
            throw PrefillParseError(message: "Invalid JSON for \(type.label)")
        }
        return parsed
    case .text, .longText:
        return raw
    }
}

/// Row-based `addToResponse` prefill editor sheet. `onApply` receives validated
/// (questionId, parsed value) entries; the caller clears pending responses, adds each, and
/// shows the form.
struct PrefillSheet: View {
    let onApply: ([(String, Any?)]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [PrefillRow]
    @State private var error: String?

    init(onApply: @escaping ([(String, Any?)]) -> Void) {
        self.onApply = onApply
        let loaded = loadPrefillRows()
        _rows = State(initialValue: loaded.isEmpty ? [PrefillRow()] : loaded)
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Each row becomes an addToResponse(questionId, value) call before the form is shown.")
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    ForEach($rows) { $row in
                        PrefillRowEditor(
                            row: $row,
                            onRemove: {
                                rows.removeAll { $0.id == row.id }
                                if rows.isEmpty { rows = [PrefillRow()] }
                            }
                        )
                        Divider()
                    }

                    Button(action: { rows.append(PrefillRow()) }) {
                        Label("Add row", systemImage: "plus.circle")
                    }

                    if let error {
                        Text(error).font(.footnote).foregroundColor(.red)
                    }
                }
                .padding()
            }
            .navigationTitle("Prefill answers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { apply() }
                        .disabled(!rows.contains { !$0.questionId.trimmingCharacters(in: .whitespaces).isEmpty })
                }
            }
        }
    }

    private func apply() {
        let filled = rows.filter { !$0.questionId.trimmingCharacters(in: .whitespaces).isEmpty }
        do {
            let entries = try filled.map { ($0.questionId.trimmingCharacters(in: .whitespaces), try parsePrefillValue($0.type, $0.value)) }
            savePrefillRows(rows)
            error = nil
            dismiss()
            onApply(entries)
        } catch let e as PrefillParseError {
            error = e.message
        } catch {
            self.error = "Invalid value"
        }
    }
}

private struct PrefillRowEditor: View {
    @Binding var row: PrefillRow
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("question id (uuid or slug)", text: $row.questionId)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)
                .disableAutocorrection(true)

            HStack {
                Menu {
                    ForEach(PREFILL_CATEGORIES, id: \.label) { category in
                        Section(category.label) {
                            ForEach(category.types, id: \.wire) { type in
                                Button(type.label) {
                                    // Type change resets the value to that type's sample, like the web tester.
                                    row.typeWire = type.wire
                                    row.value = type.sample
                                }
                            }
                        }
                    }
                } label: {
                    Label("Type: \(row.type.label)", systemImage: "chevron.up.chevron.down")
                        .font(.footnote.weight(.semibold))
                }
                Spacer()
                Button("Sample") { row.value = row.type.sample }
                    .font(.footnote)
                Button(action: onRemove) {
                    Image(systemName: "trash").foregroundColor(.red)
                }
                .font(.footnote)
            }

            switch row.type.editor {
            case .bool:
                Picker("", selection: $row.value) {
                    Text("true").tag("true")
                    Text("false").tag("false")
                }
                .pickerStyle(.segmented)
            case .number:
                TextField(row.type.hint, text: $row.value)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
            case .json, .longText:
                TextEditor(text: $row.value)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(minHeight: 88)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(.systemGray4)))
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            case .text:
                TextField(row.type.hint, text: $row.value)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }

            Text(row.type.hint).font(.caption2).foregroundColor(.secondary)
        }
    }
}
