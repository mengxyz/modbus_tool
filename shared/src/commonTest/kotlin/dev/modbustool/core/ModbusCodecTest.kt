package dev.modbustool.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ModbusCodecTest {
    @Test
    fun encodesReadHoldingRegistersRequest() {
        val pdu = ModbusPduCodec.encodeRequest(
            ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0x006B, 3),
        )

        assertContentEquals(bytes(0x03, 0x00, 0x6B, 0x00, 0x03), pdu)
    }

    @Test
    fun decodesReadHoldingRegistersResponse() {
        val request = ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 2)
        val decoded = ModbusPduCodec.decodeResponse(request, bytes(0x03, 0x04, 0x12, 0x34, 0xAB, 0xCD))

        assertEquals(listOf(0x1234, 0xABCD), assertIs<ModbusResponse.Registers>(assertIs<DecodedPdu.Response>(decoded).value).values)
    }

    @Test
    fun packsMultipleCoilsLeastSignificantBitFirst() {
        val pdu = ModbusPduCodec.encodeRequest(
            WriteMultipleCoilsRequest(0x0013, listOf(true, false, true, true, false, false, true, true, true, false)),
        )

        assertContentEquals(bytes(0x0F, 0x00, 0x13, 0x00, 0x0A, 0x02, 0xCD, 0x01), pdu)
    }

    @Test
    fun rejectsAddressRangeOverflow() {
        val error = ModbusPduCodec.validate(
            ReadRegistersRequest(FunctionCode.READ_INPUT_REGISTERS, 0xFFFF, 2),
        )

        assertNotNull(error)
        assertTrue(error.contains("exceeds"))
    }

    @Test
    fun decodesDeviceException() {
        val request = ReadBitsRequest(FunctionCode.READ_COILS, 0, 1)
        val decoded = assertIs<DecodedPdu.Exception>(
            ModbusPduCodec.decodeResponse(request, bytes(0x81, 0x02)),
        )

        assertEquals(2, decoded.code)
        assertEquals("Illegal data address", decoded.description)
    }

    @Test
    fun rejectsMismatchedWriteEcho() {
        assertFailsWith<ModbusProtocolException> {
            ModbusPduCodec.decodeResponse(
                WriteSingleRegisterRequest(1, 7),
                bytes(0x06, 0x00, 0x01, 0x00, 0x08),
            )
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
