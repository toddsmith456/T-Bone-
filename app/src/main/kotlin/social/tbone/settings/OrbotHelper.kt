package social.tbone.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.tbone.Tunables
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

const val ORBOT_PACKAGE = "org.torproject.android"
private const val PROXY_HOST = "127.0.0.1"
private const val CONNECT_TIMEOUT_MS = 2_000

/** Official Orbot download page, including Guardian Project F-Droid setup. */
const val ORBOT_DOWNLOAD_URL = "https://orbot.app/en/download/"

/** Returns false when no app can handle the HTTPS installation page. */
fun openOrbotInstallPage(context: Context): Boolean = try {
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ORBOT_DOWNLOAD_URL)))
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

sealed class OrbotStatus {
    /**
     * Orbot is installed and a proxy is reachable.
     * [proxyType] is HTTP (preferred, no DNS leak) or SOCKS (fallback).
     * [port] is the port confirmed reachable.
     */
    data class InstalledAndConnected(val proxyType: Proxy.Type, val port: Int) : OrbotStatus()
    /** Orbot is installed but not running — user needs to start it. */
    object InstalledNotRunning : OrbotStatus()
    /** Orbot is not installed — offer the official download page. */
    object NotInstalled : OrbotStatus()
}

object OrbotHelper {
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun isPortReachable(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(PROXY_HOST, port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * Probes Orbot proxy endpoints in preference order:
     * 1. HTTP CONNECT on 8118 — preferred; hostnames resolved inside Tor, no DNS leak.
     * 2. SOCKS on 9050/9150 — fallback for configs that disable the HTTP proxy.
     *
     * Returns null if nothing is reachable (Orbot not running, VPN-only mode, etc.).
     */
    suspend fun findProxy(): Pair<Proxy.Type, Int>? = withContext(Dispatchers.IO) {
        if (isPortReachable(Tunables.TOR_HTTP_PROXY_PORT))
            return@withContext Proxy.Type.HTTP to Tunables.TOR_HTTP_PROXY_PORT
        val socksPort = Tunables.TOR_SOCKS_PORTS.firstOrNull { isPortReachable(it) }
            ?: return@withContext null
        Proxy.Type.SOCKS to socksPort
    }

    suspend fun getStatus(context: Context): OrbotStatus {
        if (!isInstalled(context)) return OrbotStatus.NotInstalled
        val (type, port) = findProxy() ?: return OrbotStatus.InstalledNotRunning
        return OrbotStatus.InstalledAndConnected(type, port)
    }
}
