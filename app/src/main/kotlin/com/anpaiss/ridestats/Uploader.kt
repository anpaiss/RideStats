package com.anpaiss.ridestats

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.TimeZone

/**
 * Sends to Drive every ride of /sdcard/FitFiles not sent yet. The FIT names
 * already handled (sent, too short to be a ride, or simulated) are kept in a file, so a
 * sync only looks at new rides; whatever fails stays out of the list and is
 * tried again at the next sync.
 */
class Uploader(private val context: Context, val net: Net) {

    class Result(val sent: Int, val skipped: Int, val failed: Int, val error: String?)

    val auth = GoogleAuth(context, net)
    private val doneFile = File(context.filesDir, "handled_fits.txt")
    private val battery = BatteryLog(context)
    private val sims = SimRides(context)

    private val prefs = context.getSharedPreferences("uploads", Context.MODE_PRIVATE)

    /** When the last sync that sent something ran, and how many rides it sent. */
    val lastSentAt get() = prefs.getLong("last_at", 0)
    val lastSent get() = prefs.getInt("last_sent", 0)

    /** FIT files with a finished ride (a recording in progress has no session yet and is left for later). */
    fun fitFiles(): List<File> =
        File(Environment.getExternalStorageDirectory(), "FitFiles")
            .listFiles { f -> f.isFile && f.name.endsWith(".fit") }?.sortedBy { it.lastModified() }.orEmpty()

    suspend fun sync(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result = LOCK.withLock {
        if (!auth.connected) return Result(0, 0, 0, "not connected")
        val handled = handled().toMutableSet()
        val todo = fitFiles().filter { it.name !in handled }
        if (todo.isEmpty()) return Result(0, 0, 0, null)
        val drive = DriveClient(net) { auth.accessToken() ?: throw java.io.IOException("Google access refused") }
        val folder = runCatching { drive.ensureFolder() }.getOrElse { return Result(0, 0, todo.size, it.message) }
        var sent = 0; var skipped = 0; var failed = 0; var error: String? = null
        for ((k, f) in todo.withIndex()) {
            progress(k, todo.size)
            val ride = runCatching { FitSummary.read(f.nameWithoutExtension, f.readBytes(), ::tzMin) }.getOrNull()
                ?.let { r -> battery.forRide(r.startMs)?.let { r.copy(battStart = it.first, battEnd = it.last) } ?: r }
            when {
                ride == null -> { failed++ }   // still recording, or unreadable: next time
                ride.tooShort -> { skipped++; handled += f.name }
                sims.isSimulated(ride.startMs) -> { skipped++; handled += f.name; Log.i(TAG, "${f.name}: KarooSim ride, not sent") }
                else -> runCatching { drive.uploadRide(folder, ride.toJson()) }
                    .onSuccess { sent++; handled += f.name }
                    .onFailure { failed++; error = it.message; Log.w(TAG, "upload ${f.name}: ${it.message}") }
            }
            if (error != null && failed >= 3 && sent == 0) break   // offline: stop hammering
        }
        doneFile.writeText(handled.joinToString("\n"))
        if (sent > 0) prefs.edit().putLong("last_at", System.currentTimeMillis()).putInt("last_sent", sent).apply()
        progress(todo.size, todo.size)
        Log.i(TAG, "sync: sent $sent, skipped $skipped, failed $failed")
        Result(sent, skipped, failed, error)
    }

    private fun handled(): Set<String> =
        runCatching { doneFile.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    private fun tzMin(startMs: Long) = TimeZone.getDefault().getOffset(startMs) / 60_000

    companion object {
        const val TAG = "RideStats"
        private val LOCK = Mutex()
    }
}
