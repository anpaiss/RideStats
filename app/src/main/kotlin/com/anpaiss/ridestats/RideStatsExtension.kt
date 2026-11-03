package com.anpaiss.ridestats

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
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
                val ended = state !is RideState.Idle && s is RideState.Idle
                state = s
                if (ended) scope.launch { delay(END_DELAY_MS); sync(uploader, "ride end") }
            }
        }
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
        /** The Karoo writes the FIT when the ride is saved: give it time. */
        const val END_DELAY_MS = 30_000L
        const val START_DELAY_MS = 60_000L
    }
}
