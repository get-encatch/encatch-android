package com.encatch.core

import kotlin.random.Random

/**
 * Generates a time-ordered UUIDv7 (RFC 9562), matching the `uuidv7` npm package used
 * by the RN SDK for device/session IDs.
 */
internal fun uuidV7(random: Random = Random.Default): String {
    val timestampMs = currentTimeMillis()
    val bytes = ByteArray(16)

    // 48-bit big-endian timestamp in bytes 0-5
    bytes[0] = (timestampMs shr 40).toByte()
    bytes[1] = (timestampMs shr 32).toByte()
    bytes[2] = (timestampMs shr 24).toByte()
    bytes[3] = (timestampMs shr 16).toByte()
    bytes[4] = (timestampMs shr 8).toByte()
    bytes[5] = timestampMs.toByte()

    val randomBytes = random.nextBytes(10)
    randomBytes.copyInto(bytes, destinationOffset = 6)

    // Version 7 in the high nibble of byte 6
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    // Variant 10xxxxxx in the high bits of byte 8
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    return buildString(36) {
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            append(bytes[i].toInt().and(0xFF).toString(16).padStart(2, '0'))
        }
    }
}
