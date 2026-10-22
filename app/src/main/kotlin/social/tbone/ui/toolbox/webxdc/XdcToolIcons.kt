package social.tbone.ui.toolbox.webxdc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The 20 generic icons offered when a saved webxdc mini app is promoted to a
 * toolbox tool.
 *
 * They cover the mini-app categories the webxdc store actually ships (games,
 * polls, notes, checklists, utilities, media, reference …), and they are the
 * same Material outlined vectors the six built-in tools use, so a custom tool
 * looks exactly like the rest of the toolbox.
 */
data class XdcToolIcon(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

object XdcToolIcons {

    val all: List<XdcToolIcon> = listOf(
        XdcToolIcon("game", "game", Icons.Outlined.SportsEsports),
        XdcToolIcon("poll", "poll", Icons.Outlined.Poll),
        XdcToolIcon("checklist", "checklist", Icons.Outlined.Checklist),
        XdcToolIcon("notes", "notes", Icons.Outlined.EditNote),
        XdcToolIcon("calculator", "calculator", Icons.Outlined.Calculate),
        XdcToolIcon("calendar", "calendar", Icons.Outlined.CalendarMonth),
        XdcToolIcon("timer", "timer", Icons.Outlined.Timer),
        XdcToolIcon("map", "map", Icons.Outlined.Map),
        XdcToolIcon("music", "music", Icons.Outlined.MusicNote),
        XdcToolIcon("camera", "camera", Icons.Outlined.PhotoCamera),
        XdcToolIcon("chat", "chat", Icons.Outlined.ChatBubbleOutline),
        XdcToolIcon("puzzle", "puzzle", Icons.Outlined.Extension),
        XdcToolIcon("dice", "dice", Icons.Outlined.Casino),
        XdcToolIcon("wallet", "wallet", Icons.Outlined.AccountBalanceWallet),
        XdcToolIcon("weather", "weather", Icons.Outlined.WbSunny),
        XdcToolIcon("quiz", "quiz", Icons.Outlined.Quiz),
        XdcToolIcon("piano", "piano", Icons.Outlined.Piano),
        XdcToolIcon("document", "document", Icons.Outlined.Description),
        XdcToolIcon("favorite", "favorite", Icons.Outlined.StarBorder),
        XdcToolIcon("art", "art", Icons.Outlined.Palette),
    )

    val defaultIcon: ImageVector = Icons.Outlined.Widgets

    fun byId(id: String?): ImageVector = all.firstOrNull { it.id == id }?.icon ?: defaultIcon
}
