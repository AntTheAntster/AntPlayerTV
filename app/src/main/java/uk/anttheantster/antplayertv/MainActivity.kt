package uk.anttheantster.antplayertv

import androidx.compose.runtime.collectAsState
import uk.anttheantster.antplayertv.data.watchlist.WatchlistRepository
import uk.anttheantster.antplayertv.ui.AntAppScaffold
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import uk.anttheantster.antplayertv.BuildConfig
import uk.anttheantster.antplayertv.data.AnalyticsApi
import uk.anttheantster.antplayertv.data.AntPlayerDatabase
import uk.anttheantster.antplayertv.data.AshiEpisode
import uk.anttheantster.antplayertv.data.AshiDetails
import uk.anttheantster.antplayertv.data.EpisodeProgress
import uk.anttheantster.antplayertv.data.ProgressRepository
import uk.anttheantster.antplayertv.data.RemoteSearchApi
import uk.anttheantster.antplayertv.data.StreamOption
import uk.anttheantster.antplayertv.data.LicenseUtils
import uk.anttheantster.antplayertv.data.LicenseApi
import uk.anttheantster.antplayertv.data.UpdateApi
import uk.anttheantster.antplayertv.data.UpdateInfo
import uk.anttheantster.antplayertv.model.MediaItem
import uk.anttheantster.antplayertv.ui.AntPlayerTheme
import uk.anttheantster.antplayertv.ui.NavigationState
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.anttheantster.antplayertv.ui.LocalAntToast
import uk.anttheantster.antplayertv.ui.BootIntroScreen
import uk.anttheantster.antplayertv.ui.BootStage
import uk.anttheantster.antplayertv.ui.BrowseAllScreen
import uk.anttheantster.antplayertv.ui.BrowseScreen
import uk.anttheantster.antplayertv.ui.DetailsScreen
import uk.anttheantster.antplayertv.ui.HomeScreen
import uk.anttheantster.antplayertv.ui.LauncherHub
import uk.anttheantster.antplayertv.ui.LiveTvPlaceholderScreen

class MainActivity : ComponentActivity() {

    /**
     * v2.0 — capture the hardware Back key at the very top of the dispatch
     * chain. On Fire TV (and some other Android variants) the first Back
     * press while a focusable element is focused gets eaten by an internal
     * "defocus" consumer, so the user has to press Back twice to actually
     * navigate. Hijacking dispatchKeyEvent and calling
     * `onBackPressedDispatcher.onBackPressed()` ourselves bypasses that
     * consumer entirely — Compose `BackHandler`s still fire normally because
     * they're registered with the same dispatcher.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_BACK
        ) {
            // If any callback is registered & enabled, run it.
            // Otherwise fall through so the activity can finish() naturally.
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from Splash theme to normal theme
        setTheme(R.style.Theme_AntPlayerTV_Splash)

        super.onCreate(savedInstanceState)

        setContent {
            AntPlayerTheme {
                // v2.0 — overscan-safe margin on TV. Many TVs apply a small
                // amount of overscan (some can't be disabled in settings)
                // which clips the outermost pixels of the app. We pad the
                // root by a few dp ONLY when running on a leanback / TV
                // device, so non-TV displays (e.g. a desktop monitor used
                // for development) remain edge-to-edge.
                val isTv = remember {
                    packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_LEANBACK
                    )
                }
                Box(
                    modifier = if (isTv)
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    else
                        Modifier
                ) {
                    AntPlayerRoot()
                }
            }
        }
    }
}

@Composable
fun AntPlayerTVApp() {
    // v2.0: app starts at the new launcher Hub.
    var navState by remember { mutableStateOf<NavigationState>(NavigationState.Hub) }
    var previousState by remember { mutableStateOf<NavigationState?>(null) }

    // specifically for returning from Player screen
    var playerReturnState by remember { mutableStateOf<NavigationState?>(null) }

    val context = LocalContext.current
    val db = remember { AntPlayerDatabase.getInstance(context) }
    val watchlistRepo = remember { WatchlistRepository(db.watchlistDao()) }

    LaunchedEffect(Unit) {
        watchlistRepo.ensureDefaultWatchLater()
    }

    val watchlists by watchlistRepo.observeWatchlists().collectAsState(initial = emptyList())

    fun navigateTo(target: NavigationState) {
        previousState = navState
        navState = target
    }

    /**
     * Pick the right Details destination for a [MediaItem]:
     *   • If the item carries a TMDB anchor (set when it was resolved
     *     through the TMDB-driven flow) → route to the new [DetailsScreen].
     *   • Otherwise fall back to the legacy [AntPlayerDetails].
     */
    fun openItemDetails(item: MediaItem, from: NavigationState) {
        previousState = from
        val tid = item.tmdbId
        val ttype = item.tmdbType
        navState = if (tid != null && ttype != null) {
            NavigationState.TmdbDetails(
                tmdbId = tid,
                type = ttype,
                titleHint = item.title,
                posterHint = item.image,
            )
        } else {
            NavigationState.Details(item)
        }
    }

