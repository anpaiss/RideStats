package com.anpaiss.ridestats

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.security.MessageDigest

/**
 * Keeps in the rider's Drive a copy of what our other extensions have learned
 * (BetterETA: model, way memory, history), so a reset of the Karoo does not
 * lose it. Each extension hands its data through a provider that only apps
 * with our signature can call.
 *
 * After a reset both RideStats and the extension start empty: an empty
 * extension must not overwrite the good copy on Drive. So an app is backed up
 * only once this Karoo has "adopted" it: when Drive has no copy yet, after a
 * restore, or when the rider chooses to keep the data the Karoo has.
 */
class ExtensionBackups(private val context: Context, private val net: Net, private val auth: GoogleAuth) {

    class App(val pkg: String, val label: String) {
        val authority = "$pkg.backup"
        val fileName = "$pkg.backup.gz"
    }

    /** A copy on Drive that this Karoo has not adopted yet: restore it, or keep what is here. */
    class Pending(val app: App, val backup: DriveClient.Backup)

    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)

    fun installed(): List<App> = APPS.filter { context.packageManager.resolveContentProvider(it.authority, 0) != null }

    private fun drive() = DriveClient(net) { auth.accessToken() ?: throw java.io.IOException("Google access refused") }

    /** Sends the apps whose data changed; for apps not adopted yet, adopts them only if Drive has no copy. */
    fun backupAll(): List<Pending> = synchronized(LOCK) {
        if (!auth.connected) return@synchronized emptyList()
        val apps = installed()
        if (apps.isEmpty()) return@synchronized emptyList()
        val drive = drive()
        val folder = drive.ensureBackupFolder()
        val pending = mutableListOf<Pending>()
        for (app in apps) runCatching {
            val existing = drive.findBackup(folder, app.fileName)
            if (!adopted(app)) {
                if (existing != null) { pending += Pending(app, existing); return@runCatching }
                adopt(app)
            }
            val data = export(app)
            val hash = sha256(data)
            if (existing != null && hash == prefs.getString("hash_${app.pkg}", null)) return@runCatching
            drive.saveBackup(folder, app.fileName, data, existing)
            prefs.edit().putString("hash_${app.pkg}", hash).putLong("at_${app.pkg}", System.currentTimeMillis()).apply()
            Log.i(Uploader.TAG, "backup ${app.label}: ${data.size} bytes sent")
        }.onFailure { Log.w(Uploader.TAG, "backup ${app.label}: ${it.message}") }
        pending
    }

    /** Puts the Drive copy back into the app. Returns the line to show and whether it worked. */
    fun restore(p: Pending): Pair<Boolean, String> = synchronized(LOCK) { runCatching {
        val data = drive().download(p.backup.id)
        val r = context.contentResolver.call(Uri.parse("content://${p.app.authority}"), "import", null,
            Bundle().apply { putByteArray("data", data) }) ?: return@runCatching false to "${p.app.label} did not answer"
        val ok = r.getBoolean("ok")
        if (ok) { adopt(p.app); prefs.edit().putString("hash_${p.app.pkg}", sha256(data)).apply() }
        ok to (r.getString("result") ?: "")
    }.getOrElse { false to "Restore failed: ${it.message}" } }

    /** The rider keeps the data on this Karoo: from now on it replaces the copy on Drive. */
    fun keepCurrent(app: App) = adopt(app)

    private fun export(app: App): ByteArray =
        context.contentResolver.call(Uri.parse("content://${app.authority}"), "export", null, null)
            ?.getByteArray("data") ?: throw IllegalStateException("${app.label} gave no data")

    private fun adopted(app: App) = prefs.getBoolean("adopted_${app.pkg}", false)
    private fun adopt(app: App) = prefs.edit().putBoolean("adopted_${app.pkg}", true).apply()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        val APPS = listOf(App("com.anpaiss.bettereta", "BetterETA"))
        /** The background sync and the app screen run in the same process. */
        private val LOCK = Any()
    }
}
