package com.glyphrank.dota.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.glyph.RankAnimation
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.ReloadShake
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
 */
class RankRepository private constructor(private val context: Context) {
    interface Listener {
        /** A refresh saved a rank. [previous] is the last known rank of the same account, if any. */
        fun onRankSaved(previous: PlayerRank?, current: PlayerRank) {}

        /** Loading started or finished, an error was saved, or the account changed. */
        fun onStateChanged() {}
    }

    val store = RankStore(context)
    private val client = OpenDotaClient()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<Listener>()

    /** Running requests by account ID; the value is the shake token, if the request shakes. */
    private val inFlight = HashMap<Long, Int?>()

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
        notifyState()
    }

    /**
     * Refreshes the current account's rank if [RefreshPolicy] allows it.
     * [manual]: the user asked (button, long-press); ignores the refresh interval and shakes
     * the last known medal while loading.
     */
    fun refresh(manual: Boolean): Decision {
        val accountId = store.accountId ?: return Decision.NoAccount
        val cached = store.cachedForCurrentAccount()
        val shake = manual && cached != null
        if (accountId in inFlight) {
            if (shake && inFlight[accountId] == null) inFlight[accountId] = ReloadShake.start()
            return Decision.Joined
        }
        val now = System.currentTimeMillis()
        val decision = RefreshPolicy.decide(
            accountId, manual, cached?.fetchedAtMs, store.refreshIntervalMinutes, store.guard, now,
        )
        if (decision != Decision.Fetch) {
            if (!manual) Log.d(TAG, "Auto refresh skipped: $decision")
            return decision
        }

        Log.d(TAG, "Fetching $accountId (${if (manual) "manual" else "auto"})")
        store.guard = RefreshPolicy.attempt(store.guard, accountId, now)
        inFlight[accountId] = if (shake) ReloadShake.start() else null
        io.execute {
            val result = runCatching { client.fetch(accountId) }
            main.post { onFetched(accountId, result) }
        }
        notifyState()
        return decision
    }

    private fun onFetched(accountId: Long, result: Result<PlayerFetch>) {
        val shakeToken = inFlight.remove(accountId)
        val now = System.currentTimeMillis()
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
                Log.w(TAG, "Rank refresh failed: ${e.message}")
                store.guard = RefreshPolicy.afterFailure(store.guard, e, now)
                if (current) store.saveError(accountId, e.message ?: "Lookup failed", now)
            }
        shakeToken?.let(ReloadShake::finish) // the shake still finishes its cycle
        notifyState()
    }

    /** Points the launcher icon at the current medal (or the default icon if the setting is off). */
    fun updateLauncherIcon() {
        runCatching { LauncherIcon.update(context, store.cachedForCurrentAccount()?.player?.state, store.appIconShowsMedal) }
            .onFailure { Log.w(TAG, "Launcher icon update failed", it) }
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
        RankCelebration.play(animation.frames(previous.state, current.state, BundledMedals.load(context), show))
    }

    /** Called by the toy when it appears: plays a change that happened while it was away. */
    fun playPendingCelebration() {
        val pending = store.pendingCelebration ?: return
        store.pendingCelebration = null
        val current = store.cachedForCurrentAccount()?.player ?: return
        if (pending.accountId != current.accountId) return
        RankCelebration.play(
            animation.frames(pending.state, current.state, BundledMedals.load(context), store.showImmortalRank),
        )
    }

    private fun notifyState() = listeners.toList().forEach { it.onStateChanged() }

    companion object {
        private const val TAG = "RankRepository"

        @Volatile private var instance: RankRepository? = null

        fun get(context: Context): RankRepository =
            instance ?: synchronized(this) {
                instance ?: RankRepository(context.applicationContext).also { instance = it }
            }
    }
}
