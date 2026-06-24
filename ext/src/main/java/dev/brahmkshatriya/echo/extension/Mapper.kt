package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track

object Mapper {

    // ── ArtistData → Echo Artist ──────────────────────────────────────────────

    fun toArtist(data: ArtistData, api: ArtistGridApi): Artist {
        val trackerId = api.extractTrackerId(data.url)
        return Artist(
            id = trackerId ?: data.name,
            name = data.name,
            cover = imageUrl(api.getArtistImageUrl(data.name)),
            isRadioSupported = false,
            isSaveable = false,
            isLikeable = true,
            extras = mapOf(
                "trackerId" to (trackerId ?: ""),
                "sheetUrl" to data.url,
                "credit" to data.credit,
                "best" to data.best.toString()
            )
        )
    }

    // ── V3Response → Echo Feed tabs ───────────────────────────────────────────

    fun toTabs(v3: V3Response): List<Tab> = v3.tabs.map { Tab(id = it.slug, title = it.name) }

    // ── V3Response → List<Shelf> ──────────────────────────────────────────────

    fun toShelves(v3: V3Response, artist: Artist, trackerId: String, tabSlug: String): List<Shelf> {
        // Some tabs return a flat track list (e.g. "Recent")
        val flatTracks = v3.tracks
        if (!flatTracks.isNullOrEmpty()) {
            val tracks = flatTracks.mapNotNull { toTrackFromFlat(it, artist) }
            if (tracks.isEmpty()) return emptyList()
            return listOf(
                Shelf.Lists.Tracks(
                    id = "flat_tracks",
                    title = "Tracks",
                    list = tracks,
                    type = Shelf.Lists.Type.Linear,
                    more = null
                )
            )
        }

        // Era-grouped tabs → each era becomes an Album card
        val eras = v3.eras ?: return emptyList()
        return eras
            .filter { it.name.isNotBlank() && it.tracks.isNotEmpty() }
            .map { era -> Shelf.Item(toAlbum(era, artist, trackerId, tabSlug)) }
    }

    // ── V3Era → Echo Album ────────────────────────────────────────────────────

    fun toAlbum(era: V3Era, artist: Artist, trackerId: String, tabSlug: String): Album {
        val trackCount = era.tracks.size.toLong()
        val totalDuration = era.tracks
            .sumOf { parseDurationMs(it.trackLength) ?: 0L }
            .takeIf { it > 0L }
        return Album(
            id = "${trackerId}__${tabSlug}__${era.name}",
            title = era.name,
            type = Album.Type.PreRelease,
            cover = era.coverArt?.let { imageUrl(it) },
            artists = listOf(artist),
            trackCount = trackCount.takeIf { it > 0L },
            duration = totalDuration,
            description = era.description,
            isLikeable = true,
            extras = mapOf(
                "trackerId" to trackerId,
                "tabSlug" to tabSlug,
                "eraName" to era.name
            )
        )
    }

    /** Returns all playable tracks inside an era, for use in [loadTracks]. */
    fun eraToTracks(era: V3Era, artist: Artist): List<Track> =
        era.tracks.mapNotNull { toTrack(it, artist, era.coverArt) }

    // ── V3Track → Echo Track ──────────────────────────────────────────────────

    private fun toTrack(raw: V3Track, artist: Artist, fallbackImage: String?): Track? {
        val title = raw.name.title.ifBlank { raw.name.raw }.ifBlank { return null }
        val urls = UrlResolver.allTrackUrls(raw.links, raw.quality, raw.availableLength)
        if (urls.isEmpty()) return null
        val url = urls.first()

        val credits = raw.name.credits.filter { it.isNotBlank() }
        val coverUrl = raw.image ?: fallbackImage

        return Track(
            id = generateTrackId(url),
            title = title,
            artists = buildArtistList(artist, credits),
            cover = coverUrl?.let { imageUrl(it) },
            duration = parseDurationMs(raw.trackLength),
            description = raw.notes?.takeIf { it.isNotBlank() },
            isLikeable = true,
            streamables = urls.mapIndexed { index, u ->
                Streamable(
                    id = u,
                    quality = index,
                    type = Streamable.MediaType.Server,
                    extras = mapOf("url" to u)
                )
            },
            extras = buildMap {
                put("url", url)
                raw.quality?.takeIf { it.isNotBlank() && !UrlResolver.isUrl(it) }?.let { put("quality", it) }
                raw.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
                raw.leakDate?.let { put("leakDate", it) }
            }
        )
    }

    private fun toTrackFromFlat(raw: V3FlatTrack, artist: Artist): Track? {
        val title = raw.name.title.ifBlank { raw.name.raw }.ifBlank { return null }
        val urls = UrlResolver.allTrackUrls(raw.links, raw.quality, raw.availableLength)
        if (urls.isEmpty()) return null
        val url = urls.first()

        val credits = raw.name.credits.filter { it.isNotBlank() }

        return Track(
            id = generateTrackId(url),
            title = title,
            artists = buildArtistList(artist, credits),
            cover = raw.image?.let { imageUrl(it) },
            duration = parseDurationMs(raw.trackLength),
            description = raw.notes?.takeIf { it.isNotBlank() },
            isLikeable = true,
            streamables = urls.mapIndexed { index, u ->
                Streamable(
                    id = u,
                    quality = index,
                    type = Streamable.MediaType.Server,
                    extras = mapOf("url" to u)
                )
            },
            extras = buildMap {
                put("url", url)
                raw.quality?.takeIf { it.isNotBlank() && !UrlResolver.isUrl(it) }?.let { put("quality", it) }
                raw.type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
                raw.era.takeIf { it.isNotBlank() }?.let { put("era", it) }
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates an ImageHolder from a plain URL string. */
    fun imageUrl(url: String): ImageHolder =
        ImageHolder.NetworkRequestImageHolder(NetworkRequest(url), false)

    private fun buildArtistList(primary: Artist, credits: List<String>): List<Artist> {
        if (credits.isEmpty()) return listOf(primary)
        val featured = credits.map { Artist(id = it, name = it) }
        return listOf(primary) + featured
    }

    /** Stable, deterministic ID derived from the track's source URL. */
    fun generateTrackId(url: String): String {
        var hash = 0
        for (ch in url) hash = hash * 31 + ch.code
        return "tk${Integer.toUnsignedLong(hash).toString(36)}"
    }

    /**
     * Converts "m:ss" or "h:mm:ss" duration strings to milliseconds.
     * Returns null for missing, unknown, or unparseable values.
     */
    private fun parseDurationMs(duration: String?): Long? {
        if (duration.isNullOrBlank() || duration == "N/A" || duration == "?:??") return null
        val parts = duration.trim().split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toLongOrNull() ?: return null
                (m * 60 + s) * 1000
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toLongOrNull() ?: return null
                (h * 3600 + m * 60 + s) * 1000
            }
            else -> null
        }
    }
}
