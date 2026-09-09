package dev.modbustool.core

enum class FunctionCode(
    val code: Int,
    val displayName: String,
    val isRead: Boolean,
) {
    READ_COILS(1, "01 Read Coils", true),
    READ_DISCRETE_INPUTS(2, "02 Read Discrete Inputs", true),
    READ_HOLDING_REGISTERS(3, "03 Read Holding Registers", true),
    READ_INPUT_REGISTERS(4, "04 Read Input Registers", true),
    WRITE_SINGLE_COIL(5, "05 Write Single Coil", false),
    WRITE_SINGLE_REGISTER(6, "06 Write Single Register", false),
    WRITE_MULTIPLE_COILS(15, "15 Write Multiple Coils", false),
    WRITE_MULTIPLE_REGISTERS(16, "16 Write Multiple Registers", false),
    ;

    companion object {
        fun fromCode(code: Int): FunctionCode? = entries.firstOrNull { it.code == code }
    }
}

sealed interface ModbusRequest {
    val function: FunctionCode
    val address: Int
}

data class ReadBitsRequest(
    override val function: FunctionCode,
    override val address: Int,
    val quantity: Int,
) : ModbusRequest

data class ReadRegistersRequest(
    override val function: FunctionCode,
    override val address: Int,
    val quantity: Int,
) : ModbusRequest

data class WriteSingleCoilRequest(
    override val address: Int,
    val value: Boolean,
) : ModbusRequest {
    override val function: FunctionCode = FunctionCode.WRITE_SINGLE_COIL
}

data class WriteSingleRegisterRequest(
    override val address: Int,
    val value: Int,
) : ModbusRequest {
    override val function: FunctionCode = FunctionCode.WRITE_SINGLE_REGISTER
}

data class WriteMultipleCoilsRequest(
    override val address: Int,
    val values: List<Boolean>,
) : ModbusRequest {
    override val function: FunctionCode = FunctionCode.WRITE_MULTIPLE_COILS
}

data class WriteMultipleRegistersRequest(
    override val address: Int,
    val values: List<Int>,
) : ModbusRequest {
    override val function: FunctionCode = FunctionCode.WRITE_MULTIPLE_REGISTERS
}

sealed interface ModbusResponse {
    data class Bits(val values: List<Boolean>) : ModbusResponse
    data class Registers(val values: List<Int>) : ModbusResponse
    data class WriteAcknowledgement(
        val function: FunctionCode,
        val address: Int,
        val quantityOrValue: Int,
    ) : ModbusResponse
}

data class TransportExchange(
    val requestFrame: ByteArray,
    val responseFrame: ByteArray,
    val responsePdu: ByteArray,
    val elapsedMillis: Long,
)

enum class FailureKind {
    VALIDATION,
    TIMEOUT,
    CONNECTION,
    PROTOCOL,
    CANCELLED,
    UNKNOWN,
}

sealed interface ModbusOutcome {
    data class Success(
        val response: ModbusResponse,
        val exchange: TransportExchange,
    ) : ModbusOutcome

    data class DeviceException(
        val function: FunctionCode,
        val code: Int,
        val description: String,
        val exchange: TransportExchange,
    ) : ModbusOutcome

    data class Failure(
        val kind: FailureKind,
        val message: String,
        val requestFrame: ByteArray? = null,
        val responseFrame: ByteArray? = null,
    ) : ModbusOutcome
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

interface ModbusTransport {
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>

    suspend fun connect()

    suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int? = null): TransportExchange

    suspend fun disconnect()
}

open class ModbusFailure(
    message: String,
    val kind: FailureKind,
    val requestFrame: ByteArray? = null,
    val responseFrame: ByteArray? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class ModbusProtocolException(
    message: String,
    requestFrame: ByteArray? = null,
    responseFrame: ByteArray? = null,
) : ModbusFailure(message, FailureKind.PROTOCOL, requestFrame, responseFrame)
