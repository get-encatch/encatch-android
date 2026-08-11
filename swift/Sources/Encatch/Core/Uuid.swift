import Foundation

/// Generates a time-ordered UUIDv7 (RFC 9562), matching the `uuidv7` npm package used
/// by the RN SDK for device/session IDs.
func uuidV7() -> String {
    let timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
    var bytes = [UInt8](repeating: 0, count: 16)

    // 48-bit big-endian timestamp in bytes 0-5
    bytes[0] = UInt8(truncatingIfNeeded: timestampMs >> 40)
    bytes[1] = UInt8(truncatingIfNeeded: timestampMs >> 32)
    bytes[2] = UInt8(truncatingIfNeeded: timestampMs >> 24)
    bytes[3] = UInt8(truncatingIfNeeded: timestampMs >> 16)
    bytes[4] = UInt8(truncatingIfNeeded: timestampMs >> 8)
    bytes[5] = UInt8(truncatingIfNeeded: timestampMs)

    for i in 6..<16 {
        bytes[i] = UInt8.random(in: 0...255)
    }

    // Version 7 in the high nibble of byte 6
    bytes[6] = (bytes[6] & 0x0F) | 0x70
    // Variant 10xxxxxx in the high bits of byte 8
    bytes[8] = (bytes[8] & 0x3F) | 0x80

    var result = ""
    result.reserveCapacity(36)
    for i in 0..<16 {
        if i == 4 || i == 6 || i == 8 || i == 10 {
            result.append("-")
        }
        result.append(String(format: "%02x", bytes[i]))
    }
    return result
}
