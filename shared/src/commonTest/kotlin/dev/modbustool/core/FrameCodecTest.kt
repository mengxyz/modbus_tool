package dev.modbustool.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {
    @Test
    fun calculatesKnownRtuCrcAndRoundTripsFrame() {
        val pdu = bytes(0x03, 0x00, 0x6B, 0x00, 0x03)
        val frame = RtuCodec.encode(0x11, pdu)

        assertContentEquals(bytes(0x11, 0x03, 0x00, 0x6B, 0x00, 0x03, 0x76, 0x87), frame)
        assertContentEquals(pdu, RtuCodec.decode(frame, 0x11))
    }

    @Test
    fun rejectsInvalidRtuCrc() {
        val frame = bytes(0x01, 0x03, 0x02, 0x12, 0x34, 0x00, 0x00)
        assertFailsWith<ModbusProtocolException> { RtuCodec.decode(frame, 1) }
    }

    @Test
    fun encodesAndDecodesMbapFrame() {
        val pdu = bytes(0x03, 0x00, 0x00, 0x00, 0x01)
        val frame = MbapCodec.encode(0x1234, 7, pdu)

        assertContentEquals(bytes(0x12, 0x34, 0, 0, 0, 6, 7, 3, 0, 0, 0, 1), frame)
        assertContentEquals(pdu, MbapCodec.decode(frame, 0x1234, 7))
        assertEquals(5, MbapCodec.responseLengthFromHeader(frame.copyOfRange(0, 7)))
    }

    @Test
    fun rejectsWrongMbapTransaction() {
        val frame = MbapCodec.encode(2, 1, bytes(3, 2, 0, 1))
        assertFailsWith<ModbusProtocolException> { MbapCodec.decode(frame, 1, 1) }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
