package dev.modbustool.transport

import dev.modbustool.core.FailureKind
import dev.modbustool.core.FunctionCode
import dev.modbustool.core.ModbusClient
import dev.modbustool.core.ModbusOutcome
import dev.modbustool.core.ModbusResponse
import dev.modbustool.core.ReadRegistersRequest
import dev.modbustool.core.RtuCodec
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class SerialTransportTest {
    @Test
    fun readsRtuResponseDeliveredOneByteAtATime() = runTest {
        val endpoint = FakeSerialEndpoint(
            response = RtuCodec.encode(1, bytes(3, 2, 0x12, 0x34)),
            maximumReadSize = 1,
        )
        val transport = SerialModbusTransport(config(), endpoint)
        transport.connect()

        val outcome = ModbusClient(transport).execute(
            1,
            ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 1),
        )

        val response = assertIs<ModbusOutcome.Success>(outcome).response
        assertEquals(listOf(0x1234), assertIs<ModbusResponse.Registers>(response).values)
        assertEquals(bytes(1, 3, 0, 0, 0, 1).toList(), endpoint.written.take(6))
    }

    @Test
    fun reportsSerialReadTimeout() = runTest {
        val transport = SerialModbusTransport(config(), FakeSerialEndpoint(ByteArray(0), 1))
        transport.connect()

        val outcome = ModbusClient(transport).execute(
            1,
            ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 1),
        )

        assertEquals(FailureKind.TIMEOUT, assertIs<ModbusOutcome.Failure>(outcome).kind)
    }

    private fun config() = ConnectionConfig.Serial("fake", 9600, SerialParity.NONE, 1, 1000)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

private class FakeSerialEndpoint(
    private val response: ByteArray,
    private val maximumReadSize: Int,
) : SerialEndpoint {
    override var isOpen: Boolean = false
    val written = mutableListOf<Byte>()
    private var readOffset = 0

    override fun configureAndOpen(config: ConnectionConfig.Serial) {
        isOpen = true
    }

    override fun setReadTimeout(timeoutMillis: Int) = Unit

    override fun flush() = Unit

    override fun write(bytes: ByteArray): Int {
        written += bytes.toList()
        return bytes.size
    }

    override fun read(buffer: ByteArray): Int {
        if (readOffset >= response.size) return 0
        val count = min(min(buffer.size, maximumReadSize), response.size - readOffset)
        response.copyInto(buffer, 0, readOffset, readOffset + count)
        readOffset += count
        return count
    }

    override fun close() {
        isOpen = false
    }
}