    AntAppScaffold(
        current = navState,
        watchlists = watchlists,
        onNavigate = { navigateTo(it) },
        // Drawer / Menu navigation is disabled while video is playing —
        // it interrupts playback and historically caused focus to vanish
        // out of the ExoPlayer controls.
        drawerEnabled = navState !is NavigationState.Player
    ) { modifier, openDrawer ->

        // Wrap the routed content in the scaffold-supplied Modifier so the
        // focus-containment / focusRequester chain actually applies. Every
        // screen previously ignored this modifier.
        androidx.compose.foundation.layout.Box(modifier = modifier) {
        when (val state = navState) {
            // v2.0 — 4-card launcher
            is NavigationState.Hub -> {
                LauncherHub(
                    onBrowse = { navState = NavigationState.Browse },
                    onLiveTv = { navState = NavigationState.LiveTV },
                    onHome = { navState = NavigationState.Home },
                    onSettings = { navState = NavigationState.Settings },
                    onOpenDrawer = { openDrawer() }
                )
            }

            is NavigationState.Home -> {
                // Belt-and-braces back handling: HomeScreen has its own
                // BackHandler, this route-level one is registered first
                // (so it sits *underneath* HomeScreen's in OnBackPressedDispatcher
                // priority) and acts as a fallback in case a focus-traversal
                // pass swallows the deeper one.
                BackHandler { navState = NavigationState.Hub }
                HomeScreen(
                    onBack = { navState = NavigationState.Hub },
                    onItemSelected = { item -> openItemDetails(item, NavigationState.Home) }
                )
            }

            is NavigationState.Browse -> {
                BackHandler { navState = NavigationState.Hub }
                BrowseScreen(
                    onBack = { navState = NavigationState.Hub },
                    onViewAll = { navState = NavigationState.BrowseAll },
                    onItemSelected = { card ->
                        previousState = NavigationState.Browse
                        navState = NavigationState.TmdbDetails(
                            tmdbId = card.tmdbId,
                            type = card.type,
                            titleHint = card.title,
                            posterHint = card.backdropUrl.ifBlank { card.posterUrl }
                        )
                    }
                )
            }

            // Legacy Search route → forward to the new Browse screen.
            is NavigationState.Search -> {
                BackHandler { navState = NavigationState.Hub }
                BrowseScreen(
                    onBack = { navState = NavigationState.Hub },
                    onViewAll = { navState = NavigationState.BrowseAll },
                    onItemSelected = { card ->
                        previousState = NavigationState.Browse
                        navState = NavigationState.TmdbDetails(
                            tmdbId = card.tmdbId,
                            type = card.type,
                            titleHint = card.title,
                            posterHint = card.backdropUrl.ifBlank { card.posterUrl }
                        )
                    }
                )
            }

            is NavigationState.BrowseAll -> {
                BackHandler { navState = NavigationState.Browse }
                BrowseAllScreen(
                    onBack = { navState = NavigationState.Browse },
                    onItemSelected = { card ->
                        previousState = NavigationState.BrowseAll
                        navState = NavigationState.TmdbDetails(
                            tmdbId = card.tmdbId,
                            type = card.type,
                            titleHint = card.title,
                            posterHint = card.backdropUrl.ifBlank { card.posterUrl }
                        )
                    }
                )
            }

            is NavigationState.TmdbDetails -> {
                DetailsScreen(
                    tmdbId = state.tmdbId,
                    type = state.type,
                    titleHint = state.titleHint,
                    posterHint = state.posterHint,
                    onBack = { navState = previousState ?: NavigationState.Hub },
                    onPlay = { playableItem ->
                        playerReturnState = state
                        navState = NavigationState.Player(
                            item = playableItem,
                            startPositionMs = null
                        )
                    }
                )
            }

            is NavigationState.LiveTV -> {
                BackHandler { navState = NavigationState.Hub }
                LiveTvPlaceholderScreen()
            }

            is NavigationState.Settings -> {
                AntPlayerSettings(
                    onBack = { navState = NavigationState.Hub }
                )
            }

            is NavigationState.Watchlist -> {
                AntPlayerWatchlistScreen(
                    watchlistId = state.id,
                    watchlistName = state.name,
                    onItemSelected = { item ->
                        openItemDetails(item, NavigationState.Watchlist(state.id, state.name))
                    },
                    onBack = { navState = NavigationState.Hub }
                )
            }

            is NavigationState.Details -> {
                AntPlayerDetails(
                    item = state.item,
                    onPlay = { playableItem ->
                        playerReturnState = state
                        navState = NavigationState.Player(playableItem, startPositionMs = null)
                    },
                    onResume = { playableItem, resumeMs ->
                        playerReturnState = state
                        navState = NavigationState.Player(playableItem, startPositionMs = resumeMs)
                    },
                    onBack = { navState = previousState ?: NavigationState.Hub }
                )
            }

            is NavigationState.Player -> {
                AntPlayerPlayer(
                    mediaItem = state.item,
                    startPositionMs = state.startPositionMs,
                    onBack = {
                        navState = playerReturnState ?: previousState ?: NavigationState.Hub
                    },
                    onAutoPlayNext = { nextItem ->
                        navState = NavigationState.Player(nextItem, startPositionMs = 0L)
                    }
                )
            }
        }
        } // close the wrapping Box
    }
}

@Composable
fun MediaCard(item: MediaItem, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "cardScale"
    )

    // For remote shows like "Title - Ep 3 (Sub)", show just "Title" on the card
    val displayTitle by remember(item.title) {
        mutableStateOf(
            item.title
                .substringBefore(" - Ep ")
                .ifBlank { item.title }
        )
    }

    Column(
        modifier = Modifier
            .width(200.dp)
            .padding(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
            }
            .clickable { onClick() }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = item.image,
            contentDescription = item.title,
            modifier = Modifier
                .height(230.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        )
        Text(
            text = displayTitle,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

/**
 * Walks a Context's ContextWrapper chain to find the hosting
 * [ComponentActivity]. Necessary because Compose's `LocalContext.current`
 * may be wrapped (e.g. via a ContextThemeWrapper) before reaching the
 * activity, which would defeat a naive `as? ComponentActivity` cast.
 */
private fun android.content.Context.findComponentActivity(): ComponentActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * v2.0 — recursively walks the ExoPlayer controller's view hierarchy and
 * stamps a state-list focus background onto every focusable leaf View, so
 * the currently-focused control (play/pause, rewind, fast-forward, settings,
 * subtitle picker, etc.) shows a visible highlight on TV.
 *
 * The DefaultTimeBar (`exo_progress`) is intentionally skipped: it draws
 * its own scrubber thumb, and overwriting its background hides that.
 */
private fun applyPlayerFocusHighlights(playerView: PlayerView, ctx: android.content.Context) {
    val focusBg: Drawable? = ContextCompat.getDrawable(ctx, R.drawable.player_button_focus)
    // The DefaultTimeBar draws its own scrubber thumb; replacing its
    // background would hide that, so we leave it alone.
    val timeBarId = androidx.media3.ui.R.id.exo_progress

    fun walk(view: View) {
        if (view !is ViewGroup &&
            view.isFocusable &&
            view.id != timeBarId
        ) {
            // Each view needs its own Drawable instance so focus state is
            // tracked independently across multiple buttons.
            view.background = focusBg?.constantState?.newDrawable()?.mutate()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i))
            }
        }
    }
    walk(playerView)
}

data class EpisodeIdInfo(
    val seriesId: String,
    val episodeNumber: Int,
    val watchLabel: String
)

private fun parseEpisodeId(id: String): EpisodeIdInfo? {
    // Format: "<seriesId>#ep<episodeNumber>#<label>"
    val parts = id.split("#")
    if (parts.size < 3) return null

    val seriesId = parts[0]
    val epPart = parts[1]          // e.g. "ep12"
    val label = parts[2]           // e.g. "Hardsub English"

    val number = epPart.removePrefix("ep").toIntOrNull() ?: return null

    return EpisodeIdInfo(
        seriesId = seriesId,
        episodeNumber = number,
        watchLabel = label
    )
}

