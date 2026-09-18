package com.trevor.assistant

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.PI
import kotlin.math.E

object OfflineCalculator {

    fun calculate(expression: String): Result<Double> {
        return try {
            val parser = Parser(expression)
            val result = parser.parse()

            if (!result.isFinite()) {
                Result.failure(
                    ArithmeticException("Result is not a finite number.")
                )
            } else {
                Result.success(result)
            }

        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException(
                    e.message ?: "Invalid calculation."
                )
            )
        }
    }

    fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.10f", value)
                .trimEnd('0')
                .trimEnd('.')
        }
    }

    private class Parser(
        private val input: String
    ) {

        private var position = 0

        fun parse(): Double {
            val result = parseExpression()
            skipSpaces()

            if (position != input.length) {
                throw IllegalArgumentException(
                    "Unexpected character at position ${position + 1}."
                )
            }

            return result
        }

        private fun parseExpression(): Double {
            var result = parseTerm()

            while (true) {
                skipSpaces()

                result = when {
                    match('+') -> result + parseTerm()
                    match('-') -> result - parseTerm()
                    else -> return result
                }
            }
        }

        private fun parseTerm(): Double {
            var result = parsePower()

            while (true) {
                skipSpaces()

                result = when {
                    match('*') -> result * parsePower()

                    match('/') -> {
                        val divisor = parsePower()

                        if (divisor == 0.0) {
                            throw ArithmeticException(
                                "Division by zero."
                            )
                        }

                        result / divisor
                    }

                    else -> return result
                }
            }
        }

        private fun parsePower(): Double {
            var result = parseUnary()

            skipSpaces()

            if (match('^')) {
                val exponent = parsePower()
                result = result.pow(exponent)
            }

            return result
        }

        private fun parseUnary(): Double {
            skipSpaces()

            return when {
                match('+') -> parseUnary()
                match('-') -> -parseUnary()
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpaces()

            if (match('(')) {
                val result = parseExpression()

                skipSpaces()

                if (!match(')')) {
                    throw IllegalArgumentException(
                        "Missing closing parenthesis."
                    )
                }

                return result
            }

            if (position >= input.length) {
                throw IllegalArgumentException(
                    "Unexpected end of expression."
                )
            }

            if (input[position].isDigit() || input[position] == '.') {
                return parseNumber()
            }

            if (input[position].isLetter()) {
                val name = parseIdentifier()

                skipSpaces()

                return if (match('(')) {
                    val argument = parseExpression()

                    skipSpaces()

                    if (!match(')')) {
                        throw IllegalArgumentException(
                            "Missing closing parenthesis."
                        )
                    }

                    applyFunction(name, argument)
                } else {
                    getConstant(name)
                }
            }

            throw IllegalArgumentException(
                "Unexpected character '${input[position]}'."
            )
        }

        private fun parseNumber(): Double {
            val start = position

            var hasDigits = false

            while (
                position < input.length &&
                input[position].isDigit()
            ) {
                position++
                hasDigits = true
            }

            if (
                position < input.length &&
                input[position] == '.'
            ) {
                position++

                while (
                    position < input.length &&
                    input[position].isDigit()
                ) {
                    position++
                    hasDigits = true
                }
            }

            if (!hasDigits) {
                throw IllegalArgumentException(
                    "Invalid number."
                )
            }

            if (
                position < input.length &&
                (input[position] == 'e' ||
                        input[position] == 'E')
            ) {
                position++

                if (
                    position < input.length &&
                    (input[position] == '+' ||
                            input[position] == '-')
                ) {
                    position++
                }

                val exponentStart = position

                while (
                    position < input.length &&
                    input[position].isDigit()
                ) {
                    position++
                }

                if (position == exponentStart) {
                    throw IllegalArgumentException(
                        "Invalid scientific notation."
                    )
                }
            }

            return input
                .substring(start, position)
                .toDouble()
        }

        private fun parseIdentifier(): String {
            val start = position

            while (
                position < input.length &&
                (
                    input[position].isLetter() ||
                    input[position].isDigit()
                )
            ) {
                position++
            }

            return input
                .substring(start, position)
                .lowercase()
        }

        private fun getConstant(
            name: String
        ): Double {
            return when (name) {
                "pi" -> PI
                "e" -> E
                else -> throw IllegalArgumentException(
                    "Unknown constant: $name"
                )
            }
        }

        private fun applyFunction(
            name: String,
            value: Double
        ): Double {

            return when (name) {

                "sqrt" -> {
                    if (value < 0) {
                        throw ArithmeticException(
                            "Square root of a negative number."
                        )
                    }

                    sqrt(value)
                }

                "abs" -> abs(value)

                "sin" -> sin(Math.toRadians(value))

                "cos" -> cos(Math.toRadians(value))

                "tan" -> tan(Math.toRadians(value))

                "asin" -> Math.toDegrees(asin(value))

                "acos" -> Math.toDegrees(acos(value))

                "atan" -> Math.toDegrees(atan(value))

                "log" -> {
                    if (value <= 0) {
                        throw ArithmeticException(
                            "Logarithm requires a positive number."
                        )
                    }

                    log10(value)
                }

                "ln" -> {
                    if (value <= 0) {
                        throw ArithmeticException(
                            "Natural logarithm requires a positive number."
                        )
                    }

                    ln(value)
                }

                "exp" -> exp(value)

                else -> throw IllegalArgumentException(
                    "Unknown function: $name"
                )
            }
        }

        private fun match(
            character: Char
        ): Boolean {
            skipSpaces()

            if (
                position < input.length &&
                input[position] == character
            ) {
                position++
                return true
            }

            return false
        }

        private fun skipSpaces() {
            while (
                position < input.length &&
                input[position].isWhitespace()
            ) {
                position++
            }
        }

        private fun Double.pow(
            exponent: Double
        ): Double {
            return this.powInternal(exponent)
        }

        private fun Double.powInternal(
            exponent: Double
        ): Double {
            return kotlin.math.pow(this, exponent)
        }
    }
}
