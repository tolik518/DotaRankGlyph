package com.glyphrank.dota.glyph

import com.glyphrank.dota.util.FakeScheduler
import com.glyphrank.dota.util.MainThread
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReloadShakeTest {
    private val main = FakeScheduler()

    @Before fun setUp() {
        MainThread.delegate = main
    }

    @After fun tearDown() {
        main.advanceBy(60_000)
        MainThread.delegate = null
    }

    @Test fun `a slow reload shakes for at most about 10 s`() {
        val token = ReloadShake.start()
        main.advanceBy(9_000)
        assertTrue(ReloadShake.isShaking)
        main.advanceBy(1_500)
        assertFalse(ReloadShake.isShaking) // OpenDota may still be answering; the medal settles anyway
        ReloadShake.finish(token) // the late result doesn't restart or break anything
        assertFalse(ReloadShake.isShaking)
    }

    @Test fun `a quick reload stops after its cycle`() {
        val token = ReloadShake.start()
        main.advanceBy(100)
        ReloadShake.finish(token)
        main.advanceBy(RankRenderer.SHAKE_FRAME_MS * RankRenderer.SHAKE_CYCLE_STEPS)
        assertFalse(ReloadShake.isShaking)
    }
}
