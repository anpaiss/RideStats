package com.anpaiss.ridestats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The RideStats screen on the Karoo: connect Google with a code confirmed on
 * the phone, then send the rides. After that everything happens on its own at
 * the end of each ride (RideStatsExtension).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var karoo: KarooSystemService
    private lateinit var uploader: Uploader
    private var loginJob: Job? = null

    private val status by lazy { findViewById<TextView>(R.id.status) }
    private val codeBox by lazy { findViewById<View>(R.id.code_box) }
    private val intro by lazy { findViewById<View>(R.id.intro) }
    private val codeText by lazy { findViewById<TextView>(R.id.code) }
    private val codeUrl by lazy { findViewById<TextView>(R.id.code_url) }
    private val connectBtn by lazy { findViewById<Button>(R.id.btn_connect) }
    private val sendBtn by lazy { findViewById<Button>(R.id.btn_send) }
    private val progress by lazy { findViewById<TextView>(R.id.progress) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        karoo = KarooSystemService(applicationContext).also { it.connect { } }
        uploader = Uploader(applicationContext, Net(karoo))
        connectBtn.setOnClickListener { if (uploader.auth.connected) disconnect() else connect() }
        sendBtn.setOnClickListener { send() }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        refresh()
    }

    override fun onDestroy() {
        karoo.disconnect()
        super.onDestroy()
    }

    private fun refresh() {
        val a = uploader.auth
        status.text = when {
            a.connected && a.needsReconnect -> getString(R.string.status_reconnect)
            a.connected -> getString(R.string.status_connected) + (uploader.lastSentAt.takeIf { it > 0 }?.let {
                " " + getString(R.string.status_last, uploader.lastSent,
                    android.text.format.DateUtils.formatDateTime(this, it, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME))
            } ?: "")
            else -> getString(R.string.status_not_connected)
        }
        connectBtn.text = getString(if (a.connected && !a.needsReconnect) R.string.disconnect else R.string.connect)
        sendBtn.isEnabled = a.connected && !a.needsReconnect
    }

    private fun connect() {
        loginJob?.cancel()
        uploader.auth.disconnect()
        loginJob = lifecycleScope.launch {
            val code = runCatching { withContext(Dispatchers.IO) { uploader.auth.startDeviceLogin() } }.getOrElse {
                status.text = getString(R.string.status_offline, it.message); return@launch
            }
            codeText.text = code.userCode
            codeUrl.text = code.url.removePrefix("https://").removePrefix("www.")
            showCode(true)
            status.text = getString(R.string.status_waiting)
            while (isActive && System.currentTimeMillis() < code.expiresAtMs) {
                delay(code.intervalS * 1000L)
                when (val p = runCatching { withContext(Dispatchers.IO) { uploader.auth.poll(code) } }.getOrNull()) {
                    GoogleAuth.Poll.Done -> { showCode(false); refresh(); send(); return@launch }
                    is GoogleAuth.Poll.Failed -> { showCode(false); status.text = getString(R.string.status_refused, p.reason); return@launch }
                    else -> {}   // pending, or a network hiccup: keep asking
                }
            }
            showCode(false)
            status.text = getString(R.string.status_expired)
        }
    }

    /** The code takes the place of the intro, so the whole box fits on the Karoo screen. */
    private fun showCode(on: Boolean) {
        codeBox.visibility = if (on) View.VISIBLE else View.GONE
        intro.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun disconnect() {
        uploader.auth.disconnect()
        refresh()
    }

    private fun send() {
        sendBtn.isEnabled = false
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                uploader.sync { done, total -> runOnUiThread { progress.text = getString(R.string.progress, done, total) } }
            }
            progress.text = when {
                r.error != null && r.sent == 0 -> getString(R.string.sync_failed, r.error)
                r.sent == 0 && r.failed == 0 -> getString(R.string.sync_nothing)
                else -> getString(R.string.sync_done, r.sent) + (if (r.failed > 0) " " + getString(R.string.sync_retry, r.failed) else "")
            }
            refresh()
        }
    }
}
