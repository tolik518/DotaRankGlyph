package com.glyphrank.dota.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankAnimation
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.util.MainThread
import com.glyphrank.dota.util.Scheduler
import com.glyphrank.dota.widget.RankRefreshJob
import com.glyphrank.dota.widget.RankWidget
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The one place that fetches ranks, shared by the settings screen and the toy service
 * (same process). Main thread only.
 *
 *  - Every request goes through [RefreshPolicy] (rate limits, backoff, minimum gap).
 *  - One request per account at a time: a second caller joins the running one.
 *  - Results and errors are saved in [RankStore]; [Listener]s hear about every change,
 *    whichever side started the refresh.
 *  - Manual reloads shake the last known medal via [ReloadShake] until the request is done.
 *  - A changed rank plays [RankAnimation] via [RankCelebration]; if the toy isn't on the
 *    Glyph, the change is kept for [playPendingCelebration].
 *  - In the background Android blocks this app's network (Android 15 "APP_BACKGROUND"), e.g.
 *    when the Glyph toy refreshes while the app isn't open. Then the request is queued and
 *    sent from a job ([runQueuedFetch]), which Android lets use the network.
 */
class RankRepository internal constructor(
    val store: RankStore,
    private val client: OpenDotaClient,
    private val io: Executor,
    private val main: Scheduler,
    private val clock: () -> Long,
    private val medals: () -> MedalArt?,
    /** Applies [LauncherIcon] for (current rank, "App icon shows my medal"). */
    private val applyLauncherIcon: (RankState?, Boolean) -> Unit,
    /** Redraws the home-screen widgets from the store. */
    private val updateWidgets: () -> Unit = {},
    /** False when the app can't use the network right now (blocked in the background, or offline). */
    private val canUseNetworkNow: () -> Boolean = { true },
    /** Schedules a job that calls [runQueuedFetch] as soon as it may use the network. */
    private val scheduleBackgroundFetch: () -> Unit = {},
) {
    interface Listener {
        /** A refresh saved a rank. [previous] is the last known rank of the same account, if any. */
        fun onRankSaved(previous: PlayerRank?, current: PlayerRank) {}

        /** Loading started or finished, an error was saved, or the account changed. */
        fun onStateChanged() {}
    }

    private val listeners = LinkedHashSet<Listener>()

    /** Running requests by account ID; the value is the shake token, if the request shakes. */
    private val inFlight = HashMap<Long, Int?>()

    /** An account whose request waits for the background job (see [runQueuedFetch]). */
    private var queued: Long? = null

    /** Gives up waiting for the job (no connection); the job still fetches once it runs. */
    private val queueTimeout = Runnable {
        val accountId = queued ?: return@Runnable
        queued = null
        Log.w(TAG, "No network for $accountId within ${QUEUE_TIMEOUT_MS / 1000} s")
        onFetched(accountId, Result.failure(OpenDotaException(OpenDotaException.Kind.NETWORK, "No internet connection")))
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** True while the current account's rank is being fetched. */
    val isLoading: Boolean get() = store.accountId?.let { it in inFlight } ?: false

    /** Switches to another account. Call [refresh] afterwards to fetch it. */
    fun setAccount(accountId: Long) {
        if (store.accountId == accountId) return
        store.accountId = accountId
        store.guard = store.guard.copy(failures = 0) // the backoff was for the old account
        if (store.appIconShowsMedal) updateLauncherIcon() // its cached medal, if any
        refreshWidgets()
        notifyState()
    }

    /**
     * Refreshes the current account's rank if [RefreshPolicy] allows it.
     * [manual]: the user asked (button, long-press); ignores the refresh interval and shakes
     * the last known medal while loading.
     */
    fun refresh(manual: Boolean): Decision = refresh(manual, fromJob = false)

    /**
     * Called by the background job: sends a queued request, or does a normal automatic
     * refresh. Returns true if a request is running (the job waits for it).
     */
    fun runQueuedFetch(): Boolean {
        main.remove(queueTimeout)
        val accountId = queued
        if (accountId != null) {
            queued = null
            startFetch(accountId, manual = true)
            return true
        }
        val decision = refresh(manual = false, fromJob = true)
        return decision == Decision.Fetch || decision == Decision.Joined
    }

    private fun refresh(manual: Boolean, fromJob: Boolean): Decision {
        val accountId = store.accountId ?: return Decision.NoAccount
        val cached = store.cachedForCurrentAccount()
        val shake = manual && cached != null
        if (accountId in inFlight) {
            if (shake && inFlight[accountId] == null) inFlight[accountId] = ReloadShake.start()
            return Decision.Joined
        }
        val now = clock()
        val decision = RefreshPolicy.decide(
            accountId, manual, cached?.fetchedAtMs, store.refreshIntervalMinutes, store.guard, now,
        )
        if (decision != Decision.Fetch) {
            if (!manual) Log.d(TAG, "Auto refresh skipped: $decision")
            return decision
        }

        inFlight[accountId] = if (shake) ReloadShake.start() else null
        if (fromJob || canUseNetworkNow()) {
            startFetch(accountId, manual)
        } else {
            // Blocked in the background (or offline): let a job send it.
            Log.d(TAG, "No network for the app right now; queueing $accountId for a job")
            store.guard = RefreshPolicy.attempt(store.guard, accountId, now) // counts for gap and backoff
            queued = accountId
            main.remove(queueTimeout)
            main.postDelayed(queueTimeout, QUEUE_TIMEOUT_MS)
            scheduleBackgroundFetch()
        }
        notifyState()
        return decision
    }

    private fun startFetch(accountId: Long, manual: Boolean) {
        Log.d(TAG, "Fetching $accountId (${if (manual) "manual" else "auto"})")
        store.guard = RefreshPolicy.attempt(store.guard, accountId, clock())
        io.execute {
            val result = runCatching { client.fetch(accountId) }
            main.post { onFetched(accountId, result) }
        }
    }

    private fun onFetched(accountId: Long, result: Result<PlayerFetch>) {
        val shakeToken = inFlight.remove(accountId)
        val now = clock()
        val current = store.accountId == accountId // otherwise the ID changed mid-request
        result
            .onSuccess { fetch ->
                Log.d(TAG, "Fetched $accountId, requests left: ${fetch.remainingMinute}/min, ${fetch.remainingDay}/day")
                store.guard = RefreshPolicy.afterSuccess(store.guard, fetch, now)
                if (current) {
                    val previous = store.cachedForCurrentAccount()?.player
                    store.save(fetch.player, now)
                    store.recentAccounts = RecentAccounts.add(
                        store.recentAccounts, RecentAccounts.Entry(accountId, fetch.player.personaName),
                    )
                    listeners.toList().forEach { it.onRankSaved(previous, fetch.player) }
                    if (previous != null) celebrate(previous, fetch.player)
                    if (store.appIconShowsMedal) updateLauncherIcon()
                }
            }
            .onFailure { e ->
                Log.w(TAG, "Rank refresh failed: ${e.message} (cause: ${e.cause})")
                store.guard = RefreshPolicy.afterFailure(store.guard, e, now)
                if (current) store.saveError(accountId, e.message ?: "Lookup failed", now, (e as? OpenDotaException)?.kind?.name)
            }
        shakeToken?.let(ReloadShake::finish) // the shake still finishes its cycle
        if (current) refreshWidgets()
        notifyState()
    }

    /** Points the launcher icon at the current medal (or the default icon if the setting is off). */
    fun updateLauncherIcon() {
        runCatching { applyLauncherIcon(store.cachedForCurrentAccount()?.player?.state, store.appIconShowsMedal) }
            .onFailure { Log.w(TAG, "Launcher icon update failed", it) }
    }

    /** Redraws the home-screen widgets, e.g. after a display setting changed. */
    fun refreshWidgets() {
        runCatching(updateWidgets).onFailure { Log.w(TAG, "Widget update failed", it) }
    }

    private val animation = RankAnimation()

    /** Animates [previous] → [current] wherever it is visible, and remembers it for the Glyph if needed. */
    private fun celebrate(previous: PlayerRank, current: PlayerRank) {
        val show = store.showImmortalRank
        if (RankAnimation.classify(previous.state, current.state, show) == RankAnimation.Change.NONE) return
        if (!RankCelebration.glyphListening) {
            // Keep the oldest "before" so several changes play as one when the toy shows up.
            val pending = store.pendingCelebration?.takeIf { it.accountId == current.accountId }
            if (pending == null) store.pendingCelebration = previous
        }
        RankCelebration.play(animation.frames(previous.state, current.state, medals(), show))
    }

    /** Called by the toy when it appears: plays a change that happened while it was away. */
    fun playPendingCelebration() {
        val pending = store.pendingCelebration ?: return
        store.pendingCelebration = null
        val current = store.cachedForCurrentAccount()?.player ?: return
        if (pending.accountId != current.accountId) return
        RankCelebration.play(
            animation.frames(pending.state, current.state, medals(), store.showImmortalRank),
        )
    }

    private fun notifyState() = listeners.toList().forEach { it.onStateChanged() }

    companion object {
        private const val TAG = "RankRepository"

        /** How long a queued request waits for its job before it counts as failed. */
        const val QUEUE_TIMEOUT_MS = 20_000L

        private fun create(app: Context) = RankRepository(
            store = RankStore(app),
            client = OpenDotaClient(),
            io = Executors.newSingleThreadExecutor(),
            main = MainThread,
            clock = System::currentTimeMillis,
            medals = { BundledMedals.load(app) },
            applyLauncherIcon = { state, showMedal -> LauncherIcon.update(app, state, showMedal) },
            updateWidgets = { RankWidget.updateAll(app) },
            // getActiveNetwork() is null when there is no network *or* this app's network is blocked.
            canUseNetworkNow = { app.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null },
            scheduleBackgroundFetch = { RankRefreshJob.scheduleFetchNow(app) },
        )

        /** Created on first use; Robolectric tests put in one that talks to a mock server. */
        @Volatile internal var instance: RankRepository? = null

        fun get(context: Context): RankRepository =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
    }
}
