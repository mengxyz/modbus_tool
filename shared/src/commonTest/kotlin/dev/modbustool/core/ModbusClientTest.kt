package dev.modbustool.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class ModbusClientTest {
    @Test
    fun preservesRawFramesWhenPduValidationFails() = runTest {
        val requestFrame = byteArrayOf(0, 1, 0, 0, 0, 6, 1, 3, 0, 0, 0, 1)
        val responseFrame = byteArrayOf(0, 1, 0, 0, 0, 4, 1, 3, 1, 42)
        val transport = StubTransport(
            TransportExchange(requestFrame, responseFrame, byteArrayOf(3, 1, 42), 1),
        )

        val outcome = ModbusClient(transport).execute(
            1,
            ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 1),
        )

        val failure = assertIs<ModbusOutcome.Failure>(outcome)
        assertEquals(FailureKind.PROTOCOL, failure.kind)
        assertContentEquals(requestFrame, failure.requestFrame)
        assertContentEquals(responseFrame, failure.responseFrame)
    }
}

private class StubTransport(
    private val exchange: TransportExchange,
) : ModbusTransport {
    private val mutableState = MutableStateFlow(ConnectionState.CONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState
    override suspend fun connect() = Unit
    override suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int?): TransportExchange = exchange
    override suspend fun disconnect() {
        mutableState.value = ConnectionState.DISCONNECTED
    }
}
