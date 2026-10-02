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
        val snap = MarketSnapshot(
            timestamp = System.currentTimeMillis(), nifty = board.nifty!!.copy(intraday = intra, daily = daily), bankNifty = board.bank, vix = board.vix?.copy(daily = vixDaily),
            futures = fut, optionChain = chain, constituents = cons, sectors = board.sectors, flows = flows, source = "test",
        )
        val out = NiftyDirectionEngine().process(snap)
        println("Bull %.2f Bear %.2f Range %.2f conf %s regime %s".format(out.direction.pBull, out.direction.pBear, out.direction.pRange,
            out.direction.confidence, out.regime.regime))
        println("Heavyweights: %+.1f pts, adv %d dec %d, weights=%s".format(out.heavyweights.totalContributionPts,
            out.heavyweights.advancers, out.heavyweights.decliners, out.heavyweights.weightsSource))
        out.heavyweights.rows.take(5).forEach { println("  ${it.symbol} %.2f%% wt, %+.2f%% → %+.1f pts".format(it.weightPct, it.changePct, it.contributionPts)) }
        out.signals.values.forEach { s -> println(s.name + ": " + "%+.2f (conf %.2f) ".format(s.score, s.confidence) + s.tags + " " + s.details.joinToString { it.key + "=" + it.value }) }
        println("Move: ${out.expectedMove}")
        println("Best option: ${out.options.best}")
        println("Decision: ${out.decision.headline} ${out.decision.reasons}")
    }
}
