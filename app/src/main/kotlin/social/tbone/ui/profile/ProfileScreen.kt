package social.tbone.ui.profile

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.feed.NoteCard
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

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
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActiveUserProfile by viewModel.isActiveUserProfile.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val isFollowLoading by viewModel.isFollowLoading.collectAsStateWithLifecycle()
    val isBlocked by viewModel.isBlocked.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val repliedByMe by viewModel.repliedByMe.collectAsStateWithLifecycle()
    val pollVoteCounts by viewModel.pollVoteCounts.collectAsStateWithLifecycle()
    val pollMyVotes by viewModel.pollMyVotes.collectAsStateWithLifecycle()
    val pollVoteVersion by viewModel.pollVoteVersion.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val pubkey = viewModel.pubkey

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val onShareNote = remember {
        { event: Event ->
            val noteUri = "nostr:${Nip19.hexToNote(event.id)}"
            val shareText = buildString {
                val text = event.content.take(280).trim()
                if (text.isNotEmpty()) { append(text); append("\n\n") }
                append(noteUri)
            }
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) },
                "Share note",
            ))
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val phrase6 = remember(pubkey) { Phrase.wordsFor(pubkey, 6) }
    val npub = remember(pubkey) { Nip19.hexToNpub(pubkey) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
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
                        .background(BonyColors.Bg)
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
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "⋯",
                        style = BonyType.body.copy(color = BonyColors.TextMute),
                        modifier = Modifier.clickable { /* more menu — future */ },
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                // ── Banner + Avatar ───────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + AVATAR_SIZE / 2),
                ) {
                    // Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .background(BonyColors.SurfaceAlt),
                    ) {
                        if (profile?.banner != null) {
                            AsyncImage(
                                model = profile?.banner,
                                contentDescription = "Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
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
                        Spacer(Modifier.height(10.dp))
                    }

                    // Fingerprint pill
                    Box(
                        modifier = Modifier
                            .border(1.dp, BonyColors.Rule)
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

                    Spacer(Modifier.height(12.dp))

                    // Own profile: edit picture / banner.
                    if (isActiveUserProfile) {
                        BonyButton(
                            label = "✎ EDIT PICTURE & BANNER",
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
                            )
                            Spacer(Modifier.width(8.dp))
                            BonyButton(
                                label = if (isBlocked) "UNBLOCK" else "BLOCK",
                                primary = false,
                                onClick = viewModel::toggleBlock,
                                modifier = Modifier.weight(0.7f),
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
                            )
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
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("NOTES", style = BonyType.tag.copy(color = BonyColors.Accent))
                    Spacer(Modifier.width(20.dp))
                    Text("REPLIES", style = BonyType.tag.copy(color = BonyColors.TextMute))
                    Spacer(Modifier.width(20.dp))
                    Text("MEDIA", style = BonyType.tag.copy(color = BonyColors.TextMute))
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            }

            if (isLoading && notes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                    }
                }
            }

            items(notes, key = { it.id }) { event ->
                NoteCard(
                    event = event,
                    profile = profile,
                    onThreadClick = onThreadClick,
                    onProfileClick = onProfileClick,
                    onHashtagClick = onHashtagClick,
                    onReply = onReplyClick,
                    onBoost = viewModel::boost,
                    onQuote = onQuoteClick,
                    onLike = viewModel::react,
                    onShare = onShareNote,
                    reactors = reactions[event.id],
                    replies = replies,
                    repliedByMe = repliedByMe,
                    pollVoteCounts = pollVoteCounts,
                    pollMyVotes = pollMyVotes,
                    pollVoteVersion = pollVoteVersion,
                    onPollVote = viewModel::voteOnPoll,
                    activePubkey = activePubkey,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
