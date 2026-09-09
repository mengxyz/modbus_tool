package dev.modbustool.transport

import dev.modbustool.core.ConnectionState
import dev.modbustool.core.FailureKind
import dev.modbustool.core.MbapCodec
import dev.modbustool.core.ModbusFailure
import dev.modbustool.core.ModbusProtocolException
import dev.modbustool.core.ModbusTransport
import dev.modbustool.core.TransportExchange
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TcpModbusTransport(
    private val config: ConnectionConfig.Tcp,
) : ModbusTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var transactionId = 0

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        mutableState.value = ConnectionState.CONNECTING
        try {
            socket = withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMillis)
                    soTimeout = config.responseTimeoutMillis
                }
            }
            mutableState.value = ConnectionState.CONNECTED
        } catch (error: Exception) {
            closeNow()
            throw connectionFailure("Unable to connect to ${config.host}:${config.port}", error)
        }
    }

    override suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int?): TransportExchange = mutex.withLock {
        val activeSocket = socket?.takeIf { it.isConnected && !it.isClosed }
            ?: throw ModbusFailure("TCP transport is not connected", FailureKind.CONNECTION)
        transactionId = (transactionId + 1) and 0xFFFF
        val currentTransaction = transactionId
        val requestFrame = MbapCodec.encode(currentTransaction, unitId, requestPdu)
        val mark = TimeSource.Monotonic.markNow()
        val effectiveTimeout = timeoutMillis ?: config.responseTimeoutMillis
        var responseFrame: ByteArray? = null
        try {
            responseFrame = withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + effectiveTimeout * 1_000_000L
                activeSocket.getOutputStream().apply {
                    write(requestFrame)
                    flush()
                }
                var matchingFrame: ByteArray? = null
                while (matchingFrame == null) {
                    val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1).toInt()
                    activeSocket.soTimeout = remaining
                    val header = activeSocket.getInputStream().readExactly(7)
                    val pduLength = MbapCodec.responseLengthFromHeader(header)
                    val frame = header + activeSocket.getInputStream().readExactly(pduLength)
                    val responseTransaction = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                    if (responseTransaction == currentTransaction) matchingFrame = frame
                    if (System.nanoTime() >= deadline && matchingFrame == null) throw SocketTimeoutException("No matching transaction")
                }
                matchingFrame
            }
            val frame = requireNotNull(responseFrame)
            val pdu = MbapCodec.decode(frame, currentTransaction, unitId)
            TransportExchange(requestFrame, frame, pdu, mark.elapsedNow().inWholeMilliseconds)
        } catch (error: SocketTimeoutException) {
            throw ModbusFailure("TCP response timed out", FailureKind.TIMEOUT, requestFrame, responseFrame, error)
        } catch (error: ModbusProtocolException) {
            throw ModbusProtocolException(error.message ?: "Invalid TCP response", requestFrame, responseFrame)
        } catch (error: Exception) {
            closeNow()
            throw connectionFailure("TCP connection failed", error, requestFrame, responseFrame)
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        mutableState.value = ConnectionState.DISCONNECTING
        withContext(Dispatchers.IO) { closeNow() }
    }

    private fun closeNow() {
        runCatching { socket?.close() }
        socket = null
        mutableState.value = ConnectionState.DISCONNECTED
    }
}

class UdpModbusTransport(
    private val config: ConnectionConfig.Udp,
) : ModbusTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState
    private val mutex = Mutex()
    private var socket: DatagramSocket? = null
    private var transactionId = 0

    override suspend fun connect(): Unit = mutex.withLock {
        if (socket?.isClosed == false) return
        mutableState.value = ConnectionState.CONNECTING
        try {
            socket = withContext(Dispatchers.IO) {
                val address = InetAddress.getByName(config.host)
                DatagramSocket().apply {
                    connect(address, config.port)
                    soTimeout = config.responseTimeoutMillis
                }
            }
            mutableState.value = ConnectionState.CONNECTED
        } catch (error: Exception) {
            closeNow()
            throw connectionFailure("Unable to open UDP endpoint ${config.host}:${config.port}", error)
        }
    }

    override suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int?): TransportExchange = mutex.withLock {
        val activeSocket = socket?.takeIf { !it.isClosed }
            ?: throw ModbusFailure("UDP transport is not connected", FailureKind.CONNECTION)
        transactionId = (transactionId + 1) and 0xFFFF
        val currentTransaction = transactionId
        val requestFrame = MbapCodec.encode(currentTransaction, unitId, requestPdu)
        val mark = TimeSource.Monotonic.markNow()
        val effectiveTimeout = timeoutMillis ?: config.responseTimeoutMillis
        var responseFrame: ByteArray? = null
        try {
            responseFrame = withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + effectiveTimeout * 1_000_000L
                activeSocket.send(DatagramPacket(requestFrame, requestFrame.size))
                var matchingFrame: ByteArray? = null
                while (matchingFrame == null) {
                    activeSocket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1).toInt()
                    val buffer = ByteArray(260)
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    val frame = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (frame.size >= 2) {
                        val responseTransaction = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                        if (responseTransaction == currentTransaction) matchingFrame = frame
                    }
                    if (System.nanoTime() >= deadline && matchingFrame == null) throw SocketTimeoutException("No matching transaction")
                }
                matchingFrame
            }
            val frame = requireNotNull(responseFrame)
            val pdu = MbapCodec.decode(frame, currentTransaction, unitId)
            TransportExchange(requestFrame, frame, pdu, mark.elapsedNow().inWholeMilliseconds)
        } catch (error: SocketTimeoutException) {
            throw ModbusFailure("UDP response timed out", FailureKind.TIMEOUT, requestFrame, responseFrame, error)
        } catch (error: ModbusProtocolException) {
            throw ModbusProtocolException(error.message ?: "Invalid UDP response", requestFrame, responseFrame)
        } catch (error: Exception) {
            if (error is SocketException) closeNow()
            throw connectionFailure("UDP exchange failed", error, requestFrame, responseFrame)
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        mutableState.value = ConnectionState.DISCONNECTING
        withContext(Dispatchers.IO) { closeNow() }
    }

    private fun closeNow() {
        runCatching { socket?.close() }
        socket = null
        mutableState.value = ConnectionState.DISCONNECTED
    }
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val result = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(result, offset, count - offset)
        if (read < 0) throw EOFException("Connection closed while reading a Modbus response")
        offset += read
    }
    return result
}

private fun connectionFailure(
    message: String,
    cause: Throwable,
    requestFrame: ByteArray? = null,
    responseFrame: ByteArray? = null,
): ModbusFailure = ModbusFailure(
    "$message: ${cause.message ?: cause::class.simpleName}",
    FailureKind.CONNECTION,
    requestFrame,
    responseFrame,
    cause,
)
