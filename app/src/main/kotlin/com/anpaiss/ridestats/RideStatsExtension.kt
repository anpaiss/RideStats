package com.anpaiss.ridestats

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs in the background on the Karoo: when a ride ends (recording → idle) it
 * waits for the Karoo to close the FIT file, then sends the new rides to
 * Drive. It also catches up once when the Karoo starts, for rides that ended
 * while there was no connection.
 */
class RideStatsExtension : KarooExtension("ridestats", BuildConfig.VERSION_NAME) {

    private lateinit var karoo: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consumerId: String? = null
    private var state: RideState = RideState.Idle

    override fun onCreate() {
        super.onCreate()
        karoo = KarooSystemService(applicationContext)
        val uploader = Uploader(applicationContext, Net(karoo))
        karoo.connect { connected ->
            if (!connected) return@connect
            scope.launch { delay(START_DELAY_MS); sync(uploader, "start") }
            consumerId = karoo.addConsumer(RideState.Params, { _ -> }, {}) { s: RideState ->
                val started = state is RideState.Idle && s !is RideState.Idle
                val ended = state !is RideState.Idle && s is RideState.Idle
                state = s
                if (started) startBattery()
                if (ended) {
                    stopBattery()
                    scope.launch { delay(END_DELAY_MS); sync(uploader, "ride end") }
                }
            }
        }
    }

    // ── e-bike battery, from the BoschData extension, only while recording ──

    private var batteryId: String? = null
    private var rideStartMs = 0L
    @Volatile private var firstPct: Int? = null
    @Volatile private var lastPct: Int? = null

    private fun startBattery() {
        rideStartMs = System.currentTimeMillis()
        firstPct = null; lastPct = null
        // Streaming only while the bike is on and BoschData talks to it; without BoschData nothing arrives
        batteryId = karoo.addConsumer(OnStreamState.StartStreaming(BIKE_BATTERY), { _ -> }, {}) { ev: OnStreamState ->
            (ev.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                val pct = Math.round(it).toInt()
                if (firstPct == null) firstPct = pct
                lastPct = pct
            }
        }
    }

    private fun stopBattery() {
        batteryId?.let { karoo.removeConsumer(it) }
        batteryId = null
        val first = firstPct ?: return
        BatteryLog(applicationContext).save(BatteryLog.Entry(rideStartMs, first, lastPct ?: first))
        Log.i(Uploader.TAG, "battery: $first% → ${lastPct ?: first}%")
    }

    private suspend fun sync(uploader: Uploader, why: String) {
        if (!uploader.auth.connected) return
        val r = runCatching { uploader.sync() }.getOrElse { Log.w(Uploader.TAG, "sync ($why): ${it.message}"); return }
        Log.i(Uploader.TAG, "sync ($why): sent ${r.sent}, failed ${r.failed}${r.error?.let { ", $it" } ?: ""}")
    }

    override fun onDestroy() {
        consumerId?.let { karoo.removeConsumer(it) }
        karoo.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** The battery stream of the BoschData extension (the one boschRange reads). */
        val BIKE_BATTERY = DataType.dataTypeId("bosch-data", "bike_battery")
        /** The Karoo writes the FIT when the ride is saved: give it time. */
        const val END_DELAY_MS = 30_000L
        const val START_DELAY_MS = 60_000L
    }
}
