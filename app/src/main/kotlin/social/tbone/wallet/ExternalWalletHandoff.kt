package social.tbone.wallet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Android intent hand-off for an invoice/address; it never sends a payment. */
object ExternalWalletHandoff {
    fun openLightningWallet(context: Context, invoice: String): Boolean =
        openUri(context, "lightning:${invoice.trim()}", "Choose a Lightning wallet")

    fun openMoneroWallet(context: Context, address: String): Boolean =
        openUri(context, WalletPaymentUris.moneroAddress(address), "Choose a Monero wallet")

    private fun openUri(context: Context, uri: String, chooserTitle: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (!hasHandler(context, intent)) return false
        return try {
            val chooser = Intent.createChooser(intent, chooserTitle)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun hasHandler(context: Context, intent: Intent): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ).isNotEmpty()
        } else {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        }
}
