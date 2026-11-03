package com.anpaiss.ridestats

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compares the Kotlin reader with the prototype's extract.py (fitdecode) on
 * real Karoo rides: env RIDESTATS_FITDIR=<folder of .fit>, RIDESTATS_EXPECTED=<rides.json>. Skipped
 * when they are not given, so CI without the rides still passes.
 */
class FitSummaryTest {

    @Test
    fun matchesPrototypeOnRealRides() {
        val dir = System.getenv("RIDESTATS_FITDIR")?.let(::File)
        val expected = System.getenv("RIDESTATS_EXPECTED")?.let(::File)
        assumeTrue(dir != null && expected != null && dir.isDirectory && expected.exists())
        val rides = JSONArray(expected!!.readText())
        var checked = 0
        for (k in 0 until rides.length()) {
            val e = rides.getJSONObject(k)
            val id = e.getString("id")
            val s = FitSummary.read(id, File(dir, "$id.fit").readBytes()) { e.getInt("tz_min") }
            assertNotNull(id, s)
            val j = s!!.toJson()
            for (key in listOf("start", "ascent_m", "descent_m", "timer_s", "elapsed_s", "avg_hr", "max_hr", "avg_cad", "avg_temp")) {
                assertEquals("$id $key", e.opt(key)?.toString(), j.opt(key)?.toString())
            }
            // Python rounds half to even, Kotlin half up: one unit apart at most
            for (key in listOf("dist_m", "max_alt_m")) assertEquals("$id $key", e.getDouble(key), j.getDouble(key), 1.0)
            // moving time: fitdecode and this reader may differ by a few seconds on gaps
            assertEquals("$id moving_s", e.getLong("moving_s").toDouble(), j.getLong("moving_s").toDouble(), 30.0)
            assertEquals("$id excluded by speed", e.getBoolean("excluded") && !manual(e.getString("start")), s.looksLikeTest)
            checked++
        }
        println("checked $checked rides")
    }

    // The five test recordings Andrea listed by hand, not by speed
    private fun manual(start: String) = start.take(16) in setOf("2026-04-19T16:10", "2026-04-21T18:45", "2026-07-11T05:15", "2026-09-04T17:14", "2026-11-01T15:47")
}
