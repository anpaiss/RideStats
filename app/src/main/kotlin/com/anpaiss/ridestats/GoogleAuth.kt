package com.anpaiss.ridestats

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Google sign-in for a device without a keyboard (OAuth "device flow"): the
 * Karoo shows a code, the rider confirms it on google.com/device from the
 * phone. Only `drive.file` is asked: access to the files RideStats creates and
 * nothing else in the Drive. (`drive.appdata` would hide them, but the device
 * flow refuses it: invalid_scope, tried 3/11/2026.)
 */
class GoogleAuth(context: Context, private val net: Net) {

    private val prefs = context.getSharedPreferences("google", Context.MODE_PRIVATE)

    class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalS: Int, val expiresAtMs: Long)

    sealed class Poll {
        object Pending : Poll()
        object Done : Poll()
        class Failed(val reason: String) : Poll()
    }

    val connected get() = prefs.getString(KEY_REFRESH, null) != null

    /** Set when Google refused the saved access (expired after 7 days while the app is in test, or revoked). */
    val needsReconnect get() = prefs.getBoolean(KEY_RECONNECT, false)

    fun startDeviceLogin(): DeviceCode {
        val j = post(DEVICE_URL, mapOf("client_id" to BuildConfig.GOOGLE_CLIENT_ID, "scope" to SCOPE))
        if (!j.has("user_code")) throw IOException(j.optString("error", "no code"))
        return DeviceCode(
            j.getString("user_code"), j.optString("verification_url", "https://www.google.com/device"),
            j.getString("device_code"), j.optInt("interval", 5), System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000,
        )
    }

    fun poll(code: DeviceCode): Poll {
        val j = post(TOKEN_URL, mapOf(
            "client_id" to BuildConfig.GOOGLE_CLIENT_ID, "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
            "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        ))
        if (j.has("access_token")) {
            prefs.edit()
                .putString(KEY_REFRESH, j.getString("refresh_token"))
                .putString(KEY_ACCESS, j.getString("access_token"))
                .putLong(KEY_EXPIRES, System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
                .putBoolean(KEY_RECONNECT, false)
                .apply()
            return Poll.Done
        }
        return when (val e = j.optString("error")) {
            "authorization_pending", "slow_down" -> Poll.Pending
            else -> Poll.Failed(e.ifBlank { "unknown" })
        }
    }

    /** A valid access token, refreshed when needed; null when not connected or Google refused the refresh. */
    fun accessToken(): String? {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null)
        if (access != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0) - 60_000) return access
        val j = post(TOKEN_URL, mapOf(
            "client_id" to BuildConfig.GOOGLE_CLIENT_ID, "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
            "refresh_token" to refresh, "grant_type" to "refresh_token",
        ))
        if (!j.has("access_token")) {
            if (j.optString("error") == "invalid_grant") prefs.edit().putBoolean(KEY_RECONNECT, true).apply()
            return null
        }
        prefs.edit()
            .putString(KEY_ACCESS, j.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
            .apply()
        return j.getString("access_token")
    }

    fun disconnect() = prefs.edit().clear().apply()

    private fun post(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val r = net.request("POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray())
        return runCatching { JSONObject(r.body) }.getOrElse { throw IOException("HTTP ${r.code}") }
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val KEY_REFRESH = "refresh"
        const val KEY_ACCESS = "access"
        const val KEY_EXPIRES = "expires"
        const val KEY_RECONNECT = "reconnect"
    }
}
