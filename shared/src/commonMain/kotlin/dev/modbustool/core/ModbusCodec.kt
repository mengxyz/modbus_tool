package dev.modbustool.core

object ModbusPduCodec {
    fun validate(request: ModbusRequest): String? {
        if (request.address !in 0..0xFFFF) return "Address must be between 0 and 65535"

        val quantity = when (request) {
            is ReadBitsRequest -> {
                if (request.function !in setOf(FunctionCode.READ_COILS, FunctionCode.READ_DISCRETE_INPUTS)) {
                    return "ReadBitsRequest requires function 01 or 02"
                }
                if (request.quantity !in 1..2000) return "Bit read quantity must be between 1 and 2000"
                request.quantity
            }
            is ReadRegistersRequest -> {
                if (request.function !in setOf(FunctionCode.READ_HOLDING_REGISTERS, FunctionCode.READ_INPUT_REGISTERS)) {
                    return "ReadRegistersRequest requires function 03 or 04"
                }
                if (request.quantity !in 1..125) return "Register read quantity must be between 1 and 125"
                request.quantity
            }
            is WriteSingleCoilRequest -> 1
            is WriteSingleRegisterRequest -> {
                if (request.value !in 0..0xFFFF) return "Register value must be between 0 and 65535"
                1
            }
            is WriteMultipleCoilsRequest -> {
                if (request.values.size !in 1..1968) return "Coil write quantity must be between 1 and 1968"
                request.values.size
            }
            is WriteMultipleRegistersRequest -> {
                if (request.values.size !in 1..123) return "Register write quantity must be between 1 and 123"
                if (request.values.any { it !in 0..0xFFFF }) return "Every register value must be between 0 and 65535"
                request.values.size
            }
        }

        if (request.address + quantity > 0x10000) return "Address range exceeds 65535"
        return null
    }

    fun encodeRequest(request: ModbusRequest): ByteArray {
        validate(request)?.let { throw IllegalArgumentException(it) }
        val output = mutableListOf(request.function.code.toByte())
        output.addU16(request.address)

        when (request) {
            is ReadBitsRequest -> output.addU16(request.quantity)
            is ReadRegistersRequest -> output.addU16(request.quantity)
            is WriteSingleCoilRequest -> output.addU16(if (request.value) 0xFF00 else 0x0000)
            is WriteSingleRegisterRequest -> output.addU16(request.value)
            is WriteMultipleCoilsRequest -> {
                output.addU16(request.values.size)
                val byteCount = (request.values.size + 7) / 8
                output += byteCount.toByte()
                repeat(byteCount) { byteIndex ->
                    var packed = 0
                    repeat(8) { bitIndex ->
                        val valueIndex = byteIndex * 8 + bitIndex
                        if (valueIndex < request.values.size && request.values[valueIndex]) {
                            packed = packed or (1 shl bitIndex)
                        }
                    }
                    output += packed.toByte()
                }
            }
            is WriteMultipleRegistersRequest -> {
                output.addU16(request.values.size)
                output += (request.values.size * 2).toByte()
                request.values.forEach(output::addU16)
            }
        }
        return output.toByteArray()
    }

    fun decodeResponse(request: ModbusRequest, pdu: ByteArray): DecodedPdu {
        if (pdu.isEmpty()) throw ModbusProtocolException("Response PDU is empty")
        val actualFunction = pdu[0].u8()
        if (actualFunction == (request.function.code or 0x80)) {
            if (pdu.size != 2) throw ModbusProtocolException("Malformed Modbus exception response")
            val code = pdu[1].u8()
            return DecodedPdu.Exception(code, exceptionDescription(code))
        }
        if (actualFunction != request.function.code) {
            throw ModbusProtocolException(
                "Expected function ${request.function.code}, received $actualFunction",
            )
        }

        val response = when (request) {
            is ReadBitsRequest -> decodeBits(pdu, request.quantity)
            is ReadRegistersRequest -> decodeRegisters(pdu, request.quantity)
            is WriteSingleCoilRequest -> {
                requireWriteEcho(pdu, request.address, if (request.value) 0xFF00 else 0)
                ModbusResponse.WriteAcknowledgement(request.function, request.address, if (request.value) 0xFF00 else 0)
            }
            is WriteSingleRegisterRequest -> {
                requireWriteEcho(pdu, request.address, request.value)
                ModbusResponse.WriteAcknowledgement(request.function, request.address, request.value)
            }
            is WriteMultipleCoilsRequest -> {
                requireWriteEcho(pdu, request.address, request.values.size)
                ModbusResponse.WriteAcknowledgement(request.function, request.address, request.values.size)
            }
            is WriteMultipleRegistersRequest -> {
                requireWriteEcho(pdu, request.address, request.values.size)
                ModbusResponse.WriteAcknowledgement(request.function, request.address, request.values.size)
            }
        }
        return DecodedPdu.Response(response)
    }

    private fun decodeBits(pdu: ByteArray, quantity: Int): ModbusResponse.Bits {
        if (pdu.size < 2) throw ModbusProtocolException("Bit response is truncated")
        val byteCount = pdu[1].u8()
        val expectedBytes = (quantity + 7) / 8
        if (byteCount != expectedBytes || pdu.size != byteCount + 2) {
            throw ModbusProtocolException("Unexpected bit response byte count")
        }
        return ModbusResponse.Bits(
            List(quantity) { index ->
                pdu[2 + index / 8].u8() and (1 shl (index % 8)) != 0
            },
        )
    }

