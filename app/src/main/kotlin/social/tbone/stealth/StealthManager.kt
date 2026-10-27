package social.tbone.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toggles the launcher icon between T-Bone and the Notepad disguise.
 *
 * Manifest defines two <activity-alias> entries:
 *  - social.tbone.LauncherTBone    (enabled by default, @mipmap/ic_launcher)
 *  - social.tbone.LauncherNotepad  (disabled  by default, @mipmap/ic_notepad)
 * Both aliases target [social.tbone.MainActivity].
 *
 * We use [PackageManager.setComponentEnabledSetting] with DONT_KILL_APP so
 * the current process survives the swap. The new icon takes effect after the
 * launcher re-queries activities (usually immediate, worst-case next reboot).
 */
@Singleton
class StealthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val packageName: String get() = context.packageName

    private fun component(suffix: String) = ComponentName(packageName, "$packageName.$suffix")

    /** True if Notepad alias is currently the enabled one. */
    fun isNotepadActive(): Boolean {
        val pm = context.packageManager
        val tBone = pm.getComponentEnabledSetting(component("LauncherTBone"))
        // Default (DEFAULT) means manifest value: T-Bone enabled, Notepad disabled.
        if (tBone == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return true
        val notepad = pm.getComponentEnabledSetting(component("LauncherNotepad"))
        return notepad == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    /**
     * Switch launcher alias. [enableNotepad] true → show Notepad icon/label,
     * false → restore T-Bone icon/label.
     */
    fun setNotepadFront(enableNotepad: Boolean) {
        val pm = context.packageManager
        try {
            pm.setComponentEnabledSetting(
                component("LauncherTBone"),
                if (enableNotepad) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                component("LauncherNotepad"),
                if (enableNotepad) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (_: Exception) {
            // Some OEM launchers cache aggressively — failure is non-fatal.
        }
    }
}
