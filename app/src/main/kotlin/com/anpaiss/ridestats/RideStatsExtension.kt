package com.anpaiss.ridestats

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs in the background on the Karoo: when a ride ends (recording → idle) it
 * waits for the Karoo to close the FIT file, then sends the new rides to
 * Drive. It also catches up when the Karoo starts and, after a sync that could
 * not send everything (a ride ended out of WiFi), when a network comes back and
 * every 15 minutes until it works. Rides recorded with KarooSim on are not sent.
 */
class RideStatsExtension : KarooExtension("ridestats", BuildConfig.VERSION_NAME) {

    private lateinit var karoo: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consumerId: String? = null
    private var state: RideState = RideState.Idle
    private lateinit var uploader: Uploader
    private lateinit var sims: SimRides
    /** The last sync left rides unsent: retry when a network comes back, and every 15 minutes. */
    @Volatile private var pending = false
    private var retryJob: Job? = null
    private var simJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        karoo = KarooSystemService(applicationContext)
        uploader = Uploader(applicationContext, Net(karoo))
        sims = SimRides(applicationContext)
        watchNetwork()
        karoo.connect { connected ->
            if (!connected) return@connect
            scope.launch { delay(START_DELAY_MS); sync(uploader, "start") }
            consumerId = karoo.addConsumer(RideState.Params, { _ -> }, {}) { s: RideState ->
                val started = state is RideState.Idle && s !is RideState.Idle
                val ended = state !is RideState.Idle && s is RideState.Idle
                state = s
                if (started) { startBattery(); watchSimulator() }
                if (ended) {
                    stopBattery()
                    simJob?.cancel()
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

    // ── KarooSim: a ride recorded with the simulator on is noted, the upload leaves it out ──

    private fun watchSimulator() {
        val start = rideStartMs
        simJob?.cancel()
        simJob = scope.launch {
            while (true) {
                if (sims.simulatorOn()) { sims.mark(start); Log.i(Uploader.TAG, "KarooSim on: this ride will not be sent"); return@launch }
                delay(SIM_CHECK_MS)
            }
        }
    }

    // ── Retry after a sync that could not send everything ──

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (pending && state is RideState.Idle) scope.launch { delay(NET_SETTLE_MS); sync(uploader, "network back") }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (pending) {
                delay(RETRY_MS)
                if (state is RideState.Idle) sync(uploader, "retry")
            }
        }
    }

    private suspend fun sync(uploader: Uploader, why: String) {
        if (!uploader.auth.connected) return
        val r = runCatching { uploader.sync() }.getOrElse { Log.w(Uploader.TAG, "sync ($why): ${it.message}"); pending = true; scheduleRetry(); return }
        Log.i(Uploader.TAG, "sync ($why): sent ${r.sent}, failed ${r.failed}${r.error?.let { ", $it" } ?: ""}")
        pending = r.failed > 0 && r.error != null
        if (pending) { scheduleRetry(); return }   // offline: the backups would fail too
        runCatching { ExtensionBackups(applicationContext, uploader.net, uploader.auth).backupAll() }
            .onFailure { Log.w(Uploader.TAG, "backup ($why): ${it.message}") }
    }

    override fun onDestroy() {
        consumerId?.let { karoo.removeConsumer(it) }
        netCallback?.let { cb -> runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
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
        const val RETRY_MS = 15 * 60_000L
        /** A network just up may not route yet: wait a little before the sync. */
        const val NET_SETTLE_MS = 15_000L
        const val SIM_CHECK_MS = 20_000L
    }
}
