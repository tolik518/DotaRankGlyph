package com.glyphrank.dota.data

import android.content.ComponentName
import android.content.pm.PackageManager
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [LauncherIcon.update] against the package manager, with the aliases from the real manifest. */
@RunWith(RobolectricTestRunner::class)
class LauncherIconUpdateTest {
    private val app = RuntimeEnvironment.getApplication()
    private val pm = app.packageManager
    private val aliases = listOf("") + Medal.entries.filter { it != Medal.IMMORTAL }.map { it.displayName }

    /** The launcher entries that are on, e.g. ["Legend"]; "" is the default icon. */
    private fun enabled(): List<String> = aliases.filter { suffix ->
        val component = ComponentName(app, "com.glyphrank.dota.ui.Launcher$suffix")
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> pm.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            else -> false
        }
    }

    @Test fun `out of the box only the default icon is on`() {
        assertEquals(listOf(""), enabled())
    }

    @Test fun `exactly one icon is on as the medal changes`() {
        LauncherIcon.update(app, RankState.Ranked(Medal.LEGEND, 4), showMedal = true)
        assertEquals(listOf("Legend"), enabled())
        LauncherIcon.update(app, RankState.Ranked(Medal.ANCIENT, 1), showMedal = true)
        assertEquals(listOf("Ancient"), enabled())
        LauncherIcon.update(app, RankState.Immortal(2488), showMedal = true)
        assertEquals(listOf(""), enabled())
    }

    @Test fun `turning the setting off brings back the default icon`() {
        LauncherIcon.update(app, RankState.Ranked(Medal.HERALD, 2), showMedal = true)
        LauncherIcon.update(app, RankState.Ranked(Medal.HERALD, 2), showMedal = false)
        assertEquals(listOf(""), enabled())
    }
}
