package dev.modbustool.transport

import dev.modbustool.core.FunctionCode
import dev.modbustool.core.MbapCodec
import dev.modbustool.core.ModbusClient
import dev.modbustool.core.ModbusOutcome
import dev.modbustool.core.ModbusResponse
import dev.modbustool.core.ReadRegistersRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class NetworkTransportTest {
    @Test
    fun tcpHandlesFragmentedResponse() = runTest {
        ServerSocket(0).use { server ->
            server.soTimeout = 2_000
            val serverError = AtomicReference<Throwable?>()
            val worker = thread(name = "modbus-tcp-test", isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val header = client.getInputStream().readNBytes(7)
                        val pduSize = ((header[4].toInt() and 0xFF) shl 8 or (header[5].toInt() and 0xFF)) - 1
                        client.getInputStream().readNBytes(pduSize)
                        val transactionId = (header[0].toInt() and 0xFF) shl 8 or (header[1].toInt() and 0xFF)
                        val response = MbapCodec.encode(transactionId, header[6].toInt() and 0xFF, bytes(3, 2, 0x12, 0x34))
                        client.getOutputStream().write(response, 0, 3)
                        client.getOutputStream().flush()
                        client.getOutputStream().write(response, 3, response.size - 3)
                        client.getOutputStream().flush()
                    }
                }.onFailure(serverError::set)
            }

            val transport = TcpModbusTransport(ConnectionConfig.Tcp("127.0.0.1", server.localPort, 1_000, 1_000))
            transport.connect()
            val outcome = ModbusClient(transport).execute(
                1,
                ReadRegistersRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 1),
            )
            transport.disconnect()
            worker.join(2_000)

            val response = assertIs<ModbusOutcome.Success>(outcome).response
            assertEquals(listOf(0x1234), assertIs<ModbusResponse.Registers>(response).values)
            assertNull(serverError.get())
        }
    }

    @Test
    fun udpPairsResponseByTransactionId() = runTest {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val serverError = AtomicReference<Throwable?>()
            val worker = thread(name = "modbus-udp-test", isDaemon = true) {
                runCatching {
                    val receiveBuffer = ByteArray(260)
                    val request = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    server.receive(request)
                    val frame = request.data.copyOfRange(request.offset, request.offset + request.length)
                    val transactionId = (frame[0].toInt() and 0xFF) shl 8 or (frame[1].toInt() and 0xFF)
                    val response = MbapCodec.encode(transactionId, frame[6].toInt() and 0xFF, bytes(4, 2, 0xBE, 0xEF))
                    server.send(DatagramPacket(response, response.size, request.address, request.port))
                }.onFailure(serverError::set)
            }

            val transport = UdpModbusTransport(ConnectionConfig.Udp("127.0.0.1", server.localPort, 1_000))
            transport.connect()
            val outcome = ModbusClient(transport).execute(
                7,
                ReadRegistersRequest(FunctionCode.READ_INPUT_REGISTERS, 0, 1),
            )
            transport.disconnect()
            worker.join(2_000)

            val response = assertIs<ModbusOutcome.Success>(outcome).response
            assertEquals(listOf(0xBEEF), assertIs<ModbusResponse.Registers>(response).values)
            assertNull(serverError.get())
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
