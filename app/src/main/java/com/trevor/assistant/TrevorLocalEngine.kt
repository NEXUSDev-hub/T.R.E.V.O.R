package com.trevor.assistant

import android.content.Context
import java.text.DateFormat
import java.util.Date
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
        if (lower in setOf("help", "local help", "what can you do offline")) return TrevorEngineResult.Answer("Local TREVOR handles calculations, percentages, conversions, dates, dictionary, device state, usage analysis, memory/tasks and deterministic multi-step Android automation. These consume 0 Gemini tokens.")
        if (lower == "time" || lower.contains("what time is it")) return TrevorEngineResult.Answer("Local time: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date()))
        if (lower == "date" || lower.contains("today's date") || lower.contains("todays date")) return TrevorEngineResult.Answer("Today: " + DateFormat.getDateInstance(DateFormat.FULL).format(Date()))
        val calc = Regex("""^(?:calculate|calc|solve)\s+(.+)$""", RegexOption.IGNORE_CASE).find(input)
        if (calc != null) return OfflineCalculator.calculate(calc.groupValues[1]).fold({ TrevorEngineResult.Answer(OfflineCalculator.formatResult(it)) }, { TrevorEngineResult.Error(it.message ?: "Invalid calculation.") })
        Regex("""(?:what is|calculate|find)\s+(-?\d+(?:\.\d+)?)%\s+of\s+(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(input)?.let { return TrevorEngineResult.Answer(format(it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100.0)) }
        Regex("""(?:increase|decrease)\s+(-?\d+(?:\.\d+)?)\s+by\s+(-?\d+(?:\.\d+)?)%""", RegexOption.IGNORE_CASE).find(input)?.let {
            val value = it.groupValues[1].toDouble(); val percent = it.groupValues[2].toDouble()
            val multiplier = if (lower.contains("decrease")) 1.0 - percent / 100.0 else 1.0 + percent / 100.0
            return TrevorEngineResult.Answer(format(value * multiplier))
        }
        Regex("""(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*(km|m|cm|mm|mi|ft|in|kg|g|lb|c|f|k)\s+(?:to|into)\s*(km|m|cm|mm|mi|ft|in|kg|g|lb|c|f|k)""", RegexOption.IGNORE_CASE).find(input)?.let { m ->
            convert(m.groupValues[1].toDouble(), m.groupValues[2].lowercase(), m.groupValues[3].lowercase())?.let { return TrevorEngineResult.Answer(m.groupValues[1] + " " + m.groupValues[2] + " = " + format(it) + " " + m.groupValues[3]) }
        }
        val word = when {
            lower.startsWith("define ") -> input.substring(7).trim()
            lower.startsWith("definition of ") -> input.substring(14).trim()
            lower.startsWith("meaning of ") -> input.substring(11).trim()
            else -> null
        }
        if (word != null) {
            if (word.isBlank()) return TrevorEngineResult.Error("Please provide a word to define.")
            return OfflineDictionary.lookup(word)?.let { TrevorEngineResult.Answer(it.word + "\n\n" + it.definition + "\n\nCategory: " + it.category) } ?: TrevorEngineResult.NeedAI(input)
        }
        OfflineDictionary.lookup(input)?.let { return TrevorEngineResult.Answer(it.word + "\n\n" + it.definition + "\n\nCategory: " + it.category) }
        return TrevorEngineResult.NeedAI(input)
    }

    suspend fun processCommand(context: Context, command: String): TrevorEngineResult {
        val automation = TrevorAutomationEngine.plan(command)
        if (automation != null) return TrevorEngineResult.Answer(TrevorAutomationEngine.execute(context, automation))
        return processCommand(command)
    }

    private fun format(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.8f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun convert(value: Double, from: String, to: String): Double? {
        if (from == to) return value
        val length = mapOf("m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001, "mi" to 1609.344, "ft" to 0.3048, "in" to 0.0254)
        val mass = mapOf("kg" to 1.0, "g" to 0.001, "lb" to 0.45359237)
        if (from in length && to in length) return value * length.getValue(from) / length.getValue(to)
        if (from in mass && to in mass) return value * mass.getValue(from) / mass.getValue(to)
        if (from in setOf("c", "f", "k") && to in setOf("c", "f", "k")) {
            val c = when (from) { "c" -> value; "f" -> (value - 32) * 5 / 9; "k" -> value - 273.15; else -> return null }
            return when (to) { "c" -> c; "f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> null }
        }
        return null
    }
}
