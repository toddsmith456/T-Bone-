package social.tbone.ui.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Imperative helper for "ask for every permission the tapped feature needs,
 * then actually run it once granted."
 *
 * Handles the three real-world outcomes:
 *  - all granted  -> the pending action runs immediately,
 *  - denied once  -> a one-line message tells the user what was refused,
 *  - denied twice / "don't ask again" -> the OS won't show a dialog anymore,
 *    so we flag those as permanently denied and the UI can offer an
 *    "open settings" deep link instead of silently doing nothing.
 */
class PermissionRequester(
    private val context: Context,
    private val launch: (Array<String>) -> Unit,
) {
    /** Permissions the OS refuses to ask about again (user chose don't-ask / denied twice). */
    var permanentlyDenied by mutableStateOf<List<String>>(emptyList())
        private set

    /** Message for a one-time denial (the user can still be re-prompted). */
    var lastDenial by mutableStateOf<String?>(null)
        private set

    private var pendingAction: (() -> Unit)? = null
    private var pendingOptional: Set<String> = emptySet()

    /**
     * If any of [permissions] is missing, request them all; then run [action].
     * Permissions listed in [optional] do not block the action when denied
     * (e.g. POST_NOTIFICATIONS — the feature still works without it).
     */
    fun requestOrRun(permissions: List<String>, action: () -> Unit, optional: List<String> = emptyList()) {
        if (permissions.isEmpty()) {
            action()
            return
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAction = action
        pendingOptional = optional.toSet()
        lastDenial = null
        launch(missing.toTypedArray())
    }

    /** Called when the OS permission dialog resolves. */
    fun onResult(result: Map<String, Boolean>) {
        val action = pendingAction
        pendingAction = null
        val optional = pendingOptional
        pendingOptional = emptySet()
        val denied = result.filterValues { !it }.keys.toList()
        // Only permissions that were NOT marked optional block the action.
        val blocking = denied.filter { it !in optional }
        if (blocking.isEmpty()) {
            permanentlyDenied = emptyList()
            lastDenial = null
            action?.invoke()
            return
        }
        // A permission is permanently blocked when the OS would no longer show
        // a rationale/dialog for it (denied twice or "don't ask again").
        val permanent = blocking.filter { perm ->
            val activity = context as? android.app.Activity
            if (activity == null) false
            else !activity.shouldShowRequestPermissionRationale(perm)
        }
        if (permanent.isNotEmpty()) {
            permanentlyDenied = (permanentlyDenied + permanent).distinct()
        }
        lastDenial = "permission needed: " + blocking.joinToString(", ") { it.substringAfterLast('.') }
    }

    /** Re-checks flagged permissions (e.g. after the user returns from Settings). */
    fun refresh() {
        val stillBlocked = permanentlyDenied.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        permanentlyDenied = stillBlocked
        if (stillBlocked.isEmpty()) lastDenial = null
    }

    /** Deep link to this app's permission page in system Settings. */
    fun openSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
    }
}

/** Composable that owns the permission launcher and returns a [PermissionRequester]. */
@Composable
fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    // Holder breaks the circular reference: the launcher callback needs the
    // requester, and the requester needs the launcher.
    val holder = remember { arrayOfNulls<PermissionRequester>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        holder[0]?.onResult(result)
    }
    val requester = remember(context, launcher) {
        PermissionRequester(context) { perms -> launcher.launch(perms) }
    }
    holder[0] = requester
    return requester
}
