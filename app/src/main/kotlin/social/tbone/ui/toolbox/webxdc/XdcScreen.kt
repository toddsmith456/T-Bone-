package social.tbone.ui.toolbox.webxdc

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.webxdc.SavedXdcApp
import social.tbone.webxdc.XdcCustomTool
import social.tbone.webxdc.XdcFeedApp
import social.tbone.webxdc.XdcStoreCatalog
import social.tbone.webxdc.XdcStoreEntry
import java.io.File

/**
 * The webxdc tool.
 *
 * "saved" is the library and the home tab; "store", "t-bone" and "nostr" are
 * the three feeds. The fourth source — a `.xdc` from device storage — lives
 * behind the "+" button in the header.
 */
@Composable
fun XdcScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    viewModel: XdcViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.savedApps.collectAsStateWithLifecycle()
    val busy by viewModel.busyKeys.collectAsStateWithLifecycle()
    val tools by viewModel.customTools.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var toolTarget by remember { mutableStateOf<SavedXdcApp?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importLocal(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    val toolByAppId = remember(tools) { tools.associateBy { it.appId } }

    Box(modifier = Modifier.fillMaxSize().background(BonyColors.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Header
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("webxdc", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(
                        "single-player mini apps",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .appBorder(1.dp, BonyColors.Rule)
                        .background(BonyColors.SurfaceAlt)
                        .clickable { importLauncher.launch(arrayOf("*/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Import a local .xdc file",
                        tint = BonyColors.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            // Tabs
            Row(modifier = Modifier.fillMaxWidth()) {
                XdcTab.entries.forEach { tab ->
                    val label = when (tab) {
                        XdcTab.SAVED -> "saved"
                        XdcTab.STORE -> "store"
                        XdcTab.TBONE -> "t-bone"
                        XdcTab.NOSTR -> "nostr"
                    }
                    val count = when (tab) {
                        XdcTab.SAVED -> saved.size
                        XdcTab.STORE -> state.storeApps.size
                        XdcTab.TBONE -> state.tboneApps.size
                        XdcTab.NOSTR -> state.nostrApps.size
                    }
                    val active = state.tab == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.selectTab(tab) }
                            .padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = label,
                                style = BonyType.tag.copy(
                                    color = if (active) BonyColors.Accent else BonyColors.TextMute,
                                ),
                            )
                            if (count > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = count.toString(),
                                    style = BonyType.caption.copy(
                                        color = if (active) BonyColors.AccentDim else BonyColors.TextMute,
                                    ),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) BonyColors.Accent else Color.Transparent),
                        )
                    }
                }
            }

            if (state.tab != XdcTab.SAVED) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = { Text("filter apps", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                    textStyle = BonyType.meta.copy(color = BonyColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            when (state.tab) {
                XdcTab.SAVED -> SavedList(
                    apps = saved,
                    tools = toolByAppId,
                    iconFor = viewModel::iconFile,
                    onOpen = onOpenApp,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onAddTool = { toolTarget = it },
                    onDelete = viewModel::deleteApp,
                    onRemoveTool = { viewModel.removeTool(it) },
                )
                XdcTab.STORE -> StoreList(
                    apps = state.filteredStore(),
                    loading = state.storeLoading,
                    error = state.storeError,
                    busy = busy,
                    context = context,
                    onAdd = viewModel::saveStoreEntry,
                    onRetry = { viewModel.loadStore(force = true) },
                )
                XdcTab.TBONE -> FeedList(
                    apps = state.filteredFeed(state.tboneApps),
                    loading = state.tboneLoading,
                    emptyText = "the t-bone npub hasn't published a mini app yet",
                    emptyHint = "npub1zx2…ftjq · kind 1063 · #m application/x-webxdc",
                    busy = busy,
                    onAdd = { viewModel.saveFeedApp(it, "tbone") },
                    context = context,
                    onRefresh = { viewModel.refreshFeed(XdcTab.TBONE) },
                )
                XdcTab.NOSTR -> FeedList(
                    apps = state.filteredFeed(state.nostrApps),
                    loading = state.nostrLoading,
                    emptyText = "no webxdc apps on your relays yet",
                    emptyHint = "kind 1063 · #m application/x-webxdc · all authors",
                    busy = busy,
                    onAdd = { viewModel.saveFeedApp(it, "nostr") },
                    context = context,
                    onRefresh = { viewModel.refreshFeed(XdcTab.NOSTR) },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp),
        )
    }

    toolTarget?.let { app ->
        XdcAddToolDialog(
            app = app,
            existing = toolByAppId[app.id],
            onDismiss = { toolTarget = null },
            onConfirm = { name, iconId ->
                viewModel.addAsTool(app, name, iconId)
                toolTarget = null
            },
        )
    }
}

// ── Saved library ────────────────────────────────────────────────────────────

@Composable
private fun SavedList(
    apps: List<SavedXdcApp>,
    tools: Map<String, XdcCustomTool>,
    iconFor: (SavedXdcApp) -> File?,
    onOpen: (String) -> Unit,
    onImport: () -> Unit,
    onAddTool: (SavedXdcApp) -> Unit,
    onDelete: (SavedXdcApp) -> Unit,
    onRemoveTool: (String) -> Unit,
) {
    if (apps.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "no saved mini apps",
                style = BonyType.body.copy(color = BonyColors.TextDim),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "import a .xdc file, or add one from the store or a Nostr feed. Saved apps play offline and stay on this device.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.Accent)
                    .clickable(onClick = onImport)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("import .xdc", style = BonyType.button.copy(color = BonyColors.Accent))
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(apps, key = { it.id }) { app ->
            SavedAppRow(
                app = app,
                tool = tools[app.id],
                icon = iconFor(app),
                onOpen = { onOpen(app.id) },
                onAddTool = { onAddTool(app) },
                onDelete = { onDelete(app) },
                onRemoveTool = { onRemoveTool(app.id) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SavedAppRow(
    app: SavedXdcApp,
    tool: XdcCustomTool?,
    icon: File?,
    onOpen: () -> Unit,
    onAddTool: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTool: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon = icon, fallbackSeed = app.name, size = 48)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append(Formatter.formatShortFileSize(context, app.sizeBytes))
                append(" · ")
                append(DateUtils.getRelativeTimeSpanString(app.importedAt).toString())
                if (tool != null) append(" · tool")
            }
            Text(meta, style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
        Box {
            Text(
                "⋯",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (tool == null) "add as tool" else "change tool icon", style = BonyType.body) },
                    onClick = {
                        menuOpen = false
                        onAddTool()
                    },
                )
                if (tool != null) {
                    DropdownMenuItem(
                        text = { Text("remove from toolbox", style = BonyType.body) },
                        onClick = {
                            menuOpen = false
                            onRemoveTool()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("delete app", style = BonyType.body.copy(color = BonyColors.Danger)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

// ── Store ────────────────────────────────────────────────────────────────────

@Composable
private fun StoreList(
    apps: List<XdcStoreEntry>,
    loading: Boolean,
    error: String?,
    busy: Set<String>,
    context: android.content.Context,
    onAdd: (XdcStoreEntry) -> Unit,
    onRetry: () -> Unit,
) {
    if (loading && apps.isEmpty()) {
        LoadingBox("loading webxdc.org…")
        return
    }
    if (error != null && apps.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("couldn't load the store", style = BonyType.body.copy(color = BonyColors.TextDim))
            Spacer(Modifier.height(6.dp))
            Text(error, style = BonyType.meta.copy(color = BonyColors.TextMute))
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.appBorder(1.dp, BonyColors.Accent).clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = BonyColors.Accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("retry", style = BonyType.button.copy(color = BonyColors.Accent))
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "apps.testrun.org · tap + to download",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        items(apps, key = { it.appId }) { entry ->
            CatalogRow(
                title = entry.name,
                subtitle = XdcStoreCatalog.subtitle(entry),
                meta = listOfNotNull(
                    entry.category.takeIf { it.isNotBlank() },
                    Formatter.formatShortFileSize(context, entry.size),
                ).joinToString(" · "),
                iconUrl = XdcStoreCatalog.iconUrl(entry),
                busy = entry.appId in busy,
                onAdd = { onAdd(entry) },
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Nostr feeds ──────────────────────────────────────────────────────────────

@Composable
private fun FeedList(
    apps: List<XdcFeedApp>,
    loading: Boolean,
    emptyText: String,
    emptyHint: String,
    busy: Set<String>,
    onAdd: (XdcFeedApp) -> Unit,
    context: android.content.Context,
    onRefresh: () -> Unit,
) {
    if (loading && apps.isEmpty()) {
        LoadingBox("asking relays…")
        return
    }
    if (apps.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(emptyText, style = BonyType.body.copy(color = BonyColors.TextDim))
            Spacer(Modifier.height(6.dp))
            Text(emptyHint, style = BonyType.caption.copy(color = BonyColors.TextMute))
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.appBorder(1.dp, BonyColors.Rule).clickable(onClick = onRefresh)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = BonyColors.TextMute, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("refresh", style = BonyType.button.copy(color = BonyColors.TextMute))
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "${apps.size} app${if (apps.size == 1) "" else "s"} · tap + to save",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        items(apps, key = { it.eventId }) { app ->
            CatalogRow(
                title = app.name,
                subtitle = app.description?.lineSequence()?.firstOrNull()?.trim().orEmpty(),
                meta = listOfNotNull(
                    "nostr",
                    app.sizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                ).joinToString(" · "),
                iconUrl = app.iconUrl,
                busy = app.eventId in busy,
                onAdd = { onAdd(app) },
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun LoadingBox(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, style = BonyType.caption.copy(color = BonyColors.TextMute))
    }
}

@Composable
private fun AppIcon(
    icon: Any?,
    fallbackSeed: String,
    size: Int,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(BonyColors.SurfaceAlt)
            .border(1.dp, BonyColors.Rule, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackSeed.take(2).uppercase(),
                style = BonyType.tag.copy(color = BonyColors.TextMute),
            )
        }
    }
}

@Composable
private fun CatalogRow(
    title: String,
    subtitle: String,
    meta: String,
    iconUrl: String?,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconUrl != null) {
            AppIcon(icon = iconUrl, fallbackSeed = title, size = 52)
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BonyColors.SurfaceAlt)
                    .border(1.dp, BonyColors.Rule, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Widgets,
                    contentDescription = null,
                    tint = BonyColors.AccentDim,
                    modifier = Modifier.size(24.dp),
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
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = BonyType.meta.copy(color = BonyColors.TextDim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(meta, style = BonyType.caption.copy(color = BonyColors.TextMute))
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.SurfaceAlt)
                .clickable(enabled = !busy, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = BonyColors.Accent,
                    strokeWidth = 1.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Save app",
                    tint = BonyColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
