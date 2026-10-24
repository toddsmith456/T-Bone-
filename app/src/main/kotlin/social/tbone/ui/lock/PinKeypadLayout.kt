package social.tbone.ui.lock

import kotlin.random.Random

/** Digit ordering for the shared PIN pad. The delete/blank keys stay in fixed positions. */
object PinKeypadLayout {
    val DEFAULT_DIGITS: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    fun digits(scramble: Boolean, random: Random = Random.Default): List<String> =
        if (scramble) DEFAULT_DIGITS.shuffled(random) else DEFAULT_DIGITS

    fun rows(digits: List<String>): List<List<String>> {
        require(digits.size == 10 && digits.toSet() == DEFAULT_DIGITS.toSet()) {
            "A PIN keypad layout must contain each decimal digit exactly once"
        }
        return listOf(
            digits.subList(0, 3),
            digits.subList(3, 6),
            digits.subList(6, 9),
            listOf("←", digits[9], ""),
        )
    }
}
