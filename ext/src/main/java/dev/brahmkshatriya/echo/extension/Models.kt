package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Artist list (from artists.csv) ───────────────────────────────────────────

data class ArtistData(
    val name: String,
    val url: String,
    val credit: String,
    val linksWork: Int,
    val updated: Int,
    val best: Boolean
)

// ─── TrackerAPI V3 response models ────────────────────────────────────────────

@Serializable
data class V3TrackName(
    val raw: String = "",
    val title: String = "",
    val credits: List<String> = emptyList()
)

@Serializable
data class V3Track(
    val name: V3TrackName = V3TrackName(),
    val notes: String? = null,
    @SerialName("track_length") val trackLength: String? = null,
    @SerialName("file_date") val fileDate: String? = null,
    @SerialName("leak_date") val leakDate: String? = null,
    @SerialName("available_length") val availableLength: String? = null,
    val quality: String? = null,
    val links: List<String>? = null,
    val image: String? = null,
    val type: String? = null,
    @SerialName("sub_era") val subEra: String? = null
)

@Serializable
data class V3FlatTrack(
    val name: V3TrackName = V3TrackName(),
    val notes: String? = null,
    @SerialName("track_length") val trackLength: String? = null,
    @SerialName("available_length") val availableLength: String? = null,
    val quality: String? = null,
    val links: List<String>? = null,
    val image: String? = null,
    val type: String? = null,
    @SerialName("sub_era") val subEra: String? = null,
    val era: String = "",
    @SerialName("era_color") val eraColor: String? = null,
    @SerialName("era_text_color") val eraTextColor: String? = null,
    @SerialName("og_filename") val ogFilename: String? = null
)

@Serializable
data class V3Era(
    val name: String = "",
    val aka: List<String>? = null,
    val timeline: String? = null,
    val description: String? = null,
    @SerialName("cover_art") val coverArt: String? = null,
    val color: String? = null,
    @SerialName("text_color") val textColor: String? = null,
    val tracks: List<V3Track> = emptyList()
)

@Serializable
data class V3Tab(
    val name: String,
    val slug: String,
    val gid: String = ""
)

@Serializable
data class V3Response(
    val name: String = "",
    val tab: V3Tab = V3Tab("", ""),
    val tabs: List<V3Tab> = emptyList(),
    val eras: List<V3Era>? = null,
    val tracks: List<V3FlatTrack>? = null
)

// ─── Local liked-item storage ──────────────────────────────────────────────────

@Serializable
data class LikedItemData(
    val type: String,           // "artist" | "album" | "track"
    val id: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val extras: Map<String, String> = emptyMap(),
    val duration: Long? = null,
    val description: String? = null,
    val artistNames: List<String> = emptyList(),
    val artistIds: List<String> = emptyList()
)
