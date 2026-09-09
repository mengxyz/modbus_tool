package dev.modbustool.transport

import com.fazecast.jSerialComm.SerialPort
import dev.modbustool.core.ConnectionState
import dev.modbustool.core.FailureKind
import dev.modbustool.core.ModbusFailure
import dev.modbustool.core.ModbusProtocolException
import dev.modbustool.core.ModbusTransport
import dev.modbustool.core.RtuCodec
import dev.modbustool.core.RtuTransportMarker
import dev.modbustool.core.TransportExchange
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SerialModbusTransport(
    private val config: ConnectionConfig.Serial,
    private val endpoint: SerialEndpoint = JSerialEndpoint(config.portName),
) : ModbusTransport, RtuTransportMarker {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState
    private val mutex = Mutex()

    override suspend fun connect(): Unit = mutex.withLock {
        if (endpoint.isOpen) return
        mutableState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) { endpoint.configureAndOpen(config) }
            mutableState.value = ConnectionState.CONNECTED
        } catch (error: Exception) {
            closeNow()
            throw ModbusFailure(
                "Unable to open ${config.portName}: ${error.message ?: error::class.simpleName}",
                FailureKind.CONNECTION,
                cause = error,
            )
        }
    }

    override suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int?): TransportExchange = mutex.withLock {
        if (!endpoint.isOpen) throw ModbusFailure("Serial transport is not connected", FailureKind.CONNECTION)
        val requestFrame = RtuCodec.encode(unitId, requestPdu)
        val mark = TimeSource.Monotonic.markNow()
        var responseFrame: ByteArray? = null
        try {
            responseFrame = withContext(Dispatchers.IO) {
                endpoint.setReadTimeout(timeoutMillis ?: config.responseTimeoutMillis)
                endpoint.flush()
                val written = endpoint.write(requestFrame)
                if (written != requestFrame.size) error("Only $written of ${requestFrame.size} bytes were written")
                readResponse(endpoint)
            }
            val frame = requireNotNull(responseFrame)
            val pdu = RtuCodec.decode(frame, unitId)
            TransportExchange(requestFrame, frame, pdu, mark.elapsedNow().inWholeMilliseconds)
        } catch (error: TimeoutException) {
            throw ModbusFailure("Serial response timed out", FailureKind.TIMEOUT, requestFrame, responseFrame, error)
        } catch (error: ModbusProtocolException) {
            throw ModbusProtocolException(error.message ?: "Invalid RTU response", requestFrame, responseFrame)
        } catch (error: Exception) {
            closeNow()
            throw ModbusFailure(
                "Serial exchange failed: ${error.message ?: error::class.simpleName}",
                FailureKind.CONNECTION,
                requestFrame,
                responseFrame,
                error,
            )
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        mutableState.value = ConnectionState.DISCONNECTING
        withContext(Dispatchers.IO) { closeNow() }
    }

    private fun readResponse(port: SerialEndpoint): ByteArray {
        val prefix = port.readExactly(2)
        val function = prefix[1].toInt() and 0xFF
        return when {
            function and 0x80 != 0 -> prefix + port.readExactly(3)
            function in 1..4 -> {
                val byteCount = port.readExactly(1)
                prefix + byteCount + port.readExactly((byteCount[0].toInt() and 0xFF) + 2)
            }
            function in setOf(5, 6, 15, 16) -> prefix + port.readExactly(6)
            else -> throw ModbusProtocolException("Unexpected RTU function $function")
        }
    }

    private fun SerialEndpoint.readExactly(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val chunk = ByteArray(count - offset)
            val read = read(chunk)
            if (read <= 0) throw TimeoutException("Timed out while reading $count bytes")
            chunk.copyInto(result, offset, 0, read)
            offset += read
        }
        return result
    }

    private fun closeNow() {
        runCatching { endpoint.close() }
        mutableState.value = ConnectionState.DISCONNECTED
    }

    companion object {
        fun availablePorts(): List<SerialPortInfo> = SerialPort.getCommPorts()
            .map { SerialPortInfo(it.systemPortName, it.descriptivePortName.orEmpty()) }
            .sortedBy(SerialPortInfo::systemName)
    }
}

interface SerialEndpoint {
    val isOpen: Boolean

    fun configureAndOpen(config: ConnectionConfig.Serial)

    fun setReadTimeout(timeoutMillis: Int)

    fun flush()

    fun write(bytes: ByteArray): Int

    fun read(buffer: ByteArray): Int

    fun close()
}

private class JSerialEndpoint(portName: String) : SerialEndpoint {
    private val port = SerialPort.getCommPort(portName)

    override val isOpen: Boolean get() = port.isOpen

    override fun configureAndOpen(config: ConnectionConfig.Serial) {
        port.setComPortParameters(
            config.baudRate,
            8,
            if (config.stopBits == 2) SerialPort.TWO_STOP_BITS else SerialPort.ONE_STOP_BIT,
            when (config.parity) {
                SerialParity.NONE -> SerialPort.NO_PARITY
                SerialParity.EVEN -> SerialPort.EVEN_PARITY
                SerialParity.ODD -> SerialPort.ODD_PARITY
            },
        )
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_BLOCKING,
            config.responseTimeoutMillis,
            config.responseTimeoutMillis,
        )
        if (!port.openPort()) error("The operating system refused to open the port")
    }

    override fun setReadTimeout(timeoutMillis: Int) {
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, timeoutMillis, timeoutMillis)
    }

    override fun flush() {
        port.flushIOBuffers()
    }

    override fun write(bytes: ByteArray): Int = port.writeBytes(bytes, bytes.size)

    override fun read(buffer: ByteArray): Int = port.readBytes(buffer, buffer.size)

    override fun close() {
        port.closePort()
    }
}

object TransportFactory {
    fun create(config: ConnectionConfig): ModbusTransport = when (config) {
        is ConnectionConfig.Serial -> SerialModbusTransport(config)
        is ConnectionConfig.Tcp -> TcpModbusTransport(config)
        is ConnectionConfig.Udp -> UdpModbusTransport(config)
    }
}