    private fun decodeRegisters(pdu: ByteArray, quantity: Int): ModbusResponse.Registers {
        if (pdu.size < 2) throw ModbusProtocolException("Register response is truncated")
        val byteCount = pdu[1].u8()
        if (byteCount != quantity * 2 || pdu.size != byteCount + 2) {
            throw ModbusProtocolException("Unexpected register response byte count")
        }
        return ModbusResponse.Registers(
            List(quantity) { index -> pdu.u16(2 + index * 2) },
        )
    }

    private fun requireWriteEcho(pdu: ByteArray, address: Int, value: Int) {
        if (pdu.size != 5) throw ModbusProtocolException("Write acknowledgement must contain 5 bytes")
        if (pdu.u16(1) != address || pdu.u16(3) != value) {
            throw ModbusProtocolException("Write acknowledgement does not match the request")
        }
    }

    private fun exceptionDescription(code: Int): String = when (code) {
        1 -> "Illegal function"
        2 -> "Illegal data address"
        3 -> "Illegal data value"
        4 -> "Server device failure"
        5 -> "Acknowledge"
        6 -> "Server device busy"
        8 -> "Memory parity error"
        10 -> "Gateway path unavailable"
        11 -> "Gateway target device failed to respond"
        else -> "Unknown device exception"
    }
}

sealed interface DecodedPdu {
    data class Response(val value: ModbusResponse) : DecodedPdu
    data class Exception(val code: Int, val description: String) : DecodedPdu
}

object RtuCodec {
    fun encode(unitId: Int, pdu: ByteArray): ByteArray {
        require(unitId in 1..247) { "RTU unit ID must be between 1 and 247" }
        require(pdu.size <= 253) { "PDU is too large" }
        val body = byteArrayOf(unitId.toByte()) + pdu
        val crc = crc16(body)
        return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    fun decode(frame: ByteArray, expectedUnitId: Int): ByteArray {
        if (frame.size < 5) throw ModbusProtocolException("RTU response is too short", responseFrame = frame)
        if (frame[0].u8() != expectedUnitId) {
            throw ModbusProtocolException("RTU response unit ID does not match", responseFrame = frame)
        }
        val expectedCrc = crc16(frame.copyOf(frame.size - 2))
        val actualCrc = frame[frame.lastIndex - 1].u8() or (frame.last().u8() shl 8)
        if (actualCrc != expectedCrc) {
            throw ModbusProtocolException("RTU response CRC is invalid", responseFrame = frame)
        }
        return frame.copyOfRange(1, frame.size - 2)
    }

    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        bytes.forEach { byte ->
            crc = crc xor byte.u8()
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}

object MbapCodec {
    fun encode(transactionId: Int, unitId: Int, pdu: ByteArray): ByteArray {
        require(transactionId in 0..0xFFFF) { "Transaction ID must be between 0 and 65535" }
        require(unitId in 0..0xFF) { "Unit ID must be between 0 and 255" }
        require(pdu.size in 1..253) { "PDU must contain between 1 and 253 bytes" }
        val length = pdu.size + 1
        return byteArrayOf(
            (transactionId ushr 8).toByte(), transactionId.toByte(),
            0, 0,
            (length ushr 8).toByte(), length.toByte(),
            unitId.toByte(),
        ) + pdu
    }

    fun responseLengthFromHeader(header: ByteArray): Int {
        if (header.size != 7) throw ModbusProtocolException("MBAP header must contain 7 bytes")
        val length = header.u16(4)
        if (length !in 2..254) throw ModbusProtocolException("Invalid MBAP length $length")
        return length - 1
    }

    fun decode(frame: ByteArray, expectedTransactionId: Int, expectedUnitId: Int): ByteArray {
        if (frame.size < 8) throw ModbusProtocolException("MBAP response is too short", responseFrame = frame)
        if (frame.u16(0) != expectedTransactionId) {
            throw ModbusProtocolException("Transaction ID does not match", responseFrame = frame)
        }
        if (frame.u16(2) != 0) throw ModbusProtocolException("MBAP protocol ID must be zero", responseFrame = frame)
        val length = frame.u16(4)
        if (length !in 2..254 || frame.size != 6 + length) {
            throw ModbusProtocolException("MBAP length does not match response", responseFrame = frame)
        }
        if (frame[6].u8() != expectedUnitId) {
            throw ModbusProtocolException("MBAP unit ID does not match", responseFrame = frame)
        }
        return frame.copyOfRange(7, frame.size)
    }
}

internal fun Byte.u8(): Int = toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int {
    if (index < 0 || index + 1 >= size) throw ModbusProtocolException("Frame is truncated")
    return this[index].u8() shl 8 or this[index + 1].u8()
}

private fun MutableList<Byte>.addU16(value: Int) {
    add((value ushr 8).toByte())
    add(value.toByte())
}

fun ByteArray.toHexString(): String = joinToString(" ") { it.u8().toString(16).uppercase().padStart(2, '0') }
