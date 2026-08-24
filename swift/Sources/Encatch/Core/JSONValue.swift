import Foundation

/// A JSON-safe value representation used everywhere the Kotlin source uses
/// `kotlinx.serialization.json.JsonElement` (e.g. `EventPayload.data`,
/// `ShowFormResponse.questionnaireFields`, `Answer.ratingMatrix`).
///
/// This is the Swift analog of `JsonConversions.kt`'s `anyToJsonElement`/`jsonElementToAny`: rather
/// than converting to/from a Kotlin `JsonElement`, Swift code constructs/reads `JSONValue` directly,
/// and `toAny()`/`from(any:)` provide the same "arbitrary dynamic value" bridge Kotlin needed for
/// interop with plain `Map<String, Any?>`-shaped data (e.g. WebView JS bridge messages).
public indirect enum JSONValue: Codable, Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    /// Converts to a plain Swift value tree (`String`/`Double`/`Bool`/`[String: Any]`/`[Any]`/`NSNull`)
    /// — the Swift equivalent of Kotlin's `jsonElementToAny`.
    public func toAny() -> Any {
        switch self {
        case .string(let value): return value
        case .number(let value): return value
        case .bool(let value): return value
        case .object(let value): return value.mapValues { $0.toAny() }
        case .array(let value): return value.map { $0.toAny() }
        case .null: return NSNull()
        }
    }

    /// Converts an arbitrary Swift value into a `JSONValue` — the Swift equivalent of Kotlin's
    /// `anyToJsonElement`. Unrecognized types fall back to `.null`.
    public static func from(any value: Any?) -> JSONValue {
        guard let value else { return .null }
        switch value {
        case is NSNull:
            return .null
        case let value as JSONValue:
            return value
        case let value as String:
            return .string(value)
        case let value as Bool:
            return .bool(value)
        case let value as Int:
            return .number(Double(value))
        case let value as Int64:
            return .number(Double(value))
        case let value as Double:
            return .number(value)
        case let value as Float:
            return .number(Double(value))
        case let value as NSNumber:
            // Disambiguate booleans boxed as NSNumber (common when values originate from JSONSerialization).
            if CFGetTypeID(value) == CFBooleanGetTypeID() {
                return .bool(value.boolValue)
            }
            return .number(value.doubleValue)
        case let value as [String: Any?]:
            return .object(value.mapValues { JSONValue.from(any: $0) })
        case let value as [Any?]:
            return .array(value.map { JSONValue.from(any: $0) })
        default:
            // Mirrors Kotlin's `anyToJsonElement` fallback: unrecognized types are stringified
            // rather than dropped.
            return .string(String(describing: value))
        }
    }

    /// Parses a raw JSON string into a `JSONValue`, returning `nil` on malformed input (mirrors
    /// `runCatching { Json.parseToJsonElement(...) }.getOrNull()` usage patterns in the Kotlin source).
    public static func parse(_ string: String) -> JSONValue? {
        guard let data = string.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: data)
    }

    /// Serializes to a compact JSON string.
    public func toJSONString() -> String {
        guard let data = try? JSONEncoder().encode(self), let string = String(data: data, encoding: .utf8) else {
            return "null"
        }
        return string
    }

    /// Decodes this value into a `Decodable` type via a JSON round-trip. Used to bridge dynamic
    /// `JSONValue` payloads (e.g. a WebView bridge message field) into fixed-schema `Codable`
    /// structs like `FormResponse`.
    public func decode<T: Decodable>(as type: T.Type = T.self) -> T? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    /// The wrapped object, or `nil` when this value is not `.object`.
    public var asObject: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    /// The wrapped array, or `nil` when this value is not `.array`.
    public var asArray: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    /// The wrapped string, or `nil` when this value is not `.string`.
    public var asString: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// The wrapped number, or `nil` when this value is not `.number`.
    public var asDouble: Double? {
        if case .number(let value) = self { return value }
        return nil
    }
}

extension Encodable {
    /// Encodes this value into a `JSONValue` via a JSON round-trip. Used to bridge fixed-schema
    /// `Codable` structs into the dynamic `JSONValue` representation used for WebView bridge
    /// messages.
    public func toJSONValue() -> JSONValue? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: data)
    }
}
