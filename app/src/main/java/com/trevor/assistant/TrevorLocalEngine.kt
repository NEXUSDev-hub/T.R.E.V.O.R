package com.trevor.assistant

import java.util.Locale

sealed class TrevorEngineResult {
    data class Answer(val text: String) : TrevorEngineResult()
    data class NeedAI(val prompt: String) : TrevorEngineResult()
    data class Error(val message: String) : TrevorEngineResult()
}

object TrevorLocalEngine {
    fun processCommand(command: String): TrevorEngineResult {
        val input = command.trim()
        if (input.isBlank()) return TrevorEngineResult.Error("Please enter a command.")
        val lower = input.lowercase(Locale.ROOT)

        val calculationPrefix = when {
            lower.startsWith("calculate ") -> 10
            lower.startsWith("calc ") -> 5
            lower.startsWith("solve ") -> 6
            else -> -1
        }
        if (calculationPrefix >= 0) {
            val expression = input.substring(calculationPrefix).trim()
            if (expression.isBlank()) return TrevorEngineResult.Error("Please provide a calculation.")
            val result = OfflineCalculator.calculate(expression)
            return if (result.isSuccess) {
                result.getOrNull()?.let { TrevorEngineResult.Answer(OfflineCalculator.formatResult(it)) }
                    ?: TrevorEngineResult.Error("Calculator returned no result.")
            } else {
                TrevorEngineResult.Error(result.exceptionOrNull()?.message ?: "Invalid calculation.")
            }
        }

        Regex("""(?:what is|calculate|find)\s+(\d+(?:\.\d+)?)%\s+of\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(input)?.let {
                val percent = it.groupValues[1].toDouble()
                val base = it.groupValues[2].toDouble()
                return TrevorEngineResult.Answer(format(percent * base / 100.0))
            }
        Regex("""(?:increase|decrease)\s+(\d+(?:\.\d+)?)\s+by\s+(\d+(?:\.\d+)?)%""", RegexOption.IGNORE_CASE)
            .find(input)?.let {
                val value = it.groupValues[1].toDouble()
                val percent = it.groupValues[2].toDouble()
                val multiplier = if (lower.contains("decrease")) 1.0 - percent / 100.0 else 1.0 + percent / 100.0
                return TrevorEngineResult.Answer(format(value * multiplier))
            }

        Regex("""(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*(km|m|cm|mm|mi|ft|in|kg|g|lb|c|f|k)\s+(?:to|into)\s*(km|m|cm|mm|mi|ft|in|kg|g|lb|c|f|k)""", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                val value = m.groupValues[1].toDouble()
                val from = m.groupValues[2].lowercase()
                val to = m.groupValues[3].lowercase()
                val converted = convert(value, from, to)
                if (converted != null) return TrevorEngineResult.Answer("$value $from = ${format(converted)} $to")
            }

        val dictionaryWord = when {
            lower.startsWith("define ") -> input.substring(7).trim()
            lower.startsWith("definition of ") -> input.substring(14).trim()
            lower.startsWith("meaning of ") -> input.substring(11).trim()
            else -> null
        }
        if (dictionaryWord != null) {
            if (dictionaryWord.isBlank()) return TrevorEngineResult.Error("Please provide a word to define.")
            val entry = OfflineDictionary.lookup(dictionaryWord)
            return if (entry != null) dictionaryAnswer(entry) else TrevorEngineResult.NeedAI(input)
        }

        OfflineDictionary.lookup(input)?.let { return dictionaryAnswer(it) }

        if (lower in setOf("help", "what can you do", "offline help", "local help")) {
            return TrevorEngineResult.Answer(
                "Local mode can handle calculations, percentages, unit conversions, dictionary lookups, date/time, device status, memory status and pending-task status without an AI provider."
            )
        }

        return TrevorEngineResult.NeedAI(input)
    }

    private fun dictionaryAnswer(entry: DictionaryEntry): TrevorEngineResult.Answer =
        TrevorEngineResult.Answer("${entry.word}\n\n${entry.definition}\n\nCategory: ${entry.category}")

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else "%.8f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

    private fun convert(value: Double, from: String, to: String): Double? {
        if (from == to) return value
        val length = mapOf("m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001, "mi" to 1609.344, "ft" to 0.3048, "in" to 0.0254)
        val mass = mapOf("kg" to 1.0, "g" to 0.001, "lb" to 0.45359237)
        if (from in length && to in length) return value * length.getValue(from) / length.getValue(to)
        if (from in mass && to in mass) return value * mass.getValue(from) / mass.getValue(to)
        val temperature = { v: Double, unit: String ->
            when (unit) {
                "c" -> v
                "f" -> (v - 32.0) * 5.0 / 9.0
                "k" -> v - 273.15
                else -> Double.NaN
            }
        }
        if (from in setOf("c", "f", "k") && to in setOf("c", "f", "k")) {
            val c = temperature(value, from)
            return when (to) {
                "c" -> c
                "f" -> c * 9.0 / 5.0 + 32.0
                "k" -> c + 273.15
                else -> null
            }
        }
        return null
    }
}
