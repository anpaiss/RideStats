package com.anpaiss.ridestats

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One ride as RideStats stores it in Drive and the dashboard reads it: the
 * totals of the FIT `session` message plus the moving time worked out from the
 * records. Same fields and rules as the prototype's extract.py, so the numbers
 * match what Andrea already checked.
 */
data class RideSummary(
    val id: String,
    val startMs: Long,
    val tzMin: Int,
    val distM: Double,
    val ascentM: Int?,
    val descentM: Int?,
    val movingS: Long,
    val timerS: Long,
    val elapsedS: Long,
    val maxSpeedMs: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCad: Int?,
    val avgTemp: Int?,
    val maxAltM: Double?,
    val battStart: Int? = null,
    val battEnd: Int? = null,
) {
    /** Too short to be a ride: a few metres or a few minutes. Never uploaded. */
    val tooShort get() = distM < 1000 || movingS < 300

    /** Looks like a test or a car trip: uploaded, but unticked in the dashboard. */
    val looksLikeTest get() = (distM / movingS * 3.6).let { it < 3 || it > 30 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("start", ISO.get()!!.format(startMs))
        put("tz_min", tzMin)
        put("dist_m", Math.round(distM))
        put("ascent_m", ascentM ?: JSONObject.NULL)
        put("descent_m", descentM ?: JSONObject.NULL)
        put("moving_s", movingS)
        put("timer_s", timerS)
        put("elapsed_s", elapsedS)
        put("max_speed_ms", maxSpeedMs?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
        put("avg_hr", avgHr ?: JSONObject.NULL)
        put("max_hr", maxHr ?: JSONObject.NULL)
        put("avg_cad", avgCad ?: JSONObject.NULL)
        put("avg_temp", avgTemp ?: JSONObject.NULL)
        put("max_alt_m", maxAltM?.let { Math.round(it).toDouble() } ?: JSONObject.NULL)
        put("batt_start", battStart ?: JSONObject.NULL)
        put("batt_end", battEnd ?: JSONObject.NULL)
        put("excluded", looksLikeTest)
    }

    private companion object {
        val ISO = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}

/**
 * Minimal FIT reader for the summary: the `session` message (18) and, from the
 * `record` messages (20), timestamp and distance for the moving time. Same
 * approach as BetterETA's FitRecordReader: definitions give size and type of
 * every field, so unknown and developer fields are skipped.
 */
object FitSummary {

    private const val MSG_SESSION = 18
    private const val MSG_RECORD = 20
    private const val FIT_EPOCH_S = 631_065_600L      // 1989-12-31 00:00 UTC
    /** Below 0.5 m/s (1.8 km/h) the bike counts as stopped. */
    private const val MOVING_MS = 0.5

    private class FieldDef(val num: Int, val size: Int)
    private class MsgDef(val globalNum: Int, val littleEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int) {
        val dataSize = fields.sumOf { it.size } + devBytes
    }

    /** Null when the file has no session (a ride still recording, or a broken file). */
    fun read(id: String, bytes: ByteArray, tzOffsetMin: (Long) -> Int): RideSummary? {
        if (bytes.size < 14 || bytes[8] != '.'.code.toByte() || bytes[9] != 'F'.code.toByte()) return null
        val headerSize = bytes[0].toInt() and 0xff
        val end = minOf(bytes.size.toLong(), headerSize + u32(bytes, 4, true)).toInt()
        val defs = HashMap<Int, MsgDef>()
        var session: Map<Int, Long>? = null
        var lastTimeS = 0L
        var moving = 0L
        var prevT = -1L; var prevD = 0.0
        var i = headerSize

        fun onRecord(f: Map<Int, Long>) {
            val t = f[253] ?: lastTimeS
            f[253]?.let { lastTimeS = it }
            val d = f[5]?.let { it / 100.0 } ?: return
            if (prevT >= 0) {
                val dt = t - prevT
                if (dt in 1..10 && (d - prevD) / dt > MOVING_MS) moving += dt
            }
            prevT = t; prevD = d
        }

        while (i < end) {
            val h = bytes[i].toInt() and 0xff
            i++
            if (h and 0x80 != 0) {
                // Compressed header: local type in bits 5-6, time offset in bits 0-4
                val def = defs[(h shr 5) and 0x3] ?: return null
                if (i + def.dataSize > end) break
                val offset = (h and 0x1f).toLong()
                lastTimeS = (lastTimeS and 0x1f.inv().toLong()) + offset + (if (offset < (lastTimeS and 0x1f)) 32 else 0)
                if (def.globalNum == MSG_RECORD) onRecord(fields(bytes, i, def) + (253 to lastTimeS))
                i += def.dataSize
                continue
            }
            val local = h and 0x0f
            if (h and 0x40 != 0) {
                if (i + 5 > end) break
                val little = bytes[i + 1].toInt() == 0
                val global = u16(bytes, i + 2, little).toInt()
                val n = bytes[i + 4].toInt() and 0xff
                var p = i + 5
                val list = ArrayList<FieldDef>(n)
                repeat(n) { list.add(FieldDef(bytes[p].toInt() and 0xff, bytes[p + 1].toInt() and 0xff)); p += 3 }
                var devBytes = 0
                if (h and 0x20 != 0) {
                    val nDev = bytes[p].toInt() and 0xff
                    p++
                    repeat(nDev) { devBytes += bytes[p + 1].toInt() and 0xff; p += 3 }
                }
                defs[local] = MsgDef(global, little, list, devBytes)
                i = p
            } else {
                val def = defs[local] ?: return null
                if (i + def.dataSize > end) break
                val f = fields(bytes, i, def)
                when (def.globalNum) {
                    MSG_RECORD -> onRecord(f)
                    MSG_SESSION -> session = f
                    else -> f[253]?.let { lastTimeS = it }
                }
                i += def.dataSize
            }
        }

        val s = session ?: return null
        val startS = s[2] ?: return null
        val startMs = (startS + FIT_EPOCH_S) * 1000
        val timer = (s[8] ?: 0L) / 1000
        return RideSummary(
            id = id,
            startMs = startMs,
            tzMin = tzOffsetMin(startMs),
            distM = (s[9] ?: 0L) / 100.0,
            ascentM = s[22]?.toInt(),
            descentM = s[23]?.toInt(),
            movingS = moving,
            timerS = timer,
            elapsedS = (s[7]?.let { it / 1000 }) ?: timer,
            maxSpeedMs = (s[125] ?: s[15])?.let { it / 1000.0 },
            avgHr = s[16]?.toInt(),
            maxHr = s[17]?.toInt(),
            avgCad = s[18]?.toInt(),
            avgTemp = s[57]?.toInt(),
            maxAltM = (s[128] ?: s[50])?.let { it / 5.0 - 500.0 },
        )
    }

    /** Valid values of the 1, 2 and 4 byte fields; "invalid" (all bits set, or 0x7F for sint8) is left out. */
    private fun fields(b: ByteArray, start: Int, def: MsgDef): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var p = start
        for (f in def.fields) {
            when (f.size) {
                1 -> {
                    val u = b[p].toLong() and 0xff
                    // avg_temperature (57) is the only signed byte read here: invalid is 0x7F
                    if (f.num == 57) { if (u != 0x7fL) out[57] = b[p].toLong() } else if (u != 0xffL) out[f.num] = u
                }
                2 -> u16(b, p, def.littleEndian).takeIf { it != 0xffffL }?.let { out[f.num] = it }
                4 -> u32(b, p, def.littleEndian).takeIf { it != 0xffffffffL }?.let { out[f.num] = it }
            }
            p += f.size
        }
        return out
    }

    private fun u16(b: ByteArray, p: Int, le: Boolean): Long {
        val x = b[p].toLong() and 0xff; val y = b[p + 1].toLong() and 0xff
        return if (le) x or (y shl 8) else (x shl 8) or y
    }

    private fun u32(b: ByteArray, p: Int, le: Boolean): Long {
        var v = 0L
        for (k in 0 until 4) v = v or ((b[p + (if (le) k else 3 - k)].toLong() and 0xff) shl (8 * k))
        return v
    }
}
