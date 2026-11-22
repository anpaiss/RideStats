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
    private val backupHeader by lazy { findViewById<View>(R.id.backup_header) }
    private val backupList by lazy { findViewById<android.widget.LinearLayout>(R.id.backup_list) }

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
        checkBackups()
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
                " " + resources.getQuantityString(R.plurals.status_last, uploader.lastSent, uploader.lastSent,
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
                else -> resources.getQuantityString(R.plurals.sync_done, r.sent, r.sent) + (if (r.failed > 0) " " + getString(R.string.sync_retry, r.failed) else "")
            }
            refresh()
            checkBackups()
        }
    }

    /** Backs up what is on and changed; a copy on Drive this Karoo has not adopted (after a reset) is offered for restore. */
    private fun checkBackups() {
        showBackups(backups.localStates())
        if (!uploader.auth.connected || uploader.auth.needsReconnect) return
        lifecycleScope.launch {
            val states = runCatching { withContext(Dispatchers.IO) { backups.backupAll() } }.getOrNull() ?: return@launch
            showBackups(states)
        }
    }

    /** One switch per extension that can be backed up, off until the rider turns it on. */
    private fun showBackups(states: List<ExtensionBackups.State>) {
        backupHeader.visibility = if (states.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.backup_intro).visibility = backupHeader.visibility
        backupList.removeAllViews()
        for (st in states) {
            val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = st.app.label + "\n" + when {
                    st.enabled == null -> getString(R.string.backup_new)
                    st.enabled == false -> getString(R.string.backup_off)
                    st.lastMs > 0 -> getString(R.string.backup_last, fmt(st.lastMs))
                    else -> getString(R.string.backup_none_yet)
                }
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                isChecked = st.enabled == true
                setPadding(0, 16, 0, 16)
                setOnCheckedChangeListener { _, on ->
                    backups.setEnabled(st.app, on)
                    checkBackups()
                }
            }
            backupList.addView(sw)
        }
        showPending(states.firstOrNull { it.pending != null })
    }

    private fun fmt(ms: Long) = android.text.format.DateUtils.formatDateTime(this, ms,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)

    private fun showPending(st: ExtensionBackups.State?) {
        val backup = st?.pending
        restoreBox.visibility = if (backup == null) View.GONE else View.VISIBLE
        if (st == null || backup == null) return
        val label = st.app.label
        val date = fmt(backup.modifiedMs)
        restoreText.text = getString(R.string.backup_found, label, date)
        restoreBtn.text = getString(R.string.backup_restore, label)
        restoreBtn.isEnabled = true
        restoreBtn.setOnClickListener {
            confirmDanger(getString(R.string.danger_restore, label, date), getString(R.string.danger_restore_btn)) {
                restoreBtn.isEnabled = false
                backupStatus.text = getString(R.string.backup_restoring, label)
                lifecycleScope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) { backups.restore(st.app, backup) }
                    backupStatus.text = msg
                    if (ok) checkBackups() else restoreBtn.isEnabled = true
                }
            }
        }
        keepBtn.setOnClickListener {
            confirmDanger(getString(R.string.danger_keep, label, date), getString(R.string.danger_keep_btn)) {
                backups.backUpThisKaroo(st.app)
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
