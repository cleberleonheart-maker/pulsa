package com.pulsa.player.ime

import java.util.Locale

enum class ImeLayer {
    LETTERS,
    NUMBERS,
    SYMBOLS,
}

enum class ImeShiftState {
    OFF,
    ONE_SHOT,
    LOCKED,
}

enum class ImeAction {
    BACKSPACE,
    SHIFT,
    SPACE,
    ENTER,
    LETTERS,
    NUMBERS,
    SYMBOLS,
    GLOBE,
    EMOJI,
    VOICE,
}

data class ImeKey(
    val action: ImeAction? = null,
    val character: String? = null,
    val label: String? = null,
    val weight: Float = 1f,
)

object ImeKeyboardLayout {
    fun rows(
        language: String,
        layer: ImeLayer,
        email: Boolean = false,
        url: Boolean = false,
    ): List<List<ImeKey>> = when (layer) {
        ImeLayer.LETTERS -> letterRows(language, email, url)
        ImeLayer.NUMBERS -> numberRows()
        ImeLayer.SYMBOLS -> symbolRows()
    }

    fun accentAlternatives(base: String, uppercase: Boolean, language: String): List<String> {
        val portuguese = language.startsWith("pt", ignoreCase = true)
        val spanish = language.startsWith("es", ignoreCase = true)
        val alternatives = when (base.lowercase(Locale.ROOT)) {
            "a" -> if (portuguese) listOf("á", "à", "â", "ã", "ä") else if (spanish) listOf("á") else emptyList()
            "e" -> if (portuguese) listOf("é", "ê", "è", "ë") else if (spanish) listOf("é") else emptyList()
            "i" -> if (portuguese) listOf("í", "î", "ì", "ï") else if (spanish) listOf("í") else emptyList()
            "o" -> if (portuguese) listOf("ó", "ô", "õ", "ò", "ö") else if (spanish) listOf("ó") else emptyList()
            "u" -> if (portuguese) listOf("ú", "û", "ù", "ü") else if (spanish) listOf("ú", "ü") else emptyList()
            "c" -> if (portuguese) listOf("ç", "ć") else emptyList()
            "n" -> if (portuguese) listOf("ñ", "ń") else if (spanish) listOf("ñ") else emptyList()
            else -> emptyList()
        }
        return alternatives.map { if (uppercase) it.uppercase(Locale.ROOT) else it }
    }

    private fun letterRows(language: String, email: Boolean, url: Boolean): List<List<ImeKey>> {
        val secondRow = when {
            language.startsWith("pt", ignoreCase = true) -> listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ç")
            language.startsWith("es", ignoreCase = true) -> listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ")
            else -> listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        }
        val contextual = when {
            email -> listOf(ImeKey(character = "@"))
            url -> listOf(ImeKey(character = "/"), ImeKey(character = ".com"))
            else -> emptyList()
        }
        val bottom = buildList {
            add(ImeKey(ImeAction.NUMBERS, label = "123", weight = 1.1f))
            add(ImeKey(ImeAction.GLOBE, weight = 0.9f))
            add(ImeKey(ImeAction.EMOJI, weight = 0.9f))
            add(ImeKey(ImeAction.VOICE, weight = 0.9f))
            add(ImeKey(ImeAction.SPACE, label = "space", weight = 4f))
            addAll(contextual)
            add(ImeKey(ImeAction.ENTER, weight = 1.35f))
        }
        return listOf(
            letters("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            letters(*secondRow.toTypedArray()),
            listOf(
                ImeKey(ImeAction.SHIFT, weight = 1.45f),
                ImeKey(character = "z"),
                ImeKey(character = "x"),
                ImeKey(character = "c"),
                ImeKey(character = "v"),
                ImeKey(character = "b"),
                ImeKey(character = "n"),
                ImeKey(character = "m"),
                ImeKey(ImeAction.BACKSPACE, weight = 1.45f),
            ),
            bottom,
        )
    }

    private fun numberRows(): List<List<ImeKey>> = listOf(
        letters("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        letters("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
        listOf(
            ImeKey(ImeAction.SYMBOLS, label = "#+=", weight = 1.35f),
            ImeKey(character = "."),
            ImeKey(character = ","),
            ImeKey(character = "?"),
            ImeKey(character = "!"),
            ImeKey(character = "'"),
            ImeKey(ImeAction.BACKSPACE, weight = 1.55f),
        ),
        listOf(
            ImeKey(ImeAction.LETTERS, label = "ABC", weight = 1.25f),
            ImeKey(ImeAction.GLOBE, weight = 0.9f),
            ImeKey(ImeAction.EMOJI, weight = 0.9f),
            ImeKey(ImeAction.VOICE, weight = 0.9f),
            ImeKey(ImeAction.SPACE, label = "space", weight = 4f),
            ImeKey(ImeAction.ENTER, weight = 1.35f),
        ),
    )

    private fun symbolRows(): List<List<ImeKey>> = listOf(
        letters("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
        letters("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
        listOf(
            ImeKey(ImeAction.NUMBERS, label = "123", weight = 1.35f),
            ImeKey(character = "."),
            ImeKey(character = ","),
            ImeKey(character = "?"),
            ImeKey(character = "!"),
            ImeKey(character = "'"),
            ImeKey(ImeAction.BACKSPACE, weight = 1.55f),
        ),
        listOf(
            ImeKey(ImeAction.LETTERS, label = "ABC", weight = 1.25f),
            ImeKey(ImeAction.GLOBE, weight = 0.9f),
            ImeKey(ImeAction.EMOJI, weight = 0.9f),
            ImeKey(ImeAction.VOICE, weight = 0.9f),
            ImeKey(ImeAction.SPACE, label = "space", weight = 4f),
            ImeKey(ImeAction.ENTER, weight = 1.35f),
        ),
    )

    private fun letters(vararg values: String): List<ImeKey> =
        values.map { ImeKey(character = it) }
}
