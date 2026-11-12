package com.anpaiss.ridestats

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.security.MessageDigest

/**
 * Keeps in the rider's Drive a copy of what our other extensions have learned
 * (BetterETA: model, way memory, history…), so a reset of the Karoo does not
 * lose it. Each extension opens a provider of the KarooSwitches library,
 * guarded by a permission only our own apps hold: RideStats finds them all
 * on the Karoo, so a new extension shows up here without changing RideStats.
 *
 * Nothing is backed up until the rider turns it on, per extension.
 *
 * After a reset both RideStats and the extension start empty: an empty
 * extension must not overwrite the good copy on Drive. So an app is backed up
 * only once this Karoo has "adopted" it: when Drive has no copy yet, after a
 * restore, or when the rider chooses to back up this Karoo instead.
 */
class ExtensionBackups(private val context: Context, private val net: Net, private val auth: GoogleAuth) {

    class App(val pkg: String, val label: String, val authority: String) {
        val fileName = "$pkg.backup.gz"
    }

    /**
     * One row of the Backups list. [enabled] null = the rider has not chosen yet (off).
     * [pending]: a copy on Drive this Karoo has not adopted, to restore or replace.
     */
    class State(val app: App, val enabled: Boolean?, val lastMs: Long, val pending: DriveClient.Backup?)

    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)

    /** Our extensions on this Karoo that open a backup provider. */
    fun discover(): List<App> {
        val pm = context.packageManager
        return pm.queryContentProviders(null, 0, 0).orEmpty()
            .filter { it.readPermission == PERMISSION && it.packageName != context.packageName && it.authority != null }
            .map { App(it.packageName, pm.getApplicationLabel(it.applicationInfo).toString(), it.authority.split(';').first()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** The list as known without asking Drive (for the screen before the network answers). */
    fun localStates(): List<State> = discover().map { State(it, enabled(it), prefs.getLong("at_${it.pkg}", 0), null) }

    /**
     * Backs up the extensions that are on and whose data changed. Never overwrites a copy on Drive
     * this Karoo has not adopted: that one comes back as [State.pending].
     */
    fun backupAll(): List<State> = synchronized(LOCK) {
        val apps = discover()
        if (!auth.connected || apps.isEmpty()) return@synchronized localStates()
        val drive = drive()
        val folder = runCatching { drive.ensureBackupFolder() }
            .getOrElse { Log.w(Uploader.TAG, "backup: ${it.message}"); return@synchronized localStates() }
        apps.map { app ->
            var pending: DriveClient.Backup? = null
            val on = enabled(app)
            if (on != false) runCatching {
                val existing = drive.findBackup(folder, app.fileName)
                if (!adopted(app)) {
                    // after a reset, or a second Karoo: ask first, whether it was on or not chosen yet
                    if (existing != null) { pending = existing; return@runCatching }
                    if (on != true) return@runCatching
                    adopt(app)
                }
                if (on != true) return@runCatching
                val data = export(app)
                val hash = sha256(data)
                if (existing != null && hash == prefs.getString("hash_${app.pkg}", null)) return@runCatching
                drive.saveBackup(folder, app.fileName, data, existing)
                prefs.edit().putString("hash_${app.pkg}", hash).putLong("at_${app.pkg}", System.currentTimeMillis()).apply()
                Log.i(Uploader.TAG, "backup ${app.label}: ${data.size} bytes sent")
            }.onFailure { Log.w(Uploader.TAG, "backup ${app.label}: ${it.message}") }
            State(app, enabled(app), prefs.getLong("at_${app.pkg}", 0), pending)
        }
    }

    /** Puts the Drive copy back into the app. Returns whether it worked and the line to show. */
    fun restore(app: App, backup: DriveClient.Backup): Pair<Boolean, String> = synchronized(LOCK) { runCatching {
        val data = drive().download(backup.id)
        val r = context.contentResolver.call(Uri.parse("content://${app.authority}"), "import", null,
            Bundle().apply { putByteArray("data", data) }) ?: return@runCatching false to "${app.label} did not answer"
        val ok = r.getBoolean("ok")
        if (ok) {
            setEnabled(app, true); adopt(app)
            prefs.edit().putString("hash_${app.pkg}", sha256(data)).putLong("at_${app.pkg}", backup.modifiedMs).apply()
        }
        ok to "${app.label}: ${r.getString("result") ?: ""}"
    }.getOrElse { false to "Restore failed: ${it.message}" } }

    /** The rider keeps the data on this Karoo: from now on it replaces the copy on Drive. */
    fun backUpThisKaroo(app: App) { setEnabled(app, true); adopt(app) }

    fun setEnabled(app: App, on: Boolean) = prefs.edit().putBoolean("enabled_${app.pkg}", on).apply()

    private fun enabled(app: App): Boolean? =
        if (prefs.contains("enabled_${app.pkg}")) prefs.getBoolean("enabled_${app.pkg}", false) else null

    private fun drive() = DriveClient(net) { auth.accessToken() ?: throw java.io.IOException("Google access refused") }

    private fun export(app: App): ByteArray =
        context.contentResolver.call(Uri.parse("content://${app.authority}"), "export", null, null)
            ?.getByteArray("data") ?: throw IllegalStateException("${app.label} gave no data")

    private fun adopted(app: App) = prefs.getBoolean("adopted_${app.pkg}", false)
    private fun adopt(app: App) = prefs.edit().putBoolean("adopted_${app.pkg}", true).apply()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        /** Declared by the KarooSwitches library in every extension that can be backed up. */
        const val PERMISSION = "com.anpaiss.permission.EXTENSION_BACKUP"
        /** The background sync and the app screen run in the same process. */
        private val LOCK = Any()
    }
}
