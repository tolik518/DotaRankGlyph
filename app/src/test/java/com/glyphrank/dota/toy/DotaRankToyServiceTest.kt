package com.glyphrank.dota.toy

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.PlayerRank
import com.glyphrank.dota.data.TestRepository
import com.glyphrank.dota.data.TestRepository.Companion.player
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.RankState
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import com.nothing.thirdparty.IGlyphService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The toy as Nothing's Glyph service drives it: bound by the system, connected through the
 * real Glyph SDK to a fake Glyph service that records the frames, Glyph Button events sent
 * to its Messenger.
 */
@RunWith(RobolectricTestRunner::class)
class DotaRankToyServiceTest {
    /** Nothing's Glyph service, as far as the SDK uses it. */
    private class FakeGlyphService : IGlyphService.Stub() {
        val frames: MutableList<IntArray> = java.util.Collections.synchronizedList(mutableListOf())
        val registered = mutableListOf<String>()

        override fun registerMatrixSDK(device: String): Boolean = true.also { registered += device }
        override fun setMatrixColors(colors: IntArray) {
            frames += colors.copyOf()
        }

        override fun setFrameColors(colors: IntArray) = Unit
        override fun openSession() = Unit
        override fun closeSession() = Unit
        override fun register(key: String) = true
        override fun registerSDK(key: String, device: String) = true
        override fun setGlyphMatrixTimeout(enabled: Boolean) = Unit
        override fun setAppMatrixColors(colors: IntArray) = Unit
        override fun closeAppMatrix() = Unit
    }

    private val app = RuntimeEnvironment.getApplication()
    private val env = TestRepository(app)
    private val glyph = FakeGlyphService()
    private val glyphComponent = ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.GlyphService")
    private lateinit var service: ServiceController<DotaRankToyService>
    private lateinit var events: Messenger
    private val renderer = RankRenderer()
    private val zq = 116233682L

    @Before fun setUp() {
        // The SDK keeps one manager per process, holding the context of an earlier test's app.
        GlyphMatrixManager::class.java.getDeclaredField("mInstance").apply { isAccessible = true }.set(null, null)
        shadowOf(app).setComponentNameAndServiceForBindService(glyphComponent, glyph)
        env.server.handler = { Response(200, player(zq, "ZQuixotix", 80, 2488)) }
    }

    @After fun tearDown() {
        service.get().onUnbind(Intent())
        service.destroy()
        env.close()
    }

    /** The user selects the toy: the system binds it, the SDK connects to the Glyph service. */
    private fun select() {
        service = Robolectric.buildService(DotaRankToyService::class.java).create()
        events = Messenger(service.get().onBind(Intent("com.nothing.glyph.TOY")))
        env.idle()
    }

    private fun send(event: String) {
        events.send(Message.obtain(null, GlyphToy.MSG_GLYPH_TOY).apply {
            data = Bundle().apply { putString(GlyphToy.MSG_GLYPH_TOY_DATA, event) }
        })
        env.idle()
    }

    private fun glyphConnection(): ServiceConnection = shadowOf(app).boundServiceConnections.single()

    private val medal get() = renderer.render(RankState.Immortal(2488), com.glyphrank.dota.data.BundledMedals.load(app))

    @Test fun `selecting the toy shows the saved medal on the phone (3) matrix`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        assertEquals(listOf(Glyph.DEVICE_23112), glyph.registered)
        assertArrayEquals(medal, glyph.frames.last())
        assertTrue(env.server.requests.isEmpty() && env.store.toyUsed)
    }

    @Test fun `a long-press of the glyph button checks the rank`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        send(GlyphToy.EVENT_CHANGE)
        env.finishRequests()
        env.idleFor(10_000)
        assertEquals(1, env.server.requests.size)
        assertArrayEquals(medal, glyph.frames.last())
    }

    @Test fun `the always-on tick fetches a stale rank`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        env.now += 2 * 24 * 60 * 60_000L
        send(GlyphToy.EVENT_AOD)
        env.finishRequests()
        assertEquals(1, env.server.requests.size)
    }

    @Test fun `short presses are ignored`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        val shown = glyph.frames.size
        send(GlyphToy.EVENT_ACTION_DOWN)
        send(GlyphToy.EVENT_ACTION_UP)
        env.idleFor(1_000)
        assertEquals(shown, glyph.frames.size)
        assertTrue(env.server.requests.isEmpty())
    }

    @Test fun `after the glyph service restarts, the toy shows the medal again`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        glyphConnection().onServiceDisconnected(glyphComponent)
        env.store.showImmortalRank = false // not drawn while disconnected
        val whileAway = glyph.frames.size
        glyphConnection().onServiceConnected(glyphComponent, glyph as IBinder)
        env.idle()
        assertEquals(whileAway + 1, glyph.frames.size)
        assertArrayEquals(renderer.render(RankState.Immortal(2488), com.glyphrank.dota.data.BundledMedals.load(app), false), glyph.frames.last())
    }

    @Test fun `a reconnect without a disconnect doesn't leave two toys listening`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        glyphConnection().onServiceConnected(glyphComponent, glyph as IBinder)
        env.idle()
        val shown = glyph.frames.size
        env.store.showImmortalRank = false
        assertEquals(shown + 1, glyph.frames.size) // one redraw, not one per connect
    }

    @Test fun `moving on to another toy turns the matrix off and stops drawing`() {
        env.store.accountId = zq
        env.store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        select()
        service.get().onUnbind(Intent())
        // The SDK turns the matrix off from its own thread.
        val deadline = System.currentTimeMillis() + 2_000
        while (glyph.frames.last().any { it != 0 } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(glyph.frames.last().all { it == 0 })
        val shown = glyph.frames.size
        env.store.showImmortalRank = false
        env.repo.refresh(manual = true)
        env.finishRequests()
        env.idleFor(10_000)
        assertEquals(shown, glyph.frames.size)
    }
}
