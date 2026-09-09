package dev.modbustool.core

enum class RegisterFormat(val label: String, val wordsPerValue: Int) {
    UNSIGNED_16("Unsigned 16-bit", 1),
    SIGNED_16("Signed 16-bit", 1),
    HEX_16("Hex 16-bit", 1),
    UNSIGNED_32("Unsigned 32-bit", 2),
    SIGNED_32("Signed 32-bit", 2),
    FLOAT_32("Float 32-bit", 2),
}

enum class WordOrder(val label: String) {
    HIGH_WORD_FIRST("High word first"),
    LOW_WORD_FIRST("Low word first"),
}

data class DecodedRegister(
    val offset: Int,
    val registerCount: Int,
    val raw: List<Int>,
    val displayValue: String,
)

object RegisterValueCodec {
    fun decode(values: List<Int>, format: RegisterFormat, wordOrder: WordOrder): List<DecodedRegister> {
        if (format.wordsPerValue == 1) {
            return values.mapIndexed { index, value ->
                DecodedRegister(index, 1, listOf(value), format16(value, format))
            }
        }

        return values.chunked(2).mapIndexed { index, words ->
            if (words.size < 2) {
                DecodedRegister(index * 2, 1, words, "Incomplete 32-bit pair")
            } else {
                val bits = combine(words[0], words[1], wordOrder)
                val text = when (format) {
                    RegisterFormat.UNSIGNED_32 -> bits.toUInt().toString()
                    RegisterFormat.SIGNED_32 -> bits.toString()
                    RegisterFormat.FLOAT_32 -> Float.fromBits(bits).toString()
                    else -> error("Unsupported 32-bit format")
                }
                DecodedRegister(index * 2, 2, words, text)
            }
        }
    }

    fun encode(inputs: List<String>, format: RegisterFormat, wordOrder: WordOrder): Result<List<Int>> = runCatching {
        inputs.map(String::trim).filter(String::isNotEmpty).flatMap { input ->
            when (format) {
                RegisterFormat.UNSIGNED_16 -> listOf(parseUnsigned(input, 0xFFFFu).toInt())
                RegisterFormat.SIGNED_16 -> {
                    val value = input.toIntOrNull() ?: error("'$input' is not a signed 16-bit integer")
                    require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "'$input' is outside the signed 16-bit range" }
                    listOf(value and 0xFFFF)
                }
                RegisterFormat.HEX_16 -> listOf(parseHex(input, 4).toInt())
                RegisterFormat.UNSIGNED_32 -> split(parseUnsigned(input, UInt.MAX_VALUE), wordOrder)
                RegisterFormat.SIGNED_32 -> {
                    val value = input.toIntOrNull() ?: error("'$input' is not a signed 32-bit integer")
                    split(value.toUInt(), wordOrder)
                }
                RegisterFormat.FLOAT_32 -> {
                    val value = input.toFloatOrNull() ?: error("'$input' is not a 32-bit floating-point value")
                    split(value.toRawBits().toUInt(), wordOrder)
                }
            }
        }.also { require(it.isNotEmpty()) { "Enter at least one value" } }
    }

    private fun format16(value: Int, format: RegisterFormat): String = when (format) {
        RegisterFormat.UNSIGNED_16 -> value.toString()
        RegisterFormat.SIGNED_16 -> value.toShort().toString()
        RegisterFormat.HEX_16 -> "0x${value.toString(16).uppercase().padStart(4, '0')}"
        else -> error("Unsupported 16-bit format")
    }

    private fun combine(first: Int, second: Int, order: WordOrder): Int {
        val high = if (order == WordOrder.HIGH_WORD_FIRST) first else second
        val low = if (order == WordOrder.HIGH_WORD_FIRST) second else first
        return (high shl 16) or low
    }

    private fun split(value: UInt, order: WordOrder): List<Int> {
        val high = (value shr 16).toInt() and 0xFFFF
        val low = value.toInt() and 0xFFFF
        return if (order == WordOrder.HIGH_WORD_FIRST) listOf(high, low) else listOf(low, high)
    }

    private fun parseUnsigned(input: String, max: UInt): UInt {
        val value = if (input.startsWith("0x", ignoreCase = true)) {
            input.drop(2).toUIntOrNull(16)
        } else {
            input.toUIntOrNull()
        } ?: error("'$input' is not an unsigned integer")
        require(value <= max) { "'$input' is outside the supported range" }
        return value
    }

    private fun parseHex(input: String, maxDigits: Int): UInt {
        val digits = input.removePrefix("0x").removePrefix("0X")
        require(digits.length <= maxDigits) { "'$input' has too many hexadecimal digits" }
        return digits.toUIntOrNull(16) ?: error("'$input' is not hexadecimal")
    }
}
