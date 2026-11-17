package social.tbone.ui.profile

import social.tbone.ui.theme.feedPageBackground
import social.tbone.ui.theme.appBorder
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import dev.youniversal.theme.YouniversalTheme
import social.tbone.ui.theme.appBackground
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import dev.youniversal.theme.YouniversalSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.tbone.nostr.DailyTokens
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.tokens.IncomingSteakOffer
import social.tbone.tokens.OfferStatus
import social.tbone.tokens.ProfileSteakSummary
import social.tbone.ui.components.SteakIcon
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.feed.ObservedNoteCard
import social.tbone.ui.media.LocalVideoQuality
import social.tbone.ui.media.VideoViewerDialog
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

@Composable
private fun EmptyTabLabel(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = BonyType.meta.copy(color = BonyColors.TextMute))
    }
}

private val BANNER_HEIGHT = 80.dp
private val AVATAR_SIZE = 56.dp

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onVerifyClick: (pubkey: String) -> Unit = {},
    onThreadClick: (eventId: String) -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (tag: String) -> Unit = {},
    onReplyClick: (Event) -> Unit = {},
    onQuoteClick: (Event) -> Unit = {},
    onEditProfile: () -> Unit = {},
    onSteakLeaderboardClick: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val replyNotes by viewModel.replyNotes.collectAsStateWithLifecycle()
    val pinnedNotes by viewModel.pinnedNotes.collectAsStateWithLifecycle()
    val allNotes by viewModel.allNotes.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val selectedTab by viewModel.tab.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActiveUserProfile by viewModel.isActiveUserProfile.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val isFollowLoading by viewModel.isFollowLoading.collectAsStateWithLifecycle()
    val isBlocked by viewModel.isBlocked.collectAsStateWithLifecycle()
    val isBlockLoading by viewModel.isBlockLoading.collectAsStateWithLifecycle()
    val followIsLocal by viewModel.followIsLocal.collectAsStateWithLifecycle()
    val blockIsLocal by viewModel.blockIsLocal.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val steakSummary by viewModel.steakSummary.collectAsStateWithLifecycle()
    val steakCooldownUntil by viewModel.steakCooldownUntil.collectAsStateWithLifecycle()
    val isSendingSteak by viewModel.isSendingSteak.collectAsStateWithLifecycle()
    val incomingSteaks by viewModel.incomingSteaks.collectAsStateWithLifecycle()
    val unlistedSteakClaims by viewModel.unlistedSteakClaims.collectAsStateWithLifecycle()
    val allProfiles by viewModel.profiles.collectAsStateWithLifecycle()
    val pubkey = viewModel.pubkey

    // Ticks every 30s while a cooldown or pending steak timer is active.
    val nowEpochSec by produceState(
        initialValue = System.currentTimeMillis() / 1000L,
        key1 = steakCooldownUntil,
        key2 = incomingSteaks.size,
    ) {
        while (true) {
            value = System.currentTimeMillis() / 1000L
            delay(30_000L)
        }
    }
    var showSteakSheet by remember { mutableStateOf(false) }
    var showSendSteakConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Which cards the current tab shows. NOTES is top-level notes, ALL adds
    // replies and reposts, REPLIES is replies only, and MEDIA is the gallery.
    // Pinned notes have no tab of their own any more: they are lifted to the top
    // of NOTES and ALL so they are seen without hunting for a separate tab.
    val tabNotes = when (selectedTab) {
        ProfileTab.NOTES -> notes
        ProfileTab.ALL -> allNotes
        ProfileTab.REPLIES -> replyNotes
        ProfileTab.MEDIA -> emptyList()
    }
    val visibleNotes = when (selectedTab) {
        ProfileTab.NOTES, ProfileTab.ALL -> viewModel.pinnedFirst(tabNotes, pinnedNotes)
        else -> tabNotes
    }
    val pinnedIds = remember(pinnedNotes) { pinnedNotes.map { it.id }.toSet() }

    // Index of the tapped gallery image (null = viewer closed).
    var galleryViewerIndex by remember { mutableStateOf<Int?>(null) }
    var videoViewerUrl by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val phrase6 = remember(pubkey) { Phrase.wordsFor(pubkey, 6) }
    val npub = remember(pubkey) { Nip19.hexToNpub(pubkey) }

    val quoteState = remember(viewModel) {
        social.tbone.ui.feed.QuoteState(
            onRetry = viewModel::retryQuote,
            unresolvedFlow = viewModel.unresolvedQuoteIds,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(feedPageBackground()),
    ) {
        CompositionLocalProvider(
            social.tbone.ui.feed.LocalQuoteState provides quoteState,
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                // ── Top bar ───────────────────────────────────────────────────
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
                        modifier = Modifier.clickable { onBack() },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "profile",
                        style = BonyType.body.copy(color = BonyColors.Text),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "↗",
                        style = BonyType.body.copy(color = BonyColors.TextMute),
                        modifier = Modifier.clickable {
                            val shareText = buildString {
                                profile?.bestName?.let { append("$it\n") }
                                append("nostr:$npub")
                            }
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                },
                                "Share profile",
                            ))
                        },
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                // ── Banner + Avatar ───────────────────────────────────────────
                // Youniversal: inset + rounded like the feed's cards so the
                // header speaks the same visual language as posts.
                val youniversalHeader = YouniversalTheme.enabled
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + AVATAR_SIZE / 2)
                        .then(if (youniversalHeader) Modifier.padding(horizontal = 8.dp) else Modifier),
                ) {
                    // Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .appBackground(BonyColors.SurfaceAlt, MaterialTheme.shapes.medium),
                    ) {
                        val bannerUrl = profile?.banner?.takeIf { it.isNotBlank() }
                        val bannerContext = LocalContext.current
                        val bannerFilter = social.tbone.settings.LocalContentFilter.current
                        val bannerAiKey = bannerFilter.aiFilterConfig.takeIf { bannerFilter.aiHasInstalledModel }
                        if (bannerUrl != null) {
                            val bannerRequest = remember(bannerUrl, pubkey, bannerAiKey) {
                                coil.request.ImageRequest.Builder(bannerContext)
                                    .data(bannerUrl)
                                    .diskCacheKey(social.tbone.cache.CacheKeyPolicy.mediaDiskKey(bannerUrl))
                                    .memoryCacheKey(social.tbone.cache.CacheKeyPolicy.mediaMemoryKey(bannerUrl, social.tbone.cache.CacheKeyPolicy.VARIANT_BANNER))
                                    .setParameter(social.tbone.ai.AiCoilParams.SURFACE, social.tbone.ai.ScanSurface.BANNER.name, memoryCacheKey = null)
                                    .setParameter(social.tbone.ai.AiCoilParams.AUTHOR_PUBKEY, pubkey, memoryCacheKey = null)
                                    .build()
                            }
                            coil.compose.SubcomposeAsyncImage(
                                model = bannerRequest,
                                contentDescription = "Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                error = {
                                    // Fall back cleanly to diagonal stripes if offline AI blocks an explicit banner.
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val stripeColor = BonyColors.Rule.copy(alpha = 0.8f)
                                        val pitch = 16.dp.toPx()
                                        clipRect {
                                            var off = -size.height
                                            while (off < size.width + size.height) {
                                                drawLine(
                                                    color = stripeColor,
                                                    start = Offset(off, 0f),
                                                    end = Offset(off + size.height, size.height),
                                                    strokeWidth = 8.dp.toPx(),
                                                )
                                                off += pitch
                                            }
                                        }
                                    }
                                },
                            )
                        } else {
                            // Diagonal stripe placeholder
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val stripeColor = BonyColors.Rule.copy(alpha = 0.8f)
                                val pitch = 16.dp.toPx()
                                clipRect {
                                    var off = -size.height
                                    while (off < size.width + size.height) {
                                        drawLine(
                                            color = stripeColor,
                                            start = Offset(off, 0f),
                                            end = Offset(off + size.height, size.height),
                                            strokeWidth = 8.dp.toPx(),
                                        )
                                        off += pitch
                                    }
                                }
                            }
                        }
                    }

                    // Avatar — overlapping banner
                    UserAvatar(
                        pubkeyHex = pubkey,
                        profile = profile,
                        size = AVATAR_SIZE,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = 14.dp),
                    )
                }

                // ── Profile info ──────────────────────────────────────────────
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val name = profile?.bestName
                    if (name != null) {
                        Text(name, style = BonyType.title.copy(color = BonyColors.Text))
                        Spacer(Modifier.height(2.dp))
                    }

                    if (profile?.nip05 != null) {
                        Text(
                            text = "✓ ${profile?.nip05}",
                            style = BonyType.meta.copy(color = BonyColors.Accent),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    // Fingerprint pill
                    Box(
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.Rule)
                            .background(BonyColors.SurfaceAlt)
                            .clickable { onVerifyClick(pubkey) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Text(
                                text = "FINGERPRINT ↗",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                            Spacer(Modifier.height(4.dp))
                            // Row 1: words 0-2
                            Row {
                                phrase6.take(3).forEachIndexed { i, word ->
                                    if (i > 0) {
                                        Text(" · ", style = BonyType.body.copy(color = BonyColors.TextMute))
                                    }
                                    Text(word, style = BonyType.body.copy(color = BonyColors.Text))
                                }
                            }
                            // Row 2: words 3-5
                            Row {
                                phrase6.drop(3).forEachIndexed { i, word ->
                                    if (i > 0) {
                                        Text(" · ", style = BonyType.body.copy(color = BonyColors.TextMute))
                                    }
                                    Text(word, style = BonyType.body.copy(color = BonyColors.Text))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "tap to verify · 66 bits",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )

                    if (!profile?.about.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(
                                text = profile?.about ?: "",
                                style = BonyType.body.copy(color = BonyColors.TextDim),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── Profile Steaks badge chip (NIP-XX) ────────────────────
                    val pendingClaimableCount = remember(incomingSteaks, nowEpochSec, isActiveUserProfile) {
                        if (!isActiveUserProfile) 0
                        else incomingSteaks.count { it.statusAt(nowEpochSec) == OfferStatus.CLAIMABLE }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .appBorder(1.dp, if (pendingClaimableCount > 0) BonyColors.Accent else BonyColors.Rule)
                            .background(BonyColors.SurfaceAlt)
                            .clickable { showSteakSheet = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        SteakIcon(size = 20.dp, contentDescription = "Steaks")
                        Spacer(Modifier.width(8.dp))
                        val steakCount = steakSummary.count
                        Text(
                            text = "$steakCount ${if (steakCount == 1) "steak" else "steaks"}",
                            style = BonyType.body.copy(color = BonyColors.Text),
                        )
                        if (steakSummary.isLoading) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = BonyColors.Accent,
                                strokeWidth = 1.dp,
                            )
                        } else if (pendingClaimableCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "· $pendingClaimableCount to claim",
                                style = BonyType.caption.copy(color = BonyColors.Accent),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "↗",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Own profile: edit picture / banner.
                    if (isActiveUserProfile) {
                        BonyButton(
                            label = "✎ EDIT PROFILE & WALLET",
                            primary = false,
                            onClick = onEditProfile,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (!isActiveUserProfile) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isFollowLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BonyColors.Accent,
                                    strokeWidth = 1.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            BonyButton(
                                label = if (isFollowing) "UNFOLLOW" else "+ FOLLOW",
                                primary = !isFollowing,
                                enabled = !isFollowLoading,
                                onClick = if (isFollowing) viewModel::unfollow else viewModel::follow,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                minHeight = 28.dp,
                                shape = MaterialTheme.shapes.small,
                            )
                            Spacer(Modifier.width(8.dp))
                            BonyButton(
                                label = if (isBlocked) "UNMUTE" else "MUTE",
                                primary = false,
                                enabled = !isBlockLoading,
                                onClick = viewModel::toggleBlock,
                                modifier = Modifier.weight(0.7f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                minHeight = 28.dp,
                                shape = MaterialTheme.shapes.small,
                            )
                            Spacer(Modifier.width(8.dp))
                            BonyButton(
                                label = "↗ SHARE",
                                primary = false,
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$npub"))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                    val others = context.packageManager
                                        .queryIntentActivities(intent, 0)
                                        .filter { it.activityInfo.packageName != context.packageName }
                                    if (others.size == 1) {
                                        context.startActivity(intent.setPackage(others.first().activityInfo.packageName))
                                    } else if (others.size > 1) {
                                        val bonyComponent = ComponentName(
                                            context.packageName,
                                            "${context.packageName}.MainActivity",
                                        )
                                        context.startActivity(
                                            Intent.createChooser(intent, "Open with").apply {
                                                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(bonyComponent))
                                            }
                                        )
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("No DM app found") }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                minHeight = 28.dp,
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                        if (followIsLocal || blockIsLocal) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = when {
                                    followIsLocal && blockIsLocal -> "offline lists on · follow and mute stay on this device only"
                                    followIsLocal -> "offline follow list on · follow stays on this device only"
                                    else -> "offline mute list on · mute stays on this device only"
                                },
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }

                        // ── Send daily Steak button (profile-only entry point) ──
                        Spacer(Modifier.height(8.dp))
                        val cooldownRemaining = (steakCooldownUntil - nowEpochSec).coerceAtLeast(0L)
                        val canSendSteak = !isSendingSteak && cooldownRemaining <= 0L
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .appBorder(1.dp, if (canSendSteak) BonyColors.Accent else BonyColors.Rule)
                                .background(if (canSendSteak) BonyColors.Surface else BonyColors.SurfaceAlt)
                                .then(
                                    if (canSendSteak) Modifier.clickable { showSendSteakConfirm = true }
                                    else Modifier,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (isSendingSteak) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BonyColors.Accent,
                                    strokeWidth = 1.5.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "SIZZLING STEAK…",
                                    style = BonyType.tag.copy(color = BonyColors.Accent),
                                )
                            } else {
                                SteakIcon(
                                    size = 18.dp,
                                    dimmed = !canSendSteak,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (cooldownRemaining > 0L) {
                                        "NEXT STEAK IN ${DailyTokens.formatRemainingDuration(cooldownRemaining).uppercase()}"
                                    } else {
                                        "SEND STEAK"
                                    },
                                    style = BonyType.tag.copy(
                                        color = if (canSendSteak) BonyColors.Accent else BonyColors.TextMute,
                                    ),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                // ── Note tab header ───────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BonyColors.Surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    ProfileTab.entries.forEachIndexed { index, tab ->
                        if (index > 0) Spacer(Modifier.width(20.dp))
                        val isSelected = tab == selectedTab
                        Text(
                            text = tab.label,
                            style = BonyType.tag.copy(
                                color = if (isSelected) BonyColors.Accent else BonyColors.TextMute,
                            ),
                            modifier = Modifier
                                .clickable { viewModel.selectTab(tab) }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            }

            if (isLoading && visibleNotes.isEmpty() && mediaItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                    }
                }
            }

            // ── Media tab: five-wide gallery ──────────────────────────────────
            if (selectedTab == ProfileTab.MEDIA) {
                if (mediaItems.isEmpty()) {
                    if (!isLoading) {
                        item {
                            EmptyTabLabel("no media yet")
                        }
                    }
                } else {
                    // One lazy item per gallery row (5 pictures wide) so only the
                    // rows on screen are composed while flinging.
                    val rows = mediaItems.chunked(GALLERY_COLUMNS)
                    itemsIndexed(rows, key = { index, _ -> "media_row_$index" }) { rowIndex, row ->
                        ProfileGalleryRow(
                            items = row,
                            baseIndex = rowIndex * GALLERY_COLUMNS,
                            onMediaClick = { index ->
                                val item = mediaItems.getOrNull(index)
                                if (item?.isVideo == true) videoViewerUrl = item.url
                                else galleryViewerIndex = index
                            },
                        )
                    }
                }
            } else {
                if (visibleNotes.isEmpty() && !isLoading) {
                    item {
                        EmptyTabLabel(
                            if (selectedTab == ProfileTab.REPLIES) "no replies yet" else "no notes yet",
                        )
                    }
                }

                items(
                    items = visibleNotes,
                    key = { it.id },
                    contentType = { event ->
                        when (event.kind) {
                            EventKind.REPOST, EventKind.GENERIC_REPOST -> "repost"
                            EventKind.POLL -> "poll"
                            else -> "note"
                        }
                    },
                ) { event ->
                    ObservedNoteCard(
                        event = event,
                        profilesFlow = viewModel.profiles,
                        quotedEventsFlow = viewModel.quotedEvents,
                        reactionsFlow = viewModel.reactions,
                        repliesFlow = viewModel.replies,
                        repliedByMeFlow = viewModel.repliedByMe,
                        pollVoteCountsFlow = viewModel.pollVoteCounts,
                        pollTotalVotesFlow = viewModel.pollTotalVotes,
                        pollMyVotesFlow = viewModel.pollMyVotes,
                        activePubkey = activePubkey,
                        fallbackAuthorProfile = profile.takeIf { event.pubkey == pubkey },
                        isPinned = event.id in pinnedIds,
                        onThreadClick = onThreadClick,
                        onProfileClick = onProfileClick,
                        onHashtagClick = onHashtagClick,
                        onReply = onReplyClick,
                        onBoost = viewModel::boost,
                        onQuote = onQuoteClick,
                        onLike = viewModel::react,
                        onPollVote = viewModel::voteOnPoll,
                    )
                }
            }
        }

        YouniversalSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Full-screen swipeable gallery, opened from a thumbnail.
        galleryViewerIndex?.let { index ->
            ProfileGalleryViewer(
                items = mediaItems,
                startIndex = index,
                onClose = { galleryViewerIndex = null },
                onOpenNote = onThreadClick,
            )
        }
        videoViewerUrl?.let { url ->
            VideoViewerDialog(
                url = url,
                quality = LocalVideoQuality.current,
                onClose = { videoViewerUrl = null },
            )
        }
        if (showSendSteakConfirm) {
            val targetName = profile?.bestName ?: phrase6.take(2).joinToString("·")
            AlertDialog(
                onDismissRequest = { showSendSteakConfirm = false },
                containerColor = BonyColors.Surface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SteakIcon(size = 26.dp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Send today's Steak?",
                            style = BonyType.title.copy(color = BonyColors.Text),
                        )
                    }
                },
                text = {
                    Text(
                        text = "Send a steak to $targetName. They must accept it within 24 hours for it to count on their profile, and your next steak unlocks in 24 hours.",
                        style = BonyType.body.copy(color = BonyColors.TextDim),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSendSteakConfirm = false
                            viewModel.sendSteak()
                        },
                    ) {
                        Text("SEND STEAK", style = BonyType.tag.copy(color = BonyColors.Accent))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSendSteakConfirm = false }) {
                        Text("CANCEL", style = BonyType.tag.copy(color = BonyColors.TextMute))
                    }
                },
            )
        }
        if (showSteakSheet) {
            ProfileSteakShelfSheet(
                summary = steakSummary,
                profiles = allProfiles,
                isOwnProfile = isActiveUserProfile,
                incomingSteaks = incomingSteaks,
                unlistedClaims = unlistedSteakClaims,
                nowEpochSec = nowEpochSec,
                onClaimSteak = viewModel::claimSteak,
                onSyncShelf = viewModel::syncSteakShelf,
                onRefresh = viewModel::refreshSteaks,
                onOpenProfile = { pk ->
                    showSteakSheet = false
                    if (pk != pubkey) onProfileClick(pk)
                },
                onOpenLeaderboard = {
                    showSteakSheet = false
                    onSteakLeaderboardClick()
                },
                onDismiss = { showSteakSheet = false },
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSteakShelfSheet(
    summary: ProfileSteakSummary,
    profiles: Map<String, ProfileContent>,
    isOwnProfile: Boolean,
    incomingSteaks: List<IncomingSteakOffer>,
    unlistedClaims: Int,
    nowEpochSec: Long,
    onClaimSteak: (Event) -> Unit,
    onSyncShelf: () -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenLeaderboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = remember(incomingSteaks, nowEpochSec, isOwnProfile) {
        if (!isOwnProfile) emptyList()
        else incomingSteaks.filter {
            val st = it.statusAt(nowEpochSec)
            st == OfferStatus.CLAIMABLE || st == OfferStatus.CLAIMING
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BonyColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SteakIcon(size = 32.dp, contentDescription = "Steaks")
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${summary.count} verified ${if (summary.count == 1) "steak" else "steaks"}",
                        style = BonyType.title.copy(color = BonyColors.Text),
                    )
                    Text(
                        text = "NIP-XX · 16-bit PoW · 24h claim window",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
                Text(
                    text = if (summary.isLoading) "…" else "↻",
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .clickable(enabled = !summary.isLoading, onClick = onRefresh)
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            BonyButton(
                label = "STEAK LEADERBOARD →",
                primary = true,
                onClick = onOpenLeaderboard,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isOwnProfile && unlistedClaims > 0) {
                Spacer(Modifier.height(8.dp))
                BonyButton(
                    label = "SYNC $unlistedClaims CLAIMED STEAK${if (unlistedClaims == 1) "" else "S"} TO PROFILE",
                    primary = false,
                    onClick = onSyncShelf,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "FRESH STEAKS TO CLAIM",
                    style = BonyType.caption.copy(color = BonyColors.Accent),
                )
                Spacer(Modifier.height(6.dp))
                pending.forEach { item ->
                    val senderProfile = profiles[item.offer.senderPubkey]
                    val senderName = senderProfile?.bestName
                        ?: Phrase.wordsFor(item.offer.senderPubkey, 2).joinToString("·")
                    val remaining = item.remainingSecondsAt(nowEpochSec)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UserAvatar(
                            pubkeyHex = item.offer.senderPubkey,
                            profile = senderProfile,
                            size = 30.dp,
                            modifier = Modifier.clickable { onOpenProfile(item.offer.senderPubkey) },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = senderName,
                                style = BonyType.body.copy(color = BonyColors.Text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "expires in ${DailyTokens.formatRemainingDuration(remaining)}",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                        if (item.claiming) {
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
                                    .clickable { onClaimSteak(item.offer.event) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = BonyColors.Rule)
            Spacer(Modifier.height(10.dp))

            if (summary.receipts.isEmpty()) {
                Text(
                    text = if (summary.isLoading) "verifying profile steaks…" else "no verified steaks yet",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Text(
                    text = "ACCEPTED STEAKS",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(summary.receipts, key = { it.offerId }) { receipt ->
                        val senderProfile = profiles[receipt.senderPubkey]
                        val senderName = senderProfile?.bestName
                            ?: Phrase.wordsFor(receipt.senderPubkey, 2).joinToString("·")
                        val claimedDate = remember(receipt.claimedAt) {
                            java.time.Instant.ofEpochSecond(receipt.claimedAt)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProfile(receipt.senderPubkey) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SteakIcon(size = 18.dp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            UserAvatar(
                                pubkeyHex = receipt.senderPubkey,
                                profile = senderProfile,
                                size = 28.dp,
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
                                    text = "accepted $claimedDate",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
