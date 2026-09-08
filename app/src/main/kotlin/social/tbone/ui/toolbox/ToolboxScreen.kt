package social.tbone.ui.toolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

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
    onVoiceRecorder: () -> Unit,
    onGeohashChannels: () -> Unit,
    onCalendar: () -> Unit,
    onToolboxSettings: () -> Unit,
    viewModel: ToolboxViewModel = hiltViewModel(),
) {
    val pinEnabled by viewModel.pinEnabled.collectAsStateWithLifecycle()
    val savedOrder by viewModel.toolOrder.collectAsStateWithLifecycle()
    // rememberSaveable survives navigating to sub-screens and back, so the PIN
    // is only asked once per toolbox visit (it resets when the toolbox back
    // stack entry is popped, i.e. leaving to the main menu).
    var unlocked by rememberSaveable { mutableStateOf(false) }

    if (pinEnabled && !unlocked) {
        ToolboxLockScreen(onUnlocked = { unlocked = true })
        return
    }

    // Ordered tool list (persisted; falls back to the default until set).
    val order = remember(savedOrder) {
        val o = savedOrder.filter { it in ToolIds }.let { if (it.isEmpty()) viewModel.defaultToolOrder else it }
        // Include any tools not yet in the saved order (e.g. added later).
        (o + viewModel.defaultToolOrder.filter { it !in o }).toMutableList()
    }
    var currentOrder by remember(savedOrder) { mutableStateOf(order.toList()) }

    // Drag state.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var containerTopPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { TOOL_ROW_HEIGHT.toPx() }

    // Tools keyed by id, built here so the click handlers are in scope.
    val toolsById: Map<String, ToolEntry> = mapOf(
        "notes" to ToolEntry(Icons.Outlined.EditNote, "encrypted notes",
            "notes, checklists, folders & inline attachments", onNotes),
        "voice" to ToolEntry(Icons.Outlined.Mic, "voice recorder",
            "record, anonymize, save or share a voice note", onVoiceRecorder),
        "geohash" to ToolEntry(Icons.Outlined.Place, "geohash channels",
            "location channels with an anonymous per-area npub", onGeohashChannels),
        "calendar" to ToolEntry(Icons.Outlined.CalendarMonth, "calendar",
            "fully local encrypted calendar", onCalendar),
    )
    val tools = currentOrder.mapNotNull { id -> toolsById[id]?.let { id to it } }

    fun persist(order: List<String>) {
        viewModel.setToolOrder(order)
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
                    .border(1.dp, BonyColors.Rule)
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
)

private val ToolIds = setOf("notes", "voice", "geohash", "calendar")

@Composable
internal fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
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
                .border(1.dp, BonyColors.Rule)
                .background(BonyColors.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BonyColors.Accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
        Text("≡", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}