/**
 * Resolve the *next* episode for [mediaItem] given its parsed [episodeInfo].
 * Returns null when no next episode exists (last in the series, network
 * error, or no playable stream option).
 *
 * Pulled out as a standalone suspend fn so it can be used both for
 * eager pre-fetching (driving the queue-next-episode card) and for the
 * fallback STATE_ENDED listener inside the player.
 */
private suspend fun resolveNextEpisode(
    mediaItem: MediaItem,
    episodeInfo: EpisodeIdInfo,
    remoteApi: uk.anttheantster.antplayertv.data.RemoteSearchApi,
): MediaItem? {
    return try {
        val episodes = remoteApi.getEpisodes(episodeInfo.seriesId)
        // Season-aware lookup: for 1Movies episodes carry a season field;
        // for Animekai the season is baked into the series URL so season is null.
        val currentIndex = episodes.indexOfFirst { ep ->
            ep.number == episodeInfo.episodeNumber &&
                (ep.season == null || ep.season == mediaItem.tmdbSeason)
        }
        if (currentIndex == -1 || currentIndex + 1 >= episodes.size) return null

        val nextEpisode = episodes[currentIndex + 1]
        val options = remoteApi.getStreamOptions(nextEpisode.href)
        val pick = options.firstOrNull { it.label == episodeInfo.watchLabel }
            ?: options.firstOrNull()
        if (pick == null || pick.url.isBlank()) return null

        // nextEpisode.season may differ from current when crossing a season boundary.
        val nextTmdbSeason = nextEpisode.season ?: mediaItem.tmdbSeason
        val baseTitle = mediaItem.title.substringBefore(" - Ep ").ifBlank { mediaItem.title }
        MediaItem(
            id = "${episodeInfo.seriesId}#ep${nextEpisode.number}#${pick.label}",
            title = "$baseTitle - Ep ${nextEpisode.number} (${pick.label})",
            description = mediaItem.description,
            image = mediaItem.image,
            streamUrl = pick.url,
            releaseYear = mediaItem.releaseYear,
            totalEpisodes = mediaItem.totalEpisodes,
            type = mediaItem.type,
            ageRating = mediaItem.ageRating,
            tmdbId = mediaItem.tmdbId,
            tmdbType = mediaItem.tmdbType,
            tmdbSeason = nextTmdbSeason,
            tmdbEpisode = nextEpisode.number,
        )
    } catch (_: Exception) {
        null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AntPlayerPlayer(
    mediaItem: MediaItem,
    startPositionMs: Long?,
    onBack: () -> Unit,
    onAutoPlayNext: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Analytics
    val analyticsApi = remember { AnalyticsApi() }
    val deviceId = remember { LicenseUtils.getOrCreateDeviceId(context) }
    val licenseKey = remember { LicenseUtils.getStoredLicenseKey(context) }

    // If URL is missing, show error
    if (mediaItem.streamUrl.isBlank()) {
        BackHandler { onBack() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Unable to play this stream.", color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "(empty URL)", color = Color.Gray)
            }
        }
        return
    }

    val db = remember { AntPlayerDatabase.getInstance(context) }
    val repository = remember { ProgressRepository(db.progressDao()) }

    // API for fetching next episode stream
    val remoteApi = remember {
        RemoteSearchApi(
            baseUrl = "https://api.anttheantster.uk"
        )
    }

    // Info parsed from the encoded id: "<seriesId>#ep<episodeNumber>#<label>"
    val episodeInfo = remember(mediaItem.id) {
        parseEpisodeId(mediaItem.id)
    }

    // Ensures we only trigger autoplay once per item
    var hasTriggeredAutoplay by remember(mediaItem.id) { mutableStateOf(false) }

    // ExoPlayer instance
    val exoPlayer = remember(mediaItem.streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            val media = ExoMediaItem.fromUri(mediaItem.streamUrl)
            setMediaItem(media)
            prepare()
            playWhenReady = true

            // Listen for playback ending to auto-play next episode
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && !hasTriggeredAutoplay) {
                        hasTriggeredAutoplay = true

                        scope.launch {
                            // Save "finished" progress for this episode
                            val finalDuration = duration.coerceAtLeast(0L)
                            val finalPosition = finalDuration

                            try {
                                repository.saveProgress(mediaItem, finalPosition, finalDuration)
                            } catch (_: Exception) {
                                // ignore errors – not fatal
                            }

                            val info = episodeInfo ?: return@launch

                            try {
                                val episodes = remoteApi.getEpisodes(info.seriesId)
                                val currentIndex = episodes.indexOfFirst { ep ->
                                    ep.number == info.episodeNumber &&
                                        (ep.season == null || ep.season == mediaItem.tmdbSeason)
                                }

                                if (currentIndex == -1 || currentIndex + 1 >= episodes.size) {
                                    return@launch
                                }

                                val nextEpisode = episodes[currentIndex + 1]
                                val options = remoteApi.getStreamOptions(nextEpisode.href)
                                val selectedOption = options.firstOrNull {
                                    it.label == info.watchLabel
                                } ?: options.firstOrNull()

                                if (selectedOption == null || selectedOption.url.isBlank()) {
                                    return@launch
                                }

                                val nextTmdbSeason = nextEpisode.season ?: mediaItem.tmdbSeason
                                val baseTitle = mediaItem.title
                                    .substringBefore(" - Ep ")
                                    .ifBlank { mediaItem.title }

                                val nextMediaItem = MediaItem(
                                    id = "${info.seriesId}#ep${nextEpisode.number}#${selectedOption.label}",
                                    title = "$baseTitle - Ep ${nextEpisode.number} (${selectedOption.label})",
                                    description = mediaItem.description,
                                    image = mediaItem.image,
                                    streamUrl = selectedOption.url,
                                    releaseYear = mediaItem.releaseYear,
                                    totalEpisodes = mediaItem.totalEpisodes,
                                    type = mediaItem.type,
                                    ageRating = mediaItem.ageRating,
                                    tmdbId = mediaItem.tmdbId,
                                    tmdbType = mediaItem.tmdbType,
                                    tmdbSeason = nextTmdbSeason,
                                    tmdbEpisode = nextEpisode.number,
                                )

                                // Jump to the next episode on the main thread
                                withContext(Dispatchers.Main) {
                                    onAutoPlayNext(nextMediaItem)
                                }
                            } catch (_: Exception) {
                                // Network or parsing issue – silently skip autoplay
                            }
                        }
                    }
                }
            })
        }
    }

    // Log analytics once per stream URL
    LaunchedEffect(mediaItem.streamUrl) {
        val titleForAnalytics = mediaItem.title           // adjust if needed
        val episodeLabel = mediaItem.title        // e.g. "Episode 3" / "Movie"
        val watchTypeLabel = mediaItem.type     // e.g. "sub" / "dub" / "raw"

        analyticsApi.logPlay(
            licenseKey = licenseKey,
            deviceId = deviceId,
            title = titleForAnalytics,
            episodeLabel = episodeLabel,
            watchType = watchTypeLabel.toString()
        )
    }

    // Resume position
    LaunchedEffect(startPositionMs) {
        if (startPositionMs != null && startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }
    }

    // Save progress on back
    BackHandler {
        val position = exoPlayer.currentPosition
        val duration = exoPlayer.duration.coerceAtLeast(0L)

        scope.launch {
            repository.saveProgress(mediaItem, position, duration)
        }

        onBack()
    }

    // Release player when leaving
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // v2.0 — pause playback when the activity is no longer in the foreground
    // (e.g. user pressed the Home button on the Fire TV remote). Without this
    // ExoPlayer keeps running in the background, eating CPU/network and
    // continuing to play audio.
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(activity, exoPlayer) {
        val owner = activity
        if (owner != null) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    // Save progress so user can resume later, then pause.
                    val pos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    scope.launch {
                        try { repository.saveProgress(mediaItem, pos, dur) } catch (_: Exception) {}
                    }
                    exoPlayer.pause()
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        } else {
            onDispose { }
        }
    }

    // PlayerView reference
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // Track controller visibility ourselves — used so the very first OK
    // press while controls are mid-fade-out lands focus on the play_pause
    // button rather than nowhere.
    var controllerVisible by remember { mutableStateOf(true) }

    // Focus will live on the AndroidView (PlayerView), not the Box
    val focusRequester = remember { FocusRequester() }

    // ---- Queue-next-episode state ----
    //
    // We pre-fetch the next episode shortly after this one starts so when
    // the user gets near the end we can offer it as a single-click jump
    // (the existing STATE_ENDED listener still acts as a fallback).
    var nextMediaItem by remember(mediaItem.id) { mutableStateOf<MediaItem?>(null) }
    var positionMs   by remember(mediaItem.id) { mutableStateOf(0L) }
    var durationMs   by remember(mediaItem.id) { mutableStateOf(0L) }
    var nextCardDismissed by remember(mediaItem.id) { mutableStateOf(false) }

    LaunchedEffect(mediaItem.id) {
        val info = episodeInfo ?: return@LaunchedEffect
        // small head-start so the user's current episode isn't competing
        // with this network work mid-buffer.
        kotlinx.coroutines.delay(2_000)
        nextMediaItem = resolveNextEpisode(mediaItem, info, remoteApi)
    }

    // Position tick — drives the "near the end" check that shows the card.
    LaunchedEffect(mediaItem.streamUrl) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(500)
        }
    }

    val remainingMs = if (durationMs > 0) (durationMs - positionMs).coerceAtLeast(0L) else 0L
    val showNextCard = nextMediaItem != null &&
        durationMs > 0 &&
        remainingMs in 1..30_000L &&
        !hasTriggeredAutoplay &&
        !nextCardDismissed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // proper black bars
            // Hardware Play/Pause is handled at the outer Box so it works
            // regardless of which child currently has focus (queue-next
            // card, controller buttons, the AndroidView itself, etc.).
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> {
                        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        playerViewRef?.showController()
                        true
                    }
                    Key.MediaPlay -> {
                        exoPlayer.playWhenReady = true
                        playerViewRef?.showController()
                        true
                    }
                    Key.MediaPause -> {
                        exoPlayer.playWhenReady = false
                        playerViewRef?.showController()
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.Spacebar -> {
                            // Show controller whenever the user presses DPAD / OK.
                            val wasVisible = controllerVisible
                            playerViewRef?.showController()
                            if (!wasVisible) {
                                // Controller was hidden / mid-fade-out — grab focus
                                // on play_pause so the very first OK press lands on
                                // a real button rather than dead air.
                                playerViewRef?.findViewById<View>(
                                    androidx.media3.ui.R.id.exo_play_pause
                                )?.requestFocus()
                            }
                            // return false so PlayerView still handles the key
                            false
                        }

                        // (Play/Pause is handled higher up on the outer Box so
                        // it fires regardless of which child has focus.)

                        else -> false
                    }
                },
            factory = { ctx ->
                val themedContext = ContextThemeWrapper(
                    ctx,
                    android.R.style.Theme_Material_Light_NoActionBar
                )

                PlayerView(themedContext).apply {
                    player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 5000

                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { createdView ->
                    playerViewRef = createdView

                    // v2.0 — walk the controller's view tree once it has
                    // laid out and apply our focus-state drawable to every
                    // focusable leaf so the focused button is unmistakably
                    // visible from across the room.
                    createdView.post {
                        applyPlayerFocusHighlights(createdView, ctx)
                    }
                    // Also re-apply each time the controller visibility
                    // toggles (some buttons are inflated lazily) and keep
                    // our `controllerVisible` flag in sync so the key
                    // handler above knows when to re-focus play_pause.
                    createdView.setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerVisible = (visibility == View.VISIBLE)
                            if (visibility == View.VISIBLE) {
                                applyPlayerFocusHighlights(createdView, ctx)
                            }
                        }
                    )
                }
            },
            update = { view ->
                playerViewRef = view

                // 🔹 Ensure the view is always bound to the *current* player
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            }
        )

        // ---- Queue-next-episode overlay ----
        //
        // Visible only when (a) we successfully pre-fetched a next episode,
        // (b) we're inside the last 30s of the current one, and (c) the
        // user hasn't already auto-advanced or dismissed the card.
        val nextItem = nextMediaItem
        AnimatedVisibility(
            visible = showNextCard && nextItem != null,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 96.dp)
        ) {
            if (nextItem != null) {
                QueueNextEpisodeCard(
                    item = nextItem,
                    secondsRemaining = (remainingMs / 1000L).toInt(),
                    onPlayNow = {
                        hasTriggeredAutoplay = true
                        // Persist progress for this item before jumping.
                        val pos = exoPlayer.currentPosition
                        val dur = exoPlayer.duration.coerceAtLeast(0L)
                        scope.launch {
                            try { repository.saveProgress(mediaItem, pos, dur) } catch (_: Exception) {}
                        }
                        onAutoPlayNext(nextItem)
                    },
                    onDismiss = { nextCardDismissed = true }
                )
            }
        }

        // Grab focus when the player screen opens and show controller initially
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            playerViewRef?.showController()
        }
    }
}

