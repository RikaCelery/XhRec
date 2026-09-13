package github.rikacelery.cutter

import github.rikacelery.cutter.events.EventStats
import github.rikacelery.cutter.events.GiftMarker
import github.rikacelery.cutter.events.SpecialMarker
import github.rikacelery.cutter.events.Timeline
import github.rikacelery.cutter.events.ToyInterval
import github.rikacelery.cutter.media.Power
import github.rikacelery.cutter.suggest.CutSuggester
import github.rikacelery.cutter.suggest.SuggestPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Suggestion pipeline behaviour.
 *
 * The two properties that matter are the ones the raw data violates: a run of
 * two-second micro-commands must collapse into a handful of blocks (no jitter), and
 * genuinely separate bursts must stay separate (holes are glued only when they are
 * actually holes).
 */
class CutSuggesterTest {

    private fun toy(start: Double, end: Double, power: Power = Power.LOW) =
        ToyInterval(start, end, power, null, null, null)

    private fun timeline(
        intervals: List<ToyInterval> = emptyList(),
        specials: List<SpecialMarker> = emptyList(),
        gifts: List<GiftMarker> = emptyList(),
        chats: List<Double> = emptyList()
    ) = Timeline(
        toyIntervals = intervals,
        levelSegments = intervals,
        toySpecials = specials,
        gifts = gifts,
        goals = emptyList(),
        chats = chats,
        events = emptyList(),
        stats = EventStats(0, 0, 0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, Power.NONE, null, null, github.rikacelery.cutter.events.TimeConfidence.EXACT)
    )

    /** Twelve 2-second commands chained 0.5 s apart must become one block. */
    @Test
    fun `a burst of micro commands collapses into a single block`() {
        val intervals = (0 until 12).map { toy(it * 2.5, it * 2.5 + 2.0) }
        val blocks = CutSuggester.suggest(timeline(intervals), 3600.0, SuggestPrefs(minBlock = 1.0))
        assertEquals(1, blocks.size, "expected one glued block, got ${blocks.map { it.start to it.end }}")
        assertTrue(blocks.single().end - blocks.single().start >= 20.0)
    }

