package uk.anttheantster.antplayertv

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.res.painterResource
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
import uk.anttheantster.antplayertv.data.AshiSearchResult
import uk.anttheantster.antplayertv.data.ContentRepository
import uk.anttheantster.antplayertv.data.ProgressRepository
import uk.anttheantster.antplayertv.data.RemoteSearchApi
import uk.anttheantster.antplayertv.data.StreamOption
import uk.anttheantster.antplayertv.data.LicenseUtils
import uk.anttheantster.antplayertv.data.LicenseApi
import uk.anttheantster.antplayertv.data.UpdateApi
import uk.anttheantster.antplayertv.data.UpdateInfo
import uk.anttheantster.antplayertv.data.UpdateInstaller
import uk.anttheantster.antplayertv.model.MediaItem
import uk.anttheantster.antplayertv.ui.AntPlayerTheme
import uk.anttheantster.antplayertv.ui.NavigationState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from Splash theme to normal theme
        setTheme(R.style.Theme_AntPlayerTV_Splash)

        super.onCreate(savedInstanceState)

        setContent {
            AntPlayerTheme {
                AntPlayerRoot()
            }
        }
    }
}

@Composable
fun AntPlayerTVApp() {
    var navState by remember {
        mutableStateOf<NavigationState>(NavigationState.Home)
    }
    var previousState by remember {
        mutableStateOf<NavigationState?>(null)
    }

    when (val state = navState) {
        is NavigationState.Home -> {
            AntPlayerHome(
                onItemSelected = { item ->
                    previousState = NavigationState.Home
                    navState = NavigationState.Details(item)
                },
                onSearch = {
                    navState = NavigationState.Search
                },
                onSettings = {
                    previousState = NavigationState.Home
                    navState = NavigationState.Settings
                }
            )
        }

        is NavigationState.Search -> {
            AntPlayerSearch(
                onItemSelected = { item ->
                    previousState = NavigationState.Search
                    navState = NavigationState.Details(item)
                },
                onBack = { navState = NavigationState.Home }
            )
        }

        is NavigationState.Settings -> {
            AntPlayerSettings(
                onBack = { navState = NavigationState.Home }
            )
        }

        is NavigationState.Details -> {
            AntPlayerDetails(
                item = state.item,
                onPlay = { playableItem ->
                    navState = NavigationState.Player(
                        playableItem,
                        startPositionMs = null
                    )
                },
                onResume = { playableItem, resumeMs ->
                    navState = NavigationState.Player(
                        playableItem,
                        startPositionMs = resumeMs
                    )
                },
                onBack = {
                    navState = previousState ?: NavigationState.Home
                }
            )
        }

        is NavigationState.Player -> {
            AntPlayerPlayer(
                mediaItem = state.item,
                startPositionMs = state.startPositionMs,
                onBack = { navState = NavigationState.Details(state.item) }
            )
        }
    }
}

