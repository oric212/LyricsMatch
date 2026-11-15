package com.lyricsmatch.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds album art when the music app doesn't provide any: first Spotify's public oEmbed
 * endpoint (exact cover, needs only the track ID from Spotify's broadcast), then the free
 * iTunes Search API (artist and title only).
 */
object ArtworkFinder {
    private val cache = HashMap<String, Bitmap?>()

    /** Blocking; call from a background thread. */
    fun find(artistIn: String, titleIn: String, spotifyId: String? = null): Bitmap? {
        spotifyId?.let { id -> spotifyCover(id)?.let { return it } }
        return itunes(artistIn, titleIn)
    }

    /** The track's cover from Spotify's public oEmbed endpoint (no login needed). */
    private fun spotifyCover(id: String): Bitmap? {
        val key = "spotify:$id"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = try {
            get("https://open.spotify.com/oembed?url=${Uri.encode("https://open.spotify.com/track/$id")}")
                ?.let(::JSONObject)
                ?.optString("thumbnail_url")
                ?.takeIf { it.startsWith("https://") }
                // 300px thumbnail id -> 640px version of the same image.
                ?.let { download(it.replace("ab67616d00001e02", "ab67616d0000b273")) ?: download(it) }
        } catch (_: Exception) {
            null
        }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun itunes(artistIn: String, titleIn: String): Bitmap? {
        var artist = artistIn
        var title = titleIn
        // Local files often put everything in the title: "Artist - Song".
        if (artist.isBlank() && " - " in title) {
            artist = title.substringBefore(" - ")
            title = title.substringAfter(" - ")
        }
        val primary = primaryArtist(artist)
        val song = normalize(cleanTitle(title))
        val term = "$primary ${cleanTitle(title)}".trim()
        if (term.isEmpty() || song.isEmpty()) return null
        synchronized(cache) { if (cache.containsKey(term)) return cache[term] }

        val result = try {
            val json = get("https://itunes.apple.com/search?entity=song&limit=5&term=${Uri.encode(term)}")
                ?.let(::JSONObject)
            val results = json?.optJSONArray("results")
            // Only accept the same song (another track's cover would be misleading),
            // preferring one by the same artist.
            val sameSong = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }.filter {
                val other = normalize(cleanTitle(it.optString("trackName")))
                other.isNotEmpty() && (other.contains(song) || song.contains(other))
            }
            val best = sameSong.firstOrNull { primary.isNotEmpty() && it.optString("artistName").contains(primary, true) }
                ?: sameSong.firstOrNull()
            best?.optString("artworkUrl100")?.takeIf { it.isNotBlank() }?.let { small ->
                download(small.replace("100x100bb", "600x600bb"))
            }
        } catch (_: Exception) {
            null
        }
        synchronized(cache) { cache[term] = result }
        return result
    }

    /**
     * True for generic "no cover" images (e.g. a music app's logo for local files): a logo made
     * of a handful of flat colours, which real album covers almost never are.
     */
    fun isPlaceholder(bmp: Bitmap): Boolean = try {
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val small = Bitmap.createScaledBitmap(soft, 24, 24, true)
        val colours = HashSet<Int>()
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            colours += ((p shr 21) and 0x7) or (((p shr 13) and 0x7) shl 3) or (((p shr 5) and 0x7) shl 6)
        }
        colours.size <= 6
    } catch (_: RuntimeException) {
        false
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            if (conn.responseCode != 200) null else conn.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            conn.disconnect()
        }
    }

    private fun normalize(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun primaryArtist(artist: String) =
        artist.split(",", " & ", " feat.", " ft.", " x ", ";").first().trim()

    private fun cleanTitle(title: String) =
        title.replace(Regex("""\s*[(\[](feat\.|ft\.|with )[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
}
