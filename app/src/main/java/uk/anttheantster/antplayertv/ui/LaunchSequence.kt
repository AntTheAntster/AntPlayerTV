package uk.anttheantster.antplayertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import uk.anttheantster.antplayertv.data.BrowseApi
import uk.anttheantster.antplayertv.data.BrowseCard
import uk.anttheantster.antplayertv.data.TmdbApi
import uk.anttheantster.antplayertv.data.TmdbCard
import uk.anttheantster.antplayertv.data.toTmdbCard
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.anttheantster.antplayertv.BuildConfig
import uk.anttheantster.antplayertv.R

/* -------------------------------------------------------------------------- */
/*  Boot Sequence                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Stage of the launch animation. The boot screen drives this through
 * [LogoIntro] -> [Working] -> [Done], and a parent composable can overlay
 * licence / update prompts during the [Working] phase.
 */
enum class BootStage { LogoIntro, Working, Done }

/**
 * Cinematic intro screen.
 *
 * Phases (timed):
 *   0ms     : logo fades into the centre
 *   ~900ms  : logo slides upward
 *   ~1300ms : version label fades in below
 *   ~2100ms : [onIntroFinished] is fired so the parent can run licence /
 *             update checks while the logo stays in place.
 *
 * The screen itself stays mounted; the parent should pass [statusText]
 * (e.g. "Checking licence…", "Looking for updates…") to display under the
 * version label while [stage] == [BootStage.Working].
 */
