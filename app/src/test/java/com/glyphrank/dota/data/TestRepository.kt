package com.glyphrank.dota.data

import android.content.Context
import android.os.Looper
import com.glyphrank.dota.util.MainThread
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.Executor

/**
 * For Robolectric tests: puts a [RankRepository] into [RankRepository.instance] that works
 * like the app's (real store, launcher icon, widgets, main thread) but asks [server] instead
 * of OpenDota, runs its requests only when a test calls [finishRequests] and has its own clock.
 */
class TestRepository(context: Context, val server: MockHttpServer = MockHttpServer()) : AutoCloseable {
    private val app = context.applicationContext
    private val ioQueue = ArrayDeque<Runnable>()
    private var online = true

    /**
     * The repository's clock: 1 s into the current minute (so a test's few seconds don't cross
     * OpenDota's minute reset), moved on by [idleFor].
     */
    var now = System.currentTimeMillis() / 60_000 * 60_000 + 1_000

    val repo = RankRepository(
        store = RankStore(app),
        client = OpenDotaClient(baseUrl = "${server.baseUrl}/api", connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
        io = Executor { ioQueue += it },
        main = MainThread,
        clock = { now },
        medals = { BundledMedals.load(app) },
        applyLauncherIcon = { state, showMedal -> LauncherIcon.update(app, state, showMedal) },
        updateWidgets = { com.glyphrank.dota.widget.RankWidget.updateAll(app) },
        canUseNetworkNow = { online },
        scheduleBackgroundFetch = { com.glyphrank.dota.widget.RankRefreshJob.scheduleFetchNow(app) },
    )

    val store: RankStore get() = repo.store

    init {
        MainThread.delegate = null // the real main looper, which Robolectric runs
        RankRepository.instance = repo
    }

    /** Runs the queued requests, then the main-thread work they posted. */
    fun finishRequests() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        idle()
    }

    /** Runs the main-thread work that is due now. */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Moves the main thread's clock on (shakes, animations, button cooldown). */
    fun idleFor(ms: Long) {
        now += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    override fun close() {
        idleFor(60_000) // shakes and animations are process-wide; let them end
        RankRepository.instance = null
        server.close()
    }

    companion object {
        /** An OpenDota player body. */
        fun player(accountId: Long, name: String?, rankTier: Int?, leaderboardRank: Int? = null): String {
            val profile = """{"account_id":$accountId,"personaname":${name?.let { "\"$it\"" }}}"""
            return """{"profile":$profile,"rank_tier":$rankTier,"leaderboard_rank":$leaderboardRank}"""
        }
    }
}
