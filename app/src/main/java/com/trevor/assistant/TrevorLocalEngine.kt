package com.trevor.assistant

sealed class TrevorEngineResult {

    data class Answer(
        val text: String
    ) : TrevorEngineResult()

    data class NeedAI(
        val prompt: String
    ) : TrevorEngineResult()

    data class Error(
        val message: String
    ) : TrevorEngineResult()
}

object TrevorLocalEngine {

    fun processCommand(
        command: String
    ): TrevorEngineResult {

        val input = command.trim()

        if (input.isBlank()) {
            return TrevorEngineResult.Error(
                "Please enter a command."
            )
        }

        val lower = input.lowercase()

        // -----------------------------------------
        // OFFLINE CALCULATOR
        // -----------------------------------------

        if (
            lower.startsWith("calculate ") ||
            lower.startsWith("calc ") ||
            lower.startsWith("solve ")
        ) {

            val expression = when {
                lower.startsWith("calculate ") ->
                    input.substring(10).trim()

                lower.startsWith("calc ") ->
                    input.substring(5).trim()

                lower.startsWith("solve ") ->
                    input.substring(6).trim()

                else -> ""
            }

            if (expression.isBlank()) {
                return TrevorEngineResult.Error(
                    "Please provide a calculation."
                )
            }

            val result = OfflineCalculator.calculate(
                expression
            )

            return if (result.isSuccess) {

                val value = result.getOrNull()

                if (value == null) {
                    TrevorEngineResult.Error(
                        "Calculator returned no result."
                    )
                } else {
                    TrevorEngineResult.Answer(
                        OfflineCalculator.formatResult(value)
                    )
                }

            } else {

                TrevorEngineResult.Error(
                    result.exceptionOrNull()?.message
                        ?: "Invalid calculation."
                )
            }
        }

        // -----------------------------------------
        // OFFLINE DICTIONARY
        // -----------------------------------------

        if (
            lower.startsWith("define ") ||
            lower.startsWith("definition of ")
        ) {

            val word = when {

                lower.startsWith("define ") ->
                    input.substring(7).trim()

                lower.startsWith("definition of ") ->
                    input.substring(14).trim()

                else -> ""
            }

            if (word.isBlank()) {
                return TrevorEngineResult.Error(
                    "Please provide a word to define."
                )
            }

            val entry = OfflineDictionary.lookup(word)

            return if (entry != null) {

                TrevorEngineResult.Answer(
                    buildString {
                        append(entry.word)
                        append("\n\n")
                        append(entry.definition)
                        append("\n\n")
                        append("Category: ")
                        append(entry.category)
                    }
                )

            } else {

                TrevorEngineResult.NeedAI(
                    input
                )
            }
        }

        // -----------------------------------------
        // DIRECT OFFLINE DICTIONARY LOOKUP
        // -----------------------------------------

        val directEntry = OfflineDictionary.lookup(input)

        if (directEntry != null) {

            return TrevorEngineResult.Answer(
                buildString {
                    append(directEntry.word)
                    append("\n\n")
                    append(directEntry.definition)
                    append("\n\n")
                    append("Category: ")
                    append(directEntry.category)
                }
            )
        }

        // -----------------------------------------
        // EVERYTHING ELSE → AI
        // -----------------------------------------

        return TrevorEngineResult.NeedAI(
            input
        )
    }
}
