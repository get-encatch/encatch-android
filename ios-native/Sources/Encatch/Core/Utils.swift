import Foundation

/// Current wall-clock time in epoch milliseconds — Swift equivalent of the Kotlin
/// `expect fun currentTimeMillis()`.
func currentTimeMillis() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
