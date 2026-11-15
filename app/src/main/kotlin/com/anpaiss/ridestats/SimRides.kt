package com.anpaiss.ridestats

import android.content.Context
import android.net.Uri

/**
 * Rides recorded while KarooSim (our ride simulator: a fake GPS for testing at home) was on.
 * They are not rides: never sent to Drive, like BetterETA and boschRange never learn from them.
 * The FIT file does not say it, so the start time of the ride is noted while recording and the
 * upload matches it, as for the battery.
 */
class SimRides(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sim_rides", Context.MODE_PRIVATE)

    /** KarooSim installed and simulating now. Without KarooSim, or on any error: false. */
    fun simulatorOn(): Boolean = runCatching {
        if (appContext.packageManager.resolveContentProvider(AUTHORITY, 0) == null) return@runCatching false
        appContext.contentResolver.query(Uri.parse("content://$AUTHORITY/state"), null, null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("active")) == 1
        } ?: false
    }.getOrDefault(false)

    fun mark(rideStartMs: Long) {
        val kept = starts().filter { it != rideStartMs }.takeLast(MAX - 1) + rideStartMs
        prefs.edit().putString(KEY, kept.joinToString(",")).apply()
    }

    /** The ride that started within a few minutes of [rideStartMs] was simulated. */
    fun isSimulated(rideStartMs: Long): Boolean = starts().any { Math.abs(it - rideStartMs) < MATCH_MS }

    private fun starts(): List<Long> = prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private companion object {
        const val AUTHORITY = "com.anpaiss.karoosim.state"
        const val KEY = "starts"
        const val MAX = 100
        const val MATCH_MS = 5 * 60_000L
    }
}
