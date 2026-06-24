package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class ArtistGridApi(private val client: OkHttpClient) {

    companion object {
        const val ARTISTS_CSV_URL = "https://artists.artistgrid.cx/artists.csv"
        const val TRACKER_API_BASE = "https://trackerapi.artistgrid.cx"
        const val ASSETS_BASE = "https://assets.artistgrid.cx"

        // Regex patterns used for ID extraction
        private val PUBHTML_REGEX = Regex("""/spreadsheets/d/e/(2PACX-[a-zA-Z0-9_-]+)/""")
        private val SHEET_ID_REGEX = Regex("""/spreadsheets(?:/u/\d+)?/d/([a-zA-Z0-9_-]{20,})""")

        private val SPECIAL_IDS = mapOf(
            "yetracker.net" to "yetracker.net",
            "https://yetracker.net" to "yetracker.net",
            "https://yetracker.net/" to "yetracker.net"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── Artists CSV ───────────────────────────────────────────────────────────

    suspend fun fetchArtists(): List<ArtistData> {
        val request = Request.Builder().url(ARTISTS_CSV_URL).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: return emptyList()
        return parseCsv(body)
    }

    private fun parseCsv(csv: String): List<ArtistData> {
        val lines = csv.trim().split("\n")
        if (lines.size < 2) return emptyList()
        return lines.drop(1).mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val fields = splitCsvRow(line)
            if (fields.size < 6) return@mapNotNull null
            ArtistData(
                name = fields[0],
                url = fields[1],
                credit = fields[2],
                linksWork = fields[3].toIntOrNull() ?: 0,
                updated = fields[4].toIntOrNull() ?: 0,
                best = fields[5].trim().lowercase() == "true"
            )
        }
    }

    /** RFC 4180-compatible CSV row splitter — handles quoted fields with embedded commas/quotes. */
    private fun splitCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            when {
                inQuotes && row[i] == '"' && i + 1 < row.length && row[i + 1] == '"' -> {
                    cur.append('"'); i++ // escaped double-quote inside a quoted field
                }
                inQuotes && row[i] == '"' -> inQuotes = false
                !inQuotes && row[i] == '"' -> inQuotes = true
                !inQuotes && row[i] == ',' -> { fields.add(cur.toString()); cur.clear() }
                else -> cur.append(row[i])
            }
            i++
        }
        fields.add(cur.toString())
        return fields
    }

    // ── TrackerAPI ────────────────────────────────────────────────────────────

    suspend fun fetchTrackerData(trackerId: String, tabSlug: String? = null): V3Response? {
        return try {
            val endpoint = if (tabSlug != null) {
                "$TRACKER_API_BASE/sh/$trackerId/tab/${tabSlug.urlEncode()}"
            } else {
                "$TRACKER_API_BASE/sh/$trackerId/"
            }
            val request = Request.Builder().url(endpoint).build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            json.decodeFromString(V3Response.serializer(), body)
        } catch (_: Exception) {
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extracts the Google Sheets document ID from any tracker URL. */
    fun extractTrackerId(url: String): String? {
        SPECIAL_IDS[url]?.let { return it }
        PUBHTML_REGEX.find(url)?.groupValues?.get(1)?.let { return it }
        return SHEET_ID_REGEX.find(url)?.groupValues?.get(1)
    }

    /** Returns the CDN image URL for the given artist name. */
    fun getArtistImageUrl(name: String): String {
        val clean = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return "$ASSETS_BASE/$clean.webp"
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
