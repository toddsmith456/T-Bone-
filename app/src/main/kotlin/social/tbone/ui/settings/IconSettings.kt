package social.tbone.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.stealth.StealthManager
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.io.File

private val bgNames = listOf("Black", "Cream", "Plum", "Blue")
private val logoNames = listOf("White", "Gold", "Mint", "Dark")

/** The saved choice is separate from notepad disguise; leaving disguise restores it. */
@Composable
internal fun IconSettings() {
    val context = LocalContext.current
    val manager = remember { StealthManager(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(manager.selectedAlias()) }
    var mode by remember { mutableStateOf(if (chosen == "LauncherAlternative") "alternative" else if (chosen.startsWith("LauncherColor")) "colors" else "official") }
    var bg by remember { mutableIntStateOf(chosen.removePrefix("LauncherColor").getOrNull(0)?.digitToIntOrNull()?.coerceIn(0, 3) ?: 0) }
    var fg by remember { mutableIntStateOf(chosen.removePrefix("LauncherColor").getOrNull(1)?.digitToIntOrNull()?.coerceIn(0, 3) ?: 0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { saveSplashImage(context, uri) } }
            busy = false
            message = if (result.isSuccess) "Uploaded image saved for the in-app loading screen. Launcher icon stays unchanged." else "Could not read that image. Try another PNG or JPEG."
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CUSTOMIZE ICON", style = BonyType.meta, color = BonyColors.Accent)
        Text("Android supports bundled launcher icons. Uploaded images are for the in-app loading screen only; the system splash may differ.", style = BonyType.body, color = BonyColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("official" to "Official", "colors" to "Colors", "alternative" to "Alternative").forEach { (id, title) ->
                Text(if (mode == id) "● $title" else "○ $title", modifier = Modifier.clickable { mode = id }.padding(vertical = 8.dp), color = BonyColors.Text)
            }
        }
        if (mode == "colors") {
            Text("Background", style = BonyType.body, color = BonyColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { bgNames.forEachIndexed { i, name -> Text(if (bg == i) "● $name" else "○ $name", Modifier.clickable { bg = i }.padding(vertical = 6.dp), color = BonyColors.Text) } }
            Text("Logo", style = BonyType.body, color = BonyColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { logoNames.forEachIndexed { i, name -> Text(if (fg == i) "● $name" else "○ $name", Modifier.clickable { fg = i }.padding(vertical = 6.dp), color = BonyColors.Text) } }
        }
        val res = when (mode) {
            "alternative" -> social.tbone.R.drawable.icon_alternative_steak
            "colors" -> context.resources.getIdentifier("icon_color_${bg}_${fg}", "drawable", context.packageName)
            else -> social.tbone.R.drawable.ic_launcher_foreground
        }
        if (res != 0) Image(painterResource(res), contentDescription = "Icon preview", modifier = Modifier.size(80.dp))
        Text("SAVE ICON", Modifier.clickable {
            val next = when (mode) { "alternative" -> "LauncherAlternative"; "colors" -> "LauncherColor$bg$fg"; else -> "LauncherTBone" }
            runCatching {
                manager.setRegularIcon(next)
                check(prefs.edit().putString("splash", next).commit())
            }.onSuccess { chosen = next; message = "Saved. Some launchers may take time to refresh." }
             .onFailure { message = "Could not change the icon. Please try again." }
        }.padding(vertical = 10.dp), color = BonyColors.Accent)
        Text(if (busy) "IMPORTING…" else "UPLOAD LOADING IMAGE", Modifier.clickable(enabled = !busy) { picker.launch("image/*") }.padding(vertical = 10.dp), color = BonyColors.Accent)
        if (message.isNotEmpty()) Text(message, style = BonyType.body, color = BonyColors.TextDim)
    }
}

/** Decode at bounded dimensions without keeping a permission to a foreign URI. */
private fun saveSplashImage(context: Context, uri: Uri) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("No image")
    require(bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000)
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Unsupported image")
    val tmp = File(context.filesDir, "splash-image.tmp")
    try {
        tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(tmp.renameTo(File(context.filesDir, "splash-image.png")))
        check(context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE).edit().putString("splash", "upload").commit())
    } finally { bitmap.recycle(); tmp.delete() }
}

@Composable
internal fun StartupAppearance(context: Context) {
    val prefs = remember { context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE) }
    val choice = remember { prefs.getString("splash", "LauncherTBone") ?: "LauncherTBone" }
    val resource = when {
        choice == "LauncherAlternative" -> social.tbone.R.drawable.icon_alternative_steak
        choice.startsWith("LauncherColor") && choice.length == "LauncherColor00".length -> context.resources.getIdentifier("icon_color_${choice[13]}_${choice[14]}", "drawable", context.packageName)
        else -> social.tbone.R.drawable.ic_launcher_foreground
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
        if (choice == "upload" && File(context.filesDir, "splash-image.png").exists()) {
            AsyncImage(model = File(context.filesDir, "splash-image.png"), contentDescription = null, modifier = Modifier.size(120.dp))
        } else if (resource != 0) Image(painterResource(resource), contentDescription = null, modifier = Modifier.size(120.dp))
    }
}
