package com.glyphrank.dota.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glyphrank.dota.data.BundledMedals
import com.glyphrank.dota.data.RankRepository
import com.glyphrank.dota.data.RecentAccounts
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshInterval
import com.glyphrank.dota.data.RefreshPolicy.Decision
import com.glyphrank.dota.data.SteamProfileResolver
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankAnimation
import com.glyphrank.dota.glyph.RankCelebration
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.glyph.ReloadShake
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.PlayerInput
import com.glyphrank.dota.rank.RankState
import com.glyphrank.dota.rank.RankTier
import com.glyphrank.dota.widget.RankRefreshJob
import com.glyphrank.dota.widget.RankWidget
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Plain-View UI on purpose: no AndroidX/Compose dependencies to keep the build tiny. */
class MainActivity : Activity() {

    private lateinit var repository: RankRepository
    private val store: RankStore get() = repository.store
    private var medals: MedalArt? = null
    private val renderer = RankRenderer()
    private val steam = SteamProfileResolver()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Medal being shaken in the preview, in step with the Glyph; null when idle. */
    private var shakeBase: IntArray? = null

    /** Follows the shared reload shake, whether the reload started here or on the Glyph. */
    private val shakeListener = object : ReloadShake.Listener {
        override fun onShakeStep(step: Int) {
            if (RankCelebration.isPlaying) return
            val base = shakeBase ?: glyphFrame().also { shakeBase = it }
            preview.frame = renderer.shake(base, step)
        }

        override fun onShakeEnd() {
            shakeBase = null
            render()
        }
    }

    /** The rank-change animation, in step with the Glyph. */
    private val celebrationListener = object : RankCelebration.Listener {
        override fun onCelebrationFrame(frame: IntArray) {
            preview.frame = frame
        }

        override fun onCelebrationEnd() = render()
    }

    /** Refreshes started here or by the toy, errors, account changes. */
    private val repositoryListener = object : RankRepository.Listener {
        override fun onStateChanged() = render()
    }

    /** Keeps "Updated … ago" current while the screen is visible. */
    private val ticker = object : Runnable {
        override fun run() {
            render()
            status.postDelayed(this, TICK_MS)
        }
    }

    /** "Save & check rank" stays disabled while a lookup runs and for [CHECK_COOLDOWN_MS] after a tap. */
    private var resolving = false
    private var cooldownUntil = 0L
    private val cooldownOver = Runnable { updateCheckButton() }

