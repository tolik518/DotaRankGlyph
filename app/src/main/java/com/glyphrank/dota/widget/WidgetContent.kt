package com.glyphrank.dota.widget

import com.glyphrank.dota.data.RankStore
import com.glyphrank.dota.glyph.MedalArt
import com.glyphrank.dota.glyph.RankRenderer
import com.glyphrank.dota.rank.RankTier

/** What [RankWidget] shows for the saved account: the medal frame, name, rank and when it was fetched. */
class WidgetContent(val frame: IntArray, val name: String, val rank: String, val fetchedAtMs: Long?) {
    companion object {
        fun of(store: RankStore, medals: MedalArt?, renderer: RankRenderer = RankRenderer()): WidgetContent {
            val accountId = store.accountId
                ?: return WidgetContent(renderer.message("ID"), "Dota Rank", "Set your ID in the app", null)
            val cached = store.cachedForCurrentAccount()
                ?: return WidgetContent(renderer.loading(0), "Player $accountId", "Not checked yet", null)
            val player = cached.player
            return WidgetContent(
                renderer.render(player.state, medals, store.showImmortalRank),
                player.personaName ?: "Player ${player.accountId}",
                RankTier.describe(player.state),
                cached.fetchedAtMs,
            )
        }
    }
}