@Composable
fun AntPlayerHome(
    onItemSelected: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current

    val contentRepository = remember { ContentRepository(context) }
    val allItems by remember { mutableStateOf(contentRepository.loadAllItems()) }

    // Continue Watching from DB
    val continueWatchingItems by produceState(initialValue = emptyList<MediaItem>(), context) {
        val db = AntPlayerDatabase.getInstance(context)
        val progressRepo = ProgressRepository(db.progressDao())
        value = progressRepo.getContinueWatching()
    }

    // History from DB
    val historyItems by produceState(initialValue = emptyList<MediaItem>(), context) {
        val db = AntPlayerDatabase.getInstance(context)
        val progressRepo = ProgressRepository(db.progressDao())
        value = progressRepo.getHistory()
    }

    MaterialTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top bar
            item {
                val searchFocusRequester = remember { FocusRequester() }

                // Auto-focus Search when Home opens
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.antplayer_logo),
                            contentDescription = "AntPlayer logo",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AntPlayer TV",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "(v" + BuildConfig.VERSION_NAME + ")",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = "Settings",
                            onClick = onSettings
                        )
                        TvButton(
                            text = "Search",
                            onClick = onSearch,
                            modifier = Modifier.focusRequester(searchFocusRequester)
                        )
                    }
                }
            }

            // Continue Watching row
            if (continueWatchingItems.isNotEmpty()) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 })
                    ) {
                        Column {
                            HomeSectionTitle("Continue Watching")
                            LazyRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                items(continueWatchingItems) { item ->
                                    MediaCard(item = item, onClick = { onItemSelected(item) })
                                }
                            }
                        }
                    }
                }
            }

            // History row
            if (historyItems.isNotEmpty()) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 })
                    ) {
                        Column {
                            HomeSectionTitle("History")
                            LazyRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                items(historyItems) { item ->
                                    MediaCard(item = item, onClick = { onItemSelected(item) })
                                }
                            }
                        }
                    }
                }
            }

            // Featured row (all items)
            item {
                if (allItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Search for and watch a title for this screen to update",
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                } else {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 })
                    ) {
                        Column {
                            HomeSectionTitle("Featured")
                            LazyRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                items(allItems) { item ->
                                    MediaCard(item = item, onClick = { onItemSelected(item) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun MediaCard(item: MediaItem, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "cardScale"
    )

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
            text = item.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AntPlayerPlayer(
    mediaItem: MediaItem,
    startPositionMs: Long?,
    onBack: () -> Unit
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

    // ExoPlayer instance
    val exoPlayer = remember(mediaItem.streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            val media = ExoMediaItem.fromUri(mediaItem.streamUrl)
            setMediaItem(media)
            prepare()
            playWhenReady = true
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
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // PlayerView reference
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Focus will live on the AndroidView (PlayerView), not the Box
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // proper black bars
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp,
                            Key.DirectionDown,
                            Key.DirectionLeft,
                            Key.DirectionRight,
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.NumPadEnter,
                            Key.Spacebar -> {
                                // Show controller whenever the user presses DPAD / OK
                                playerViewRef?.showController()
                                // return false so PlayerView still handles the key
                                false
                            }
                            else -> false
                        }
                    } else {
                        false
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
                    controllerShowTimeoutMs = 5000 // auto-hide after 5s

                    isFocusable = true
                    isFocusableInTouchMode = true

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { createdView ->
                    playerViewRef = createdView
                }
            },
            update = { view ->
                playerViewRef = view
            }
        )

        // Grab focus when the player screen opens and show controller initially
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            playerViewRef?.showController()
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

    // local/progress lookup is always by item.id
    val resumePositionMs by produceState<Long?>(initialValue = null, item) {
        val db = AntPlayerDatabase.getInstance(context)
        val repo = ProgressRepository(db.progressDao())
        value = repo.getProgressFor(item.id)
    }

    // Heuristic: remote Ashi "series" item = no direct streamUrl but special id
    val isRemoteSeries =
        item.streamUrl.isBlank() &&
                (item.id.startsWith("Animekai:") ||
                        item.id.contains("1movies", ignoreCase = true))

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

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )

                        Spacer(modifier = Modifier.height(20.dp))

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
            // ───── Remote Ashi series: details + episodes, per-episode expand + play ─────
            val api = remember {
                RemoteSearchApi(
                    baseUrl = "https://api.anttheantster.uk" // your Node server
                )
            }

            var details by remember { mutableStateOf<AshiDetails?>(null) }
            var episodes by remember { mutableStateOf<List<AshiEpisode>>(emptyList()) }
            var expandedEpisode by remember { mutableStateOf<AshiEpisode?>(null) }
            var loadingMeta by remember { mutableStateOf(true) }

            // For the stream dialog
            var dialogEpisode by remember { mutableStateOf<AshiEpisode?>(null) }
            var showStreamDialog by remember { mutableStateOf(false) }
            var streamOptions by remember { mutableStateOf<List<StreamOption>>(emptyList()) }
            var loadingStreams by remember { mutableStateOf(false) }

            // Load show details + episodes once for this item
            LaunchedEffect(item.id) {
                loadingMeta = true
                val info = api.getDetails(item.id)
                val eps = api.getEpisodes(item.id)
                details = info
                episodes = eps
                expandedEpisode = eps.firstOrNull()     // start with first expanded
                loadingMeta = false
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
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Spacer(modifier = Modifier.height(8.dp))

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

                            if (loadingMeta) {
                                Text(
                                    text = "Loading episodes...",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            } else if (episodes.isEmpty()) {
                                Text(
                                    text = "No episodes found.",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            } else {
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
                                    episodes.forEachIndexed { index, episode ->
                                        EpisodeRow(
                                            episode = episode,
                                            expanded = (episode == expandedEpisode),
                                            seriesImage = item.image,
                                            onSelectEpisode = {
                                                expandedEpisode = episode
                                            },
                                            onPlayClicked = {
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
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ───── Stream options dialog (Sub/Dub/Original etc.) ─────
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
fun AntPlayerSearch(
    onItemSelected: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val api = remember {
        RemoteSearchApi(
            baseUrl = "http://178.79.150.26:3000"
        )
    }

    var query by remember { mutableStateOf("") }

    // Results from the server
    var searchResults by remember { mutableStateOf<List<AshiSearchResult>>(emptyList()) }

    // Simple loading indicator flag
    var isLoading by remember { mutableStateOf(false) }

    // 🔹 Focus requester for the first result row
    val firstResultFocusRequester = remember { FocusRequester() }

    // When the query changes, call the server
    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = emptyList()
        } else {
            isLoading = true
            searchResults = api.search(query)
            isLoading = false
        }
    }

    // 🔹 When results appear, move focus to the first row
    LaunchedEffect(searchResults) {
        if (searchResults.isNotEmpty()) {
            firstResultFocusRequester.requestFocus()
        }
    }

    BackHandler {
        onBack()
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TvButton(text = "← Back", onClick = onBack)

                Spacer(modifier = Modifier.width(16.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search", color = MaterialTheme.colorScheme.onBackground) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Searching.", color = MaterialTheme.colorScheme.onBackground)
                    }
                }

                searchResults.isEmpty() && query.isNotBlank() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No results", color = MaterialTheme.colorScheme.onBackground)
                    }
                }

                searchResults.isEmpty() && query.isBlank() -> {
                    // Nothing typed yet – show nothing
                }

                else -> {
                    // We got results from the server
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(searchResults) { index, result ->
                            // Convert AshiSearchResult to MediaItem for UI & navigation
                            val item = MediaItem(
                                id = result.href,         // URL for details/episodes
                                title = result.title,
                                description = "",         // filled by details API later
                                image = result.image,
                                streamUrl = ""            // no direct stream yet; details will resolve
                            )

                            val rowModifier =
                                if (index == 0) Modifier.focusRequester(firstResultFocusRequester)
                                else Modifier

                            SearchResultRow(
                                item = item,
                                onClick = { onItemSelected(item) },
                                modifier = rowModifier
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultRow(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        label = "searchRowScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
            }
            .clickable(onClick = onClick)
            .focusable()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.image,
            contentDescription = item.title,
            modifier = Modifier
                .height(120.dp)
                .width(80.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))

            // metadata line: year • episodes • type
            val metaParts = mutableListOf<String>()

            item.releaseYear
                ?.takeIf { it.isNotBlank() }
                ?.let { metaParts.add(it) }

            item.totalEpisodes?.let { count ->
                val label = if (count == 1) "1 episode" else "$count episodes"
                metaParts.add(label)
            }

            item.type
                ?.takeIf { it.isNotBlank() }
                ?.let { metaParts.add(it) }

            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                maxLines = 3
            )
        }
    }
}

@Composable
fun EpisodeRow(
    episode: AshiEpisode,
    expanded: Boolean,
    seriesImage: String,
    onSelectEpisode: () -> Unit,
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

        // Expanded view: thumbnail + Play button
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = seriesImage,   // fallback to series/title thumbnail
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

                    TvButton(
                        text = "Play Episode ${episode.number}",
                        onClick = onPlayClicked
                    )
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

    // Handle stored license check
    LaunchedEffect(licenseState) {
        if (licenseState == LicenseState.CheckingSaved) {
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
    LaunchedEffect(licenseState, updateState) {
        if (licenseState == LicenseState.Licensed && updateState == UpdateState.Checking) {
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

    when (licenseState) {
        LicenseState.Initial,
        LicenseState.CheckingSaved,
        LicenseState.CheckingNew -> {
            // Simple loading screen while dealing with license
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Checking license...",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        LicenseState.NeedsKey -> {
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
        }

        LicenseState.Licensed -> {
            when (updateState) {
                UpdateState.Checking -> {
                    // Show a small loading screen while checking updates
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Checking for updates...",
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                UpdateState.NeedUpdate -> {
                    UpdateScreen(
                        info = updateInfo!!,
                        onSkip = {
                            // User chose "Later" → continue into the app
                            updateState = UpdateState.UpToDate
                        },
                        onFinished = {
                            // After starting download & install, we can let them proceed
                            updateState = UpdateState.UpToDate
                        }
                    )
                }

                UpdateState.UpToDate -> {
                    AntPlayerTVApp()
                }
            }
        }
    }
}

@Composable
fun UpdateScreen(
    info: UpdateInfo,
    onSkip: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current

    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val primaryFocusRequester = remember { FocusRequester() }

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

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!isDownloading) {
                    TvButton(
                        text = "Download & Install",
                        onClick = {
                            isDownloading = true
                            errorMessage = null
                            UpdateInstaller.downloadAndInstall(
                                context = context,
                                url = info.apkUrl,
                                onStarted = {
                                    // You could keep them on this screen, but it's safe to proceed
                                    onFinished()
                                },
                                onError = { msg ->
                                    isDownloading = false
                                    errorMessage = msg
                                }
                            )
                        },
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    )
                } else {
                    Text(
                        text = "Downloading update...",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

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
