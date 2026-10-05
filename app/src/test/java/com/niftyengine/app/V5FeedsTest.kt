package com.niftyengine.app

import com.niftyengine.app.data.NseClient
import com.niftyengine.app.data.TimeParse
import com.niftyengine.app.store.FlowHistoryStore
import com.niftyengine.app.store.ParticipantOiStore
import com.niftyengine.app.store.parseCalendar
import com.niftyengine.engine.model.CalendarCategory
import com.niftyengine.engine.model.FlowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** v5 data plumbing: NSE participant-wise OI, monthly expiry selection, flow history, user calendar. */
class V5FeedsTest {
    /** Format of nsearchives.nseindia.com/content/nsccl/fao_participant_oi_01102026.csv. */
    private val csv = """
        ""Participant wise Open Interest (no. of contracts) in Equity Derivatives as on Oct 01, 2026"",,,,,,,,,,,,,,
        Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short       ,Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put Short,Option Stock Call Long,Option Stock Put Long,Option Stock Call Short,Option Stock Put Short,Total Long Contracts      ,Total Short Contracts
        Client,306566,56754,3422680,155072,3743798,2363022,3448314,3206170,1566739,571650,883971,875714,11974455,8625995
        DII,48119,14862,264333,4574585,8533,40402,3557,876,9411,43501,204308,22745,414299,4820933
        FII,29605,339779,3393649,2858568,670483,1114773,1101948,448195,112470,227850,206962,90579,5548830,5046031
        Pro,51101,23996,817937,310374,1337674,958371,1206669,821327,669169,831768,1062548,685731,4666020,4110645
        TOTAL,435391,435391,7898599,7898599,5760488,4476568,5760488,4476568,2357789,1674769,2357789,1674769,22603604,22603604
    """.trimIndent()

    @Test fun parsesFiiRowOfParticipantOi() {
        val d = NseClient.parseParticipantOi(csv, LocalDate.of(2026, 10, 1))!!
        assertEquals("2026-10-01", d.date)
        assertEquals(29605.0, d.futIndexLong, 0.0)
        assertEquals(339779.0, d.futIndexShort, 0.0)
        assertEquals(670483.0, d.callLong, 0.0)
        assertEquals(1101948.0, d.callShort, 0.0)
        assertEquals(1114773.0, d.putLong, 0.0)
        assertEquals(448195.0, d.putShort, 0.0)
        assertEquals(29605.0 / (29605 + 339779), d.futLongPct, 1e-9)
        assertTrue("long puts / short calls = bearish exposure", d.netOptionExposure < 0)
        assertNull(NseClient.parseParticipantOi("<!DOCTYPE html><html>not found</html>", LocalDate.of(2026, 10, 3)))
    }

    @Test fun monthlyExpiryIsTheLastListedExpiryOfTheNearestMonth() {
        val list = listOf("06-Oct-2026", "13-Oct-2026", "19-Oct-2026", "27-Oct-2026", "03-Nov-2026", "23-Nov-2026", "29-Dec-2026")
        assertEquals("27-Oct-2026", NseClient.monthlyExpiryOf(list))
        // On the monthly week the nearest expiry IS the monthly one.
        assertNull(NseClient.monthlyExpiryOf(listOf("27-Oct-2026", "03-Nov-2026", "23-Nov-2026")))
        // Holiday-shifted (Monday) monthly expiry is still found.
        assertEquals("23-Nov-2026", NseClient.monthlyExpiryOf(listOf("03-Nov-2026", "10-Nov-2026", "17-Nov-2026", "23-Nov-2026", "29-Dec-2026")))
    }

    @Test fun flowHistoryAndParticipantStoresPersist() {
        val dir = File("build/tmp/v5feeds").apply { deleteRecursively(); mkdirs() }
        val fs = FlowHistoryStore(File(dir, "flows.json"))
        fs.add(FlowData(fpiNetCr = -1200.0, diiNetCr = 900.0, date = "30-Sep-2026", asOf = TimeParse.istDate("30-Sep-2026")))
        fs.add(FlowData(fpiNetCr = 800.0, diiNetCr = 300.0, date = "01-Oct-2026", asOf = TimeParse.istDate("01-Oct-2026")))
        fs.add(FlowData(fpiNetCr = 800.0, diiNetCr = 300.0, date = "01-Oct-2026", asOf = TimeParse.istDate("01-Oct-2026"))) // duplicate
        val reloaded = FlowHistoryStore(File(dir, "flows.json")).all()
        assertEquals(listOf("2026-09-30", "2026-10-01"), reloaded.map { it.date })

        val ps = ParticipantOiStore(File(dir, "poi.json"))
        ps.put(LocalDate.of(2026, 10, 1), NseClient.parseParticipantOi(csv, LocalDate.of(2026, 10, 1)))
        ps.put(LocalDate.of(2026, 10, 2), null) // holiday
        val ps2 = ParticipantOiStore(File(dir, "poi.json"))
        assertTrue(ps2.has(LocalDate.of(2026, 10, 1)) && ps2.has(LocalDate.of(2026, 10, 2)))
        assertEquals(1, ps2.last(20).size)
    }

    @Test fun userCalendarParsing() {
        val ev = parseCalendar("# comment\n2026-10-08 | RBI | RBI MPC decision | 3\nbad line\n2026-10-14|earnings|HDFC Bank Q2\n2026-13-01 | FED | invalid date | 3")
        assertEquals(2, ev.size)
        assertEquals(CalendarCategory.RBI, ev[0].category)
        assertEquals(3, ev[0].importance)
        assertEquals(CalendarCategory.EARNINGS, ev[1].category)
        assertEquals(2, ev[1].importance)
    }
}
