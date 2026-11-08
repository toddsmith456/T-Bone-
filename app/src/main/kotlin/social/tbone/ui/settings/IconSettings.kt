package social.tbone.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import social.tbone.stealth.StealthManager
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

private val bgNames = listOf("Black", "Cream", "Plum", "Blue")
private val logoNames = listOf("White", "Gold", "Mint", "Dark")

/**
 * Launcher-icon presets, shown inside the APPEARANCE settings category so it
 * collapses together with the theme controls in declutter mode.
 *
 * Android launchers only accept icons that are bundled with the APK, so the
 * choices are the official icon, a two-color preset, or the alternative
 * artwork — not arbitrary images. The saved choice is separate from the
 * notepad disguise; leaving disguise restores it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconSettings() {
    val context = LocalContext.current
    val manager = remember { StealthManager(context.applicationContext) }
    var chosen by remember { mutableStateOf(manager.selectedAlias()) }
    var mode by remember { mutableStateOf(if (chosen == "LauncherAlternative") "alternative" else if (chosen.startsWith("LauncherColor")) "colors" else "official") }
    var bg by remember { mutableIntStateOf(chosen.removePrefix("LauncherColor").getOrNull(0)?.digitToIntOrNull()?.coerceIn(0, 3) ?: 0) }
    var fg by remember { mutableIntStateOf(chosen.removePrefix("LauncherColor").getOrNull(1)?.digitToIntOrNull()?.coerceIn(0, 3) ?: 0) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("launcher icon", style = BonyType.body.copy(color = BonyColors.Text))
        Text("one of the bundled presets; launchers refresh the icon at their own pace", style = BonyType.meta.copy(color = BonyColors.TextMute))
        FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("official" to "Official", "colors" to "Colors", "alternative" to "Alternative").forEach { (id, title) ->
                Text(if (mode == id) "● $title" else "○ $title", modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected = mode == id, role = Role.RadioButton) { mode = id }
                    .padding(vertical = 12.dp), color = BonyColors.Text)
            }
        }
        if (mode == "colors") {
            Text("Background", style = BonyType.body, color = BonyColors.TextDim)
            FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { bgNames.forEachIndexed { i, name -> Text(if (bg == i) "● $name" else "○ $name", Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected = bg == i, role = Role.RadioButton) { bg = i }.padding(vertical = 12.dp), color = BonyColors.Text) } }
            Text("Logo", style = BonyType.body, color = BonyColors.TextDim)
            FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { logoNames.forEachIndexed { i, name -> Text(if (fg == i) "● $name" else "○ $name", Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected = fg == i, role = Role.RadioButton) { fg = i }.padding(vertical = 12.dp), color = BonyColors.Text) } }
        }
        val res = when (mode) {
            "alternative" -> social.tbone.R.mipmap.icon_alternative_steak
            "colors" -> context.resources.getIdentifier("icon_color_${bg}_${fg}", "drawable", context.packageName)
            else -> social.tbone.R.drawable.ic_launcher_foreground
        }
        if (res != 0) Image(painterResource(res), contentDescription = "Icon preview", modifier = Modifier.size(80.dp))
        Text("SAVE ICON", Modifier.clickable {
            val next = when (mode) { "alternative" -> "LauncherAlternative"; "colors" -> "LauncherColor$bg$fg"; else -> "LauncherTBone" }
            runCatching {
                manager.setRegularIcon(next)
                check(context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE)
                    .edit().putString("splash", next).commit())
            }.onSuccess { chosen = next; message = "Saved. Some launchers may take time to refresh." }
             .onFailure { message = "Could not change the icon. Please try again." }
        }.padding(vertical = 10.dp), color = BonyColors.Accent)
        if (message.isNotEmpty()) Text(message, style = BonyType.body, color = BonyColors.TextDim)
    }
}

@Composable
internal fun StartupAppearance(context: Context) {
    val prefs = remember { context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE) }
    val choice = remember { prefs.getString("splash", "LauncherTBone") ?: "LauncherTBone" }
    val resource = when {
        choice == "LauncherAlternative" -> social.tbone.R.mipmap.icon_alternative_steak
        choice.startsWith("LauncherColor") && choice.length == "LauncherColor00".length -> context.resources.getIdentifier("icon_color_${choice[13]}_${choice[14]}", "drawable", context.packageName)
        else -> social.tbone.R.drawable.ic_launcher_foreground
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
        if (resource != 0) Image(painterResource(resource), contentDescription = null, modifier = Modifier.size(120.dp))
    }
}