    /** Two bursts an hour apart must not be glued together. */
    @Test
    fun `distant bursts stay separate`() {
        val first = (0 until 10).map { toy(it * 2.5, it * 2.5 + 2.0) }
        val second = (0 until 10).map { toy(1800.0 + it * 2.5, 1800.0 + it * 2.5 + 2.0) }
        val blocks = CutSuggester.suggest(timeline(first + second), 3600.0, SuggestPrefs(minBlock = 1.0))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].end < blocks[1].start)
        assertTrue(blocks[1].start - blocks[0].end > 1000)
    }

    /**
     * A large `mergeGap` glues two nearby bursts into one; a small one keeps them
     * apart. This is the knob the UI exposes, so it has to actually work.
     */
    @Test
    fun `mergeGap controls whether a hole is glued`() {
        val a = (0 until 10).map { toy(it * 2.5, it * 2.5 + 2.0) }
        val b = (0 until 10).map { toy(200.0 + it * 2.5, 200.0 + it * 2.5 + 2.0) }

        // The hole between the two bursts is ~175 s.
        val glued = CutSuggester.suggest(timeline(a + b), 3600.0, SuggestPrefs(mergeGap = 200.0, minBlock = 1.0))
        assertEquals(1, glued.size, "mergeGap=200 should close a 175 s hole")

        val split = CutSuggester.suggest(timeline(a + b), 3600.0, SuggestPrefs(mergeGap = 10.0, minBlock = 1.0))
        assertEquals(2, split.size, "mergeGap=10 must not close a 175 s hole")
    }

    /** An isolated two-second blip is jitter, not content. */
    @Test
    fun `an isolated blip is dropped`() {
        val blocks = CutSuggester.suggest(timeline(listOf(toy(1000.0, 1002.0))), 3600.0)
        assertTrue(blocks.isEmpty(), "a 2 s isolated command should not become a suggestion")
    }

    /**
     * A short block right next to a long one is absorbed, not discarded: its events
     * still belong to the neighbouring cut.
     */
    @Test
    fun `a short block beside a long one is absorbed`() {
        val long = (0 until 40).map { toy(it * 2.0, it * 2.0 + 2.0) }        // 0..80
        val blip = listOf(toy(100.0, 102.0))                                  // 20 s later
        val blocks = CutSuggester.suggest(
            timeline(long + blip), 3600.0,
            SuggestPrefs(mergeGap = 5.0, minBlock = 45.0, absorbGap = 60.0)
        )
        assertEquals(1, blocks.size, "the blip should join the neighbouring block")
        assertTrue(blocks.single().end >= 102.0)
    }

    @Test
    fun `special commands and gifts produce blocks with reasons`() {
        val t = timeline(
            specials = listOf(SpecialMarker(500.0, "giveControl", "UZU96", 69)),
            gifts = listOf(GiftMarker(500.0, 1000, "interactiveToy", "UZU96", "specialCommand"))
        )
        val blocks = CutSuggester.suggest(t, 3600.0, SuggestPrefs(minBlock = 1.0, minScore = 0.0))
        assertEquals(1, blocks.size)
        val kinds = blocks.single().reasons.map { it.kind }.toSet()
        assertTrue("SPECIAL" in kinds)
        assertTrue("TIP" in kinds)
        assertEquals(1000L, blocks.single().tipTotal)
    }

    /** Chat only counts in clusters; scattered messages are not a reason to cut. */
    @Test
    fun `scattered chat does not create suggestions but a cluster does`() {
        val scattered = (0 until 20).map { it * 300.0 }
        val noBlocks = CutSuggester.suggest(
            timeline(chats = scattered), 3600.0,
            SuggestPrefs(minBlock = 1.0, minScore = 0.0, mergeGap = 5.0)
        )
        assertTrue(noBlocks.isEmpty(), "one message every five minutes is not a cluster")

        val clustered = (0 until 40).map { 1000.0 + it * 1.0 }
        val blocks = CutSuggester.suggest(
            timeline(chats = clustered), 3600.0,
            SuggestPrefs(minBlock = 1.0, minScore = 0.0)
        )
        assertTrue(blocks.isNotEmpty(), "40 messages in 40 s is a cluster")
    }

    /** Every block must be inside the recording. */
    @Test
    fun `blocks are clamped to the recording`() {
        val intervals = listOf(toy(0.0, 3.0)) + (0 until 10).map { toy(it * 2.5 + 60, it * 2.5 + 62) }
        val blocks = CutSuggester.suggest(timeline(intervals), 200.0)
        assertTrue(blocks.all { it.start >= 0.0 && it.end <= 200.0 }, "got ${blocks.map { it.start to it.end }}")
    }

    /**
     * The pure toy rule: only *runtime* creates candidates.
     *
     * A gift burst with no toy running must produce nothing at all, and a block's
     * rank must not move when gifts or chat are added — otherwise the rule is not
     * pure, it just has small weights.
     */
    @Test
    fun `toyOnly ignores gifts and chat entirely`() {
        val giftsOnly = timeline(
            gifts = (0 until 30).map { GiftMarker(1000.0 + it, 500, "interactiveToy", "u", null) },
            chats = (0 until 60).map { 1000.0 + it * 0.5 }
        )
        assertTrue(
            CutSuggester.suggest(giftsOnly, 3600.0, SuggestPrefs(toyOnly = true, minBlock = 1.0, minScore = 0.0)).isEmpty(),
            "with the pure rule, gifts and chat alone must not create a block"
        )
        // The same timeline does produce blocks under the default mixed rule.
        assertTrue(
            CutSuggester.suggest(giftsOnly, 3600.0, SuggestPrefs(minBlock = 1.0, minScore = 0.0)).isNotEmpty(),
            "the default rule should still react to gifts"
        )
    }

    @Test
    fun `toyOnly ranks purely by runtime`() {
        val intervals = (0 until 30).map { toy(it * 2.0, it * 2.0 + 2.0) }
        val bare = CutSuggester.suggest(timeline(intervals), 3600.0, SuggestPrefs(toyOnly = true, minBlock = 1.0, minScore = 0.0))
        val withGifts = CutSuggester.suggest(
            timeline(intervals, gifts = (0 until 30).map { GiftMarker(it * 2.0, 9999, "interactiveToy", "u", null) }),
            3600.0, SuggestPrefs(toyOnly = true, minBlock = 1.0, minScore = 0.0)
        )
        assertEquals(1, bare.size)
        assertEquals(1, withGifts.size)
        assertEquals(bare.single().score, withGifts.single().score, 1e-9)
        assertEquals(bare.single().start, withGifts.single().start, 1e-9)
        assertEquals(bare.single().end, withGifts.single().end, 1e-9)
        // The block is only ever justified by runtime.
        assertTrue(withGifts.single().reasons.all { it.kind == "TOY" })
    }

    @Test
    fun `an empty timeline yields no suggestions`() {
        assertTrue(CutSuggester.suggest(timeline(), 3600.0).isEmpty())
    }
}