@Composable
fun BootIntroScreen(
    stage: BootStage,
    statusText: String?,
    onIntroFinished: () -> Unit
) {
    // Logo alpha: starts at 0, fades in
    val logoAlpha = remember { Animatable(0f) }
    // Logo Y offset (in dp). Starts at 0 (centred), ends at -100.dp after slide-up.
    val logoOffsetY = remember { Animatable(0f) }
    // Version + status label alpha
    val labelAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 1 — fade in
        logoAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = LinearEasing)
        )
        // brief hold
        delay(180)
        // Phase 2 — slide up
        logoOffsetY.animateTo(
            targetValue = -90f,
            animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
        )
        // Phase 3 — version fades in
        labelAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = LinearEasing)
        )
        // Hold a beat then signal parent
        delay(280)
        onIntroFinished()
    }

    AntScreenBackground {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.antplayer_logo),
                    contentDescription = "AntPlayer logo",
                    // graphicsLayer must come BEFORE size+clip so the clip
                    // travels with the translation rather than staying
                    // pinned at the original slot.
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = logoAlpha.value
                            translationY = logoOffsetY.value * density
                        }
                        .size(140.dp)
                        .clip(RoundedCornerShape(28.dp))
                )

                Spacer(Modifier.height(20.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = labelAlpha.value
                            translationY = logoOffsetY.value * density
                        }
                ) {
                    Text(
                        text = "AntPlayer TV",
                        style = MaterialTheme.typography.headlineLarge,
                        color = AntColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AntColors.TextSecondary
                    )
                }
            }

            // Status footer (only shown during Working stage).
            AnimatedVisibility(
                visible = stage == BootStage.Working && !statusText.isNullOrBlank(),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = statusText.orEmpty(),
                    color = AntColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Launcher Hub — 4 floating cards                                           */
/* -------------------------------------------------------------------------- */

data class HubDestination(
    val label: String,
    val icon: ImageVector,
    val onSelect: () -> Unit
)

/**
 * The post-boot launcher: four square "floating" cards animated in from below.
 * The cards are: Browse, Live TV, Home, Settings.
 */
@Composable
fun LauncherHub(
    onBrowse: () -> Unit,
    onLiveTv: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val destinations = listOf(
        HubDestination("Browse",   Icons.Filled.Search,   onBrowse),
        HubDestination("Live TV",  Icons.Filled.LiveTv,   onLiveTv),
        HubDestination("Home",     Icons.Filled.Home,     onHome),
        HubDestination("Settings", Icons.Filled.Settings, onSettings),
    )

    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Tiny delay to allow any prior screen fade-out to settle
        delay(60)
        revealed = true
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(revealed) {
        if (revealed) {
            // Wait until cards are on-screen and focusable
            delay(380)
            try { firstFocus.requestFocus() } catch (_: Throwable) { /* no-op */ }
        }
    }

    AntScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header strip — small AntPlayer mark + version, top-left aligned via Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.antplayer_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "AntPlayer TV",
                        color = AntColors.TextPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        color = AntColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    "Press Menu for navigation",
                    color = AntColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable { onOpenDrawer() }
                )
            }

            Spacer(Modifier.height(48.dp))

            Text(
                text = "Where would you like to go?",
                color = AntColors.TextPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)
            ) {
                destinations.forEachIndexed { index, dest ->
                    AnimatedVisibility(
                        visible = revealed,
                        enter = fadeIn(animationSpec = tween(420, delayMillis = 80 * index)) +
                                slideInVertically(
                                    animationSpec = tween(540, delayMillis = 80 * index, easing = EaseOutCubic),
                                    initialOffsetY = { it / 3 }
                                ) +
                                scaleIn(
                                    animationSpec = tween(420, delayMillis = 80 * index, easing = EaseOutCubic),
                                    initialScale = 0.85f
                                ),
                        exit = fadeOut() + scaleOut()
                    ) {
                        HubCard(
                            label = dest.label,
                            icon = dest.icon,
                            onClick = dest.onSelect,
                            modifier = if (index == 0)
                                Modifier.focusRequester(firstFocus)
                            else
                                Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (focused) 1.06f else 1f
    val borderWidth = if (focused) 3.dp else 1.dp
    val borderColor = if (focused) AntColors.AccentPurple else AntColors.Divider

    Surface(
        // Order matters on Fire TV: onFocusChanged → clickable → focusable
        // mirrors the working TvButton elsewhere in the app, otherwise
        // .focusable() can swallow the OK keypress before .clickable runs.
        modifier = modifier
            .size(width = 200.dp, height = 200.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(24.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (focused) AntColors.SurfaceElev3 else AntColors.SurfaceElev1,
        tonalElevation = if (focused) 8.dp else 2.dp,
        shadowElevation = if (focused) 12.dp else 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = if (focused)
                        Brush.verticalGradient(
                            listOf(
                                AntColors.AccentDeep.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        )
                    else
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Transparent)
                        )
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(56.dp),
                    tint = if (focused) AntColors.AccentSoft else AntColors.TextPrimary
                )
                Text(
                    text = label,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Browse screen                                                             */
/* -------------------------------------------------------------------------- */

@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    onViewAll: () -> Unit,
    onItemSelected: (TmdbCard) -> Unit,
) {
    val tmdbApi   = remember { TmdbApi(baseUrl = "https://api.anttheantster.uk") }
    val browseApi = remember { BrowseApi(baseUrl = "https://api.anttheantster.uk") }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TmdbCard>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }

    var popularCards by remember { mutableStateOf<List<BrowseCard>>(emptyList()) }
    var newCards     by remember { mutableStateOf<List<BrowseCard>>(emptyList()) }
    var browseLoaded by remember { mutableStateOf(false) }

    var keyboardActive       by remember { mutableStateOf(false) }
    val searchFocus          = remember { FocusRequester() }
    val firstResultFocus     = remember { FocusRequester() }
    val firstBrowseCardFocus = remember { FocusRequester() }
    var searchFieldFocused   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { searchFocus.requestFocus() } catch (_: Throwable) {}
        popularCards = try { browseApi.getPopular(limit = 20) } catch (_: Exception) { emptyList() }
        newCards     = try { browseApi.getNew(limit = 20)     } catch (_: Exception) { emptyList() }
        browseLoaded = true
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = emptyList()
            searchLoading = false
        } else {
            delay(220)
            searchLoading = true

            // Merge results from two sources, in this order:
            //   1. Verified cache hits — fast, guaranteed playable.
            //   2. Extra TMDB hits the cache doesn't know about yet.
            //
            // Earlier we did "fallback when cache empty", but that hides
            // legitimately findable titles (Konosuba OVAs, special
            // editions, etc.) the moment the main series is cached. Now
            // verified results show first, then everything else TMDB
            // surfaces — so the user can still find OVAs even when the
            // main show is already cached. Unverified extras may still
            // fail at play time with a clean "Failed to fetch streams"
            // message; that's the trade-off for broader discoverability.
            val verified = try { tmdbApi.searchStreamable(query) }
                            catch (_: Exception) { emptyList() }
            val all = try { tmdbApi.search(query) }
                       catch (_: Exception) { emptyList() }
            val verifiedKeys = verified.map { "${it.tmdbId}:${it.type}" }.toSet()
            val extras = all.filter { "${it.tmdbId}:${it.type}" !in verifiedKeys }
            searchResults = verified + extras

            searchLoading = false
        }
    }

    LaunchedEffect(searchResults, searchFieldFocused) {
        if (!searchFieldFocused && searchResults.isNotEmpty()) {
            try { firstResultFocus.requestFocus() } catch (_: Throwable) {}
        }
    }

    BackHandler(enabled = keyboardActive) { keyboardActive = false }

    AntScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.TravelExplore,
                    contentDescription = null,
                    tint = AntColors.AccentPurple,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Browse",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AntColors.TextPrimary
                )
            }

            Spacer(Modifier.height(20.dp))

            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                readOnly = !keyboardActive,
                placeholder = {
                    Text(
                        if (keyboardActive) "Type to search…"
                        else "Press OK to search titles, shows, movies…",
                        color = AntColors.TextMuted
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = AntColors.TextSecondary)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardActive = false },
                    onDone   = { keyboardActive = false },
                    onNext   = { keyboardActive = false },
                    onGo     = { keyboardActive = false }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus)
                    .onFocusChanged { state ->
                        searchFieldFocused = state.isFocused
                        if (!state.isFocused && keyboardActive) keyboardActive = false
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> {
                                if (!keyboardActive && query.isBlank() && popularCards.isNotEmpty()) {
                                    try { firstBrowseCardFocus.requestFocus() } catch (_: Throwable) {}
                                    true
                                } else false
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (!keyboardActive) { keyboardActive = true; true } else false
                            }
                            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                                if (keyboardActive) { keyboardActive = false; true } else false
                            }
                            else -> false
                        }
                    }
            )

            Spacer(Modifier.height(20.dp))

            // Content area
            when {
                query.isNotBlank() -> {
                    when {
                        searchLoading && searchResults.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Searching…", color = AntColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        searchResults.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No results for \"$query\"", color = AntColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        else -> {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(searchResults) { index, card ->
                                    val rowMod =
                                        if (index == 0) Modifier.focusRequester(firstResultFocus)
                                        else Modifier
                                    TmdbResultRow(card = card, onClick = { onItemSelected(card) },
                                        modifier = rowMod)
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Browse sections — verticalScroll for the What's New row to be reachable
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        BrowseHorizontalRow(
                            title          = "Popular",
                            cards          = popularCards,
                            loaded         = browseLoaded,
                            firstCardFocus = firstBrowseCardFocus,
                            onCardClick    = { onItemSelected(it.toTmdbCard()) },
                        )
                        BrowseHorizontalRow(
                            title       = "What's New",
                            cards       = newCards,
                            loaded      = browseLoaded,
                            onCardClick = { onItemSelected(it.toTmdbCard()) },
                        )
                        ViewAllButton(onClick = onViewAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AntColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun BrowseHorizontalRow(
    title: String,
    cards: List<BrowseCard>,
    loaded: Boolean = true,
    firstCardFocus: FocusRequester? = null,
    onCardClick: (BrowseCard) -> Unit,
) {
    BrowseSection(title = title) {
        when {
            !loaded          -> EmptyRowPlaceholder("Loading…")
            cards.isEmpty()  -> EmptyRowPlaceholder("Nothing available yet.")
            else             -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(cards) { index, card ->
                    val cardMod = if (index == 0 && firstCardFocus != null)
                        Modifier.focusRequester(firstCardFocus) else Modifier
                    BrowsePosterCard(card = card, onClick = { onCardClick(card) }, modifier = cardMod)
                }
            }
        }
    }
}

@Composable
private fun BrowsePosterCard(
    card: BrowseCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .width(130.dp)
            .height(262.dp)     // fixed total height keeps all cards identical
            .clip(shape)
            .background(
                color = if (focused) AntColors.SurfaceElev3 else AntColors.SurfaceElev1,
                shape = shape
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = card.posterUrl.ifBlank { null },
            contentDescription = card.title,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        // Fixed-height text area — title capped at 1 line, year below
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelLarge,
                color = AntColors.TextPrimary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (card.year != null) {
                Text(
                    text = card.year,
                    style = MaterialTheme.typography.labelSmall,
                    color = AntColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyRowPlaceholder(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        shape = RoundedCornerShape(16.dp),
        color = AntColors.SurfaceElev1,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = AntColors.Divider,
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                color = AntColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ViewAllButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        shape = shape,
        color = if (focused) AntColors.AccentDeep else AntColors.SurfaceElev1,
        tonalElevation = if (focused) 4.dp else 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "View All",
                color = AntColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Browse All screen                                                         */
/* -------------------------------------------------------------------------- */

@Composable
fun BrowseAllScreen(
    onBack: () -> Unit,
    onItemSelected: (TmdbCard) -> Unit,
) {
    val browseApi = remember { BrowseApi(baseUrl = "https://api.anttheantster.uk") }

    var filterType      by remember { mutableStateOf("") }
    var filterSort      by remember { mutableStateOf("rating_desc") }
    var filterMinRating by remember { mutableStateOf<Double?>(null) }

    var results by remember { mutableStateOf<List<BrowseCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val firstFilterFocus = remember { FocusRequester() }
    // Hoisted FocusRequester for the first result row. Each filter
    // dropdown explicitly routes DPAD-Down to this so the user can
    // always cross from the filter row into the results list — without
    // it, Compose's geometric focus search fails to bridge a static
    // Row → LazyColumn boundary cleanly and DPAD-Down just defocuses
    // the filters into nowhere.
    val firstResultFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80)
        try { firstFilterFocus.requestFocus() } catch (_: Throwable) {}
    }

    LaunchedEffect(filterType, filterSort, filterMinRating) {
        loading = true
        results = try {
            browseApi.getFilter(
                type      = filterType.ifBlank { null },
                sort      = filterSort,
                minRating = filterMinRating,
                limit     = 50,
            )
        } catch (_: Exception) { emptyList() }
        loading = false
    }

    BackHandler { onBack() }

    AntScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.TravelExplore,
                    contentDescription = null,
                    tint = AntColors.AccentPurple,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Browse All",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AntColors.TextPrimary
                )
            }

            Spacer(Modifier.height(20.dp))

            // Filter row. Each dropdown explicitly redirects DPAD-Down
            // to the first result row (when there are results). Passing
            // null disables the redirect so a no-results screen leaves
            // focus politely stuck on the filters.
            val downBridge = if (results.isNotEmpty()) firstResultFocus else null
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrowseFilterDropdown(
                    label = when (filterType) {
                        "movie" -> "Movies"
                        "tv"    -> "TV Shows"
                        "anime" -> "Anime"
                        else    -> "All Types"
                    },
                    anchorFocus = firstFilterFocus,
                    downTarget  = downBridge,
                    options = listOf("All Types" to "", "Movies" to "movie", "TV Shows" to "tv", "Anime" to "anime"),
                    selected = filterType,
                    onSelect = { filterType = it },
                )
                BrowseFilterDropdown(
                    label = when (filterSort) {
                        "rating_asc" -> "Rating ↑"
                        "year_desc"  -> "Year ↓"
                        "year_asc"   -> "Year ↑"
                        else         -> "Rating ↓"
                    },
                    downTarget = downBridge,
                    options = listOf(
                        "Rating ↓" to "rating_desc",
                        "Rating ↑" to "rating_asc",
                        "Year ↓"   to "year_desc",
                        "Year ↑"   to "year_asc",
                    ),
                    selected = filterSort,
                    onSelect = { filterSort = it },
                )
                BrowseFilterDropdown(
                    label = when (filterMinRating) {
                        6.0  -> "Rating 6+"
                        7.0  -> "Rating 7+"
                        8.0  -> "Rating 8+"
                        else -> "Any Rating"
                    },
                    downTarget = downBridge,
                    options = listOf(
                        "Any Rating" to null,
                        "Rating 6+"  to 6.0,
                        "Rating 7+"  to 7.0,
                        "Rating 8+"  to 8.0,
                    ),
                    selected = filterMinRating,
                    onSelect = { filterMinRating = it },
                )
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading…", color = AntColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
                results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results for these filters.", color = AntColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(results) { index, card ->
                        // First row claims the explicit FocusRequester
                        // that the filter dropdowns route DPAD-Down to,
                        // so cross-container traversal lands here
                        // deterministically.
                        val rowMod =
                            if (index == 0) Modifier.focusRequester(firstResultFocus)
                            else Modifier
                        TmdbResultRow(
                            card = card.toTmdbCard(),
                            onClick = { onItemSelected(card.toTmdbCard()) },
                            modifier = rowMod,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> BrowseFilterDropdown(
    label: String,
    anchorFocus: FocusRequester? = null,
    /**
     * Optional FocusRequester that DPAD-Down should jump to. Used to
     * bridge the static filter Row across to the LazyColumn of results
     * below — Compose's geometric focus search alone doesn't make that
     * jump cleanly.
     */
    downTarget: FocusRequester? = null,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused  by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Box {
        Surface(
            modifier = Modifier
                .let { m -> if (anchorFocus != null) m.focusRequester(anchorFocus) else m }
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                    shape = shape
                )
                .focusProperties {
                    if (downTarget != null) down = downTarget
                }
                .onFocusChanged { focused = it.isFocused }
                .clickable { expanded = !expanded }
                .focusable(),
            shape = shape,
            color = AntColors.SurfaceElev2,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium
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
            options.forEachIndexed { index, (optLabel, optValue) ->
                val isSelected = selected == optValue
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optLabel,
                            color = AntColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    onClick = {
                        onSelect(optValue)
                        expanded = false
                    },
                    modifier = Modifier
                        .background(
                            if (isSelected) AntColors.AccentDeep.copy(alpha = 0.20f)
                            else Color.Transparent
                        )
                        .let { m -> if (index == 0) m.focusRequester(firstFocus) else m },
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Live TV + Home placeholders                                               */
/* -------------------------------------------------------------------------- */

@Composable
fun LiveTvPlaceholderScreen() {
    PlaceholderScreen(
        icon = Icons.Filled.LiveTv,
        title = "Live TV",
        subtitle = "IPTV channels are coming soon.",
        accent = AntColors.AccentPurple
    )
}

@Composable
private fun PlaceholderScreen(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color
) {
    // The placeholder has no interactive widgets, so without a focus target
    // the scaffold's preview key handler never fires (Menu / DPAD-Left can't
    // open the drawer). Give the screen a single root-level focusable.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { rootFocus.requestFocus() } catch (_: Throwable) { /* no-op */ }
    }

    AntScreenBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = AntColors.SurfaceElev2,
                    tonalElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = accent
                        )
                    }
                }
                Text(
                    text = title,
                    color = AntColors.TextPrimary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = AntColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Press Menu to navigate elsewhere.",
                    color = AntColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  TMDB result row                                                           */
/* -------------------------------------------------------------------------- */

/**
 * One TMDB search result rendered in the Browse list. Aims for a clean,
 * Netflix/Prime-style row: poster on the left, title + facets + synopsis
 * preview on the right, accent border + scale-up on focus.
 */
@Composable
private fun TmdbResultRow(
    card: TmdbCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        // Order matters on Fire TV: onFocusChanged → clickable → focusable.
        // Without the explicit .focusable() the row isn't reliably picked
        // up by DPAD focus search inside a LazyColumn — the View All
        // screen would let the user see the title cards but never focus
        // them when DPAD-Down'ing in from the filter row.
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AntColors.AccentPurple else AntColors.Divider,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = if (focused) AntColors.SurfaceElev3 else AntColors.SurfaceElev1,
        tonalElevation = if (focused) 4.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = card.posterUrl.ifBlank { null },
                contentDescription = card.title,
                modifier = Modifier
                    .size(width = 88.dp, height = 130.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = AntColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (card.year != null) {
                        Text(
                            text = "(${card.year})",
                            style = MaterialTheme.typography.titleMedium,
                            color = AntColors.TextSecondary
                        )
                    }
                }

                // Facet row: type + rating
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FacetChip(
                        text = if (card.type == "tv") "TV" else "Movie",
                        accent = AntColors.AccentPurple
                    )
                    if (card.rating != null && card.ratingCount > 0) {
                        StarRating(rating10 = card.rating)
                    }
                }

                if (card.synopsis.isNotBlank()) {
                    Text(
                        text = card.synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AntColors.TextSecondary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FacetChip(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.18f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = AntColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Compact 5-star rendering of TMDB's 0..10 score (Prime-style).
 * Half-stars are shown when the second decimal lands above 0.25.
 * Trailing numeric ".x / 10" appended for users who want the precise
 * score.
 */
@Composable
private fun StarRating(rating10: Double) {
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
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${"%.1f".format(rating10)} / 10",
            style = MaterialTheme.typography.labelLarge,
            color = AntColors.TextSecondary
        )
    }
}