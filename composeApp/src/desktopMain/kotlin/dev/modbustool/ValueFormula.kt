package dev.modbustool

import java.math.BigDecimal

/** Small, deterministic arithmetic evaluator for display scaling; it never executes code. */
object ValueFormula {
    fun evaluate(expression: String, value: Double): Result<String> = runCatching {
        if (expression.isBlank()) return@runCatching display(value)
        val result = Parser(expression, value).parse()
        require(result.isFinite()) { "Formula result is not finite" }
        display(result)
    }

    private fun display(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private class Parser(private val input: String, private val value: Double) {
        private var position = 0

        fun parse(): Double {
            val result = expression()
            skipSpaces()
            require(position == input.length) { "Unexpected '${input[position]}' at ${position + 1}" }
            return result
        }

        private fun expression(): Double {
            var result = term()
            while (true) {
                skipSpaces()
                result = when {
                    take('+') -> result + term()
                    take('-') -> result - term()
                    else -> return result
                }
            }
        }

        private fun term(): Double {
            var result = factor()
            while (true) {
                skipSpaces()
                result = when {
                    take('*') -> result * factor()
                    take('/') -> result / factor()
                    else -> return result
                }
            }
        }

        private fun factor(): Double {
            skipSpaces()
            if (take('+')) return factor()
            if (take('-')) return -factor()
            if (take('(')) {
                val result = expression()
                skipSpaces()
                require(take(')')) { "Missing ')'" }
                return result
            }
            if (input.regionMatches(position, "value", 0, 5, ignoreCase = true)) {
                position += 5
                return value
            }
            if (position < input.length && (input[position] == 'x' || input[position] == 'X')) {
                position++
                return value
            }
            val start = position
            while (position < input.length && (input[position].isDigit() || input[position] in ".eE" ||
                    (input[position] in "+-" && position > start && input[position - 1] in "eE"))) position++
            require(position > start) { "Expected a number or value at ${position + 1}" }
            return input.substring(start, position).toDoubleOrNull() ?: error("Invalid number")
        }

        private fun take(character: Char): Boolean = position < input.length && input[position] == character && run { position++; true }
        private fun skipSpaces() { while (position < input.length && input[position].isWhitespace()) position++ }
    }
}
