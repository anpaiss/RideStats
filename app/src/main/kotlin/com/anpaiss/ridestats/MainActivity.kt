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
    private lateinit var backups: ExtensionBackups
    private var loginJob: Job? = null

    private val status by lazy { findViewById<TextView>(R.id.status) }
    private val codeBox by lazy { findViewById<View>(R.id.code_box) }
    private val intro by lazy { findViewById<View>(R.id.intro) }
    private val codeText by lazy { findViewById<TextView>(R.id.code) }
    private val codeUrl by lazy { findViewById<TextView>(R.id.code_url) }
    private val connectBtn by lazy { findViewById<Button>(R.id.btn_connect) }
    private val sendBtn by lazy { findViewById<Button>(R.id.btn_send) }
    private val progress by lazy { findViewById<TextView>(R.id.progress) }
    private val restoreBox by lazy { findViewById<View>(R.id.restore_box) }
    private val restoreText by lazy { findViewById<TextView>(R.id.restore_text) }
    private val restoreBtn by lazy { findViewById<Button>(R.id.btn_restore) }
    private val keepBtn by lazy { findViewById<Button>(R.id.btn_keep) }
    private val backupStatus by lazy { findViewById<TextView>(R.id.backup_status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        karoo = KarooSystemService(applicationContext).also { it.connect { } }
        uploader = Uploader(applicationContext, Net(karoo))
        backups = ExtensionBackups(applicationContext, uploader.net, uploader.auth)
        connectBtn.setOnClickListener { if (uploader.auth.connected) disconnect() else connect() }
        sendBtn.setOnClickListener { send() }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        refresh()
        if (uploader.auth.connected && !uploader.auth.needsReconnect) checkBackups()
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
            checkBackups()
        }
    }

    /** Backs up what changed; a copy on Drive this Karoo has not adopted (after a reset) is offered for restore. */
    private fun checkBackups() {
        lifecycleScope.launch {
            val pending = runCatching { withContext(Dispatchers.IO) { backups.backupAll() } }.getOrDefault(emptyList())
            showPending(pending.firstOrNull())
        }
    }

    private fun showPending(p: ExtensionBackups.Pending?) {
        restoreBox.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p == null) return
        val label = p.app.label
        val date = android.text.format.DateUtils.formatDateTime(this, p.backup.modifiedMs,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)
        restoreText.text = getString(R.string.backup_found, label, date)
        restoreBtn.text = getString(R.string.backup_restore, label)
        restoreBtn.isEnabled = true
        restoreBtn.setOnClickListener {
            confirmDanger(getString(R.string.danger_restore, label, date), getString(R.string.danger_restore_btn)) {
                restoreBtn.isEnabled = false
                backupStatus.text = getString(R.string.backup_restoring, label)
                lifecycleScope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { backups.restore(p) }
                    backupStatus.text = msg
                    if (ok) checkBackups() else restoreBtn.isEnabled = true
                }
            }
        }
        keepBtn.setOnClickListener {
            confirmDanger(getString(R.string.danger_keep, label, date), getString(R.string.danger_keep_btn)) {
                backups.keepCurrent(p.app)
                backupStatus.text = getString(R.string.backup_kept, label)
                checkBackups()
            }
        }
    }

    /** Second step for anything that overwrites data: a red box that says what is lost, confirm or cancel. */
    private fun confirmDanger(text: String, confirm: String, onConfirm: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_danger, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        view.findViewById<TextView>(R.id.danger_text).text = text
        view.findViewById<Button>(R.id.danger_confirm).apply {
            this.text = confirm
            setOnClickListener { dialog.dismiss(); onConfirm() }
        }
        view.findViewById<Button>(R.id.danger_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFFB71C1C.toInt()))
    }
}
