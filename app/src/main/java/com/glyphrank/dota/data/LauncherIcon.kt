package com.glyphrank.dota.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting
import android.util.Log
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState

/**
 * "App icon shows my medal": Android can't give an app an arbitrary launcher bitmap, so the
 * manifest has one launcher activity-alias per icon and exactly one of them is enabled.
 * The default alias shows the Immortal medal and is also used for Immortal and uncalibrated.
 */
object LauncherIcon {
    private const val TAG = "LauncherIcon"
    private const val DEFAULT_ALIAS = "com.glyphrank.dota.ui.Launcher"

    /** Medals with their own alias (Herald … Divine). */
    private val MEDAL_ALIASES = Medal.entries.filter { it != Medal.IMMORTAL }

    fun aliasFor(state: RankState?, showMedal: Boolean): String {
        val medal = (state as? RankState.Ranked)?.medal
        return if (showMedal && medal != null) DEFAULT_ALIAS + medal.displayName else DEFAULT_ALIAS
    }

    private val allAliases = listOf(DEFAULT_ALIAS) + MEDAL_ALIASES.map { DEFAULT_ALIAS + it.displayName }

    /**
     * Enables the launcher alias for [state] and disables the others, in one atomic change.
     * Does nothing if that alias is already the enabled one, so the launcher only
     * refreshes when the medal actually changes.
     */
    fun update(context: Context, state: RankState?, showMedal: Boolean) {
        val pm = context.packageManager
        val wanted = aliasFor(state, showMedal)
        val changes = allAliases.mapNotNull { alias ->
            val component = ComponentName(context, alias)
            val enable = alias == wanted
            if (isEnabled(pm, component, alias) == enable) return@mapNotNull null
            ComponentEnabledSetting(
                component,
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        if (changes.isEmpty()) return
        Log.d(TAG, "Launcher icon -> ${wanted.substringAfterLast('.')}")
        pm.setComponentEnabledSettings(changes)
    }

    private fun isEnabled(pm: PackageManager, component: ComponentName, alias: String): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == DEFAULT_ALIAS // as in the manifest
            else -> false
        }
}
