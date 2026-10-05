package com.niftyengine.app

import com.niftyengine.app.data.KiteApi
import com.niftyengine.app.data.KiteHistoricalSource
import com.niftyengine.app.store.AppJson
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.BacktestReport
import com.niftyengine.engine.engines.HistoricalBacktest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real Kite historical backtest from a workstation/CI:
 *   ./gradlew :app:testDebugUnitTest --tests '*KiteBacktestLiveRun*' -DkiteKey=... -DkiteToken=... -DbacktestMonths=6
 * Writes build/backtest/report.json and predictions.csv. Skipped unless credentials are given.
 */
class KiteBacktestLiveRun {
    @Test fun run() {
        val key = System.getProperty("kiteKey").orEmpty(); val token = System.getProperty("kiteToken").orEmpty()
        assumeTrue(key.isNotBlank() && token.isNotBlank())
        val months = System.getProperty("backtestMonths")?.toLongOrNull() ?: 3
        val to = Session.zdt(System.currentTimeMillis()).toLocalDate().minusDays(1)
        val from = to.minusMonths(months)
        val out = File("build/backtest").apply { mkdirs() }
        val src = KiteHistoricalSource(KiteApi(key, token), File(out, "cache")) { println("… $it") }
        val run = HistoricalBacktest(EngineConfig()).run(src, from, to, onProgress = { m, p -> println("[%3.0f%%] %s".format(p * 100, m)) })
        File(out, "report.json").writeText(AppJson.encodeToString(BacktestReport.serializer(), run.report))
        File(out, "predictions.csv").bufferedWriter().use { w ->
            w.write("time,spot,regime,horizon,score,pBull,pNeutral,pBear,calibrated,move,class,inRange\n")
            run.records.forEach { r ->
                r.horizons.forEach { h ->
                    val o = h.outcome
                    w.write("${r.timestamp},${r.spot},${r.regime},${h.id},${h.score},${h.pBull},${h.pNeutral},${h.pBear},${h.calibrated},${o?.move ?: ""},${o?.realized ?: ""},${o?.insideRange ?: ""}\n")
                }
            }
        }
        println("REPORT ${run.report}")
        println("SOURCE NOTES ${src.notes}")
    }
}
