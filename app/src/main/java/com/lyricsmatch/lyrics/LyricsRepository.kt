package com.lyricsmatch.lyrics

import android.content.Context
import com.lyricsmatch.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Fetches time-synced lyrics from LRCLIB (https://lrclib.net), with a memory + disk cache. */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "LyricsMatch/1.0 (personal Android app)"

    private val memory = object : LinkedHashMap<String, LyricsStatus>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsStatus>) = size > 64
    }

    suspend fun load(context: Context, np: NowPlaying): LyricsStatus = withContext(Dispatchers.IO) {
        val key = np.trackKey
        synchronized(memory) { memory[key] }?.let { return@withContext it }

        val file = cacheFile(context, key)
        readCache(file)?.let { cached ->
            synchronized(memory) { memory[key] = cached }
            return@withContext cached
        }

        val (status, blob) = try {
            query(np)
        } catch (e: IOException) {
            return@withContext LyricsStatus.Error("Couldn't reach the lyrics service")
        } catch (e: org.json.JSONException) {
            return@withContext LyricsStatus.Error("Lyrics service sent a bad response")
        }
        synchronized(memory) { memory[key] = status }
        if (blob != null) runCatching { file.parentFile?.mkdirs(); file.writeText(blob) }
        status
    }

    /** Drops the cached result so the next [load] queries the network again. */
    fun forget(context: Context, np: NowPlaying) {
        synchronized(memory) { memory.remove(np.trackKey) }
        cacheFile(context, np.trackKey).delete()
    }

    // ---- network ----

    private fun query(np: NowPlaying): Pair<LyricsStatus, String?> {
        val durationSec = np.durationMs / 1000
        val primaryArtist = primaryArtist(np.artist)
        val candidates = mutableListOf<JSONObject>()

        // 1) Exact signature match (fast, most accurate).
        if (!np.album.isNullOrBlank() && durationSec > 0) {
            getJson(
                "/get", mapOf(
                    "track_name" to np.title,
                    "artist_name" to np.artist,
                    "album_name" to np.album,
                    "duration" to durationSec.toString(),
                )
            )?.let { if (it is JSONObject) candidates += it }
        }

        // 2) Fuzzy search, with progressively cleaned titles/artists.
        if (candidates.none { it.optString("syncedLyrics").isNotBlank() }) {
            val tries = linkedSetOf(
                np.title to np.artist,
                np.title to primaryArtist,
                cleanTitle(np.title) to primaryArtist,
            )
            for ((title, artist) in tries) {
                val arr = getJson("/search", mapOf("track_name" to title, "artist_name" to artist))
                if (arr is JSONArray) for (i in 0 until arr.length()) candidates += arr.getJSONObject(i)
                if (candidates.any { it.optString("syncedLyrics").isNotBlank() }) break
            }
        }

        return pick(candidates, durationSec)
    }

    private fun pick(candidates: List<JSONObject>, durationSec: Long): Pair<LyricsStatus, String?> {
        // Ignore versions whose length is far off (live / extended edits would be out of sync).
        val fitting = if (durationSec > 0) {
            candidates.filter { abs(it.optDouble("duration", durationSec.toDouble()) - durationSec) <= 5 }
                .ifEmpty { candidates }
        } else candidates

        fitting.firstNotNullOfOrNull { c ->
            c.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        }?.let { lrc ->
            val lines = LrcParser.parse(lrc)
            if (lines.isNotEmpty()) return LyricsStatus.Synced(lines) to "S\n$lrc"
        }
        fitting.firstNotNullOfOrNull { c ->
            c.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        }?.let { return LyricsStatus.Plain(it) to "P\n$it" }
        if (fitting.any { it.optBoolean("instrumental") }) return LyricsStatus.Instrumental to "I\n"
        return LyricsStatus.NotFound to null
    }

    /** Returns parsed JSON, or null for 404. */
    private fun getJson(path: String, params: Map<String, String>): Any? {
        val qs = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("$BASE$path?$qs").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            return if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    // ---- helpers ----

    private fun primaryArtist(artist: String): String =
        artist.split(",", " & ", " feat.", " ft.", " x ", ";").first().trim()

    /** "Song (feat. X) - Remastered 2011" -> "Song" */
    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*[(\[](feat\.|ft\.|with |from |remaster|live|radio edit|version)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mix).*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun cacheFile(context: Context, key: String) =
        File(context.filesDir, "lyrics/${key.hashCode().toUInt()}_${key.length}.txt")

    private fun readCache(file: File): LyricsStatus? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val body = text.substringAfter('\n', "")
        return when (text.firstOrNull()) {
            'S' -> LrcParser.parse(body).takeIf { it.isNotEmpty() }?.let { LyricsStatus.Synced(it) }
            'P' -> LyricsStatus.Plain(body)
            'I' -> LyricsStatus.Instrumental
            else -> null
        }
    }
}
