package social.tbone.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StealthManagerTest {
    @Test fun switchesManifestAliasesEvenWhenApplicationIdDiffersFromNamespace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = StealthManager(context)
        val pm = context.packageManager
        val normal = ComponentName(context.packageName, "social.tbone.LauncherTBone")
        val disguise = ComponentName(context.packageName, "social.tbone.LauncherNotepad")
        val oldNormal = pm.getComponentEnabledSetting(normal)
        val oldDisguise = pm.getComponentEnabledSetting(disguise)
        try {
            manager.setNotepadFront(true)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(normal))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(disguise))
            manager.setNotepadFront(false)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(normal))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(disguise))
        } finally {
            pm.setComponentEnabledSetting(normal, oldNormal, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(disguise, oldDisguise, PackageManager.DONT_KILL_APP)
        }
    }
    @Test fun selectedIconIsRestoredAfterDisguise() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = StealthManager(context)
        val previous = manager.selectedAlias()
        try {
            manager.setNotepadFront(false)
            manager.setRegularIcon("LauncherColor12")
            assertEquals("LauncherColor12", manager.selectedAlias())
            manager.setNotepadFront(true)
            assertEquals(true, manager.isNotepadActive())
            manager.setNotepadFront(false)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, "social.tbone.LauncherColor12")))
        } finally {
            manager.setNotepadFront(false)
            manager.setRegularIcon(previous)
        }
    }
}
