package com.trevor.assistant

import kotlin.math.*

object TrevorScientificCalculator {
    fun evaluate(expression: String, degrees: Boolean = false): String {
        return runCatching {
            val parser = Parser(expression.replace("π", "pi"), degrees)
            val value = parser.parse()
            if (!value.isFinite()) error("Result is not finite.")
            if (abs(value - value.roundToInt()) < 1e-12) value.roundToInt().toString()
            else "%.12g".format(java.util.Locale.US, value)
        }.getOrElse { "Error: " + (it.message ?: "Invalid expression.") }
    }

    private class Parser(private val source: String, private val degrees: Boolean) {
        private var p = 0
        private fun skip() { while (p < source.length && source[p].isWhitespace()) p++ }
        private fun eat(c: Char): Boolean {
            skip()
            return if (p < source.length && source[p] == c) { p++; true } else false
        }
        private fun identifier(): String {
            skip()
            val s = p
            while (p < source.length && source[p].isLetter()) p++
            return source.substring(s, p).lowercase()
        }
        fun parse(): Double {
            val v = expression()
            skip()
            if (p != source.length) error("Unexpected input near " + source.substring(p) + ".")
            return v
        }
        private fun expression(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }
        private fun term(): Double {
            var v = power()
            while (true) v = when {
                eat('*') -> v * power()
                eat('/') -> { val d = power(); if (d == 0.0) error("Division by zero."); v / d }
                else -> return v
            }
        }
        private fun power(): Double {
            var v = unary()
            if (eat('^')) v = v.pow(power())
            return v
        }
        private fun unary(): Double = when {
            eat('+') -> unary()
            eat('-') -> -unary()
            else -> postfix()
        }
        private fun postfix(): Double {
            var v = primary()
            while (eat('!')) {
                if (v < 0 || v > 170 || v != floor(v)) error("Factorial needs an integer from 0 to 170.")
                var f = 1.0
                for (i in 2..v.toInt()) f *= i
                v = f
            }
            return v
        }
        private fun primary(): Double {
            skip()
            if (eat('(')) {
                val v = expression()
                if (!eat(')')) error("Missing ).")
                return v
            }
            skip()
            if (p < source.length && (source[p].isDigit() || source[p] == '.')) {
                val s = p
                while (p < source.length && (source[p].isDigit() || source[p] == '.')) p++
                if (p < source.length && (source[p] == 'e' || source[p] == 'E')) {
                    p++
                    if (p < source.length && (source[p] == '+' || source[p] == '-')) p++
                    while (p < source.length && source[p].isDigit()) p++
                }
                return source.substring(s, p).toDoubleOrNull() ?: error("Invalid number.")
            }
            val id = identifier()
            if (id.isBlank()) error("Expected a number, constant, or function.")
            if (id == "pi") return PI
            if (id == "e") return E
            if (!eat('(')) error("Function " + id + " needs parentheses.")
            val x = expression()
            if (!eat(')')) error("Missing ) after " + id + ".")
            val angle = if (degrees) x * PI / 180.0 else x
            return when (id) {
                "sin" -> sin(angle)
                "cos" -> cos(angle)
                "tan" -> tan(angle)
                "asin" -> asin(x).let { if (degrees) it * 180.0 / PI else it }
                "acos" -> acos(x).let { if (degrees) it * 180.0 / PI else it }
                "atan" -> atan(x).let { if (degrees) it * 180.0 / PI else it }
                "sqrt" -> sqrt(x)
                "abs" -> abs(x)
                "ln" -> ln(x)
                "log" -> log10(x)
                "exp" -> exp(x)
                else -> error("Unknown function " + id + ".")
            }
        }
    }
}
