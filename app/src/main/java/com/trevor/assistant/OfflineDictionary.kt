package com.trevor.assistant

data class DictionaryEntry(
    val word: String,
    val definition: String,
    val category: String
)

object OfflineDictionary {

    private val entries = listOf(

        DictionaryEntry(
            word = "algorithm",
            definition = "A step-by-step procedure used to solve a problem or perform a task.",
            category = "Computer Science"
        ),

        DictionaryEntry(
            word = "asteroid",
            definition = "A rocky or metallic object that orbits the Sun, mostly found in the asteroid belt between Mars and Jupiter.",
            category = "Astronomy"
        ),

        DictionaryEntry(
            word = "atmosphere",
            definition = "The layer of gases surrounding a planet or other celestial body.",
            category = "Science"
        ),

        DictionaryEntry(
            word = "aerospace",
            definition = "The field involving aircraft, spacecraft, and the technologies used to design and operate them.",
            category = "Engineering"
        ),

        DictionaryEntry(
            word = "calculation",
            definition = "The process of using mathematics to determine a result.",
            category = "Mathematics"
        ),

        DictionaryEntry(
            word = "computer",
            definition = "An electronic machine that processes data according to programmed instructions.",
            category = "Technology"
        ),

        DictionaryEntry(
            word = "database",
            definition = "An organized collection of data that can be stored, searched, and managed electronically.",
            category = "Computer Science"
        ),

        DictionaryEntry(
            word = "encryption",
            definition = "The process of converting information into a protected form so that unauthorized people cannot easily read it.",
            category = "Computer Science"
        ),

        DictionaryEntry(
            word = "energy",
            definition = "The capacity of a system to do work or cause physical change.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "force",
            definition = "A push or pull that can change the motion of an object.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "galaxy",
            definition = "A huge system containing stars, gas, dust, and other objects held together by gravity.",
            category = "Astronomy"
        ),

        DictionaryEntry(
            word = "gravity",
            definition = "The attractive interaction between objects that have mass.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "hypothesis",
            definition = "A proposed explanation that can be tested through observation or experimentation.",
            category = "Science"
        ),

        DictionaryEntry(
            word = "inertia",
            definition = "The tendency of an object to resist changes in its state of motion.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "machine",
            definition = "A device that uses mechanical or electrical processes to perform a task.",
            category = "Engineering"
        ),

        DictionaryEntry(
            word = "matter",
            definition = "Physical substance that has mass and occupies space.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "momentum",
            definition = "A quantity of motion equal to an object's mass multiplied by its velocity.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "orbit",
            definition = "The curved path followed by an object as it moves around another object under gravity or another force.",
            category = "Astronomy"
        ),

        DictionaryEntry(
            word = "planet",
            definition = "A large astronomical body that orbits a star and is massive enough to become approximately round under its own gravity.",
            category = "Astronomy"
        ),

        DictionaryEntry(
            word = "program",
            definition = "A set of instructions written for a computer to perform a particular task.",
            category = "Computer Science"
        ),

        DictionaryEntry(
            word = "rocket",
            definition = "A vehicle or engine that produces thrust by expelling mass, allowing it to operate even without atmospheric oxygen.",
            category = "Aerospace"
        ),

        DictionaryEntry(
            word = "satellite",
            definition = "An object that orbits a planet, moon, or other celestial body. It can be natural or artificial.",
            category = "Astronomy"
        ),

        DictionaryEntry(
            word = "velocity",
            definition = "The rate at which an object's position changes, including its direction of motion.",
            category = "Physics"
        ),

        DictionaryEntry(
            word = "variable",
            definition = "A quantity or value that can change or represent different values in a program or mathematical expression.",
            category = "Mathematics"
        ),

        DictionaryEntry(
            word = "virtual",
            definition = "Something represented or operating digitally rather than existing in the same physical form.",
            category = "Technology"
        )
    )

    fun lookup(word: String): DictionaryEntry? {
        val normalized = word
            .trim()
            .lowercase()

        if (normalized.isBlank()) {
            return null
        }

        return entries.firstOrNull {
            it.word.lowercase() == normalized
        }
    }

    fun search(query: String): List<DictionaryEntry> {
        val normalized = query
            .trim()
            .lowercase()

        if (normalized.isBlank()) {
            return emptyList()
        }

        return entries.filter {
            it.word.lowercase().contains(normalized) ||
                    it.definition.lowercase().contains(normalized) ||
                    it.category.lowercase().contains(normalized)
        }
    }

    fun allEntries(): List<DictionaryEntry> {
        return entries
    }
}
