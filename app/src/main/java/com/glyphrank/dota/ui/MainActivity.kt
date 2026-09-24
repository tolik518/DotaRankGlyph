package com.glyphrank.dota.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
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
import com.glyphrank.dota.data.OpenDotaClient
import com.glyphrank.dota.data.PlayerRank
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RefreshInterval
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.PlayerInput
import com.glyphrank.dota.rank.RankTier
import com.glyphrank.dota.toy.ReloadShake
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Plain-View UI on purpose: no AndroidX/Compose dependencies to keep the build tiny. */
class MainActivity : Activity() {

    private lateinit var store: RankStore
    private var medals: MedalArt? = null
    private var currentPlayer: PlayerRank? = null
    private val renderer = RankRenderer()
    private val client = OpenDotaClient()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Medal being shaken in the preview, in step with the Glyph; null when idle. */
    private var shakeBase: IntArray? = null
    /** True while our own lookup is holding the shared [ReloadShake]. */
    private var ownsShake = false
    /** Our own lookup's result, shown once the shake has finished its cycle. */
    private var afterShake: (() -> Unit)? = null
    private var visible = false

    /** Follows the shared reload shake, whether the reload started here or on the Glyph. */
    private val shakeListener = object : ReloadShake.Listener {
        override fun onShakeStep(step: Int) {
            val base = shakeBase ?: glyphFrame().also { shakeBase = it }
            preview.frame = renderer.shake(base, step)
        }

        override fun onShakeEnd(error: Throwable?) {
            shakeBase = null
            val own = afterShake
            afterShake = null
            val cached = store.cachedForCurrentAccount()
            when {
                own != null -> own()
                error != null -> showError(error.message ?: "Lookup failed") // Glyph long-press failed
                cached != null -> showPlayer(cached.player)
                else -> preview.frame = glyphFrame()
            }
        }
    }

    /** "Save & check rank" stays disabled while a lookup runs and for [CHECK_COOLDOWN_MS] after a tap. */
    private var fetching = false
    private var cooldownUntil = 0L
    private val cooldownOver = Runnable { updateCheckButton() }

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var preview: MatrixPreviewView
    private lateinit var checkButton: Button
    private lateinit var intervalPicker: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RankStore(this)
        medals = BundledMedals.load(this)
        setContentView(buildLayout())
        setUpIntervalPicker()

        store.accountId?.let { input.setText(it.toString()) }
        val cached = store.cachedForCurrentAccount()
        when {
            cached != null -> showPlayer(cached.player)
            store.accountId == null -> {
                preview.frame = renderer.message("ID")
                status.text = "Enter your Dota friend ID to get started."
            }
            else -> {
                preview.frame = renderer.loading(0)
                status.text = "Not checked yet."
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        ReloadShake.addListener(shakeListener)
    }

    override fun onStop() {
        visible = false
        ReloadShake.removeListener(shakeListener)
        if (shakeBase != null) {
            shakeBase = null
            preview.frame = glyphFrame()
        }
        afterShake?.let { afterShake = null; it() } // don't lose our result while hidden
        super.onStop()
    }

    override fun onDestroy() {
        releaseShake(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun saveAndCheck() {
        if (!checkButton.isEnabled) return // cooling down (the keyboard's Done key ends up here too)
        when (val parsed = PlayerInput.parse(input.text.toString())) {
            is PlayerInput.Invalid -> showError(parsed.reason)
            is PlayerInput.SteamVanity -> showError(
                "Custom Steam URLs (/id/${parsed.vanityName}) aren't supported yet. " +
                    "Paste your friend ID, SteamID64 or a steamcommunity.com/profiles/… link.",
            )
            is PlayerInput.Account -> {
                store.accountId = parsed.accountId
                input.setText(parsed.accountId.toString())
                fetch(parsed.accountId)
            }
        }
    }

    private fun fetch(accountId: Long) {
        fetching = true
        cooldownUntil = SystemClock.elapsedRealtime() + CHECK_COOLDOWN_MS
        checkButton.removeCallbacks(cooldownOver)
        checkButton.postDelayed(cooldownOver, CHECK_COOLDOWN_MS)
        updateCheckButton()
        status.setTextColor(TEXT)
        status.text = "Checking OpenDota for $accountId…"
        if (currentPlayer?.accountId == accountId) {
            ownsShake = true
            ReloadShake.start() // shakes the preview, and the Glyph if the toy is showing
        } else {
            preview.frame = renderer.loading(3)
        }
        io.execute {
            val result = runCatching { client.fetchPlayer(accountId) }
            runOnUiThread {
                releaseShake(result.exceptionOrNull()) // even if we're gone, or the Glyph keeps shaking
                if (isDestroyed) return@runOnUiThread
                val show: () -> Unit = {
                    fetching = false
                    updateCheckButton()
                    result
                        .onSuccess { player ->
                            store.save(player)
                            showPlayer(player)
                        }
                        .onFailure { showError(it.message ?: "Lookup failed") }
                }
                // Let the shake finish its cycle first, in step with the Glyph.
                if (visible && ReloadShake.isShaking) afterShake = show else show()
            }
        }
    }

    private fun releaseShake(error: Throwable?) {
        if (!ownsShake) return
        ownsShake = false
        ReloadShake.finish(error)
    }

    private fun updateCheckButton() {
        checkButton.isEnabled = !fetching && SystemClock.elapsedRealtime() >= cooldownUntil
    }

    private fun showPlayer(player: PlayerRank) {
        currentPlayer = player
        preview.frame = renderer.render(player.state, medals, store.showImmortalRank)
        val name = player.personaName ?: "Player ${player.accountId}"
        status.setTextColor(TEXT)
        status.text = "$name\n${RankTier.describe(player.state)}  (rank_tier ${player.rankTier ?: "none"})"
    }

    /** Errors appear as text only; the preview keeps showing what the Glyph shows. */
    private fun showError(message: String) {
        status.setTextColor(ERROR)
        status.text = message
        preview.frame = glyphFrame()
    }

    /** Mirrors the toy: last known medal for the saved account, never an error. */
    private fun glyphFrame(): IntArray {
        val cached = store.cachedForCurrentAccount()
        return when {
            cached != null -> renderer.render(cached.player.state, medals, store.showImmortalRank)
            store.accountId == null -> renderer.message("ID")
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
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // --- preview ----------------------------------------------------------------

    private fun refreshPreview() {
        currentPlayer?.let { preview.frame = renderer.render(it.state, medals, store.showImmortalRank) }
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
            hint = "Friend ID, SteamID64 or profile URL"
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
        column.addView(input)

        checkButton = Button(this).apply {
            text = "Save & check rank"
            setOnClickListener { saveAndCheck() }
        }
        column.addView(checkButton, spaced(top = 8))

        status = text("", 16f, TEXT)
        column.addView(status, spaced(top = 16, bottom = 16))

        preview = MatrixPreviewView(this)
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
                refreshPreview()
            }
        }, spaced(top = 8))
        column.addView(text(
            "Immortal medals show the leaderboard place, e.g. 2488, when OpenDota has one.",
            13f, MUTED,
        ), spaced(top = 4))

        column.addView(Button(this).apply {
            text = "Add to Glyph Toys"
            setOnClickListener { openToyManager() }
        }, spaced(top = 16))

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
    }
}
