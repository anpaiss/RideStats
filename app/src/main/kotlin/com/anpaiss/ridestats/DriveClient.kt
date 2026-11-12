package com.anpaiss.ridestats

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/**
 * The few Drive calls RideStats needs: a "RideStats" folder in the rider's
 * Drive and one JSON file per ride inside it, named after the FIT file so a
 * second upload of the same ride finds the first one instead of duplicating it.
 */
class DriveClient(private val net: Net, private val token: () -> String) {

    fun ensureFolder(): String {
        find("name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false")?.let { return it }
        val r = net.request("POST", "$API/files?fields=id", auth() + ("Content-Type" to "application/json"),
            JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME).toString().toByteArray())
        return idOf(r)
    }

    /** Uploads the ride unless a file with its name is already in the folder. Returns true if it wrote one. */
    fun uploadRide(folderId: String, ride: JSONObject): Boolean {
        val name = "ride-${ride.getString("id")}.json"
        if (find("name = '$name' and '$folderId' in parents and trashed = false") != null) return false
        val b = UUID.randomUUID().toString()
        val meta = JSONObject().put("name", name).put("mimeType", "application/json").put("parents", org.json.JSONArray().put(folderId))
        val body = "--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$b\r\nContent-Type: application/json\r\n\r\n$ride\r\n--$b--"
        idOf(net.request("POST", "$UPLOAD/files?uploadType=multipart&fields=id",
            auth() + ("Content-Type" to "multipart/related; boundary=$b"), body.toByteArray()))
        return true
    }

    // ── Backups of our other extensions, one file per app, in a folder of their own:
    //    the dashboard reads every file of the RideStats folder as a ride ──

    class Backup(val id: String, val modifiedMs: Long)

    fun ensureBackupFolder(): String {
        find("name = '$BACKUP_FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false")?.let { return it }
        val meta = JSONObject().put("name", BACKUP_FOLDER).put("mimeType", FOLDER_MIME)
        return idOf(net.request("POST", "$API/files?fields=id", auth() + ("Content-Type" to "application/json"), meta.toString().toByteArray()))
    }

    fun findBackup(folderId: String, name: String): Backup? {
        val q = URLEncoder.encode("name = '$name' and '$folderId' in parents and trashed = false", "UTF-8")
        val r = net.request("GET", "$API/files?fields=files(id,modifiedTime)&pageSize=1&q=$q", auth())
        if (r.code != 200) throw IOException("Drive search: HTTP ${r.code}")
        val f = JSONObject(r.body).optJSONArray("files")?.optJSONObject(0) ?: return null
        val ms = runCatching { java.time.Instant.parse(f.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0L)
        return Backup(f.getString("id"), ms)
    }

    /** Writes the backup over the previous one (Drive keeps the earlier revisions for a while). */
    fun saveBackup(folderId: String, name: String, data: ByteArray, existing: Backup?) {
        if (existing != null) {
            // HttpURLConnection has no PATCH: Google takes the method from this header
            idOf(net.request("POST", "$UPLOAD/files/${existing.id}?uploadType=media&fields=id",
                auth() + ("Content-Type" to "application/gzip") + ("X-HTTP-Method-Override" to "PATCH"), data))
            return
        }
        val b = UUID.randomUUID().toString()
        val meta = JSONObject().put("name", name).put("mimeType", "application/gzip").put("parents", org.json.JSONArray().put(folderId))
        val body = ByteArrayOutputStream().apply {
            write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: application/gzip\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$b--".toByteArray())
        }.toByteArray()
        idOf(net.request("POST", "$UPLOAD/files?uploadType=multipart&fields=id",
            auth() + ("Content-Type" to "multipart/related; boundary=$b"), body))
    }

    fun download(id: String): ByteArray {
        val r = net.request("GET", "$API/files/$id?alt=media", auth())
        if (r.code != 200) throw IOException("Drive download: HTTP ${r.code}")
        return r.bytes
    }

    private fun find(q: String): String? {
        val r = net.request("GET", "$API/files?fields=files(id)&pageSize=1&q=" + URLEncoder.encode(q, "UTF-8"), auth())
        if (r.code != 200) throw IOException("Drive search: HTTP ${r.code}")
        val files = JSONObject(r.body).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun idOf(r: HttpResult): String {
        if (r.code != 200) throw IOException("Drive: HTTP ${r.code} ${r.body.take(200)}")
        return JSONObject(r.body).getString("id")
    }

    private fun auth() = mapOf("Authorization" to "Bearer ${token()}")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "RideStats"
        const val BACKUP_FOLDER = "RideStats Backups"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
