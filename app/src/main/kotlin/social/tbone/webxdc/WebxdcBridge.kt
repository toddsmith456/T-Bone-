package social.tbone.webxdc

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Native side of the webxdc.js bridge.
 *
 * A single [call] entry point keeps the JavaScript surface tiny: the injected
 * shim turns every `window.webxdc.*` call into `TBoneXdc.call(method, id, json)`
 * and native answers with `window.__tboneXdcResolve(id, ok, result, error)` or
 * pushes state with `window.__tboneXdcDeliver(update)`.
 *
 * This player is intentionally single-user: updates are written to a local
 * journal instead of being published to relays, and `importFiles`/`sendToChat`
 * are handed to the host Activity (SAF picker / share sheet).
 */
class WebxdcBridge(
    private val store: XdcUpdateStore,
    private val selfAddr: String,
    private val selfName: String,
    private val host: Host,
) {
    interface Host {
        /** Runs JavaScript on the WebView (marshalled to the main thread by the host). */
        fun post(js: String)

        /** Opens a document picker; the host resolves [requestId] when the user is done. */
        fun importFiles(requestId: Int, filterJson: String)

        /** Saves the message and opens Android's share sheet; resolves [requestId]. */
        fun sendToChat(requestId: Int, messageJson: String)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    val selfIdentity: Pair<String, String> get() = selfAddr to selfName

    @JavascriptInterface
    fun call(method: String, id: Int, paramsJson: String) {
        try {
            val params = runCatching {
                json.parseToJsonElement(paramsJson).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            when (method) {
                "sendUpdate" -> sendUpdate(id, params)
                "setUpdateListener" -> setUpdateListener(id, params)
                "getAllUpdates" -> resolve(id, updatesJson())
                "importFiles" -> host.importFiles(id, params["filter"]?.toString() ?: "{}")
                "sendToChat" -> host.sendToChat(id, params.toString())
                "realtimeJoin", "realtimeLeave" -> resolve(id, "null")
                else -> reject(id, "unsupported webxdc method: $method")
            }
        } catch (t: Throwable) {
            reject(id, t.message ?: "webxdc bridge error")
        }
    }

    private fun sendUpdate(requestId: Int, params: Map<String, JsonElement>) {
        val update = store.append(
            payloadJson = (params["payload"] ?: JsonNull).toString(),
            info = params["info"].asStringOrNull(),
            document = params["document"].asStringOrNull(),
            summary = params["summary"].asStringOrNull(),
            href = params["href"].asStringOrNull(),
        )
        deliver(update)
        resolve(requestId, "null")
    }

    private fun setUpdateListener(requestId: Int, params: Map<String, JsonElement>) {
        val after = params["serial"].asStringOrNull()?.toIntOrNull() ?: 0
        store.load().filter { it.serial > after }.forEach { deliver(it) }
        // Resolve after the replay so the listener promise settles once every
        // update known at call time has been handed to the app.
        resolve(requestId, "null")
    }

    private fun updatesJson(): String = buildJsonArray {
        val all = store.load()
        all.forEach { update ->
            addJsonObject {
                put("payload", runCatching { json.parseToJsonElement(update.payloadJson) }.getOrDefault(JsonNull))
                put("serial", update.serial)
                put("max_serial", all.size)
                update.info?.let { put("info", it) }
                update.document?.let { put("document", it) }
                update.summary?.let { put("summary", it) }
                update.href?.let { put("href", it) }
            }
        }
    }.toString()

    private fun deliver(update: XdcUpdate) = synchronized(lock) {
        val payload = buildJsonObject {
            put("payload", runCatching { json.parseToJsonElement(update.payloadJson) }.getOrDefault(JsonNull))
            put("serial", update.serial)
            put("max_serial", store.load().size)
            update.info?.let { put("info", it) }
            update.document?.let { put("document", it) }
            update.summary?.let { put("summary", it) }
            update.href?.let { put("href", it) }
        }
        host.post("window.__tboneXdcDeliver($payload);")
        Unit
    }

    fun resolve(id: Int, resultJson: String?) {
        host.post("window.__tboneXdcResolve($id, true, ${jsQuote(resultJson ?: "null")}, null);")
    }

    fun reject(id: Int, error: String) {
        host.post("window.__tboneXdcResolve($id, false, null, ${jsQuote(error)});")
    }

    companion object {
        /** Escapes a Kotlin string into a safe JavaScript/JSON string literal. */
        fun jsQuote(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\u2028' -> sb.append("\\u2028")
                    '\u2029' -> sb.append("\\u2029")
                    '<' -> sb.append("\\u003c")
                    '>' -> sb.append("\\u003e")
                    '&' -> sb.append("\\u0026")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun JsonElement?.asStringOrNull(): String? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            return primitive.content.takeIf { it.isNotEmpty() }
        }
    }
}
