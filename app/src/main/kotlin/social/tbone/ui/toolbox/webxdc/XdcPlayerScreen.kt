package social.tbone.ui.toolbox.webxdc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import social.tbone.nostr.Nip19
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.webxdc.WebxdcBridge
import social.tbone.webxdc.WebxdcShim
import social.tbone.webxdc.XdcWebViewClient
import timber.log.Timber
import java.io.File

private sealed interface JsPrompt {
    data class Alert(val message: String, val result: JsResult) : JsPrompt
    data class Confirm(val message: String, val result: JsResult) : JsPrompt
    data class Prompt(val message: String, val default: String, val result: JsPromptResult) : JsPrompt
}

/**
 * The single-player webxdc runtime.
 *
 * Each app runs in a WebView bound to its own private origin
 * (`https://<appId>.xdc.local`), with the archive's files served out of
 * app-private storage and every other origin blocked. The webxdc API is the
 * T-Bone shim: updates are journalled locally, `importFiles` opens Android's
 * document picker and `sendToChat` opens the share sheet. No relays, no peers.
 */
@Composable
fun XdcPlayerScreen(
    onBack: () -> Unit,
    viewModel: XdcPlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val app by viewModel.app.collectAsStateWithLifecycle()
    val libraryLoaded by viewModel.libraryLoaded.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val selfAddr = remember(account) {
        account?.pubkey?.let { runCatching { Nip19.hexToNpub(it) }.getOrNull() } ?: "tbone:local"
    }
    val selfName = remember(account, profiles) {
        val pubkey = account?.pubkey
        val profile = pubkey?.let { profiles[it] }
        profile?.bestName ?: "you"
    }

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val bridgeState = remember { mutableStateOf<WebxdcBridge?>(null) }
    val scope = rememberCoroutineScope()

    var pendingImportId by remember { mutableStateOf<Int?>(null) }
    var jsPrompt by remember { mutableStateOf<JsPrompt?>(null) }
    var promptValue by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showToolDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val requestId = pendingImportId
        pendingImportId = null
        val bridge = bridgeState.value
        if (requestId == null || bridge == null) return@rememberLauncherForActivityResult
        if (uris.isNullOrEmpty()) {
            bridge.reject(requestId, "cancelled")
        } else {
            scope.launch {
                runCatching { filesJson(context, uris) }
                    .onSuccess { bridge.resolve(requestId, it) }
                    .onFailure { bridge.reject(requestId, it.message ?: "could not read files") }
            }
        }
    }

    val host = remember(importLauncher, context) {
        object : WebxdcBridge.Host {
            override fun post(js: String) {
                val view = webViewState.value ?: return
                view.post { view.evaluateJavascript(js, null) }
            }

            override fun importFiles(requestId: Int, filterJson: String) {
                pendingImportId = requestId
                importLauncher.launch(arrayOf("*/*"))
            }

            override fun sendToChat(requestId: Int, messageJson: String) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { shareToChat(context, messageJson) }.getOrDefault(false)
                    }
                    bridgeState.value?.let { bridge ->
                        if (ok) bridge.resolve(requestId, "true") else bridge.reject(requestId, "could not share")
                    }
                }
            }
        }
    }

    val bridge = remember(app?.id, selfAddr, selfName, host) {
        app?.let { WebxdcBridge(viewModel.store(), selfAddr, selfName, host) }
    }
    LaunchedEffect(bridge) { bridgeState.value = bridge }

    BackHandler {
        val view = webViewState.value
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
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
            Spacer(Modifier.width(10.dp))
            if (app != null) {
                val icon = viewModel.iconFile(app!!)
                if (icon != null) {
                    AsyncImage(
                        model = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp).appBorder(1.dp, BonyColors.Rule),
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                text = app?.name ?: "webxdc",
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    text = "⋯",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("add as tool", style = BonyType.body.copy(color = BonyColors.Text)) },
                        onClick = {
                            menuOpen = false
                            showToolDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("reload", style = BonyType.body.copy(color = BonyColors.Text)) },
                        onClick = {
                            menuOpen = false
                            webViewState.value?.reload()
                        },
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(1.dp),
                color = BonyColors.Accent,
                trackColor = BonyColors.Rule,
            )
        }

        val current = app
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (libraryLoaded) "app not found" else "opening…",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            return@Column
        }
        if (bridge == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("preparing…", style = BonyType.meta.copy(color = BonyColors.TextMute))
            }
            return@Column
        }

        val originHost = "${current.id}.xdc.local"
        val shim = remember(selfAddr, selfName) { WebxdcShim.build(selfAddr, selfName) }

        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.userAgentString = settings.userAgentString + " TBoneWebxdc/1.0"
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setGeolocationEnabled(false)
                        settings.safeBrowsingEnabled = true
                        settings.textZoom = 100
                        settings.setSaveFormData(false)
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false

                        addJavascriptInterface(bridge, WebxdcShim.BRIDGE_NAME)

                        webViewClient = XdcWebViewClient(
                            originHost = originHost,
                            filesDir = viewModel.filesDir(),
                            shim = shim,
                            onExternalUrl = { uri ->
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(
                                consoleMessage: android.webkit.ConsoleMessage?,
                            ): Boolean {
                                Timber.d(
                                    "webxdc console: %s (%s:%d)",
                                    consoleMessage?.message(),
                                    consoleMessage?.sourceId(),
                                    consoleMessage?.lineNumber() ?: 0,
                                )
                                return true
                            }

                            override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: JsResult?,
                            ): Boolean {
                                if (result == null) return false
                                jsPrompt = JsPrompt.Alert(message.orEmpty(), result)
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: JsResult?,
                            ): Boolean {
                                if (result == null) return false
                                jsPrompt = JsPrompt.Confirm(message.orEmpty(), result)
                                return true
                            }

                            override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: JsPromptResult?,
                            ): Boolean {
                                if (result == null) return false
                                promptValue = defaultValue.orEmpty()
                                jsPrompt = JsPrompt.Prompt(message.orEmpty(), defaultValue.orEmpty(), result)
                                return true
                            }
                        }

                        webViewState.value = this
                        loadUrl("https://$originHost/index.html")
                    }
                },
                onRelease = { view ->
                    view.removeJavascriptInterface(WebxdcShim.BRIDGE_NAME)
                    view.loadUrl("about:blank")
                    view.destroy()
                    webViewState.value = null
                },
            )
        }
    }

    val currentApp = app
    if (showToolDialog && currentApp != null) {
        XdcAddToolDialog(
            app = currentApp,
            existing = viewModel.toolFor(currentApp.id),
            onDismiss = { showToolDialog = false },
            onConfirm = { name, iconId ->
                viewModel.addAsTool(currentApp, name, iconId)
                showToolDialog = false
                Toast.makeText(context, "added to the toolbox", Toast.LENGTH_SHORT).show()
            },
        )
    }

    when (val prompt = jsPrompt) {
        null -> Unit
        is JsPrompt.Alert -> AlertDialog(
            onDismissRequest = {
                prompt.result.confirm()
                jsPrompt = null
            },
            title = { Text("mini app", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = { Text(prompt.message, style = BonyType.meta.copy(color = BonyColors.TextDim)) },
            confirmButton = {
                TextButton(onClick = {
                    prompt.result.confirm()
                    jsPrompt = null
                }) { Text("ok") }
            },
            containerColor = BonyColors.Surface,
        )
        is JsPrompt.Confirm -> AlertDialog(
            onDismissRequest = {
                prompt.result.cancel()
                jsPrompt = null
            },
            title = { Text("mini app", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = { Text(prompt.message, style = BonyType.meta.copy(color = BonyColors.TextDim)) },
            confirmButton = {
                TextButton(onClick = {
                    prompt.result.confirm()
                    jsPrompt = null
                }) { Text("ok") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prompt.result.cancel()
                    jsPrompt = null
                }) { Text("cancel") }
            },
            containerColor = BonyColors.Surface,
        )
        is JsPrompt.Prompt -> AlertDialog(
            onDismissRequest = {
                prompt.result.cancel()
                jsPrompt = null
            },
            title = { Text("mini app", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = {
                Column {
                    Text(prompt.message, style = BonyType.meta.copy(color = BonyColors.TextDim))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = promptValue,
                        onValueChange = { promptValue = it },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt.result.confirm(promptValue)
                    jsPrompt = null
                }) { Text("ok") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prompt.result.cancel()
                    jsPrompt = null
                }) { Text("cancel") }
            },
            containerColor = BonyColors.Surface,
        )
    }
}

// ── File bridge ──────────────────────────────────────────────────────────────

private suspend fun filesJson(context: Context, uris: List<Uri>): String = withContext(Dispatchers.IO) {
    buildJsonArray {
        var total = 0L
        for (uri in uris.take(16)) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: continue
            if (bytes.size > 8 * 1024 * 1024) continue
            total += bytes.size
            if (total > 24 * 1024 * 1024) break
            addJsonObject {
                put("name", displayName(context, uri))
                put("type", context.contentResolver.getType(uri) ?: "application/octet-stream")
                put("size", bytes.size)
                put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
    }.toString()
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "file"

/**
 * `webxdc.sendToChat()` maps to Android's share sheet: the mini app hands us a
 * file and/or text, we write it to a FileProvider-backed cache dir and let the
 * user send it wherever they want. There are no chats inside T-Bone, so this is
 * the closest single-player equivalent of "send to chat".
 */
private fun shareToChat(context: Context, messageJson: String): Boolean {
    val obj = Json.parseToJsonElement(messageJson).jsonObject
    val text = (obj["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val fileObj = obj["file"]?.jsonObject

    var stream: Uri? = null
    var mime = "text/plain"

    if (fileObj != null) {
        val rawName = (fileObj["name"] as? JsonPrimitive)?.content ?: "file"
        val name = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "file" }
        val base64 = (fileObj["base64"] as? JsonPrimitive)?.content
        val plainText = (fileObj["plainText"] as? JsonPrimitive)?.content
        val bytes: ByteArray? = when {
            base64 != null -> Base64.decode(base64, Base64.DEFAULT)
            plainText != null -> plainText.toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bytes != null) {
            val dir = File(context.cacheDir, "webxdc_share").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}_$name")
            file.writeBytes(bytes)
            stream = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            mime = context.contentResolver.getType(stream) ?: mimeFor(name)
        }
    }

    if (stream == null && text == null) return false

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let {
            putExtra(Intent.EXTRA_STREAM, it)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share mini app file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip", "xdc" -> "application/zip"
    "txt", "md" -> "text/plain"
    "json" -> "application/json"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}
