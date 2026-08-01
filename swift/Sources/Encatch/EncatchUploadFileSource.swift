import EncatchCore
import Foundation

/// Real Swift enum with associated values, standing in for the bridged `UploadFileSource` class
/// hierarchy (`UploadFileSourceBytes`/`UploadFileSourceContentUri`).
public enum EncatchUploadFileSource: Sendable {
    case bytes(Data, mimeType: String?)

    /// Android-only — `:core`'s `readContentUri` actual throws `UnsupportedOperationException` on
    /// iOS/Desktop, so a `.contentUri` source will fail if actually uploaded from this platform.
    /// Kept for API parity/documentation; iOS callers should use `.bytes` instead.
    case contentUri(String, mimeType: String?)

    public var kotlin: UploadFileSource {
        switch self {
        case let .bytes(data, mimeType):
            return UploadFileSource.Bytes(bytes: data.toKotlinByteArray(), mimeType: mimeType)
        case let .contentUri(uri, mimeType):
            return UploadFileSource.ContentUri(uri: uri, mimeType: mimeType)
        }
    }
}

extension Data {
    fileprivate func toKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}
