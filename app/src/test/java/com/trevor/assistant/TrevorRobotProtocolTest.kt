package com.trevor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrevorRobotProtocolTest {
    @Test fun driveClampsMotorSpeedsAndSequence() {
        assertEquals("D,255,-255,999999\n", TrevorRobotProtocol.drive(900, -700, 1_200_000))
    }

    @Test fun stopIsNewlineTerminatedAndSequenced() {
        assertEquals("S,7\n", TrevorRobotProtocol.stop(7))
    }

    @Test fun parsesPositiveAndNegativeAcknowledgements() {
        assertEquals(TrevorRobotProtocol.Ack(42, true), TrevorRobotProtocol.parseAck("ACK,42,OK\n"))
        assertEquals(TrevorRobotProtocol.Ack(43, false), TrevorRobotProtocol.parseAck("ACK,43,ERR"))
    }

    @Test fun rejectsMalformedAcknowledgements() {
        assertNull(TrevorRobotProtocol.parseAck("ACK,nope,OK"))
        assertNull(TrevorRobotProtocol.parseAck("ACK,2,MAYBE"))
        assertNull(TrevorRobotProtocol.parseAck("STAT,0,0,-1,0"))
    }
}
