package com.anpaiss.ridestats

import org.json.JSONObject
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
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
