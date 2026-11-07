package social.tbone.notifications

import android.content.Intent
import social.tbone.nostr.Nip19

/** Parse only supported destinations before placing them in the pending queue. */
internal fun dispatchNavigationIntent(intent: Intent?, handler: DeepLinkHandler): Boolean {
    val eventId = intent?.getStringExtra(EXTRA_EVENT_ID)
    if (eventId != null && eventId.length == 64 && eventId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        handler.navigateToThread(eventId.lowercase())
        return true
    }
    val data = intent?.data ?: return false
    if (!data.scheme.equals("nostr", ignoreCase = true)) return false
    val entity = data.schemeSpecificPart ?: return false
    when {
        entity.startsWith("note1") || entity.startsWith("nevent1") -> {
            val id = Nip19.nostrUriToEventId(entity) ?: return false
            handler.navigateToThread(id)
        }
        entity.startsWith("npub1") -> {
            val id = Nip19.npubToHex(entity) ?: return false
            handler.navigateToProfile(id)
        }
        entity.startsWith("nprofile1") -> {
            val id = Nip19.nprofileToHex(entity) ?: return false
            handler.navigateToProfile(id)
        }
        else -> return false
    }
    return true
}
