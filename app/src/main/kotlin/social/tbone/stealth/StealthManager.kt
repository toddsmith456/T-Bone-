package social.tbone.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Launcher aliases are bundled resources: arbitrary bitmaps cannot be launcher icons. */
@Singleton
class StealthManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher_appearance", Context.MODE_PRIVATE)
    private fun component(suffix: String) = ComponentName(context.packageName, "${social.tbone.MainActivity::class.java.`package`!!.name}.$suffix")
    private val regularAliases = listOf("LauncherTBone", "LauncherAlternative") +
        (0..3).flatMap { bg -> (0..3).map { fg -> "LauncherColor$bg$fg" } }
    private val allAliases get() = regularAliases + "LauncherNotepad"

    fun selectedAlias(): String = prefs.getString("alias", "LauncherTBone")
        ?.takeIf { it in regularAliases } ?: "LauncherTBone"

    fun isNotepadActive(): Boolean = context.packageManager.getComponentEnabledSetting(component("LauncherNotepad")) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Enable the destination before disabling others, to avoid losing the launcher. */
    private fun switchTo(destination: String) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(destination), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        allAliases.filter { it != destination }.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    fun setNotepadFront(enableNotepad: Boolean) = switchTo(if (enableNotepad) "LauncherNotepad" else selectedAlias())

    /** Save only after PackageManager accepts the requested launcher. */
    fun setRegularIcon(alias: String) {
        require(alias in regularAliases)
        if (!isNotepadActive()) switchTo(alias)
        check(prefs.edit().putString("alias", alias).commit())
    }
}
