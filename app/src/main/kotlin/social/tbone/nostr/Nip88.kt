package social.tbone.nostr

/**
 * NIP-88 — Polls.
 *
 * Polls are kind-1068 events whose content is the question, with tags:
 *   ["option", "<id>", "<label>"]   (one per option)
 *   ["polltype", "singlechoice"|"multiplechoice"]
 *   ["endsAt", "<unix>"]            (optional)
 *   ["relay", "<url>"]              (optional relays to also send votes to)
 *
 * Votes are kind-1018 events with:
 *   ["e", "<poll event id>"]
 *   ["response", "<option id>"]     (one per selected option)
 */
object Nip88 {
    const val KIND_POLL = 1068
    const val KIND_POLL_RESPONSE = 1018

    private val OPTION_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    data class PollOption(val id: String, val label: String)

    enum class PollType { SINGLECHOICE, MULTIPLECHOICE }

    /** Generates a random alphanumeric option id matching the spec format. */
    fun generateOptionId(): String =
        (1..9).map { OPTION_ID_CHARS.random() }.joinToString("")

    fun buildPollTags(
        options: List<PollOption>,
        pollType: PollType = PollType.SINGLECHOICE,
        endsAt: Long? = null,
        relayUrls: List<String> = emptyList(),
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (option in options) {
            tags.add(listOf("option", option.id, option.label))
        }
        for (url in relayUrls) {
            tags.add(listOf("relay", url))
        }
        tags.add(listOf("polltype", pollType.name.lowercase()))
        if (endsAt != null) {
            tags.add(listOf("endsAt", endsAt.toString()))
        }
        return tags
    }

    fun buildResponseTags(pollEventId: String, selectedOptionIds: List<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("e", pollEventId))
        for (optionId in selectedOptionIds) {
            tags.add(listOf("response", optionId))
        }
        return tags
    }

    fun parsePollOptions(event: Event): List<PollOption> =
        event.parsedTags
            .filter { it.name == "option" && it.values.size >= 3 }
            .map { PollOption(it.value(1) ?: "", it.value(2) ?: "") }
            .filter { it.id.isNotEmpty() }

    fun parsePollType(event: Event): PollType {
        val value = event.parsedTags
            .firstOrNull { it.name == "polltype" }
            ?.value(1)
            ?.lowercase()
        return if (value == "multiplechoice") PollType.MULTIPLECHOICE else PollType.SINGLECHOICE
    }

    fun parseEndsAt(event: Event): Long? =
        event.parsedTags
            .firstOrNull { it.name == "endsAt" }
            ?.value(1)
            ?.toLongOrNull()

    /** Extract relay URLs from a poll event's relay tags. */
    fun parsePollRelays(event: Event): List<String> =
        event.parsedTags
            .filter { it.name == "relay" }
            .mapNotNull { it.value(1) }

    fun isPollEnded(event: Event): Boolean {
        val endsAt = parseEndsAt(event) ?: return false
        return System.currentTimeMillis() / 1000 > endsAt
    }

    fun getPollEventId(event: Event): String? =
        event.parsedTags.firstOrNull { it.name == "e" }?.value(1)

    fun getResponseOptionIds(event: Event): List<String> =
        event.parsedTags
            .filter { it.name == "response" }
            .mapNotNull { it.value(1) }
}
