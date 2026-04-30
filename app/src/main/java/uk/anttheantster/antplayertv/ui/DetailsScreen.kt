package uk.anttheantster.antplayertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.anttheantster.antplayertv.data.AntPlayerDatabase
import uk.anttheantster.antplayertv.data.BrowseApi
import uk.anttheantster.antplayertv.data.EpisodeProgress
import uk.anttheantster.antplayertv.data.ProgressRepository
import uk.anttheantster.antplayertv.data.RemoteSearchApi
import uk.anttheantster.antplayertv.data.StreamOption
import uk.anttheantster.antplayertv.data.TmdbApi
import uk.anttheantster.antplayertv.data.TmdbEpisode
import uk.anttheantster.antplayertv.data.TmdbSeason
import uk.anttheantster.antplayertv.data.TmdbSeasonSummary
import uk.anttheantster.antplayertv.data.TmdbStreamBridge
import uk.anttheantster.antplayertv.data.TmdbTitle
import uk.anttheantster.antplayertv.model.MediaItem

/* -------------------------------------------------------------------------- */
/*  DetailsScreen                                                             */
/* -------------------------------------------------------------------------- */

@Composable
fun DetailsScreen(
    tmdbId: Int,
    type: String,
    titleHint: String,
    posterHint: String,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val tmdb = remember { TmdbApi(baseUrl = "https://api.anttheantster.uk") }
    val scraper = remember { RemoteSearchApi(baseUrl = "https://api.anttheantster.uk") }
    val bridge = remember { TmdbStreamBridge(scraper) }
    // Self-warming cache: every time the bridge successfully resolves a
    // stream, we ping the server to add the title to the verified cache
    // (idempotent if already there). Next search hits the fast cache path
    // instead of falling back to TMDB direct.
    val browseApi = remember { BrowseApi(baseUrl = "https://api.anttheantster.uk") }
    val context = LocalContext.current
    val progressRepo = remember {
        ProgressRepository(AntPlayerDatabase.getInstance(context).progressDao())
    }
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    var title by remember { mutableStateOf<TmdbTitle?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var pendingOptions by remember {
        mutableStateOf<TmdbStreamBridge.Options.Resolved?>(null)
    }

    // Hoisted FocusRequester for the season dropdown so the hero parking
    // box (above) and every episode card (below) can route DPAD-Up /
    // DPAD-Down explicitly back to it. Without this, Compose's geometric
    // focus search lands on whatever episode card happens to overlap
    // vertically — typically not what the user expects.
    val seasonPickerFocus = remember { FocusRequester() }
    // For movies the parking box needs an explicit DPAD-Down target because
    // the play button is geometrically inside the box and unreachable by
    // spatial traversal.
    val playButtonFocus = remember { FocusRequester() }

    LaunchedEffect(tmdbId, type) {
        loadError = null
        title = try { tmdb.getTitle(type, tmdbId) } catch (_: Exception) { null }
        if (title == null) {
            loadError = "Couldn't load details for \"$titleHint\"."
        }
    }

    /**
     * Fire-and-forget cache-warming. We tell the server "this title
     * resolved on the scraper, please add it to the verified cache if
     * it isn't already there." Server is idempotent so we can call this
     * unconditionally on every successful resolve. The series href is
     * derived the same way [TmdbStreamBridge.finishWithStream] does it.
     */
    fun warmCache(opts: TmdbStreamBridge.Options.Resolved) {
        val tid = opts.tmdbId ?: return
        val seriesHref = if (opts.isMovie) opts.match.href
                         else opts.match.href.substringBefore("?episode=")
        scope.launch {
            try {
                browseApi.registerVerified(
                    tmdbId = tid,
                    tmdbType = if (opts.isMovie) "movie" else "tv",
                    scraperHref = seriesHref,
                    scraperImage = opts.match.image,
                )
            } catch (_: Throwable) { /* fire-and-forget */ }
        }
    }

    suspend fun handleOptions(opts: TmdbStreamBridge.Options.Resolved) {
        val deduped = pickBestQualityPerType(opts.streams)
        when {
            deduped.isEmpty() -> {
                statusIsError = true
                statusMessage = "Failed to fetch streams."
            }
            deduped.size == 1 -> {
                statusMessage = "Buffering…"
                delay(450)
                statusMessage = null
                warmCache(opts)
                onPlay(bridge.finishWithStream(opts, deduped.first()))
            }
            else -> {
                statusMessage = null
                pendingOptions = opts.copy(streams = deduped)
            }
        }
    }

    AntScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop
            val backdrop = title?.backdropUrl?.takeIf { it.isNotBlank() }
                ?: posterHint.takeIf { it.isNotBlank() }
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    AntColors.SurfaceBase.copy(alpha = 0.55f),
                                    AntColors.SurfaceBase
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 28.dp)
            ) {
                if (loadError != null) {
                    Text(
                        text = loadError!!,
                        color = AntColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    return@Column
                }

                val t = title
                if (t == null) {
                    DetailsLoadingHero(titleHint)
                } else {
                    // Anime detection — drives source routing in the bridge
                    // so e.g. "From (2022)" doesn't accidentally play an
                    // Animekai title that fuzzy-matched on "From".
                    val isAnime = isAnimeTitle(t)

                    val onPlayMovieAction: (() -> Unit)? = if (t.type == "movie") {
                        {
                            scope.launch {
                                statusIsError = false
                                statusMessage = "Searching for stream…"
                                val opts = bridge.resolveMovieOptions(
                                    title = t.title,
                                    year = t.year,
                                    tmdbId = t.tmdbId,
                                    isAnime = isAnime,
                                    originalTitle = t.originalTitle.takeIf { it != t.title },
                                )
                                when (opts) {
                                    is TmdbStreamBridge.Options.Resolved -> handleOptions(opts)
                                    is TmdbStreamBridge.Options.Failure -> {
                                        statusIsError = true
                                        statusMessage = opts.reason
                                    }
                                }
                            }
                        }
                    } else null

                    if (t.type == "movie") {
                        // Movies don't need the focus-parking trick — the
                        // play button itself owns focus, so the very first
                        // OK press triggers playback. Wrapping it in the
                        // parking box used to mean the parking box grabbed
                        // focus first and the user had to press OK twice.
                        DetailsHero(
                            t = t,
                            playButtonFocus = playButtonFocus,
                            onPlayMovie = onPlayMovieAction
                        )
                    } else {
                        HeroFocusParkingBox(
                            downTarget = seasonPickerFocus,
                        ) {
                            DetailsHero(
                                t = t,
                                playButtonFocus = null,
                                onPlayMovie = null
                            )
                        }
                    }

                    if (t.type == "tv") {
                        Spacer(Modifier.height(24.dp))
                        SeasonsAndEpisodes(
                            tmdb = tmdb,
                            t = t,
                            seasonPickerFocus = seasonPickerFocus,
                            progressRepo = progressRepo,
                            onPickEpisode = { season, ep ->
                                scope.launch {
                                    statusIsError = false
                                    statusMessage = "Searching for stream…"
                                    val opts = bridge.resolveEpisodeOptions(
                                        title = t.title,
                                        seasonNumber = season,
                                        episodeNumber = ep,
                                        tmdbId = t.tmdbId,
                                        isAnime = isAnime,
                                        year = t.year,
                                        originalTitle = t.originalTitle.takeIf { it != t.title },
                                    )
                                    when (opts) {
                                        is TmdbStreamBridge.Options.Resolved -> handleOptions(opts)
                                        is TmdbStreamBridge.Options.Failure -> {
                                            statusIsError = true
                                            statusMessage = opts.reason
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Status overlay
            val status = statusMessage
            if (status != null) {
                StatusOverlay(
                    message = status,
                    isError = statusIsError,
                    onDismiss = { statusMessage = null }
                )
            }

            // Stream picker
            val opts = pendingOptions
            if (opts != null) {
                StreamPickerDialog(
                    options = opts,
                    onPick = { picked ->
                        pendingOptions = null
                        scope.launch {
                            statusIsError = false
                            statusMessage = "Buffering…"
                            delay(450)
                            statusMessage = null
                            warmCache(opts)
                            onPlay(bridge.finishWithStream(opts, picked))
                        }
                    },
                    onDismiss = { pendingOptions = null }
                )
            }
        }
    }
}

/* ---------- Hero ---------- */

@Composable
private fun DetailsLoadingHero(titleHint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = titleHint.ifBlank { "Loading…" },
            color = AntColors.TextPrimary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Loading details…",
            color = AntColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Outer focus-parking Box for the hero. Auto-focuses on entry (no visible
 * border) so the page sits at the top showing the full poster, year,
 * rating, age rating, and synopsis. DPAD-Down is *forced* to the season
 * picker (when [downTarget] is set) so the user can never get stranded
 * on an off-screen episode.
 */
@Composable
private fun HeroFocusParkingBox(
    downTarget: FocusRequester?,
    content: @Composable () -> Unit,
) {
    val parking = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        try { parking.requestFocus() } catch (_: Throwable) {}
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(parking)
            .focusProperties {
                if (downTarget != null) down = downTarget
            }
            .focusable()
    ) {
        content()
    }
}

@Composable
private fun DetailsHero(
    t: TmdbTitle,
    onPlayMovie: (() -> Unit)?,
    playButtonFocus: FocusRequester? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        if (t.posterUrl.isNotBlank()) {
            AsyncImage(
                model = t.posterUrl,
                contentDescription = t.title,
                modifier = Modifier
                    .size(width = 180.dp, height = 270.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(Modifier.width(24.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = t.title,
                color = AntColors.TextPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val facets = buildList {
                if (t.yearRange.isNotBlank()) add(t.yearRange)
                else if (t.year != null) add(t.year)
                add(if (t.type == "tv") "TV" else "Movie")
                if (t.type == "tv" && t.numberOfSeasons > 0) {
                    add(if (t.numberOfSeasons == 1) "1 season" else "${t.numberOfSeasons} seasons")
                }
                if (t.type == "movie" && t.runtimeMinutes != null && t.runtimeMinutes > 0) {
                    add(formatRuntime(t.runtimeMinutes))
                }
                if (t.ageRating.isNotBlank()) add(t.ageRating)
            }
            Text(
                text = facets.joinToString("  •  "),
                color = AntColors.TextSecondary,
                style = MaterialTheme.typography.titleMedium
            )

            if (t.rating != null && t.ratingCount > 0) {
                StarRow(rating10 = t.rating)
            }

            if (t.genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.genres.take(4).forEach { g ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AntColors.SurfaceElev2
                        ) {
                            Text(
                                text = g,
                                style = MaterialTheme.typography.labelLarge,
                                color = AntColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (t.synopsis.isNotBlank()) {
                Text(
                    text = t.synopsis,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onPlayMovie != null) {
                Spacer(Modifier.height(4.dp))
                PlayMovieButton(onClick = onPlayMovie, focusRequester = playButtonFocus)
            }
        }
    }
}

private fun formatRuntime(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Format ms → "mm:ss" or "h:mm:ss". */
private fun formatTimecode(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

@Composable
private fun StarRow(rating10: Double) {
    val rating5 = rating10 / 2.0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(5) { i ->
            val filled = rating5 - i
            val icon = when {
                filled >= 0.75 -> Icons.Filled.Star
                filled >= 0.25 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AntColors.AccentSoft,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${"%.1f".format(rating10)} / 10",
            style = MaterialTheme.typography.titleMedium,
            color = AntColors.TextSecondary
        )
    }
}

@Composable
private fun PlayMovieButton(onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }

    // When a FocusRequester is wired in (movie path), this button is the
    // sole interactive element on screen and should grab focus on entry —
    // the first OK press should fire playback, no DPAD detour required.
    if (focusRequester != null) {
        LaunchedEffect(Unit) {
            delay(80)
            try { focusRequester.requestFocus() } catch (_: Throwable) {}
        }
    }

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Surface(
        modifier = focusMod
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = AntColors.AccentPurple,
                shape = RoundedCornerShape(28.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
        shape = RoundedCornerShape(28.dp),
        color = if (focused) AntColors.AccentPurple else AntColors.AccentDeep,
        tonalElevation = if (focused) 6.dp else 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White
            )
            Text(
                text = "Play",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/* ---------- Seasons + episode rail ---------- */

@Composable
private fun SeasonsAndEpisodes(
    tmdb: TmdbApi,
    t: TmdbTitle,
    seasonPickerFocus: FocusRequester,
    progressRepo: ProgressRepository,
    onPickEpisode: (season: Int, episode: Int) -> Unit,
) {
    val seasons = t.seasons
    if (seasons.isEmpty()) {
        Text(
            text = "No seasons available.",
            color = AntColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    var selectedSeason by remember(t.tmdbId) { mutableStateOf(seasons.first().seasonNumber) }
    val selectedSummary: TmdbSeasonSummary =
        seasons.firstOrNull { it.seasonNumber == selectedSeason } ?: seasons.first()

    var seasonData by remember(t.tmdbId, selectedSeason) { mutableStateOf<TmdbSeason?>(null) }
    var seasonError by remember(t.tmdbId, selectedSeason) { mutableStateOf(false) }
    var progressByEp by remember(t.tmdbId, selectedSeason) {
        mutableStateOf<Map<Int, EpisodeProgress>>(emptyMap())
    }

    LaunchedEffect(t.tmdbId, selectedSeason) {
        seasonData = null
        seasonError = false
        progressByEp = emptyMap()
        val data = try {
            tmdb.getSeason(t.tmdbId, selectedSeason)
        } catch (_: Exception) {
            null
        }
        if (data == null) seasonError = true
        else seasonData = data

        // Pull progress in parallel — never blocks the episode list.
        val pmap = withContext(Dispatchers.IO) {
            progressRepo.getEpisodeProgressForTmdbSeason(t.tmdbId, selectedSeason)
        }
        progressByEp = pmap
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SeasonDropdown(
            seasons = seasons,
            selected = selectedSummary,
            anchorFocus = seasonPickerFocus,
            onSelect = { selectedSeason = it.seasonNumber }
        )

        when {
            seasonError -> Text(
                text = "Couldn't load episodes for ${selectedSummary.name}.",
                color = AntColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            seasonData == null -> Text(
                text = "Loading episodes…",
                color = AntColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            else -> EpisodeRail(
                episodes = seasonData!!.episodes,
                progressByEp = progressByEp,
                seasonPickerFocus = seasonPickerFocus,
                onPick = { ep -> onPickEpisode(selectedSeason, ep.episodeNumber) }
            )
        }
    }
}

@Composable
private fun SeasonDropdown(
    seasons: List<TmdbSeasonSummary>,
    selected: TmdbSeasonSummary,
    anchorFocus: FocusRequester,
    onSelect: (TmdbSeasonSummary) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Box {
        Surface(
            modifier = Modifier
                .focusRequester(anchorFocus)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                    shape = shape
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable { expanded = !expanded },
            shape = shape,
            color = AntColors.SurfaceElev2,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = selected.name,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${selected.episodeCount} eps",
                    color = AntColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = AntColors.TextSecondary
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AntColors.SurfaceElev3),
        ) {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(expanded) {
                if (expanded) {
                    delay(40)
                    try { firstFocus.requestFocus() } catch (_: Throwable) {}
                }
            }
            seasons.forEachIndexed { index, season ->
                val isSelected = season.seasonNumber == selected.seasonNumber
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = season.name,
                                color = AntColors.TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${season.episodeCount} eps",
                                color = AntColors.TextSecondary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    },
                    onClick = {
                        onSelect(season)
                        expanded = false
                    },
                    modifier = Modifier
                        .background(
                            if (isSelected) AntColors.AccentDeep.copy(alpha = 0.20f)
                            else Color.Transparent
                        )
                        .let { m -> if (isSelected) m.focusRequester(firstFocus) else m },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRail(
    episodes: List<TmdbEpisode>,
    progressByEp: Map<Int, EpisodeProgress>,
    seasonPickerFocus: FocusRequester,
    onPick: (TmdbEpisode) -> Unit,
) {
    if (episodes.isEmpty()) {
        Text(
            text = "No episodes listed.",
            color = AntColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Force every card in the row to send DPAD-Up back to the season
            // picker rather than wherever Compose's geometric search might
            // wander to. (focusProperties on the LazyRow propagates to its
            // focusable children.)
            .focusProperties { up = seasonPickerFocus }
    ) {
        items(episodes, key = { it.episodeNumber }) { ep ->
            val prog = progressByEp[ep.episodeNumber]
            EpisodeCard(
                ep = ep,
                progress = prog,
                seasonPickerFocus = seasonPickerFocus,
                onClick = { onPick(ep) }
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    ep: TmdbEpisode,
    progress: EpisodeProgress?,
    seasonPickerFocus: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    val pos = progress?.positionMs ?: 0L
    val dur = progress?.durationMs ?: 0L
    val watched = pos > 5_000L
    val completed = (dur > 0L && pos >= dur - 10_000L) || (watched && dur == 0L)
    // 0..1 fill ratio for the seek bar overlay.
    val ratio = when {
        completed -> 1f
        dur > 0L && pos > 0L -> (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    Surface(
        modifier = Modifier
            .width(280.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                shape = shape
            )
            .focusProperties { up = seasonPickerFocus }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        shape = shape,
        color = if (focused) AntColors.SurfaceElev3 else AntColors.SurfaceElev1,
        tonalElevation = if (focused) 6.dp else 1.dp
    ) {
        Column {
            Box {
                AsyncImage(
                    model = ep.stillUrl.ifBlank { null },
                    contentDescription = ep.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                // Episode number badge (top-left)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AntColors.SurfaceBase.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = "EP ${ep.episodeNumber}",
                        color = AntColors.TextPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                // Watched-time badge (above the seek bar) when there's
                // partial progress. Hidden once completed because a
                // "Completed" label takes its place.
                if (watched && !completed && dur > 0L) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AntColors.SurfaceBase.copy(alpha = 0.85f),
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
                            .align(Alignment.BottomStart)
                    ) {
                        Text(
                            text = "${formatTimecode(pos)} / ${formatTimecode(dur)}",
                            color = AntColors.TextPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                // Completed badge — Netflix-style bottom-right pill.
                if (completed) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AntColors.AccentDeep,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = "Completed",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else if (ep.runtimeMinutes != null && ep.runtimeMinutes > 0 && !watched) {
                    // Runtime-only badge (no progress yet, no Completed).
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AntColors.SurfaceBase.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = formatRuntime(ep.runtimeMinutes),
                            color = AntColors.TextPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                // Seek bar across the bottom of the thumbnail (Netflix-style).
                if (ratio > 0f) {
                    SeekBarOverlay(
                        ratio = ratio,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                }
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = ep.name,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ep.synopsis.isNotBlank()) {
                    Text(
                        text = ep.synopsis,
                        color = AntColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Two-layer seek bar drawn at the bottom of an episode thumbnail.
 * Background is a subtle dark track; foreground is bright white filling
 * the watched fraction — same visual language as Netflix / Prime.
 */
@Composable
private fun SeekBarOverlay(ratio: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .background(AntColors.SurfaceBase.copy(alpha = 0.55f))
    ) {
        // Use a Spacer with a fractional width via FractionalLayout-ish trick.
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Color.White)
        )
    }
}

/* ---------- Status overlay ---------- */

@Composable
private fun StatusOverlay(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    if (isError) BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AntColors.SurfaceBase.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        var focused by remember { mutableStateOf(false) }
        val dismissFocus = remember { FocusRequester() }
        if (isError) {
            LaunchedEffect(Unit) {
                delay(60)
                try { dismissFocus.requestFocus() } catch (_: Throwable) {}
            }
        }
        Surface(
            modifier = Modifier
                .let { m -> if (isError) m.focusRequester(dismissFocus) else m }
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = AntColors.AccentPurple,
                    shape = RoundedCornerShape(16.dp)
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(enabled = isError, onClick = onDismiss),
            shape = RoundedCornerShape(16.dp),
            color = AntColors.SurfaceElev2,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message,
                    color = if (isError) AntColors.AccentSoft else AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (isError) {
                    Text(
                        text = "OK to dismiss",
                        color = AntColors.TextMuted,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/* ---------- Stream picker ---------- */

@Composable
private fun StreamPickerDialog(
    options: TmdbStreamBridge.Options.Resolved,
    onPick: (StreamOption) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        try { firstFocus.requestFocus() } catch (_: Throwable) {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AntColors.SurfaceBase.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(420.dp),
            shape = RoundedCornerShape(16.dp),
            color = AntColors.SurfaceElev2,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Choose a stream",
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                options.streams.forEachIndexed { index, opt ->
                    StreamOptionRow(
                        label = displayLabel(opt.label),
                        sublabel = opt.label,
                        onClick = { onPick(opt) },
                        firstFocus = if (index == 0) firstFocus else null,
                    )
                    if (index < options.streams.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamOptionRow(
    label: String,
    sublabel: String,
    onClick: () -> Unit,
    firstFocus: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (firstFocus != null) m.focusRequester(firstFocus) else m }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        shape = shape,
        color = if (focused) AntColors.SurfaceElev3 else AntColors.SurfaceElev1
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = label,
                color = AntColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            if (sublabel.isNotBlank() && !sublabel.equals(label, ignoreCase = true)) {
                Text(
                    text = sublabel,
                    color = AntColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/* ---------- Anime detection ---------- */

/**
 * Source-routing predicate. Anime always carries TMDB's "Animation"
 * genre tag, so the contrapositive is rock-solid: NOT Animation means
 * 1Movies. We then trust the positive direction too — Animation →
 * Animekai — which catches anime / aeni / donghua regardless of
 * originalLanguage (Solo Leveling can be tagged ja or ko on different
 * TMDB entries; with a pure-genre check both routes correctly).
 */
private fun isAnimeTitle(t: TmdbTitle): Boolean =
    t.genres.any { it.equals("Animation", ignoreCase = true) }

/* ---------- Quality / type detection ---------- */

private val QUALITY_REGEX = Regex("""(2160|1440|1080|720|480|360|240)\s*p?""", RegexOption.IGNORE_CASE)

private fun qualityScore(label: String): Int =
    QUALITY_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

private fun classify(label: String): String {
    val low = label.lowercase()
    return when {
        "dub" in low -> "Dubbed"
        "hardsub" in low || "softsub" in low || " sub" in " $low" || "subbed" in low -> "Subbed"
        "raw" in low -> "Raw"
        "original" in low -> "Original"
        else -> label.trim().ifBlank { "Stream" }
    }
}

private fun pickBestQualityPerType(
    options: List<StreamOption>
): List<StreamOption> {
    if (options.size <= 1) return options
    return options
        .groupBy { classify(it.label) }
        .values
        .map { group -> group.maxByOrNull { qualityScore(it.label) } ?: group.first() }
}

private fun displayLabel(rawLabel: String): String = classify(rawLabel)