    private lateinit var input: EditText
    private lateinit var recentSection: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var toyPrompt: LinearLayout
    private lateinit var privateHelp: LinearLayout
    private lateinit var privateHelpTitle: TextView
    private lateinit var notice: TextView
    private lateinit var status: TextView
    private lateinit var preview: MatrixPreviewView
    private lateinit var checkButton: Button
    private lateinit var intervalPicker: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RankRepository.get(this)
        medals = BundledMedals.load(this)
        setContentView(buildLayout())
        setUpIntervalPicker()
        store.accountId?.let { input.setText(it.toString()) }
        render()
        if (savedInstanceState == null) handleShare(intent)
    }

    /** singleTask: a share (or any launch) while the screen exists arrives here instead of a second screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        ReloadShake.addListener(shakeListener)
        RankCelebration.addListener(celebrationListener)
        repository.addListener(repositoryListener)
        ticker.run()
    }

    override fun onStop() {
        ReloadShake.removeListener(shakeListener)
        RankCelebration.removeListener(celebrationListener)
        repository.removeListener(repositoryListener)
        status.removeCallbacks(ticker)
        shakeBase = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun saveAndCheck() {
        if (!checkButton.isEnabled) return // cooling down (the keyboard's Done key ends up here too)
        check(PlayerInput.parse(input.text.toString()))
    }

    private fun check(parsed: PlayerInput) {
        when (parsed) {
            is PlayerInput.Invalid -> showNotice(parsed.reason, error = true)
            is PlayerInput.SteamVanity -> resolveThenFetch(parsed.vanityName)
            is PlayerInput.Account -> saveAndFetch(parsed.accountId)
        }
    }

    /** A Steam profile (or any text with an ID) shared from another app: fill it in and check it. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val parsed = PlayerInput.fromSharedText(text)
        fillInput(parsed, text)
        if (parsed is PlayerInput.Invalid) showNotice(parsed.reason, error = true)
        else if (checkButton.isEnabled) check(parsed)
    }

    /** Reads the clipboard only when the Paste button is tapped. */
    private fun paste() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) return showNotice("The clipboard is empty.")
        fillInput(PlayerInput.fromSharedText(text), text)
        showNotice(null)
    }

    /** Shows [parsed] in the input field in its shortest form, or the raw [text] if it didn't parse. */
    private fun fillInput(parsed: PlayerInput, text: String) {
        input.setText(
            when (parsed) {
                is PlayerInput.Account -> parsed.accountId.toString()
                is PlayerInput.SteamVanity -> "steamcommunity.com/id/${parsed.vanityName}"
                is PlayerInput.Invalid -> text.trim()
            },
        )
        input.setSelection(input.text.length)
    }

    private fun saveAndFetch(accountId: Long) {
        startCooldown()
        repository.setAccount(accountId)
        input.setText(accountId.toString())
        showNotice(null)
        when (val decision = repository.refresh(manual = true)) {
            Decision.Fetch, Decision.Joined ->
                // While shaking, the result appears once the shake has finished its cycle.
                if (ReloadShake.isShaking) status.text = "Checking OpenDota for $accountId…"
            is Decision.Blocked -> showNotice(blockedText(decision.untilMs, decision.daily), error = true)
            is Decision.TooSoon -> {
                if (store.cachedForCurrentAccount() != null) ReloadShake.pulse()
                showNotice("Just checked. Try again in a few seconds.")
            }
            else -> Unit // manual checks don't wait for the refresh interval
        }
        updateCheckButton()
    }

    /** steamcommunity.com/id/<name>: one Steam lookup, then the account ID is stored like any other. */
    private fun resolveThenFetch(vanityName: String) {
        startCooldown()
        resolving = true
        updateCheckButton()
        showNotice("Looking up steamcommunity.com/id/$vanityName…")
        io.execute {
            val result = runCatching { steam.resolveVanity(vanityName) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                resolving = false
                result
                    .onSuccess { accountId -> saveAndFetch(accountId) }
                    .onFailure { showNotice(it.message ?: "Steam lookup failed", error = true) }
                updateCheckButton()
            }
        }
    }

    private fun startCooldown() {
        cooldownUntil = SystemClock.elapsedRealtime() + CHECK_COOLDOWN_MS
        checkButton.removeCallbacks(cooldownOver)
        checkButton.postDelayed(cooldownOver, CHECK_COOLDOWN_MS)
        updateCheckButton()
    }

    private fun updateCheckButton() {
        checkButton.isEnabled = !resolving && !repository.isLoading &&
            SystemClock.elapsedRealtime() >= cooldownUntil
    }

    /** A message about the last button press (bad input, Steam lookup, rate limit); null hides it. */
    private fun showNotice(message: String?, error: Boolean = false) {
        notice.visibility = if (message == null) View.GONE else View.VISIBLE
        notice.text = message
        notice.setTextColor(if (error) ERROR else TEXT)
    }

    /** Shows the saved state: preview like the Glyph, rank, age, last error. */
    private fun render() {
        updateCheckButton()
        if (ReloadShake.isShaking) return // results appear once the shake has finished its cycle
        if (!RankCelebration.isBusy) preview.frame = glyphFrame()
        status.text = statusText()
        renderRecent()
        renderPrivateHelp()
        toyPrompt.visibility =
            if (store.cachedForCurrentAccount() != null && !store.toyUsed && !store.toyPromptDone) View.VISIBLE
            else View.GONE
    }

    /**
     * How to turn on "Expose Public Match Data": shown when OpenDota has no public profile for
     * the account, or reports no rank (private match data can hide it too).
     */
    private fun renderPrivateHelp() {
        val cached = store.cachedForCurrentAccount()
        val error = store.lastErrorForCurrentAccount()?.takeIf { cached == null || it.atMs >= cached.fetchedAtMs }
        privateHelpTitle.text = when {
            error?.isNotFound == true -> "OpenDota can't see this profile. If the ID is right, its match data is private:"
            cached?.player?.state == RankState.Uncalibrated && error == null ->
                "No rank on OpenDota? If you are calibrated, your match data may be private:"
            else -> null
        }
        privateHelp.visibility = if (privateHelpTitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Recent accounts other than the current one, with their last known medal; tap to switch, long-press to remove. */
    private fun renderRecent() {
        val others = store.recentAccounts.filter { it.accountId != store.accountId }
        recentSection.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        recentList.removeAllViews()
        for (entry in others) {
            val cached = store.cachedFor(entry.accountId)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(MatrixPreviewView(this@MainActivity).apply {
                    frame = cached?.let { renderer.render(it.player.state, medals, store.showImmortalRank) }
                        ?: renderer.message("")
                }, LinearLayout.LayoutParams(dp(RECENT_MEDAL_DP), dp(RECENT_MEDAL_DP)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(entry.name ?: "Player", 15f, TEXT).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    val rank = cached?.let { RankTier.describe(it.player.state) + "  ·  " } ?: ""
                    addView(text("$rank${entry.accountId}", 13f, MUTED).apply { isSingleLine = true })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                })
                setOnClickListener {
                    if (!checkButton.isEnabled) return@setOnClickListener
                    input.setText(entry.accountId.toString())
                    saveAndCheck()
                }
                setOnLongClickListener {
                    store.recentAccounts = RecentAccounts.remove(store.recentAccounts, entry.accountId)
                    renderRecent()
                    true
                }
            }
            recentList.addView(row)
        }
    }

    private fun statusText(): CharSequence {
        val accountId = store.accountId ?: return "Enter your Dota friend ID to get started."
        val now = System.currentTimeMillis()
        val cached = store.cachedForCurrentAccount()
        val error = store.lastErrorForCurrentAccount()?.takeIf { cached == null || it.atMs >= cached.fetchedAtMs }
        val text = SpannableStringBuilder()
        if (cached != null) {
            val player = cached.player
            text.append(player.personaName ?: "Player ${player.accountId}").append('\n')
            text.append("${RankTier.describe(player.state)}  (rank_tier ${player.rankTier ?: "none"})\n")
            val age = "Updated ${TimeText.ago(cached.fetchedAtMs, now)}" +
                if (repository.isLoading) " · checking…" else ""
            text.append(age, ForegroundColorSpan(MUTED), 0)
        } else {
            text.append(
                when {
                    repository.isLoading -> "Checking OpenDota for $accountId…"
                    error == null -> "Not checked yet."
                    else -> "No rank yet for $accountId."
                },
            )
        }
        if (error != null) {
            text.append('\n')
            text.append("Last check failed (${TimeText.clock(this, error.atMs)}): ${error.message}", ForegroundColorSpan(ERROR), 0)
        }
        val guard = store.guard
        when {
            guard.blockedDaily && guard.blockedUntilMs > now ->
                text.append('\n').append(blockedText(guard.blockedUntilMs, daily = true), ForegroundColorSpan(ERROR), 0)
            guard.pausedUntilMs > now ->
                text.append('\n').append(
                    "Few OpenDota requests left today; auto refresh resumes at ${TimeText.clock(this, guard.pausedUntilMs)}.",
                    ForegroundColorSpan(MUTED), 0,
                )
        }
        return text
    }

    private fun blockedText(untilMs: Long, daily: Boolean): String =
        if (daily) "OpenDota daily limit reached, checking again at ${TimeText.clock(this, untilMs)}."
        else "OpenDota rate limit hit. Try again in a minute."

    /** Mirrors the toy: last known medal for the saved account, never an error. */
    private fun glyphFrame(): IntArray {
        val cached = store.cachedForCurrentAccount()
        return when {
            cached != null -> renderer.render(cached.player.state, medals, store.showImmortalRank)
            store.accountId == null -> renderer.message("ID")
            repository.isLoading -> renderer.loading(3)
            else -> renderer.loading(0)
        }
    }

    // --- auto refresh -----------------------------------------------------------

    private fun setUpIntervalPicker() {
        val choices = RefreshInterval.CHOICES_MINUTES
        val current = store.refreshIntervalMinutes
        val index = choices.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: choices.lastIndex
        intervalPicker.setSelection(index, false)
        intervalPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                store.refreshIntervalMinutes = choices[position]
                RankRefreshJob.reschedule(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // --- preview ----------------------------------------------------------------

    // --- debug: replay the rank-change animations -------------------------------

    private val isDebugBuild get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var nextSample = 0

    /** Debug builds only: long-press the preview to play the next sample rank change (preview and Glyph). */
    private fun replaySampleAnimation() {
        if (ReloadShake.isShaking || RankCelebration.isBusy) return
        val (label, old, new) = SAMPLE_CHANGES[nextSample++ % SAMPLE_CHANGES.size]
        Toast.makeText(this, "Debug: $label", Toast.LENGTH_SHORT).show()
        RankCelebration.play(RankAnimation().frames(old, new, medals, store.showImmortalRank))
    }

    // --- share menu -------------------------------------------------------------

    private val shareTarget get() = ComponentName(this, "com.glyphrank.dota.ui.ShareTarget")

    private var showInShareMenu: Boolean
        get() = packageManager.getComponentEnabledSetting(shareTarget) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED // default: enabled in the manifest
        set(value) = packageManager.setComponentEnabledSetting(
            shareTarget,
            if (value) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

    /** Asks the launcher to place the widget (it shows its own confirmation). */
    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val pinned = manager != null && manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(ComponentName(this, RankWidget::class.java), null, null)
        if (!pinned) {
            Toast.makeText(this, "Long-press the home screen → Widgets → Dota Rank Glyph", Toast.LENGTH_LONG).show()
        }
    }

    private fun openToyManager() {
        val intent = Intent().setComponent(
            ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings → Glyph Interface → Glyph Toys", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Open Settings → Glyph Interface → Glyph Toys", Toast.LENGTH_LONG).show()
        }
    }

    // --- layout -----------------------------------------------------------------

    private fun buildLayout(): View {
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text("DOTA RANK", 28f, TEXT, bold = true))
        column.addView(text("Glyph Toy for Nothing Phone (3)", 14f, MUTED), spaced(bottom = 24))

        input = EditText(this).apply {
            hint = "Friend ID or Steam profile link"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveAndCheck(); true } else false
            }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Paste"
                setOnClickListener { paste() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        column.addView(inputRow)

        checkButton = Button(this).apply {
            text = "Save & check rank"
            setOnClickListener { saveAndCheck() }
        }
        column.addView(checkButton, spaced(top = 8))

        notice = text("", 14f, TEXT).apply { visibility = View.GONE }
        column.addView(notice, spaced(top = 8))

        recentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentSection.addView(text("RECENT", 14f, TEXT, bold = true))
        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentSection.addView(recentList)
        recentSection.addView(text("Tap to switch, long-press to remove.", 13f, MUTED))
        column.addView(recentSection, spaced(top = 16))

        status = text("", 16f, TEXT)
        column.addView(status, spaced(top = 16, bottom = 16))

        privateHelp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(0x1C, 0x1C, 0x1C))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            privateHelpTitle = text("", 14f, TEXT)
            addView(privateHelpTitle)
            addView(text(
                "1. Start Dota 2 and open Settings (top left).\n" +
                    "2. Go to the Social tab.\n" +
                    "3. Turn on \"Expose Public Match Data\".\n" +
                    "4. Play a match. OpenDota only sees matches played after the switch is on, " +
                    "a few minutes after each match ends.\n" +
                    "5. Then tap Save & check rank again.",
                14f, MUTED,
            ), spaced(top = 8))
        }
        column.addView(privateHelp, spaced(bottom = 16))

        toyPrompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(0x1C, 0x1C, 0x1C))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(text("Show this medal on the Glyph Matrix? Add Dota Rank to your Glyph Toys.", 14f, TEXT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = "Add to Glyph Toys"
                    setOnClickListener {
                        store.toyPromptDone = true
                        render()
                        openToyManager()
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Not now"
                    setOnClickListener {
                        store.toyPromptDone = true
                        render()
                    }
                })
            }, spaced(top = 8))
        }
        column.addView(toyPrompt, spaced(bottom = 16))

        preview = MatrixPreviewView(this).apply {
            if (isDebugBuild) setOnLongClickListener { replaySampleAnimation(); true }
        }
        column.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        column.addView(text(
            "The matching Dota 2 medal appears on the Glyph Matrix; earned stars are shown along its top edge.",
            13f, MUTED,
        ), spaced(top = 16))

        column.addView(text("AUTO REFRESH", 14f, TEXT, bold = true), spaced(top = 24))
        intervalPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                RefreshInterval.CHOICES_MINUTES.map(RefreshInterval::label),
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        column.addView(intervalPicker, spaced(top = 8))
        column.addView(text(
            "How often the Glyph Toy checks OpenDota on its own. Long-press the Glyph Button to check right away.",
            13f, MUTED,
        ), spaced(top = 4))

        column.addView(text("DISPLAY", 14f, TEXT, bold = true), spaced(top = 24))
        column.addView(Switch(this).apply {
            text = "Show exact rank for Immortals"
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            isChecked = store.showImmortalRank
            setOnCheckedChangeListener { _, checked ->
                store.showImmortalRank = checked
                repository.refreshWidgets()
                render()
            }
        }, spaced(top = 8))
        column.addView(text(
            "Immortal medals show the leaderboard place, e.g. 2488, when OpenDota has one.",
            13f, MUTED,
        ), spaced(top = 4))

        column.addView(Switch(this).apply {
            text = "App icon shows my medal"
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            isChecked = store.appIconShowsMedal
            setOnCheckedChangeListener { _, checked ->
                store.appIconShowsMedal = checked
                repository.updateLauncherIcon()
            }
        }, spaced(top = 16))
        column.addView(text(
            "The launcher icon changes to your current medal after each check (Immortal and uncalibrated use the default icon).",
            13f, MUTED,
        ), spaced(top = 4))

        column.addView(text("SHARING", 14f, TEXT, bold = true), spaced(top = 24))
        column.addView(Switch(this).apply {
            text = "Show in the share menu"
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            isChecked = showInShareMenu
            setOnCheckedChangeListener { _, checked -> showInShareMenu = checked }
        }, spaced(top = 8))
        column.addView(text(
            "Share a Steam profile from the Steam app or a browser to Dota Rank Glyph to check it.",
            13f, MUTED,
        ), spaced(top = 4))

        column.addView(Button(this).apply {
            text = "Add to Glyph Toys"
            setOnClickListener { openToyManager() }
        }, spaced(top = 16))

        column.addView(Button(this).apply {
            text = "Add home-screen widget"
            setOnClickListener { pinWidget() }
        }, spaced(top = 8))

        column.addView(text(
            "Short-press the Glyph Button to reach the toy, long-press it to refresh. " +
                "Rank data comes from OpenDota and needs \"Expose Public Match Data\" enabled in Dota 2.",
            13f, MUTED,
        ), spaced(top = 8))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        }
        // targetSdk 35 draws edge-to-edge: keep content clear of the system bars and keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val TEXT = Color.WHITE
        val MUTED = Color.rgb(0x8A, 0x8A, 0x8A)
        val ERROR = Color.rgb(0xD7, 0x19, 0x21)
        const val CHECK_COOLDOWN_MS = 5_000L
        const val TICK_MS = 30_000L
        const val RECENT_MEDAL_DP = 48

        private fun ranked(medal: Medal, stars: Int) = RankState.Ranked(medal, stars)
        val SAMPLE_CHANGES = listOf(
            Triple("star up, Guardian 3 → 4", ranked(Medal.GUARDIAN, 3), ranked(Medal.GUARDIAN, 4)),
            Triple("star down, Guardian 4 → 3", ranked(Medal.GUARDIAN, 4), ranked(Medal.GUARDIAN, 3)),
            Triple("tier up, Guardian 5 → Crusader 1", ranked(Medal.GUARDIAN, 5), ranked(Medal.CRUSADER, 1)),
            Triple("tier down, Crusader 1 → Guardian 5", ranked(Medal.CRUSADER, 1), ranked(Medal.GUARDIAN, 5)),
            Triple("calibrated, ? → Herald 2", RankState.Uncalibrated, ranked(Medal.HERALD, 2)),
            Triple("Immortal, Divine 5 → #2488", ranked(Medal.DIVINE, 5), RankState.Immortal(2488)),
            Triple("place roll, #2600 → #2488", RankState.Immortal(2600), RankState.Immortal(2488)),
        )
    }
}
