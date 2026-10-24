package social.tbone.profile

import social.tbone.nostr.ProfileContent
import java.text.Normalizer
import java.util.Locale

/** Unicode-tolerant local and relay query helpers for kind-0 profile search. */
object ProfileSearch {
    private data class Alphabet(
        val name: String,
        val upper: IntArray? = null,
        val lower: IntArray? = null,
        val digits: IntArray? = null,
    ) {
        fun transform(input: String): String = buildString {
            input.forEach { char ->
                val codePoint = when {
                    char in 'A'..'Z' -> upper?.get(char - 'A')
                    char in 'a'..'z' -> lower?.get(char - 'a')
                    char in '0'..'9' -> digits?.get(char - '0')
                    else -> null
                }
                if (codePoint == null || codePoint < 0) append(char)
                else appendCodePoint(codePoint)
            }
        }
    }

    private fun contiguous(start: Int): IntArray = IntArray(26) { start + it }
    private fun digits(start: Int): IntArray = IntArray(10) { start + it }

    // Mathematical alphanumeric symbols have several legacy BMP letters in
    // the Script, Fraktur and Double-Struck alphabets. Those codepoints are
    // included explicitly so relay-side search can find the usual copy/paste
    // name styles rather than only the contiguous parts of the Unicode blocks.
    private val alphabets = listOf(
        Alphabet("math-bold", contiguous(0x1D400), contiguous(0x1D41A), digits(0x1D7CE)),
        Alphabet(
            "math-italic", contiguous(0x1D434), contiguous(0x1D44E).also { it['h' - 'a'] = 0x210E },
        ),
        Alphabet("math-bold-italic", contiguous(0x1D468), contiguous(0x1D482)),
        Alphabet(
            "math-script",
            intArrayOf(
                0x1D49C, 0x212C, 0x1D49E, 0x1D49F, 0x2130, 0x2131, 0x1D4A2, 0x210B,
                0x2110, 0x1D4A5, 0x1D4A6, 0x2112, 0x2133, 0x1D4A9, 0x1D4AA, 0x1D4AB,
                0x1D4AC, 0x211B, 0x1D4AE, 0x1D4AF, 0x1D4B0, 0x1D4B1, 0x1D4B2, 0x1D4B3,
                0x1D4B4, 0x1D4B5,
            ),
            intArrayOf(
                0x1D4B6, 0x1D4B7, 0x1D4B8, 0x1D4B9, 0x212F, 0x1D4BB, 0x210A, 0x1D4BD,
                0x1D4BE, 0x1D4BF, 0x1D4C0, 0x1D4C1, 0x1D4C2, 0x1D4C3, 0x2134, 0x1D4C5,
                0x1D4C6, 0x1D4C7, 0x1D4C8, 0x1D4C9, 0x1D4CA, 0x1D4CB, 0x1D4CC, 0x1D4CD,
                0x1D4CE, 0x1D4CF,
            ),
        ),
        Alphabet("math-bold-script", contiguous(0x1D4D0), contiguous(0x1D4EA)),
        Alphabet(
            "math-fraktur",
            intArrayOf(
                0x1D504, 0x1D505, 0x212D, 0x1D507, 0x1D508, 0x1D509, 0x1D50A, 0x210C,
                0x2111, 0x1D50D, 0x1D50E, 0x1D50F, 0x1D510, 0x1D511, 0x1D512, 0x1D513,
                0x1D514, 0x211C, 0x1D516, 0x1D517, 0x1D518, 0x1D519, 0x1D51A, 0x1D51B,
                0x1D51C, 0x2128,
            ),
            contiguous(0x1D51E),
        ),
        Alphabet(
            "math-double-struck",
            intArrayOf(
                0x1D538, 0x1D539, 0x2102, 0x1D53B, 0x1D53C, 0x1D53D, 0x1D53E, 0x210D,
                0x1D540, 0x1D541, 0x1D542, 0x1D543, 0x1D544, 0x2115, 0x1D546, 0x2119,
                0x211A, 0x211D, 0x1D54A, 0x1D54B, 0x1D54C, 0x1D54D, 0x1D54E, 0x1D54F,
                0x1D550, 0x2124,
            ),
            contiguous(0x1D552),
            digits(0x1D7D8),
        ),
        Alphabet("math-bold-fraktur", contiguous(0x1D56C), contiguous(0x1D586)),
        Alphabet("sans-serif", contiguous(0x1D5A0), contiguous(0x1D5BA), digits(0x1D7E2)),
        Alphabet("sans-serif-bold", contiguous(0x1D5D4), contiguous(0x1D5EE), digits(0x1D7EC)),
        Alphabet("sans-serif-italic", contiguous(0x1D608), contiguous(0x1D622)),
        Alphabet("sans-serif-bold-italic", contiguous(0x1D63C), contiguous(0x1D656)),
        Alphabet("monospace", contiguous(0x1D670), contiguous(0x1D68A), digits(0x1D7F6)),
        Alphabet("fullwidth", contiguous(0xFF21), contiguous(0xFF41), digits(0xFF10)),
        Alphabet("circled", contiguous(0x24B6), contiguous(0x24D0)),
    )

    /** NFKD removes compatibility styling and accents: 𝓐lice / Álice -> alice. */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .filterNot { char ->
            when (Character.getType(char)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> true
                else -> false
            }
        }
        .trim()

    /** Search names, display names, NIP-05 identifiers, and profile text. */
    fun matches(query: String, profile: ProfileContent): Boolean {
        val needle = normalize(query)
        if (needle.isBlank()) return false
        return listOfNotNull(
            profile.displayName,
            profile.name,
            profile.nip05,
            profile.about,
            profile.website,
        ).any { normalize(it).contains(needle) }
    }

    /**
     * NIP-50 is relay-dependent and often does not compatibility-normalize
     * Unicode. Ask for the plain query plus common mathematical/fullwidth
     * styles; local matching still normalizes every returned profile.
     */
    fun relayQueryVariants(query: String): List<String> {
        val trimmed = query.trim()
        val normalized = normalize(trimmed)
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length) > 48) {
            return listOf(trimmed).filter { it.isNotBlank() }
        }

        val lower = normalized.lowercase(Locale.ROOT)
        val upper = normalized.uppercase(Locale.ROOT)
        val title = titleCase(lower)
        val variants = linkedSetOf<String>()
        listOf(trimmed, normalized, lower, upper, title).forEach { value ->
            if (value.isNotBlank()) variants += value
        }
        // Keep the list bounded: 14 styles x 3 case patterns plus the plain
        // forms are enough to cover common copy/paste display-name fonts.
        alphabets.forEach { alphabet ->
            listOf(lower, upper, title).forEach { source ->
                val styled = alphabet.transform(source)
                if (styled != source) variants += styled
            }
        }
        return variants.toList().take(MAX_RELAY_VARIANTS)
    }

    private fun titleCase(value: String): String {
        var startWord = true
        return buildString(value.length) {
            value.forEach { char ->
                if (char.isLetterOrDigit()) {
                    append(if (startWord) char.uppercaseChar() else char)
                    startWord = false
                } else {
                    append(char)
                    startWord = char.isWhitespace() || char in "-_./@"
                }
            }
        }
    }

    private const val MAX_RELAY_VARIANTS = 48
}
