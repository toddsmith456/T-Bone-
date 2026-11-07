package social.tbone.webxdc

import android.webkit.ServiceWorkerController
import android.webkit.WebView

/** The mini-app player is the only WebView user in this app. Interception and
 * CSP alone do not cover WebSockets and all service-worker traffic. */
internal fun configureXdcNetworkIsolation(view: WebView) {
    view.settings.blockNetworkLoads = true
    ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
        blockNetworkLoads = true
        allowFileAccess = false
        allowContentAccess = false
    }
}

internal fun isXdcOrigin(scheme: String?, host: String?, port: Int, userInfo: String?, expectedHost: String): Boolean =
    scheme.equals("https", ignoreCase = true) && host.equals(expectedHost, ignoreCase = true) &&
        (port == -1 || port == 443) && userInfo == null
