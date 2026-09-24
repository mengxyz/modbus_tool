package dev.modbustool.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegisterValueCodecTest {
    @Test
    fun decodesSignedAndHexValues() {
        assertEquals("-1", RegisterValueCodec.decode(listOf(0xFFFF), RegisterFormat.SIGNED_16, WordOrder.HIGH_WORD_FIRST).single().displayValue)
        assertEquals("0x00AF", RegisterValueCodec.decode(listOf(0xAF), RegisterFormat.HEX_16, WordOrder.HIGH_WORD_FIRST).single().displayValue)
    }

    @Test
    fun roundTripsFloatWithBothWordOrders() {
        WordOrder.entries.forEach { order ->
            val encoded = RegisterValueCodec.encode(listOf("12.5"), RegisterFormat.FLOAT_32, order).getOrThrow()
            val decoded = RegisterValueCodec.decode(encoded, RegisterFormat.FLOAT_32, order).single()
            assertEquals("12.5", decoded.displayValue)
        }
    }

    @Test
    fun decodesUnsigned24WithBothWordOrders() {
        assertEquals(
            "11259375",
            RegisterValueCodec.decode(listOf(0x00AB, 0xCDEF), RegisterFormat.UNSIGNED_24, WordOrder.HIGH_WORD_FIRST).single().displayValue,
        )
        assertEquals(
            "11259375",
            RegisterValueCodec.decode(listOf(0xCDEF, 0x00AB), RegisterFormat.UNSIGNED_24, WordOrder.LOW_WORD_FIRST).single().displayValue,
        )
        assertEquals(
            listOf(0x00AB, 0xCDEF),
            RegisterValueCodec.encode(listOf("11259375"), RegisterFormat.UNSIGNED_24, WordOrder.HIGH_WORD_FIRST).getOrThrow(),
        )
        assertEquals(
            listOf(0xCDEF, 0x00AB),
            RegisterValueCodec.encode(listOf("11259375"), RegisterFormat.UNSIGNED_24, WordOrder.LOW_WORD_FIRST).getOrThrow(),
        )
    }

    @Test
    fun roundTripsSigned24BoundariesWithBothWordOrders() {
        WordOrder.entries.forEach { order ->
            listOf("-8388608", "-1", "0", "8388607").forEach { input ->
                val encoded = RegisterValueCodec.encode(listOf(input), RegisterFormat.SIGNED_24, order).getOrThrow()
                assertEquals(input, RegisterValueCodec.decode(encoded, RegisterFormat.SIGNED_24, order).single().displayValue)
            }
        }
    }

    @Test
    fun reportsIncompletePair() {
        val decoded = RegisterValueCodec.decode(listOf(1), RegisterFormat.UNSIGNED_32, WordOrder.HIGH_WORD_FIRST)
        assertTrue(decoded.single().displayValue.startsWith("Incomplete"))
    }

    @Test
    fun rejectsUnsignedOverflow() {
        assertTrue(RegisterValueCodec.encode(listOf("65536"), RegisterFormat.UNSIGNED_16, WordOrder.HIGH_WORD_FIRST).isFailure)
        assertTrue(RegisterValueCodec.encode(listOf("16777216"), RegisterFormat.UNSIGNED_24, WordOrder.HIGH_WORD_FIRST).isFailure)
        assertTrue(RegisterValueCodec.encode(listOf("8388608"), RegisterFormat.SIGNED_24, WordOrder.HIGH_WORD_FIRST).isFailure)
    }
}
