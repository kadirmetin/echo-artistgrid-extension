package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class UrlResolver(private val client: OkHttpClient) {

    companion object {
        private val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".opus", ".wma"
        )

        /** Hosts we can actually resolve to a playable audio stream. */
        private val RESOLVABLE_HOSTS = listOf(
            "pillows.su", "pillowcase.su",
            "pixeldrain.com",
            "krakenfiles.com",
            "imgur.gg",
            "files.yetracker.org",
            "soundcloud.com",
            "qobuz.com",
            "juicewrldapi.com"
        )

        /** Returns true only for URLs we know how to turn into audio. */
        private fun isResolvable(url: String): Boolean =
            RESOLVABLE_HOSTS.any { url.contains(it) } ||
                    AUDIO_EXTENSIONS.any { url.contains(it) }

        /** Returns true when the string looks like a URL. */
        fun isUrl(str: String?): Boolean =
            str != null && (str.startsWith("http://") || str.startsWith("https://"))

        /**
         * Picks the first link we can actually play.
         * Returns null if no resolvable URL exists — the track is then skipped entirely.
         */
        fun firstTrackUrl(links: List<String>?, quality: String?, availableLength: String?): String? =
            allTrackUrls(links, quality, availableLength).firstOrNull()

        /**
         * Returns ALL resolvable URLs for a track, in order.
         * Used to populate multiple Streamables so dead links have a fallback.
         */
        fun allTrackUrls(links: List<String>?, quality: String?, availableLength: String?): List<String> {
            val result = mutableListOf<String>()
            links?.filter { isUrl(it) && isResolvable(it) }?.mapTo(result) { normalize(it) }
            if (isUrl(quality) && isResolvable(quality!!)) result.add(normalize(quality))
            if (isUrl(availableLength) && isResolvable(availableLength!!)) result.add(normalize(availableLength))
            return result.distinct()
        }

        private fun normalize(url: String) = url.replace("pillowcase.su", "pillows.su")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolves [url] to a directly playable audio URL.
     * Returns null when the host is unsupported or the lookup fails.
     */
    suspend fun resolve(url: String): String? {
        val normalized = normalize(url)
        return try {
            when {
                normalized.contains("pillows.su/f/") -> resolvePillows(normalized)
                normalized.contains("pixeldrain.com/u/") ||
                normalized.contains("pixeldrain.com/d/") -> resolvePixeldrain(normalized)
                normalized.contains("krakenfiles.com/view/") -> resolveKrakenfiles(normalized)
                normalized.contains("imgur.gg") -> resolveImgur(normalized)
                normalized.contains("files.yetracker.org/f/") -> resolveYetracker(normalized)
                normalized.contains("soundcloud.com/") -> resolveSoundcloud(normalized)
                normalized.contains("qobuz.com/track/") -> resolveQobuz(normalized)
                // Hosts that serve audio directly — no resolution needed
                normalized.contains("juicewrldapi.com/juicewrld") -> normalized
                // Generic fallback: direct audio file extensions
                AUDIO_EXTENSIONS.any { normalized.contains(it) } -> normalized
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Per-host resolvers ────────────────────────────────────────────────────

    private fun resolvePillows(url: String): String? {
        val id = Regex("""pillows\.su/f/([a-f0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://api.pillows.su/api/download/$id"
    }

    private fun resolvePixeldrain(url: String): String? {
        val id = Regex("""pixeldrain\.com/[ud]/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://pixeldrain.com/api/file/$id"
    }

    private suspend fun resolveKrakenfiles(url: String): String? {
        val id = Regex("""krakenfiles\.com/view/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        val req = Request.Builder().url("https://info.artistgrid.cx/kf/?id=$id").build()
        val res = client.newCall(req).await()
        val data = parseJsonBody(res.body?.string()) ?: return null
        val success = data["success"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (success) data["m4a"]?.jsonPrimitive?.contentOrNull else null
    }

    private suspend fun resolveImgur(url: String): String? {
        val id = Regex("""/f/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([a-zA-Z0-9]+)(?:\?|$)""").find(url)?.groupValues?.get(1)
            ?: return null
        val req = Request.Builder().url("https://imgur.gg/api/file/$id").build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) return null
        val data = parseJsonBody(res.body?.string()) ?: return null
        val mediaType = data["mediaType"]?.jsonPrimitive?.contentOrNull
            ?: data["mimeType"]?.jsonPrimitive?.contentOrNull
            ?: ""
        if (mediaType.startsWith("image/")) return null  // images are not audio
        return data["cdnUrl"]?.jsonPrimitive?.contentOrNull
    }

    private fun resolveYetracker(url: String): String? {
        val id = Regex("""files\.yetracker\.org/f/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://files.yetracker.org/raw/$id"
    }

    private fun resolveSoundcloud(url: String): String? {
        val path = Regex("""soundcloud\.com/([^/]+/[^/?#]+)""").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://sc.maid.zone/_/restream/$path"
    }

    private suspend fun resolveQobuz(url: String): String? {
        val id = Regex("""qobuz\.com/track/(\d+)""").find(url)?.groupValues?.get(1)
            ?: return null
        val req = Request.Builder()
            .url("https://qobuz.squid.wtf/api/download-music?track_id=$id&quality=27")
            .build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) return null
        val data = parseJsonBody(res.body?.string()) ?: return null
        return data["data"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun parseJsonBody(body: String?) =
        body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
}
