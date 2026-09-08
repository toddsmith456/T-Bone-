package social.tbone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.tbone.account.Account
import social.tbone.account.SignerType
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Compact active-account row shown in the feed top bar.
 * Tapping the row opens a modal-style account switcher overlay.
 */
@Composable
fun AccountSwitcherSheet(
    activeAccount: Account?,
    accounts: List<Account>,
    onSwitch: (pubkey: String) -> Unit,
    onProfileClick: (() -> Unit)? = null,
    profiles: Map<String, ProfileContent> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Collapsed: show active account identity row
        if (activeAccount != null) {
            val profile = profiles[activeAccount.pubkey]
            val phrase = remember(activeAccount.pubkey) {
                Phrase.wordsFor(activeAccount.pubkey, 3).joinToString("·")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .clickable { if (accounts.size > 1) expanded = true else onProfileClick?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(
                    pubkeyHex = activeAccount.pubkey,
                    profile = profile,
                    size = 28.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val name = profile?.bestName ?: activeAccount.displayName
                    if (name != null) {
                        Text(name, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
                    }
                    Text(phrase, style = BonyType.metaDim.copy(color = BonyColors.TextMute), maxLines = 1)
                }
                if (accounts.size > 1) {
                    Text(
                        text = "↕",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }

        // Expanded: modal overlay drawn over feed content
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BonyColors.Bg.copy(alpha = 0.85f))
                    .clickable { expanded = false },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .border(1.dp, BonyColors.Rule),
            ) {
                // Drag handle + header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .background(BonyColors.RuleStrong),
                    )
                }
                SectionHeader("ACCOUNTS")

                accounts.forEach { account ->
                    val isActive = account.pubkey == activeAccount?.pubkey
                    val accountProfile = profiles[account.pubkey]
                    AccountRow(
                        account = account,
                        profile = accountProfile,
                        isActive = isActive,
                        onClick = {
                            onSwitch(account.pubkey)
                            expanded = false
                        },
                    )
                }

                // Footer actions
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+", style = BonyType.meta.copy(color = BonyColors.TextDim))
                    Spacer(Modifier.width(8.dp))
                    Text("add account", style = BonyType.body.copy(color = BonyColors.TextDim))
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    profile: ProfileContent?,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val phrase = remember(account.pubkey) {
        Phrase.wordsFor(account.pubkey, 3).joinToString("·")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isActive) Modifier.background(BonyColors.AccentBg) else Modifier),
    ) {
        // 2dp left accent rail when active
        if (isActive) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(56.dp)
                    .background(BonyColors.Accent)
                    .align(Alignment.CenterStart),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                pubkeyHex = account.pubkey,
                profile = profile,
                size = 40.dp,
                modifier = if (!isActive) Modifier.background(BonyColors.SurfaceAlt.copy(alpha = 0.5f)) else Modifier,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val name = profile?.bestName ?: account.displayName
                if (name != null) {
                    Text(name, style = BonyType.bodyDim.copy(color = BonyColors.Text), maxLines = 1)
                }
                Text(phrase, style = BonyType.metaDim.copy(color = BonyColors.TextMute), maxLines = 1)
            }

            // Signer type pill
            SignerPill(account.signerType)
            Spacer(Modifier.width(8.dp))

            // Active indicator dot
            if (isActive) {
                Text("●", style = BonyType.meta.copy(color = BonyColors.Accent))
            } else {
                Text("○", style = BonyType.meta.copy(color = BonyColors.TextMute))
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun SignerPill(signerType: SignerType) {
    val (label, color, borderColor) = when (signerType) {
        SignerType.AMBER -> Triple("amber", BonyColors.TextDim, BonyColors.Rule)
        SignerType.NSEC_BUNKER -> Triple("bunker", BonyColors.TextDim, BonyColors.Rule)
        SignerType.LOCAL_KEY -> Triple("keystore", BonyColors.Warn, BonyColors.WarnDim)
    }
    Box(
        modifier = Modifier
            .border(1.dp, borderColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = BonyType.tag.copy(color = color))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.height(1.dp).weight(1f).background(BonyColors.Rule))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = BonyType.caption.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.height(1.dp).weight(1f).background(BonyColors.Rule))
    }
}
