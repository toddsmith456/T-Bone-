package social.tbone.ui.toolbox

import social.tbone.ui.theme.appBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.toolbox.webxdc.XdcToolIcons
import social.tbone.updates.LocalUpdateAvailable

/** Uniform tool row height in dp — used for drag math. */
private val TOOL_ROW_HEIGHT = 68.dp

/**
 * The Toolbox tab. Tools can be reordered by LONG-PRESSING a tool and
 * dragging it up or down; the order is persisted. If a toolbox PIN is enabled
 * it is asked once per visit to the toolbox from the main menu (rememberSaveable
 * keeps it unlocked while moving between toolbox sub-screens); the duress PIN
 * wipes the toolbox. Screenshot blocking is handled app-wide by MainActivity.
 */
@Composable
fun ToolboxScreen(
    onBack: () -> Unit,
    onNotes: () -> Unit,
    onBookmarks: () -> Unit,
    onVoiceRecorder: () -> Unit,
    onGeohashChannels: () -> Unit,
    onCalendar: () -> Unit,
    onWallet: () -> Unit,
    onUpdateCenter: () -> Unit,
    onWebxdc: () -> Unit,
    onFollowLists: () -> Unit,
    onOpenXdcTool: (String) -> Unit,
    onToolboxSettings: () -> Unit,
    viewModel: ToolboxViewModel = hiltViewModel(),
    lockViewModel: ToolboxLockViewModel = hiltViewModel(),
) {
    val savedOrder by viewModel.toolOrder.collectAsStateWithLifecycle()
    val xdcTools by viewModel.xdcCustomTools.collectAsStateWithLifecycle()
    val isLocked by lockViewModel.isLocked.collectAsStateWithLifecycle()

    val hasUpdate = LocalUpdateAvailable.current

    if (isLocked) {
        ToolboxLockScreen(onUnlocked = { lockViewModel.unlock() })
        return
    }

    // Tools keyed by id, built here so the click handlers are in scope.
    // Custom webxdc apps promoted to tools use the "xdc_<appId>" id namespace.
    val toolsById: Map<String, ToolEntry> = buildMap {
        // Subtitle is kept to one line: the row is a fixed 68.dp.
        put("webxdc", ToolEntry(Icons.Outlined.Widgets, "webxdc",
            "single-player · local, store & nostr", onWebxdc))
        put("notes", ToolEntry(Icons.Outlined.EditNote, "encrypted notes",
            "notes, checklists, folders & inline attachments", onNotes))
        put("bookmarks", ToolEntry(Icons.Outlined.BookmarkBorder, "bookmarks",
            "encrypted local bookmarks, folders & quotes", onBookmarks))
        put("voice", ToolEntry(Icons.Outlined.Mic, "voice recorder",
            "record, anonymize, save or share a voice note", onVoiceRecorder))
        put("geohash", ToolEntry(Icons.Outlined.Place, "geohash channels",
            "location channels with an anonymous per-area npub", onGeohashChannels))
        put("calendar", ToolEntry(Icons.Outlined.CalendarMonth, "calendar",
            "fully local encrypted calendar", onCalendar))
        put("wallet", ToolEntry(Icons.Outlined.Bolt, "wallet tool",
            "Lightning zaps & Monero xaps", onWallet))
        put("updates", ToolEntry(Icons.Outlined.Update, "update center",
            "check, download & install app updates", onUpdateCenter, hasBadge = hasUpdate))
        put("followlists", ToolEntry(Icons.Outlined.Groups, "follow lists",
            "following · followers · blocked — online or offline", onFollowLists))
        xdcTools.forEach { tool ->
            put("xdc_${tool.appId}", ToolEntry(
                icon = XdcToolIcons.byId(tool.iconId),
                title = tool.name,
                subtitle = "webxdc mini app",
                onClick = { onOpenXdcTool(tool.appId) },
                onRemove = { viewModel.removeXdcTool(tool.appId) },
            ))
        }
    }

    // Ordered tool list (persisted; falls back to the default until set).
    val order = remember(savedOrder, toolsById.keys) {
        val o = savedOrder.filter { it in toolsById }
            .let { if (it.isEmpty()) viewModel.defaultToolOrder.filter { id -> id in toolsById } else it }
        // Include any tools not yet in the saved order (e.g. added later).
        (o + toolsById.keys.filter { it !in o }).toMutableList()
    }
    var currentOrder by remember(savedOrder, toolsById.keys) { mutableStateOf(order.toList()) }

    // Drag state.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var containerTopPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { TOOL_ROW_HEIGHT.toPx() }

    val tools = currentOrder.mapNotNull { id -> toolsById[id]?.let { id to it } }

    var pendingRemove by remember { mutableStateOf<Pair<String, (() -> Unit)>?>(null) }

    fun persist(order: List<String>) {
        viewModel.setToolOrder(order)
    }

    pendingRemove?.let { (name, action) ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("remove $name?", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = { Text("This removes it from the toolbox. The saved webxdc app stays in webxdc storage.", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            confirmButton = { TextButton(onClick = { action(); pendingRemove = null }) { Text("remove", color = BonyColors.Danger) } },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("cancel") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .onGloballyPositioned { containerTopPx = it.positionInWindow().y.roundToInt() },
    ) {
        // ── Top bar with gear (toolbox settings) ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                text = "toolbox",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.Rule)
                    .clickable(onClick = onToolboxSettings)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Toolbox settings",
                    tint = BonyColors.TextMute,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Text(
            text = "long-press a tool to reorder",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        // ── Reorderable tool rows ─────────────────────────────────────────────
        tools.forEachIndexed { index, (toolId, tool) ->
            val isDragging = index == draggingIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOOL_ROW_HEIGHT)
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffsetPx.roundToInt() else 0) }
                    .pointerInput(toolId, index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetPx = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetPx += amount.y
                            },
                            onDragEnd = {
                                if (draggingIndex >= 0) {
                                    val target = (draggingIndex + (dragOffsetPx / rowHeightPx).roundToInt())
                                        .coerceIn(0, currentOrder.lastIndex)
                                    if (target != draggingIndex) {
                                        val reordered = currentOrder.toMutableList()
                                        val item = reordered.removeAt(draggingIndex)
                                        reordered.add(target, item)
                                        currentOrder = reordered
                                        persist(reordered)
                                    }
                                    draggingIndex = -1
                                    dragOffsetPx = 0f
                                }
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetPx = 0f
                            },
                        )
                    },
            ) {
                ToolRow(
                    icon = tool.icon,
                    title = tool.title,
                    subtitle = tool.subtitle,
                    onClick = tool.onClick,
                    hasBadge = tool.hasBadge,
                    onRemove = tool.onRemove?.let { rm -> { pendingRemove = tool.title to rm } },
                )
            }
            // Drop indicator while dragging.
            if (isDragging && dragOffsetPx != 0f) {
                val target = (draggingIndex + (dragOffsetPx / rowHeightPx).roundToInt())
                    .coerceIn(0, currentOrder.lastIndex)
                if (target != draggingIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(BonyColors.Accent)
                            .offset { IntOffset(0, (target * rowHeightPx).roundToInt()) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "more tools coming soon",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

private data class ToolEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val hasBadge: Boolean = false,
    val onRemove: (() -> Unit)? = null,
)

@Composable
internal fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, hasBadge: Boolean = false, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .appBorder(1.dp, BonyColors.Rule)
                .background(BonyColors.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BonyColors.Accent,
                modifier = Modifier.size(22.dp),
            )
            if (hasBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(BonyColors.Accent, androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // The row is a fixed 68.dp; keep long subtitles on one line instead
            // of clipping them mid-glyph.
            Text(
                subtitle,
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.Rule)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("remove", style = BonyType.meta.copy(color = BonyColors.Danger))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("≡", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