/**
 * The bottom-right overlay shown in the last 30s of an episode offering a
 * one-click jump to the next one. Focusable so DPAD-Right reaches it from
 * the player controls; OK plays it; Back / dismiss button hides it for
 * the rest of the current episode.
 */
@Composable
private fun QueueNextEpisodeCard(
    item: MediaItem,
    secondsRemaining: Int,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    // v2.0 — aggressively grab focus the moment the card appears so the
    // user can hit OK immediately without having to navigate to it. We
    // delay one frame so the focusable() modifier below has actually
    // attached before we ask for focus.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        try { focusRequester.requestFocus() } catch (_: Throwable) {}
    }

    androidx.compose.material3.Surface(
        modifier = Modifier
            .focusRequester(focusRequester)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused)
                    uk.anttheantster.antplayertv.ui.AntColors.AccentPurple
                else
                    uk.anttheantster.antplayertv.ui.AntColors.Divider,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Back -> { onDismiss(); true }
                    else -> false
                }
            }
            .clickable(onClick = onPlayNow)
            .focusable(),
        shape = shape,
        color = uk.anttheantster.antplayertv.ui.AntColors.SurfaceElev2.copy(alpha = 0.94f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .width(360.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Tiny play icon badge
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = uk.anttheantster.antplayertv.ui.AntColors.AccentPurple
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Up next  •  ${secondsRemaining}s left",
                    color = uk.anttheantster.antplayertv.ui.AntColors.AccentSoft,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = item.title,
                    color = uk.anttheantster.antplayertv.ui.AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "OK to play next  •  Back to dismiss",
                    color = uk.anttheantster.antplayertv.ui.AntColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun AntPlayerDetails(
    item: MediaItem,
    onPlay: (MediaItem) -> Unit,
    onResume: (MediaItem, Long) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { AntPlayerDatabase.getInstance(context) }
    val progressRepo = remember { ProgressRepository(db.progressDao()) }

    // local/progress lookup is always by item.id (used only for non-remote items)
    val resumePositionMs by produceState<Long?>(initialValue = null, item) {
        value = progressRepo.getProgressFor(item.id)
    }

    // Heuristic: remote Ashi "series" item = no direct streamUrl but special id
    val isRemoteSeries =
        item.streamUrl.isBlank() &&
                (item.id.startsWith("Animekai:") ||
                        item.id.contains("1movies", ignoreCase = true))

    var showAddToWatchlist by remember { mutableStateOf(false) }

    MaterialTheme {
        if (!isRemoteSeries) {
            // ───── Local / simple item (demo JSON, continue watching episode, etc.) ─────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp)
            ) {
                TvButton(text = "← Back", onClick = onBack)

                Spacer(modifier = Modifier.height(20.dp))

                Row {
                    AsyncImage(
                        model = item.image,
                        contentDescription = item.title,
                        modifier = Modifier
                            .height(300.dp)
                            .width(200.dp)
                    )

                    Spacer(modifier = Modifier.width(20.dp))

                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 🔹 META LINE for local items
                        val localMetaParts = mutableListOf<String>()

                        item.releaseYear
                            ?.takeIf { it.isNotBlank() }
                            ?.let { localMetaParts.add(it) }

                        item.totalEpisodes?.let { count ->
                            val label = if (count == 1) "1 episode" else "$count episodes"
                            localMetaParts.add(label)
                        }

                        item.type
                            ?.takeIf { it.isNotBlank() }
                            ?.let { localMetaParts.add(it) }

                        if (localMetaParts.isNotEmpty()) {
                            Text(
                                text = localMetaParts.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        TvButton(
                            text = "Add to Watchlist",
                            onClick = { showAddToWatchlist = true }
                        )

                        if (showAddToWatchlist) {
                            AddToWatchlistDialog(
                                item = item,
                                onDismiss = { showAddToWatchlist = false }
                            )
                        }

                        if (resumePositionMs != null && resumePositionMs!! > 10_000L) {
                            TvButton(
                                text = "Resume",
                                onClick = { onResume(item, resumePositionMs!!) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        TvButton(
                            text = "Play from beginning",
                            onClick = { onPlay(item) }
                        )
                    }
                }
            }
        } else {
            // Remote series branch…

            val api = remember {
                RemoteSearchApi(
                    baseUrl = "https://api.anttheantster.uk"
                )
            }

            var details by remember { mutableStateOf<AshiDetails?>(null) }
            var episodes by remember { mutableStateOf<List<AshiEpisode>>(emptyList()) }
            var expandedEpisode by remember { mutableStateOf<AshiEpisode?>(null) }
            var loadingMeta by remember { mutableStateOf(true) }

            // NEW: per-episode progress + latest episode
            var episodeProgressMap by remember { mutableStateOf<Map<Int, EpisodeProgress>>(emptyMap()) }
            var latestEpisodeProgress by remember { mutableStateOf<EpisodeProgress?>(null) }

            // Stream dialog stuff…
            var dialogEpisode by remember { mutableStateOf<AshiEpisode?>(null) }
            var showStreamDialog by remember { mutableStateOf(false) }
            var streamOptions by remember { mutableStateOf<List<StreamOption>>(emptyList()) }
            var loadingStreams by remember { mutableStateOf(false) }

            // Load details, episodes and progress for this series
            LaunchedEffect(item.id) {
                loadingMeta = true

                val info = api.getDetails(item.id)
                val eps = api.getEpisodes(item.id)

                details = info
                episodes = eps
                expandedEpisode = eps.firstOrNull()
                loadingMeta = false

                // 🔹 Load progress
                val seriesId = item.id.substringBefore("#")
                val progressList = progressRepo.getEpisodeProgressForSeries(seriesId)
                episodeProgressMap = progressList.associateBy { it.episodeNumber }
                latestEpisodeProgress = progressRepo.getLatestEpisodeForSeries(seriesId)
            }

            val scrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(24.dp)
                ) {
                    TvButton(text = "← Back", onClick = onBack)

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AsyncImage(
                            model = item.image,
                            contentDescription = item.title,
                            modifier = Modifier
                                .height(300.dp)
                                .width(200.dp)
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            // 🔹 META LINE for remote Ashi items (year + episode count)
                            val remoteMetaParts = mutableListOf<String>()

                            // Derive year from details.airdate, e.g. "2012-04-05" or similar
                            val releaseYearFromDetails = remember(details) {
                                details?.airdate
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { air ->
                                        Regex("(19\\d{2}|20\\d{2})")
                                            .find(air)
                                            ?.value
                                    }
                            }

                            releaseYearFromDetails?.let { remoteMetaParts.add(it) }

                            // Once episodes are loaded, we can show an episode count
                            if (!loadingMeta && episodes.isNotEmpty()) {
                                val label = if (episodes.size == 1) "Movie" else "${episodes.size} episodes"
                                remoteMetaParts.add(label)
                            }

                            if (remoteMetaParts.isNotEmpty()) {
                                Text(
                                    text = remoteMetaParts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Text(
                                text = "Age rating: Unknown",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Tags: " + (details?.aliases ?: "Unknown"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = details?.description ?: "Description: Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.LightGray
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            TvButton(
                                text = "Add to Watchlist",
                                onClick = { showAddToWatchlist = true }
                            )

                            if (showAddToWatchlist) {
                                AddToWatchlistDialog(
                                    item = item,
                                    onDismiss = { showAddToWatchlist = false }
                                )
                            }

                            // 🔹 Resume latest episode (if applicable)
                            val latest = latestEpisodeProgress
                            if (latest != null) {
                                val dur = latest.durationMs
                                val pos = latest.positionMs
                                val finishedThreshold = 30_000L

                                val canResume = dur > 0L &&
                                        pos > 10_000L &&
                                        dur - pos > finishedThreshold

                                if (canResume) {
                                    Spacer(modifier = Modifier.height(16.dp))

                                    TvButton(
                                        text = "Resume Episode ${latest.episodeNumber}",
                                        onClick = {
                                            val mediaItemToResume = MediaItem(
                                                id = latest.mediaId,
                                                title = latest.title,
                                                description = details?.description ?: item.description,
                                                image = latest.image ?: item.image,
                                                streamUrl = latest.streamUrl,
                                                releaseYear = item.releaseYear,
                                                totalEpisodes = item.totalEpisodes,
                                                type = item.type,
                                                ageRating = item.ageRating
                                            )
                                            onResume(mediaItemToResume, latest.positionMs)
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            when {
                                loadingMeta -> {
                                    Text(
                                        text = "Loading episodes...",
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }

                                episodes.isEmpty() -> {
                                    Text(
                                        text = "No episodes found.",
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }

                                episodes.size == 1 -> {
                                    // ───── Movie / single-episode title ─────
                                    val onlyEpisode = episodes.first()

                                    Text(
                                        text = "Movie",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    TvButton(
                                        text = "Play",
                                        onClick = {
                                            dialogEpisode = onlyEpisode
                                            showStreamDialog = true
                                            loadingStreams = true
                                            streamOptions = emptyList()

                                            scope.launch {
                                                val options = api.getStreamOptions(onlyEpisode.href)
                                                streamOptions = options
                                                loadingStreams = false
                                            }
                                        }
                                    )
                                }

                                else -> {

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Episodes",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    val firstEpisodeFocusRequester = remember { FocusRequester() }

                                    // Once episodes load, focus the first one
                                    LaunchedEffect(episodes) {
                                        if (episodes.isNotEmpty()) {
                                            expandedEpisode = episodes.first()
                                            firstEpisodeFocusRequester.requestFocus()
                                        }
                                    }

                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val FINISHED_THRESHOLD_MS = 30_000L

                                        episodes.forEachIndexed { index, episode ->
                                            val epProgress = episodeProgressMap[episode.number]

                                            val (statusText, canResume, isFinished) = if (epProgress != null && epProgress.durationMs > 0L) {
                                                val pos = epProgress.positionMs
                                                val dur = epProgress.durationMs

                                                when {
                                                    dur - pos <= FINISHED_THRESHOLD_MS ->
                                                        Triple("Finished", false, true)

                                                    pos > 10_000L ->
                                                        Triple(
                                                            "Stopped at ${formatTimeMs(pos)} / ${formatTimeMs(dur)}",
                                                            true,
                                                            false
                                                        )

                                                    else ->
                                                        Triple(null, false, false)
                                                }
                                            } else {
                                                Triple(null, false, false)
                                            }

                                            // 🔹 Build lambdas for Resume / Restart
                                            val onResumeClick: (() -> Unit)? =
                                                if (canResume && epProgress != null) {
                                                    {
                                                        val mediaItemToResume = MediaItem(
                                                            id = epProgress.mediaId,
                                                            title = epProgress.title,
                                                            description = details?.description ?: item.description,
                                                            image = epProgress.image ?: item.image,
                                                            streamUrl = epProgress.streamUrl,
                                                            releaseYear = item.releaseYear,
                                                            totalEpisodes = item.totalEpisodes,
                                                            type = item.type,
                                                            ageRating = item.ageRating
                                                        )
                                                        onResume(mediaItemToResume, epProgress.positionMs)
                                                    }
                                                } else {
                                                    null
                                                }

                                            EpisodeRow(
                                                episode = episode,
                                                expanded = (episode == expandedEpisode),
                                                seriesImage = item.image,
                                                status = statusText,
                                                onSelectEpisode = {
                                                    expandedEpisode = episode
                                                },
                                                onResumeClicked = onResumeClick,
                                                onPlayClicked = {
                                                    // "Play from beginning" → use your existing stream dialog behaviour
                                                    dialogEpisode = episode
                                                    showStreamDialog = true
                                                    loadingStreams = true
                                                    streamOptions = emptyList()

                                                    scope.launch {
                                                        val options = api.getStreamOptions(episode.href)
                                                        streamOptions = options
                                                        loadingStreams = false
                                                    }
                                                },
                                                modifier = if (index == 0) {
                                                    Modifier.focusRequester(firstEpisodeFocusRequester)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ───── Stream options dialog (Sub/Dub/Original etc.) ─────
                if (showStreamDialog) {
                    val cancelFocusRequester = remember { FocusRequester() }

                    // When the dialog appears, move focus into it (on Cancel by default)
                    LaunchedEffect(showStreamDialog) {
                        if (showStreamDialog) {
                            cancelFocusRequester.requestFocus()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x80000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .background(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(24.dp)
                                .focusGroup()                    // keep D-pad focus inside the dialog
                                .focusable(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "How would you like to watch?",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            when {
                                loadingStreams -> {
                                    Text(
                                        text = "Loading streams...",
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                streamOptions.isEmpty() -> {
                                    Text(
                                        text = "No streams available.",
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                else -> {
                                    val ep = dialogEpisode ?: episodes.firstOrNull()

                                    streamOptions.forEach { option ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TvButton(
                                            text = option.label,
                                            onClick = {
                                                if (ep != null && option.url.isNotBlank()) {
                                                    val playableItem = MediaItem(
                                                        id = "${item.id}#ep${ep.number}#${option.label}",
                                                        title = "${item.title} - Ep ${ep.number} (${option.label})",
                                                        description = details?.description ?: "",
                                                        image = item.image,
                                                        streamUrl = option.url,
                                                    )
                                                    showStreamDialog = false
                                                    onPlay(playableItem)
                                                } else {
                                                    // If something is off, just close the dialog for now
                                                    showStreamDialog = false
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            TvButton(
                                text = "Cancel",
                                onClick = { showStreamDialog = false },
                                modifier = Modifier.focusRequester(cancelFocusRequester)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        label = "tvButtonScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = Color(0xFF202027),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable { onClick() }
            .focusable()
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun EpisodeRow(
    episode: AshiEpisode,
    expanded: Boolean,
    seriesImage: String,
    status: String?,
    onSelectEpisode: () -> Unit,
    onResumeClicked: (() -> Unit)?,   // NEW
    onPlayClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused || expanded) 1.03f else 1.0f,
        label = "episodeRowScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = if (isFocused || expanded) 2.dp else 0.dp,
                color = if (isFocused || expanded)
                    MaterialTheme.colorScheme.primary
                else
                    Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    onSelectEpisode()
                }
            }
            .focusable()
            .padding(8.dp)
    ) {
        // Top line: basic episode label
        Text(
            text = "Episode ${episode.number}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 🔹 Status line
        if (!status.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
        }

        // Expanded view: thumbnail + Play button
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = seriesImage,
                    contentDescription = "Episode ${episode.number}",
                    modifier = Modifier
                        .height(120.dp)
                        .width(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Press Play to watch this episode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (onResumeClicked != null) {
                        // Partially watched episode → Show Resume + Restart
                        TvButton(
                            text = "Resume",
                            onClick = onResumeClicked
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TvButton(
                            text = "Play from beginning",
                            onClick = onPlayClicked
                        )
                    } else {
                        // Never watched OR treated as finished → just Restart
                        TvButton(
                            text = "Play from beginning",
                            onClick = onPlayClicked
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AntPlayerSettings(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AntPlayerDatabase.getInstance(context) }
    val progressRepo = remember { ProgressRepository(db.progressDao()) }

    val clearButtonFocusRequester = remember { FocusRequester() }
    var message by remember { mutableStateOf<String?>(null) }

    // Auto-focus the Clear button when Settings opens
    LaunchedEffect(Unit) {
        clearButtonFocusRequester.requestFocus()
    }

    BackHandler { onBack() }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            TvButton(text = "← Back", onClick = onBack)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Storage",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            TvButton(
                text = "Clear watch history & continue watching",
                onClick = {
                    scope.launch {
                        progressRepo.clearAll()   // 👈 you'll add this in ProgressRepository
                        message = "Watch data cleared."
                    }
                },
                modifier = Modifier.focusRequester(clearButtonFocusRequester)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (message != null) {
                Text(
                    text = message!!,
                    color = Color.Green,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private enum class LicenseState {
    Initial,        // figuring out what to do
    CheckingSaved,  // checking stored key (if any)
    NeedsKey,       // show input UI
    CheckingNew,    // user just pressed "Activate"
    Licensed        // all good, show app
}

private enum class UpdateState {
    Checking,
    NeedUpdate,
    UpToDate
}

@Composable
fun AntPlayerRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val deviceId = remember { LicenseUtils.getOrCreateDeviceId(context) }
    val licenseApi = remember { LicenseApi() }
    val updateApi = remember { UpdateApi() }

    // v2.0 — boot animation gating. The intro animation runs first; only once
    // it has finished do we move on to running the existing licence + update
    // flow underneath the same logo backdrop.
    var bootStage by remember { mutableStateOf(BootStage.LogoIntro) }

    var licenseKey by remember {
        mutableStateOf(LicenseUtils.getStoredLicenseKey(context) ?: "")
    }

    var licenseState by remember {
        mutableStateOf(
            if (licenseKey.isBlank()) LicenseState.NeedsKey else LicenseState.CheckingSaved
        )
    }
    var licenseError by remember { mutableStateOf<String?>(null) }

    var updateState by remember { mutableStateOf(UpdateState.Checking) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Handle stored license check (only once intro animation has completed)
    LaunchedEffect(bootStage, licenseState) {
        if (bootStage != BootStage.LogoIntro &&
            licenseState == LicenseState.CheckingSaved
        ) {
            licenseError = null
            val result = licenseApi.checkLicense(
                licenseKey = licenseKey,
                deviceId = deviceId,
                appVersion = BuildConfig.VERSION_NAME
            )
            if (result.valid) {
                licenseState = LicenseState.Licensed
            } else {
                licenseState = LicenseState.NeedsKey
                licenseError = result.message.ifBlank { "Stored license is not valid." }
                LicenseUtils.clearLicenseKey(context)
                licenseKey = ""
            }
        }
    }

    // Once licensed, check for updates
    LaunchedEffect(bootStage, licenseState, updateState) {
        if (bootStage != BootStage.LogoIntro &&
            licenseState == LicenseState.Licensed &&
            updateState == UpdateState.Checking
        ) {
            val info = updateApi.checkUpdate()
            val currentCode = BuildConfig.VERSION_CODE
            if (info != null && info.latestVersionCode > currentCode) {
                updateInfo = info
                updateState = UpdateState.NeedUpdate
            } else {
                updateState = UpdateState.UpToDate
            }
        }
    }

    // Promote bootStage to Done once everything is settled and we don't need
    // to show a prompt screen.
    LaunchedEffect(licenseState, updateState) {
        if (licenseState == LicenseState.Licensed &&
            updateState == UpdateState.UpToDate
        ) {
            bootStage = BootStage.Done
        }
    }

    // Single mount point for the boot screen so animations only play once
    // and survive recompositions across the licence / update phases.
    val showBootScreen = bootStage == BootStage.LogoIntro ||
        licenseState == LicenseState.Initial ||
        licenseState == LicenseState.CheckingSaved ||
        licenseState == LicenseState.CheckingNew ||
        (licenseState == LicenseState.Licensed && updateState == UpdateState.Checking)

    val bootStatus = when {
        bootStage == BootStage.LogoIntro -> null
        licenseState == LicenseState.CheckingSaved ||
            licenseState == LicenseState.CheckingNew ||
            licenseState == LicenseState.Initial -> "Checking your licence…"
        licenseState == LicenseState.Licensed && updateState == UpdateState.Checking -> "Looking for updates…"
        else -> null
    }

    if (showBootScreen) {
        BootIntroScreen(
            stage = if (bootStage == BootStage.LogoIntro) BootStage.LogoIntro else BootStage.Working,
            statusText = bootStatus,
            onIntroFinished = {
                if (bootStage == BootStage.LogoIntro) bootStage = BootStage.Working
            }
        )
        return
    }

    // ---- Licence prompt ----
    if (licenseState == LicenseState.NeedsKey) {
        LicenseScreen(
            currentKey = licenseKey,
            errorMessage = licenseError,
            onKeyChange = { licenseKey = it },
            onActivate = {
                scope.launch {
                    licenseState = LicenseState.CheckingNew
                    licenseError = null

                    val result = licenseApi.checkLicense(
                        licenseKey = licenseKey,
                        deviceId = deviceId,
                        appVersion = BuildConfig.VERSION_NAME
                    )

                    if (result.valid) {
                        LicenseUtils.saveLicenseKey(context, licenseKey)
                        licenseState = LicenseState.Licensed
                        updateState = UpdateState.Checking
                    } else {
                        licenseState = LicenseState.NeedsKey
                        licenseError = result.message.ifBlank { "License invalid." }
                    }
                }
            }
        )
        return
    }

    // ---- Update prompt ----
    if (updateState == UpdateState.NeedUpdate) {
        UpdateScreen(
            info = updateInfo!!,
            onSkip = { updateState = UpdateState.UpToDate },
            onFinished = { updateState = UpdateState.UpToDate }
        )
        return
    }

    // ---- Into the app (starts at the new Hub launcher) ----
    AntPlayerTVApp()
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun UpdateScreen(
    info: UpdateInfo,
    onSkip: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current

    val primaryFocusRequester = remember { FocusRequester() }

    // Derive a filename from the URL (fallback if parsing fails)
    val apkFileName = remember(info.apkUrl) {
        val lastSegment = try {
            info.apkUrl.toUri().lastPathSegment
        } catch (e: Exception) {
            null
        }
        lastSegment ?: "AntPlayerTV-latest.apk"
    }

    // Auto-focus the Download button when this screen appears
    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    BackHandler {
        // Treat back as "Later"
        onSkip()
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp)
                    .focusGroup()
                    .focusable(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Update available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "New version: ${info.latestVersionName}",
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (info.changelog.isNotBlank()) {
                    Text(
                        text = info.changelog,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                TvButton(
                    text = "Download & Install",
                    onClick = {
                        // Fire-and-forget download + install
                        UpdateInstaller.downloadAndInstall(
                            context = context,
                            apkUrl = info.apkUrl,
                            fileName = apkFileName
                        )
                        // We let the user proceed into the app; installer UI will be on top
                        onFinished()
                    },
                    modifier = Modifier.focusRequester(primaryFocusRequester)
                )

                Spacer(modifier = Modifier.height(16.dp))

                TvButton(
                    text = "Later",
                    onClick = { onSkip() }
                )
            }
        }
    }
}

@Composable
fun LicenseScreen(
    currentKey: String,
    errorMessage: String?,
    onKeyChange: (String) -> Unit,
    onActivate: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the license text field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AntPlayer TV",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter your license key to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = currentKey,
                    onValueChange = onKeyChange,
                    label = { Text("License key") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                TvButton(
                    text = "Activate",
                    onClick = onActivate
                )
            }
        }
    }
}

@Composable
fun AntPlayerWatchlistScreen(
    watchlistId: Long,
    watchlistName: String,
    onItemSelected: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AntPlayerDatabase.getInstance(context) }
    val repo = remember { WatchlistRepository(db.watchlistDao()) }
    val items by repo.observeItems(watchlistId).collectAsState(initial = emptyList())

    val toast = LocalAntToast.current
    val scope = rememberCoroutineScope()

    // Auto-focus the back button
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocus.requestFocus() }

    // Options popup state
    var optionsFor by remember { mutableStateOf<MediaItem?>(null) }

    BackHandler {
        if (optionsFor != null) optionsFor = null else onBack()
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            TvButton(
                text = "← Back",
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = watchlistName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(16.dp))

            if (items.isEmpty()) {
                Text(
                    text = "This list is empty.",
                    color = MaterialTheme.colorScheme.onBackground
                )
                return@Column
            }

            // ✅ GRID instead of list
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items) { wlItem ->
                    val media = MediaItem(
                        id = wlItem.mediaId,
                        title = wlItem.title,
                        description = wlItem.description,
                        image = wlItem.image,
                        streamUrl = wlItem.streamUrl,
                        tmdbId = wlItem.tmdbId,
                        tmdbType = wlItem.tmdbType,
                    )

                    WatchlistGridCard(
                        item = media,
                        onClick = { onItemSelected(media) },
                        onHoldSelect = { optionsFor = media }
                    )
                }
            }
        }

        // Options popup
        optionsFor?.let { item ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { optionsFor = null },
                title = { Text(item.title) },
                text = { Text("Options") },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                repo.removeFromWatchlist(watchlistId, item.id)
                                toast("Removed from $watchlistName")
                                optionsFor = null
                            }
                        }
                    ) { Text("Remove from \"$watchlistName\"") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { optionsFor = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun AddToWatchlistDialog(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AntPlayerDatabase.getInstance(context) }
    val repo = remember { WatchlistRepository(db.watchlistDao()) }
    val watchlists by repo.observeWatchlists().collectAsState(initial = emptyList())

    val toast = LocalAntToast.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to watchlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                watchlists.forEach { wl ->
                    TvButton(
                        text = wl.name,
                        onClick = {
                            scope.launch {
                                repo.addToWatchlist(wl.id, item)
                                toast("Added to ${wl.name}")
                                onDismiss()
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun WatchlistGridCard(
    item: MediaItem,
    onClick: () -> Unit,
    onHoldSelect: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }

    // Long-press tracking + swallow KeyUp
    var longPressFired by remember { mutableStateOf(false) }
    var swallowNextKeyUp by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            // ✅ Do NOT force any size here — let MediaCard measure itself
            .wrapContentSize()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                val isSelect =
                    (e.key == androidx.compose.ui.input.key.Key.DirectionCenter ||
                            e.key == androidx.compose.ui.input.key.Key.Enter)

                if (!isSelect) return@onPreviewKeyEvent false

                when (e.type) {
                    androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            longPressFired = false
                            swallowNextKeyUp = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                kotlinx.coroutines.delay(550)
                                longPressFired = true
                                swallowNextKeyUp = true
                                onHoldSelect()
                            }
                        }
                        true
                    }

                    androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null

                        if (longPressFired || swallowNextKeyUp) {
                            longPressFired = false
                            swallowNextKeyUp = false
                            true
                        } else {
                            onClick()
                            true
                        }
                    }

                    else -> false
                }
            }
            // ✅ Border wraps the actual card size now
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
            .padding(2.dp) // little breathing room so border doesn't overlap content
    ) {
        MediaCard(item = item, onClick = onClick)
    }
}
