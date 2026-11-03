package com.anpaiss.ridestats

import android.content.Context

/**
 * The e-bike battery at the start and end of each recorded ride, read from the
 * BoschData extension while recording. The FIT file does not carry it, so the
 * upload finds the entry by start time and adds it to the ride.
 */
class BatteryLog(context: Context) {

    class Entry(val startMs: Long, val first: Int, val last: Int)

    private val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)

    fun save(e: Entry) {
        val kept = entries().filter { it.startMs != e.startMs }.takeLast(MAX - 1) + e
        prefs.edit().putString(KEY, kept.joinToString("\n") { "${it.startMs},${it.first},${it.last}" }).apply()
    }

    /** The entry of a ride that started within a few minutes of [rideStartMs] (the Karoo starts the FIT when recording starts). */
    fun forRide(rideStartMs: Long): Entry? =
        entries().filter { Math.abs(it.startMs - rideStartMs) < MATCH_MS }.minByOrNull { Math.abs(it.startMs - rideStartMs) }

    private fun entries(): List<Entry> = prefs.getString(KEY, "").orEmpty().lines().mapNotNull { line ->
        line.split(',').takeIf { it.size == 3 }?.let { p -> runCatching { Entry(p[0].toLong(), p[1].toInt(), p[2].toInt()) }.getOrNull() }
    }

    private companion object {
        const val KEY = "rides"
        const val MAX = 200
        const val MATCH_MS = 5 * 60_000L
    }
}
