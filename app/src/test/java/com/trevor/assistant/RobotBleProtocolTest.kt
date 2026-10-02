package com.trevor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RobotBleProtocolTest {
    @Test fun clientReadyIsLineDelimited() {
        assertEquals("CLIENT_READY\n", String(RobotBleProtocol.frame("CLIENT_READY")))
    }

    @Test fun validCommandsAreFramed() {
        assertEquals("FORWARD 80\n", String(RobotBleProtocol.command("FORWARD", 80)))
        assertEquals("STOP 0\n", String(RobotBleProtocol.command("STOP", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stopWithNonZeroSpeedIsRejected() {
        RobotBleProtocol.command("STOP", 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun speedAboveLimitIsRejected() {
        RobotBleProtocol.command("FORWARD", 101)
    }

    @Test fun validIncomingFrameParses() {
        assertEquals("READY", RobotBleProtocol.parseLine("READY\n".toByteArray()))
        assertEquals("HELLO TREVOR-RBT-M1 1", RobotBleProtocol.parseLine("HELLO TREVOR-RBT-M1 1\n".toByteArray()))
    }

    @Test fun controlCharactersAreRejected() {
        assertNull(RobotBleProtocol.parseLine(byteArrayOf('O'.code.toByte(), 1, 'K'.code.toByte(), '\n'.code.toByte())))
    }

    @Test fun blankIncomingFrameIsRejected() {
        assertNull(RobotBleProtocol.parseLine("\n".toByteArray()))
    }

    @Test fun protocolConstantsArePresent() {
        assertNotNull(RobotBleProtocol.SERVICE_UUID)
        assertNotNull(RobotBleProtocol.RX_UUID)
        assertNotNull(RobotBleProtocol.TX_UUID)
    }
}
