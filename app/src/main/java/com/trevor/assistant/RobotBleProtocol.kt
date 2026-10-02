package com.trevor.assistant

import java.nio.charset.StandardCharsets

object RobotBleProtocol {
    const val SERVICE_UUID = "6f726576-6f72-4d31-9f52-545245564f52"
    const val RX_UUID = "6f726576-6f72-5258-9f52-545245564f52"
    const val TX_UUID = "6f726576-6f72-5458-9f52-545245564f52"
    const val CLIENT_READY = "CLIENT_READY\n"
    const val HEARTBEAT = "HB\n"

    fun frame(value: String): ByteArray {
        require(value.isNotBlank()) { "Frame cannot be blank" }
        val normalized = value.trimEnd('\r', '\n') + "\n"
        val bytes = normalized.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size <= 65) { "Frame exceeds 64-byte payload plus newline" }
        require(bytes.dropLast(1).all { it.code in 0x20..0x7E }) {
            "Frame contains unsupported characters"
        }
        return bytes
    }

    fun command(direction: String, speed: Int): ByteArray {
        require(direction in setOf("FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"))
        require(speed in 0..100)
        require(direction != "STOP" || speed == 0) { "STOP requires speed 0" }
        return frame("$direction $speed")
    }

    fun parseLine(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > 65) return null
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (text.any { it.code < 0x20 && it != '\n' && it != '\r' }) return null
        return text.trimEnd('\r', '\n').ifBlank { null }
    }
}
