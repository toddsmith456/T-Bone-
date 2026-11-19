package social.tbone.ui.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.youniversal.theme.YouniversalSnackbarHost
import kotlinx.coroutines.delay
import social.tbone.nostr.DailyTokens
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.tokens.LeaderboardScope
import social.tbone.tokens.OfferStatus
import social.tbone.tokens.SteakLeaderboardEntry
import social.tbone.ui.components.SteakIcon
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.ui.theme.feedPageBackground

@Composable
fun SteakLeaderboardScreen(
    onBack: () -> Unit,
    onProfileClick: (pubkey: String) -> Unit,
    viewModel: SteakLeaderboardViewModel = hiltViewModel(),
) {
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val mySummary by viewModel.mySummary.collectAsStateWithLifecycle()
    val cooldownUntilByRecipient by viewModel.cooldownUntilByRecipient.collectAsStateWithLifecycle()
    val incomingOffers by viewModel.incomingOffers.collectAsStateWithLifecycle()
    val unlistedClaimCount by viewModel.unlistedClaimCount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // The leaderboard is a steak surface: hold the live subscription while it is
    // composed. Keyed on the stable view model, so onDispose always releases.
    androidx.compose.runtime.DisposableEffect(viewModel) {
        viewModel.onSurfaceVisible()
        androidx.compose.runtime.onDispose { viewModel.onSurfaceHidden() }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val nowEpochSec by produceState(
        initialValue = System.currentTimeMillis() / 1000L,
        key1 = cooldownUntilByRecipient.size,
        key2 = incomingOffers.size,
    ) {
        while (true) {
            value = System.currentTimeMillis() / 1000L
            delay(30_000L)
        }
    }

    val claimableOffers = remember(incomingOffers, nowEpochSec) {
        incomingOffers.filter {
            val st = it.statusAt(nowEpochSec)
            st == OfferStatus.CLAIMABLE || st == OfferStatus.CLAIMING
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(feedPageBackground()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(feedPageBackground())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Spacer(Modifier.width(10.dp))
                SteakIcon(size = 20.dp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "steak leaderboard",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BonyColors.Accent,
                        strokeWidth = 1.5.dp,
                    )
                } else {
                    Text(
                        text = "↻",
                        style = BonyType.body.copy(color = BonyColors.Accent),
                        modifier = Modifier
                            .clickable(onClick = viewModel::refresh)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            // ── Scope Tabs: FOLLOWS (default) | GLOBAL ────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface),
            ) {
                LeaderboardScope.entries.forEach { tab ->
                    val selected = tab == scope
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.selectScope(tab) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = BonyType.tag.copy(
                                color = if (selected) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                // ── Own Status Card ───────────────────────────────────────────
                item(key = "own_status") {
                    val myCount = mySummary?.count ?: 0
                    val sentLast24h = cooldownUntilByRecipient.values.count { it > nowEpochSec }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .appBorder(1.dp, BonyColors.Rule)
                            .background(BonyColors.Surface)
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SteakIcon(size = 28.dp, contentDescription = "Your steaks")
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Your shelf: $myCount ${if (myCount == 1) "steak" else "steaks"}",
                                    style = BonyType.body.copy(color = BonyColors.Text),
                                )
                                Text(
                                    text = if (sentLast24h > 0) {
                                        "Sent to $sentLast24h ${if (sentLast24h == 1) "person" else "people"} in the last 24h · visit any profile to send more"
                                    } else {
                                        "Visit any profile to send a steak · 1 per person every 24h"
                                    },
                                    style = BonyType.caption.copy(color = BonyColors.Accent),
                                )
                            }
                        }

                        if (unlistedClaimCount > 0) {
                            Spacer(Modifier.height(10.dp))
                            BonyButton(
                                label = "SYNC $unlistedClaimCount CLAIMED STEAK${if (unlistedClaimCount == 1) "" else "S"} TO PROFILE",
                                primary = false,
                                onClick = viewModel::syncSteakShelf,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (claimableOffers.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "WAITING FOR YOU TO ACCEPT (24H WINDOW)",
                                style = BonyType.caption.copy(color = BonyColors.Accent),
                            )
                            Spacer(Modifier.height(6.dp))
                            claimableOffers.forEach { offerItem ->
                                val senderProfile = profiles[offerItem.offer.senderPubkey]
                                val senderName = senderProfile?.bestName
                                    ?: offerItem.offer.senderPubkey.shortLabel()
                                val left = offerItem.remainingSecondsAt(nowEpochSec)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UserAvatar(
                                        pubkeyHex = offerItem.offer.senderPubkey,
                                        profile = senderProfile,
                                        size = 28.dp,
                                        modifier = Modifier.clickable {
                                            onProfileClick(offerItem.offer.senderPubkey)
                                        },
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = senderName,
                                            style = BonyType.bodyDim.copy(color = BonyColors.Text),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${DailyTokens.formatRemainingDuration(left)} left to accept",
                                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                                        )
                                    }
                                    if (offerItem.claiming) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = BonyColors.Accent,
                                            strokeWidth = 1.5.dp,
                                        )
                                    } else {
                                        Text(
                                            text = "ACCEPT",
                                            style = BonyType.tag.copy(color = BonyColors.Bg),
                                            modifier = Modifier
                                                .background(BonyColors.Accent)
                                                .clickable { viewModel.claimSteak(offerItem.offer.event) }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Ranked Rows ───────────────────────────────────────────────
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SteakIcon(size = 44.dp, dimmed = isLoading, contentDescription = null)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = when {
                                    isLoading -> "verifying steak ledgers across relays…"
                                    scope == LeaderboardScope.FOLLOWS ->
                                        "no verified steaks among people you follow yet"
                                    else ->
                                        "no verified steaks found on your connected relays yet"
                                },
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                            )
                            if (!isLoading) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "open someone's profile to send your daily steak!",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                )
                            }
                        }
                    }
                } else {
                    items(entries, key = { it.pubkey }) { entry ->
                        LeaderboardRow(
                            entry = entry,
                            profile = profiles[entry.pubkey],
                            isMe = entry.pubkey == activePubkey,
                            onClick = { onProfileClick(entry.pubkey) },
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                    }
                }

                // ── Verification Footer ───────────────────────────────────────
                item(key = "footer") {
                    Text(
                        text = "All counts verified cryptographically on-device (NIP-XX · 16-bit PoW · 24h claim window · 1 per person / 24h).",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }
        }

        YouniversalSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LeaderboardRow(
    entry: SteakLeaderboardEntry,
    profile: ProfileContent?,
    isMe: Boolean,
    onClick: () -> Unit,
) {
    val rankColor = when (entry.rank) {
        1 -> Color(0xFFD4A017) // Gold
        2 -> Color(0xFF9EA7B3) // Silver
        3 -> Color(0xFFC07A4B) // Bronze
        else -> BonyColors.TextMute
    }
    val displayName = profile?.bestName ?: entry.pubkey.shortLabel()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isMe) BonyColors.SurfaceAlt else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#${entry.rank}",
            style = BonyType.body.copy(color = rankColor),
            modifier = Modifier.width(38.dp),
        )
        UserAvatar(
            pubkeyHex = entry.pubkey,
            profile = profile,
            size = 36.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isMe) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "YOU",
                        style = BonyType.caption.copy(color = BonyColors.Accent),
                    )
                }
            }
            profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                Text(
                    text = nip05,
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .appBorder(1.dp, BonyColors.Rule)
                .background(BonyColors.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            SteakIcon(size = 16.dp, contentDescription = null)
            Text(
                text = entry.verifiedCount.toString(),
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        }
    }
}

private fun String.shortLabel(): String {
    val words = Phrase.wordsFor(this, 3)
    return if (words.all { it == "·····" }) {
        val npub = Nip19.hexToNpub(this)
        "${npub.take(10)}…"
    } else {
        words.joinToString("·")
    }
}
