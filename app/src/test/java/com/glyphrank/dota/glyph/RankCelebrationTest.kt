package com.glyphrank.dota.glyph

import com.glyphrank.dota.util.FakeScheduler
import com.glyphrank.dota.util.MainThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RankCelebrationTest {
    private val main = FakeScheduler()
    private val shown = mutableListOf<Int>()
    private var ends = 0
    private val listener = object : RankCelebration.Listener {
        override fun onCelebrationFrame(frame: IntArray) {
            shown += frame[0]
        }

        override fun onCelebrationEnd() {
            ends++
        }
    }

    /** Frames tagged 1, 2, 3 … in pixel 0, 100 ms each. */
    private fun frames(vararg tags: Int) = tags.map { tag -> AnimationFrame(IntArray(625).also { it[0] = tag }, 100) }

    @Before fun setUp() {
        MainThread.delegate = main
        RankCelebration.addListener(listener)
    }

    @After fun tearDown() {
        main.advanceBy(60_000)
        RankCelebration.removeListener(listener)
        MainThread.delegate = null
    }

    @Test fun `frames play in order, then the viewers are told it ended`() {
        RankCelebration.play(frames(1, 2, 3))
        assertTrue(RankCelebration.isPlaying)
        main.advanceBy(1_000)
        assertEquals(listOf(1, 2, 3), shown)
        assertEquals(1, ends)
        assertFalse(RankCelebration.isBusy)
    }

    @Test fun `a new animation replaces the running one`() {
        RankCelebration.play(frames(1, 2, 3))
        main.advanceBy(150) // frames 1 and 2 shown
        RankCelebration.play(frames(7, 8))
        main.advanceBy(1_000)
        assertEquals(listOf(1, 2, 7, 8), shown)
        assertEquals(1, ends)
    }

    @Test fun `an empty animation does nothing`() {
        RankCelebration.play(emptyList())
        assertFalse(RankCelebration.isBusy)
        main.advanceBy(1_000)
        assertEquals(0, ends)
    }

    @Test fun `waits for a running reload shake to finish its cycle`() {
        val token = ReloadShake.start()
        main.advanceBy(100)
        RankCelebration.play(frames(1, 2))
        assertTrue(RankCelebration.isBusy) // viewers must not draw over it
        assertFalse(RankCelebration.isPlaying)
        main.advanceBy(RankRenderer.SHAKE_FRAME_MS)
        assertTrue(shown.isEmpty())

        ReloadShake.finish(token)
        while (ReloadShake.isShaking) main.advanceBy(RankRenderer.SHAKE_FRAME_MS)
        assertTrue(RankCelebration.isPlaying) // the viewers' shake listeners now let it through
        main.advanceBy(1_000)
        assertEquals(listOf(1, 2), shown)
        assertEquals(1, ends)
    }

    @Test fun `only the toy's listener counts as the glyph listening`() {
        assertFalse(RankCelebration.glyphListening)
        val toy = object : RankCelebration.Listener {
            override fun onCelebrationFrame(frame: IntArray) = Unit
            override fun onCelebrationEnd() = Unit
        }
        RankCelebration.addListener(toy, glyph = true)
        assertTrue(RankCelebration.glyphListening)
        RankCelebration.removeListener(toy)
        assertFalse(RankCelebration.glyphListening)
    }
}
