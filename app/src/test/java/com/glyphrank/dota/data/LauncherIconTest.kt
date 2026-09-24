package com.glyphrank.dota.data

import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LauncherIconTest {
    private val default = "com.glyphrank.dota.ui.Launcher"

    @Test fun `setting off always uses the default icon`() {
        assertEquals(default, LauncherIcon.aliasFor(RankState.Ranked(Medal.GUARDIAN, 4), showMedal = false))
    }

    @Test fun `setting on uses the medal, default for immortal and uncalibrated`() {
        assertEquals("$default" + "Guardian", LauncherIcon.aliasFor(RankState.Ranked(Medal.GUARDIAN, 4), true))
        assertEquals("$default" + "Herald", LauncherIcon.aliasFor(RankState.Ranked(Medal.HERALD, 0), true))
        assertEquals(default, LauncherIcon.aliasFor(RankState.Immortal(2488), true))
        assertEquals(default, LauncherIcon.aliasFor(RankState.Uncalibrated, true))
        assertEquals(default, LauncherIcon.aliasFor(null, true))
    }

    @Test fun `every alias is declared in the manifest with its icon`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (medal in Medal.entries.filter { it != Medal.IMMORTAL }) {
            val alias = LauncherIcon.aliasFor(RankState.Ranked(medal, 1), true).removePrefix("com.glyphrank.dota")
            val block = manifest.substringAfter("android:name=\"$alias\"", "").substringBefore("</activity-alias>")
            assertEquals(alias, true, block.contains("android:icon=\"@mipmap/ic_launcher_${medal.name.lowercase()}\""))
            assertEquals(alias, true, block.contains("android:enabled=\"false\""))
            assertEquals(true, File("src/main/res/mipmap-anydpi/ic_launcher_${medal.name.lowercase()}.xml").exists())
        }
    }
}
