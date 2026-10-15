package com.trevor.assistant

/**
 * Small, platform-independent wire-protocol definition shared by the Android transport
 * and mirrored by firmware/esp32c3_trevor_ble/esp32c3_trevor_ble.ino.
 */
object TrevorRobotProtocol {
    data class Ack(val sequence: Int, val accepted: Boolean)

    fun drive(left: Int, right: Int, sequence: Int): String =
        "D,${left.coerceIn(-255, 255)},${right.coerceIn(-255, 255)},${sequence.coerceIn(0, 999999)}\n"

    fun stop(sequence: Int): String = "S,${sequence.coerceIn(0, 999999)}\n"

    fun parseAck(line: String): Ack? {
        val fields = line.trim().split(',')
        if (fields.size != 3 || fields[0] != "ACK") return null
        val sequence = fields[1].toIntOrNull()?.takeIf { it in 0..999999 } ?: return null
        val accepted = when (fields[2]) {
            "OK" -> true
            "ERR" -> false
            else -> return null
        }
        return Ack(sequence, accepted)
    }
}
