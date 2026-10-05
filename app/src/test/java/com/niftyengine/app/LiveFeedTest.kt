package com.niftyengine.app

import com.niftyengine.app.data.NseClient
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.model.MarketSnapshot
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real NSE endpoints. Run with: ./gradlew :app:testDebugUnitTest -DliveNetwork=true */
class LiveFeedTest {
    @Test
    fun nseFeedsParseAndEngineRuns() {
        assumeTrue(System.getProperty("liveNetwork") == "true")
        val board = NseClient.allIndices()
        println("NIFTY ${board.nifty?.last} prev ${board.nifty?.prevClose} VIX ${board.vix?.last} sectors ${board.sectors.keys}")
        val cons = NseClient.nifty50Constituents()
        println("constituents ${cons.size}, ffmc on ${cons.values.count { !it.freeFloatMcap.isNaN() }}")
        val chain = NseClient.optionChain()
        println("chain exp ${chain.expiry} rows ${chain.rows.size} underlying ${chain.underlying} step ${chain.strikeStep}")
        val fut = NseClient.futures()
        println("futures $fut")
        val flows = NseClient.fiiDii()
        println("flows $flows")
        val intra = runCatching { NseClient.indexIntraday() }.getOrElse { println("intraday failed: $it"); emptyList() }
        println("intraday pts ${intra.size} first ${intra.firstOrNull()} last ${intra.lastOrNull()}")
        val today = com.niftyengine.engine.core.Session.sessionStart(System.currentTimeMillis())
        val daily = NseClient.indexDaily("NIFTY 50", today)
        val vixDaily = runCatching { NseClient.indexDaily("INDIA VIX", today) }.getOrElse { println("vix daily failed $it"); emptyList() }
        println("daily ${daily.size} last ${daily.lastOrNull()} vixDaily ${vixDaily.size}")
        val gift = NseClient.giftNifty()
        println("gift $gift")
        val snap = MarketSnapshot(
            timestamp = System.currentTimeMillis(), nifty = board.nifty!!.copy(intraday = intra, daily = daily), bankNifty = board.bank, vix = board.vix?.copy(daily = vixDaily),
            futures = fut, optionChain = chain, constituents = cons, sectors = board.sectors, flows = flows, source = "test",
            giftNifty = gift,
        )
        val out = NiftyDirectionEngine().process(snap)
        out.horizons.forEach { h -> println("%-9s %s %.0f%% score %+.1f σ %.0f cov %.0f%% conf %s".format(h.id.short, h.direction, h.probability * 100, h.score, h.sigmaPts, h.coverage * 100, h.confidence)) }
        println("master ${out.master.direction} ${out.master.alignmentLabel} conf ${out.master.confidence} regime ${out.regime.regime}")
        println("Heavyweights: %+.1f pts, adv %d dec %d, weights=%s".format(out.heavyweights.totalContributionPts,
            out.heavyweights.advancers, out.heavyweights.decliners, out.heavyweights.weightsSource))
        out.heavyweights.rows.take(5).forEach { println("  ${it.symbol} %.2f%% wt, %+.2f%% → %+.1f pts".format(it.weightPct, it.changePct, it.contributionPts)) }
        out.signals.values.forEach { s -> println(s.name + ": " + "%+.2f (conf %.2f) ".format(s.score, s.confidence) + s.tags + " " + s.details.joinToString { it.key + "=" + it.value }) }
        println("Weekly: ${out.weekly?.let { "S ${it.support?.strike} R ${it.resistance?.strike} pin ${it.pin?.strike} ${it.regime}" }}")
        println("Best structure: ${out.strategies.best?.title}")
        println("Decision: ${out.decision.headline} ${out.decision.reasons}")
        println("GIFT report: ${out.gift}")
        println("Data quality %.0f%% breaker=${out.dataQuality.circuitBreaker}".format(out.dataQuality.score * 100))
        out.dataQuality.feeds.forEach { println("  " + it.name + ": " + it.status + " age=" + "%.0fs ".format(it.ageSeconds) + it.detail) }
        println("Best structure net: ${out.strategies.best?.let { "EV gross %.2f cost %.2f net %.2f".format(it.expectedPnlGross, it.costPerUnit, it.expectedPnl) }}")
    }
}
