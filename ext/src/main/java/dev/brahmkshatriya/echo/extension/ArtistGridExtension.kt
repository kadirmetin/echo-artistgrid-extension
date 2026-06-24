package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class ArtistGridExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, ArtistClient, AlbumClient, LikeClient, LibraryFeedClient, PlaylistClient, TrackClient {

    private val httpClient = OkHttpClient()
    private val api = ArtistGridApi(httpClient)
    private val urlResolver = UrlResolver(httpClient)
    private val likeJson = Json { ignoreUnknownKeys = true }

    private var artistCache: List<ArtistData> = emptyList()
    private lateinit var settings: Settings

    // ── ExtensionClient ───────────────────────────────────────────────────────

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun onInitialize() {
        artistCache = runCatching { api.fetchArtists() }.getOrDefault(emptyList())
    }

    // ── HomeFeedClient ────────────────────────────────────────────────────────

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val artists = ensureArtists().sortedByDescending { it.best }

        val shelves = listOf(
            Shelf.Lists.Items(
                id = "all_artists",
                title = "All Artists",
                list = artists.map { Mapper.toArtist(it, api) },
                type = Shelf.Lists.Type.Grid,
                more = null
            )
        )

        return feedOf(shelves)
    }

    // ── SearchFeedClient ──────────────────────────────────────────────────────

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val artists = ensureArtists()
        val filtered = if (query.isBlank()) artists
        else artists.filter { it.name.contains(query, ignoreCase = true) }

        val shelves = listOf(
            Shelf.Lists.Items(
                id = "search_results",
                title = if (query.isBlank()) "All Artists" else "Results for \"$query\"",
                list = filtered.map { Mapper.toArtist(it, api) },
                type = Shelf.Lists.Type.Linear,
                more = null
            )
        )
        return feedOf(shelves)
    }

    // ── ArtistClient ──────────────────────────────────────────────────────────

    override suspend fun loadArtist(artist: Artist): Artist = artist

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val trackerId = artist.extras["trackerId"]?.takeIf { it.isNotBlank() }
            ?: return feedOf(emptyList())

        // Fetch default tab to discover available tabs
        val firstResponse = api.fetchTrackerData(trackerId)
            ?: return feedOf(emptyList())

        val tabs = Mapper.toTabs(firstResponse)
        val firstTabSlug = firstResponse.tab.slug

        // No tabs → return a simple single-page feed
        if (tabs.isEmpty()) {
            return feedOf(Mapper.toShelves(firstResponse, artist, trackerId, firstTabSlug))
        }

        return Feed(tabs) { selectedTab ->
            val tabSlug = selectedTab?.id ?: firstTabSlug
            val v3 = if (selectedTab == null || selectedTab.id == tabs.first().id) {
                firstResponse
            } else {
                api.fetchTrackerData(trackerId, selectedTab.id) ?: firstResponse
            }
            val shelves = Mapper.toShelves(v3, artist, trackerId, tabSlug)
            Feed.Data(PagedData.Single { shelves }, null, null)
        }
    }
    // ── AlbumClient ──────────────────────────────────────────────────────────────

    override suspend fun loadAlbum(album: Album): Album = album

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val trackerId = album.extras["trackerId"]?.takeIf { it.isNotBlank() }
            ?: return feedOf(emptyList())
        val tabSlug = album.extras["tabSlug"]?.takeIf { it.isNotBlank() }
        val eraName = album.extras["eraName"] ?: return feedOf(emptyList())

        val v3 = api.fetchTrackerData(trackerId, tabSlug) ?: return feedOf(emptyList())
        val era = v3.eras?.find { it.name == eraName } ?: return feedOf(emptyList())

        val artist = album.artists.firstOrNull() ?: Artist(id = trackerId, name = "")
        val tracks = Mapper.eraToTracks(era, artist)
        return feedOf(tracks)
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ── LikeClient ────────────────────────────────────────────────────────────

    private fun loadLikedIds(): MutableSet<String> =
        settings.getStringSet("liked_ids")?.toMutableSet() ?: mutableSetOf()

    private fun loadLikedList(): MutableList<LikedItemData> {
        val raw = settings.getString("liked_data") ?: return mutableListOf()
        return runCatching {
            likeJson.decodeFromString<List<LikedItemData>>(raw).toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveLiked(ids: Set<String>, items: List<LikedItemData>) {
        settings.putStringSet("liked_ids", ids)
        settings.putString("liked_data", likeJson.encodeToString(items))
    }

    private fun coverUrlOf(item: EchoMediaItem): String =
        (item.cover as? ImageHolder.NetworkRequestImageHolder)?.request?.url ?: ""

    override suspend fun likeItem(item: EchoMediaItem, liked: Boolean) {
        val ids = loadLikedIds()
        val items = loadLikedList()
        if (liked) {
            if (!ids.contains(item.id)) {
                ids.add(item.id)
                val data = when (item) {
                    is Artist -> LikedItemData(
                        type = "artist",
                        id = item.id,
                        title = item.name,
                        coverUrl = coverUrlOf(item),
                        extras = item.extras ?: emptyMap()
                    )
                    is Album -> LikedItemData(
                        type = "album",
                        id = item.id,
                        title = item.title,
                        subtitle = item.artists.firstOrNull()?.name ?: "",
                        coverUrl = coverUrlOf(item),
                        extras = item.extras ?: emptyMap()
                    )
                    is Track -> {
                        val url = item.extras?.get("url")
                            ?: item.streamables?.firstOrNull()?.extras?.get("url") ?: ""
                        LikedItemData(
                            type = "track",
                            id = item.id,
                            title = item.title,
                            subtitle = item.artists.firstOrNull()?.name ?: "",
                            coverUrl = coverUrlOf(item),
                            extras = (item.extras ?: emptyMap()) + mapOf("url" to url),
                            duration = item.duration,
                            description = item.description,
                            artistNames = item.artists.map { it.name },
                            artistIds = item.artists.map { it.id }
                        )
                    }
                    else -> LikedItemData(type = "other", id = item.id, title = item.title)
                }
                items.add(data)
            }
        } else {
            ids.remove(item.id)
            items.removeAll { it.id == item.id }
        }
        saveLiked(ids, items)
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean =
        loadLikedIds().contains(item.id)

    // ── LibraryFeedClient ─────────────────────────────────────────────────────

    private fun buildLikedSongsPlaylist(tracks: List<LikedItemData>): Playlist =
        Playlist(
            id = "liked_songs",
            title = "Liked Songs",
            isEditable = false,
            cover = null,
            trackCount = tracks.size.toLong()
        )

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val items = loadLikedList()
        if (items.isEmpty()) return feedOf(emptyList())

        val shelves = mutableListOf<Shelf>()

        val artists = items.filter { it.type == "artist" }
        if (artists.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "liked_artists",
                    title = "Artists",
                    list = artists.map { d ->
                        Artist(
                            id = d.id,
                            name = d.title,
                            cover = d.coverUrl.takeIf { it.isNotBlank() }?.let { Mapper.imageUrl(it) },
                            extras = d.extras,
                            isLikeable = true
                        )
                    },
                    type = Shelf.Lists.Type.Grid,
                    more = null
                )
            )
        }

        val albums = items.filter { it.type == "album" }
        if (albums.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "liked_albums",
                    title = "Albums",
                    list = albums.map { d ->
                        Album(
                            id = d.id,
                            title = d.title,
                            cover = d.coverUrl.takeIf { it.isNotBlank() }?.let { Mapper.imageUrl(it) },
                            artists = d.subtitle.takeIf { it.isNotBlank() }
                                ?.let { listOf(Artist(id = it, name = it)) } ?: emptyList(),
                            extras = d.extras,
                            isLikeable = true
                        )
                    },
                    type = Shelf.Lists.Type.Linear,
                    more = null
                )
            )
        }

        val tracks = items.filter { it.type == "track" }
        if (tracks.isNotEmpty()) {
            shelves.add(Shelf.Item(buildLikedSongsPlaylist(tracks)))
        }

        return feedOf(shelves)
    }

    // ── PlaylistClient ────────────────────────────────────────────────────────

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val tracks = loadLikedList().filter { it.type == "track" }
        return buildLikedSongsPlaylist(tracks)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val tracks = loadLikedList().filter { it.type == "track" }
        return feedOf(
            tracks.mapNotNull { d ->
                val url = d.extras["url"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artists = if (d.artistIds.isNotEmpty())
                    d.artistIds.zip(d.artistNames).map { (id, name) -> Artist(id = id, name = name) }
                else
                    d.subtitle.takeIf { it.isNotBlank() }
                        ?.let { listOf(Artist(id = it, name = it)) } ?: emptyList()
                Track(
                    id = d.id,
                    title = d.title,
                    cover = d.coverUrl.takeIf { it.isNotBlank() }?.let { Mapper.imageUrl(it) },
                    artists = artists,
                    duration = d.duration,
                    description = d.description,
                    extras = d.extras,
                    isLikeable = true,
                    streamables = listOf(
                        Streamable(
                            id = url,
                            quality = 0,
                            type = Streamable.MediaType.Server,
                            extras = mapOf("url" to url)
                        )
                    )
                )
            }
        )
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    // ── TrackClient ───────────────────────────────────────────────────────────

    /**
     * The track already has a streamable attached during [Mapper.toShelves].
     * Return as-is; Echo calls [loadStreamableMedia] when playback starts.
     */
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val sourceUrl = streamable.extras["url"] ?: streamable.id

        val playableUrl = urlResolver.resolve(sourceUrl)
            ?: throw Exception("Could not resolve a playable URL for: $sourceUrl")

        return Streamable.Media.Server(
            sources = listOf(
                Streamable.Source.Http(
                    request = NetworkRequest(playableUrl),
                    type = Streamable.SourceType.Progressive
                )
            ),
            merged = false
        )
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun ensureArtists(): List<ArtistData> {
        if (artistCache.isEmpty()) {
            artistCache = runCatching { api.fetchArtists() }.getOrDefault(emptyList())
        }
        return artistCache
    }

    /** Wraps a pre-loaded list into a single-page Feed. */
    private fun <T : Any> feedOf(items: List<T>): Feed<T> =
        Feed(emptyList()) { Feed.Data(PagedData.Single { items }, null, null) }
}