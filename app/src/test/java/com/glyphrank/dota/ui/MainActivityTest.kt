package com.glyphrank.dota.ui

import android.graphics.Insets
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import com.glyphrank.dota.data.BundledMedals
import com.glyphrank.dota.data.GuardState
import com.glyphrank.dota.data.SteamProfileResolver
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.Medal
import com.glyphrank.dota.rank.RankState
import org.junit.Assert.assertArrayEquals
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.glyphrank.dota.data.MockHttpServer.Response
import com.glyphrank.dota.data.PlayerRank
import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.data.RecentAccounts
import com.glyphrank.dota.data.RefreshInterval
import com.glyphrank.dota.data.TestRepository
import com.glyphrank.dota.data.TestRepository.Companion.player
import com.glyphrank.dota.rank.PlayerInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

/** The settings screen, end to end: real views and store, OpenDota replaced by a mock server. */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val env = TestRepository(RuntimeEnvironment.getApplication())
    private val store: RankStore get() = env.store
    private lateinit var controller: ActivityController<MainActivity>
    private val activity: MainActivity get() = controller.get()

    private val zq = 116233682L

    init {
        env.server.handler = { req ->
            val id = req.path.substringAfterLast('/').toLong()
            if (id == zq) Response(200, player(zq, "ZQuixotix", 80, 2488)) else Response(200, player(id, "Player $id", 54))
        }
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        MainActivity.steamResolver = { SteamProfileResolver() }
        env.close()
    }

    private fun launch(intent: Intent? = null) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
    }

    // --- view helpers -----------------------------------------------------------------

    private val input: EditText get() = views<EditText>().single()

    private fun button(label: String): Button = views<Button>().single { it.text == label }

    private fun switch(label: String): Switch = views<Switch>().single { it.text == label }

    /** The text of every visible TextView (not buttons), top to bottom. */
    private fun screenText(): String =
        views<TextView>().filter { it !is Button && it !is EditText && it.isVisibleOnPage() }.joinToString("\n") { it.text }

    private fun View.isVisibleOnPage(): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }

    private inline fun <reified T : View> views(): List<T> = allViews(activity.window.decorView).filterIsInstance<T>()

    private fun allViews(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()

    private fun check(text: String) {
        input.setText(text)
        button("Save & check rank").performClick()
        env.finishRequests()
    }

    // --- checking an ID ---------------------------------------------------------------

    @Test fun `checking an ID shows the name, rank and when it was updated`() {
        launch()
        assertTrue(screenText(), screenText().contains("Enter your Dota friend ID to get started."))
        check("116233682")
        val text = screenText()
        assertTrue(text, text.contains("ZQuixotix\nImmortal #2488\nUpdated just now"))
        assertEquals(zq, store.accountId)
        assertEquals(listOf(zq), store.recentAccounts.map { it.accountId })
        assertEquals(listOf("/api/players/$zq"), env.server.requests.map { it.path })
    }

    @Test fun `a SteamID is saved as the friend ID`() {
        launch()
        check("${PlayerInput.STEAM_ID64_BASE + zq}")
        assertEquals("$zq", input.text.toString())
        assertEquals(zq, store.accountId)
    }

    @Test fun `invalid input shows why and sends nothing`() {
        launch()
        check("hello there")
        assertTrue(screenText(), screenText().contains("Not a friend ID, SteamID or profile URL"))
        assertNull(store.accountId)
        assertTrue(env.server.requests.isEmpty())
    }

    @Test fun `the button rests for a few seconds after a check`() {
        launch()
        check("116233682")
        assertFalse(button("Save & check rank").isEnabled)
        env.idleFor(5_000)
        assertTrue(button("Save & check rank").isEnabled)
    }

    @Test fun `a check right after the toy's is refused with a hint`() {
        store.accountId = zq
        store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now - 60 * 60_000L)
        launch()
        env.repo.refresh(manual = true) // long-press on the Glyph
        env.finishRequests()
        env.idleFor(1_000)
        check("$zq")
        assertEquals(1, env.server.requests.size)
        assertTrue(screenText(), screenText().contains("Just checked. Try again in a few seconds."))
    }

    @Test fun `a failed check keeps the last rank and shows the error`() {
        launch()
        check("116233682")
        env.server.handler = { Response(503, "<html>503</html>", contentType = "text/html") }
        env.idleFor(5_000)
        check("116233682")
        env.idleFor(10_000) // the reload shake finishes its cycle, then the result shows
        val text = screenText()
        assertTrue(text, text.contains("ZQuixotix\nImmortal #2488"))
        assertTrue(text, text.contains("OpenDota returned HTTP 503"))
    }

    @Test fun `a rate limit says when checks work again`() {
        env.server.handler = { Response(429, """{"error":"minute rate limit exceeded"}""") }
        launch()
        check("116233682")
        assertTrue(screenText(), screenText().contains("No rank yet for $zq."))
        assertTrue(screenText(), screenText().contains("OpenDota rate limit hit, try again in a minute"))
        env.idleFor(5_000)
        check("116233682")
        assertEquals(1, env.server.requests.size)
        assertTrue(screenText(), screenText().contains("OpenDota rate limit hit. Try again in a minute."))
    }

    @Test fun `a private profile shows how to expose match data`() {
        env.server.handler = { Response(200, """{"profile":null,"rank_tier":null}""") }
        launch()
        assertFalse(screenText().contains("Turn on \"Expose Public Match Data\""))
        check("116233682")
        assertTrue(screenText(), screenText().contains("OpenDota can't see this profile."))
        assertTrue(screenText(), screenText().contains("3. Turn on \"Expose Public Match Data\"."))
    }

    @Test fun `no rank on a public profile also shows the help`() {
        env.server.handler = { Response(200, player(zq, "ZQuixotix", null)) }
        launch()
        check("116233682")
        assertTrue(screenText(), screenText().contains("ZQuixotix\nUncalibrated"))
        assertTrue(screenText(), screenText().contains("No rank on OpenDota? If you are calibrated, your match data may be private:"))
    }

    // --- opening the app -----------------------------------------------------------------

    @Test fun `reopening shows the saved rank without asking OpenDota`() {
        store.accountId = zq
        store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = System.currentTimeMillis() - 3 * 60 * 60_000L)
        launch()
        assertEquals("$zq", input.text.toString())
        assertTrue(screenText(), screenText().contains("ZQuixotix\nImmortal #2488\nUpdated 3 h ago"))
        assertTrue(env.server.requests.isEmpty())
    }

    @Test fun `a saved ID that was never checked says so`() {
        store.accountId = zq
        launch()
        assertTrue(screenText(), screenText().contains("Not checked yet."))
    }

    @Test fun `a check started elsewhere shows as checking`() {
        store.accountId = zq
        launch()
        env.repo.refresh(manual = false) // e.g. the Glyph toy
        env.idle()
        assertTrue(screenText(), screenText().contains("Checking OpenDota for $zq…"))
        assertFalse(button("Save & check rank").isEnabled)
        env.finishRequests()
        assertTrue(screenText(), screenText().contains("ZQuixotix"))
        assertTrue(button("Save & check rank").isEnabled)
    }

    // --- sharing and pasting ------------------------------------------------------------

    @Test fun `a shared Steam profile link is filled in and checked`() {
        val steamId = PlayerInput.STEAM_ID64_BASE + zq
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look: https://steamcommunity.com/profiles/$steamId"))
        env.finishRequests()
        assertEquals("$zq", input.text.toString())
        assertTrue(screenText(), screenText().contains("ZQuixotix"))
    }

    @Test fun `a share without an ID says so`() {
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "gg wp"))
        assertTrue(screenText(), screenText().contains("No friend ID or Steam profile link in the shared text"))
        assertTrue(env.server.requests.isEmpty())
    }

    @Test fun `a share while the screen is open is checked too`() {
        launch()
        controller.newIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$zq"))
        env.finishRequests()
        assertEquals(zq, store.accountId)
    }

    @Test fun `paste fills in the ID without checking it`() {
        launch()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("id", "steamcommunity.com/id/Zeitboy"))
        button("Paste").performClick()
        assertEquals("steamcommunity.com/id/Zeitboy", input.text.toString())
        assertTrue(env.server.requests.isEmpty())
    }

    @Test fun `paste with an empty clipboard says so`() {
        launch()
        button("Paste").performClick()
        assertTrue(screenText(), screenText().contains("The clipboard is empty."))
    }

    // --- settings ---------------------------------------------------------------------

    @Test fun `display switches are saved`() {
        launch()
        switch("Show exact rank for Immortals").performClick()
        assertFalse(store.showImmortalRank)
        switch("App icon shows my medal").performClick()
        assertTrue(store.appIconShowsMedal)
    }

    @Test fun `the refresh picker shows and saves the interval`() {
        store.refreshIntervalMinutes = 60
        launch()
        val picker = views<Spinner>().single()
        assertEquals(RefreshInterval.CHOICES_MINUTES.indexOf(60), picker.selectedItemPosition)
        picker.setSelection(RefreshInterval.CHOICES_MINUTES.indexOf(12 * 60))
        env.idle()
        assertEquals(12 * 60, store.refreshIntervalMinutes)
    }

    @Test fun `the share menu entry can be hidden`() {
        launch()
        val share = switch("Show in the share menu")
        assertTrue(share.isChecked)
        share.performClick()
        controller.pause().stop().destroy()
        launch()
        assertFalse(switch("Show in the share menu").isChecked)
    }

    @Test fun `the glyph toy is suggested once after the first rank`() {
        launch()
        val prompt = "Show this medal on the Glyph Matrix? Add Dota Rank to your Glyph Toys."
        assertFalse(screenText().contains(prompt))
        check("116233682")
        assertTrue(screenText(), screenText().contains(prompt))
        button("Not now").performClick()
        assertFalse(screenText().contains(prompt))
        assertTrue(store.toyPromptDone)
    }

    @Test fun `no toy suggestion once the toy has been used`() {
        store.toyUsed = true
        launch()
        check("116233682")
        assertFalse(screenText().contains("Add Dota Rank to your Glyph Toys"))
    }

    @Test fun `the keyboard's done key checks too`() {
        launch()
        input.setText("$zq")
        input.onEditorAction(EditorInfo.IME_ACTION_DONE)
        env.finishRequests()
        assertEquals(zq, store.accountId)
    }

    @Test fun `a daily limit says when checking starts again`() {
        store.accountId = zq
        store.guard = GuardState(blockedUntilMs = System.currentTimeMillis() + 3 * 60 * 60_000L, blockedDaily = true)
        launch()
        assertTrue(screenText(), screenText().contains("OpenDota daily limit reached, checking again at "))
    }

    @Test fun `few requests left today says when auto refresh resumes`() {
        store.accountId = zq
        store.guard = GuardState(pausedUntilMs = System.currentTimeMillis() + 3 * 60 * 60_000L)
        launch()
        assertTrue(screenText(), screenText().contains("Few OpenDota requests left today; auto refresh resumes at "))
    }

    @Test fun `a changed rank animates in the preview, then shows the new medal`() {
        env.server.handler = { Response(200, player(zq, "ZQuixotix", 23)) }
        launch()
        check("$zq")
        env.server.handler = { Response(200, player(zq, "ZQuixotix", 24)) }
        env.idleFor(5_000)
        check("$zq")
        val preview = views<MatrixPreviewView>().single()
        val seen = mutableListOf<IntArray>()
        repeat(200) {
            env.idleFor(50)
            if (seen.none { it.contentEquals(preview.frame) }) seen += preview.frame
        }
        val newMedal = RankRenderer().render(RankState.Ranked(Medal.GUARDIAN, 4), BundledMedals.load(activity))
        assertTrue("${seen.size} frames", seen.size > 5) // the shake and the animation
        assertArrayEquals(newMedal, preview.frame)
        assertTrue(screenText(), screenText().contains("Guardian 4"))
    }

    // --- Steam custom URLs ------------------------------------------------------------

    private fun useSteamMock() {
        MainActivity.steamResolver = { SteamProfileResolver(baseUrl = env.server.baseUrl, timeoutMs = 2_000) }
    }

    /** Runs main-thread work until [condition] holds; the Steam lookup runs on the screen's own thread. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
            env.idle()
        }
    }

    @Test fun `a custom steam url is looked up, then checked`() {
        val zeitboy = 40453096L
        env.server.handler = { req ->
            if (req.path.startsWith("/id/")) Response(200, resource("/steam/profile_zeitboy.xml"), contentType = "text/xml")
            else Response(200, player(zeitboy, "Zeitboy", 24))
        }
        useSteamMock()
        launch()
        input.setText("steamcommunity.com/id/Zeitboy")
        button("Save & check rank").performClick()
        assertTrue(screenText(), screenText().contains("Looking up steamcommunity.com/id/Zeitboy…"))
        waitFor("steam lookup") { store.accountId == zeitboy }
        env.finishRequests()
        assertEquals("$zeitboy", input.text.toString())
        assertTrue(screenText(), screenText().contains("Zeitboy\nGuardian 4"))
        assertFalse(screenText().contains("Looking up"))
    }

    @Test fun `an unknown custom steam url says so and saves nothing`() {
        env.server.handler = { Response(404, "<html>404</html>", contentType = "text/html") }
        useSteamMock()
        launch()
        input.setText("steamcommunity.com/id/nobody-here")
        button("Save & check rank").performClick()
        waitFor("steam error") { screenText().contains("No Steam profile at that URL") }
        assertNull(store.accountId)
        assertTrue(env.server.requests.none { it.path.startsWith("/api/") })
    }

    // --- recent accounts ----------------------------------------------------------------

    private val zeitboy = RecentAccounts.Entry(40453096, "Zeitboy")

    private fun withRecentAccounts() {
        store.accountId = zq
        store.save(PlayerRank(zq, "ZQuixotix", 80, 2488), nowMs = env.now)
        store.save(PlayerRank(zeitboy.accountId, "Zeitboy", 24, null), nowMs = env.now)
        store.recentAccounts = listOf(RecentAccounts.Entry(zq, "ZQuixotix"), zeitboy, RecentAccounts.Entry(5, "Someone"))
    }

    /** The user taps the input field and the keyboard comes up (or goes down). */
    private fun keyboard(up: Boolean) {
        val page = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        page.dispatchApplyWindowInsets(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), if (up) Insets.of(0, 0, 0, 800) else Insets.NONE)
                .setVisible(WindowInsets.Type.ime(), up)
                .build(),
        )
        if (up) input.requestFocus()
        env.idle()
    }

    private fun dropdown(): android.widget.PopupWindow? =
        shadowOf(activity.application).latestPopupWindow?.takeIf { it.isShowing }

    /** Names in the open dropdown, top to bottom. */
    private fun dropdownNames(): List<String> {
        val list = dropdown()?.contentView as? ViewGroup ?: return emptyList()
        return (0 until list.childCount).map { i ->
            allViews(list.getChildAt(i)).filterIsInstance<TextView>().first().text.toString()
        }
    }

    @Test fun `the other recent accounts drop down while typing`() {
        withRecentAccounts()
        launch()
        assertTrue(dropdownNames().isEmpty())
        keyboard(up = true)
        assertEquals(listOf("Zeitboy", "Someone"), dropdownNames()) // the saved ID counts as nothing typed
        input.setText("zeit")
        assertEquals(listOf("Zeitboy"), dropdownNames())
        input.setText("999")
        assertNull(dropdown())
        input.setText("")
        keyboard(up = false)
        assertNull(dropdown())
    }

    @Test fun `picking a recent account checks it`() {
        withRecentAccounts()
        launch()
        keyboard(up = true)
        val row = (dropdown()!!.contentView as ViewGroup).getChildAt(0)
        val t = android.os.SystemClock.uptimeMillis()
        row.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        row.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 10f, 10f, 0))
        env.finishRequests()
        assertEquals(zeitboy.accountId, store.accountId)
        assertEquals("${zeitboy.accountId}", input.text.toString())
        assertNull(dropdown())
        assertEquals(listOf("/api/players/${zeitboy.accountId}"), env.server.requests.map { it.path })
    }

    @Test fun `removing a recent account updates the dropdown`() {
        withRecentAccounts()
        launch()
        keyboard(up = true)
        val list = dropdown()!!.contentView as ViewGroup
        allViews(list.getChildAt(0)).filterIsInstance<TextView>().single { it.text == "✕" }.performClick()
        assertEquals(listOf(zq, 5L), store.recentAccounts.map { it.accountId })
        assertEquals(listOf("Someone"), dropdownNames())
    }

    // --- buttons that leave the app -----------------------------------------------------

    @Test fun `add to glyph toys opens nothing's toy manager`() {
        launch()
        views<Button>().last { it.text == "Add to Glyph Toys" }.performClick()
        assertEquals(
            "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity",
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test fun `the toy suggestion's button opens the toy manager and goes away`() {
        launch()
        check("$zq")
        views<Button>().first { it.text == "Add to Glyph Toys" }.performClick()
        assertTrue(store.toyPromptDone)
        assertEquals(1, views<Button>().count { it.text == "Add to Glyph Toys" && it.isVisibleOnPage() })
        assertEquals("com.nothing.thirdparty", shadowOf(activity).nextStartedActivity.component?.packageName)
    }

    @Test fun `without the toy manager the app explains where to find it`() {
        launch()
        shadowOf(activity.application).checkActivities(true) // unknown activities can't be started
        views<Button>().last { it.text == "Add to Glyph Toys" }.performClick()
        assertEquals("Open Settings → Glyph Interface → Glyph Toys", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `a launcher that can't pin widgets gets instructions instead`() {
        launch()
        button("Add home-screen widget").performClick()
        assertEquals("Long-press the home screen → Widgets → Dota Rank Glyph", ShadowToast.getTextOfLatestToast())
    }

    private fun resource(path: String) = javaClass.getResource(path)!!.readText(Charsets.UTF_8)
}
