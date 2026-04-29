package uk.anttheantster.antplayertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import uk.anttheantster.antplayertv.data.AntPlayerDatabase
import uk.anttheantster.antplayertv.data.watchlist.WatchlistEntity
import uk.anttheantster.antplayertv.data.watchlist.WatchlistRepository

/**
 * Toast hook (fade in/out popup).
 * Use from anywhere inside your app:
 *
 * val toast = LocalAntToast.current
 * toast("Added to Watch later")
 */
val LocalAntToast = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AntAppScaffold(
    current: NavigationState,
    watchlists: List<WatchlistEntity>,
    onNavigate: (NavigationState) -> Unit,
    /**
     * v2.0 — when false, Menu/Left no longer open the drawer, the drawer
     * cannot be pulled out, and any in-flight drawer is force-closed.
     * Used while the player is on screen so video playback isn't interrupted
     * (and DPAD events stay with the controller).
     */
    drawerEnabled: Boolean = true,
    content: @Composable (Modifier, openDrawer: () -> Unit) -> Unit
) {
    val drawerFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }

    var drawerVisible by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val db = remember { AntPlayerDatabase.getInstance(context) }
    val watchlistRepo = remember { WatchlistRepository(db.watchlistDao()) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WatchlistEntity?>(null) }

    // Hold-select options
    var optionsForWatchlist by remember { mutableStateOf<WatchlistEntity?>(null) }
    var pendingRename by remember { mutableStateOf<WatchlistEntity?>(null) }

    // Toast state
    var toastText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val showToast: (String) -> Unit = remember {
        { msg ->
            scope.launch {
                toastText = msg
                delay(1400)
                toastText = null
            }
        }
    }

    fun openDrawer() { if (drawerEnabled) drawerVisible = true }
    fun closeDrawer() { drawerVisible = false }

    // If the screen we're on disables the drawer (e.g. switched to the
    // player while the drawer was open), make sure the drawer is closed.
    LaunchedEffect(drawerEnabled) {
        if (!drawerEnabled && drawerVisible) drawerVisible = false
    }

    // Back closes drawer first
    BackHandler(enabled = drawerVisible) { closeDrawer() }

    // When opening drawer, focus goes into it; when closing, focus goes back to content
    LaunchedEffect(drawerVisible) {
        yield()
        if (drawerVisible) drawerFocus.requestFocus() else contentFocus.requestFocus()
    }

    CompositionLocalProvider(LocalAntToast provides showToast) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    when (e.key) {
                        Key.Menu -> {
                            if (!drawerEnabled) {
                                // Player route — let the host screen decide.
                                false
                            } else {
                                if (!drawerVisible) openDrawer() else closeDrawer()
                                true
                            }
                        }

                        // DPAD LEFT — strictly focus traversal now.
                        //
                        // v2.0 update: the Menu button is the only way to
                        // open the drawer. We still intercept Left to
                        // handle our two specific cases:
                        //   1. While the drawer is open, eat Left so focus
                        //      can't escape into hidden underlying content.
                        //   2. While playing video, pass Left straight
                        //      through to ExoPlayer's controls.
                        // Otherwise we let the framework's default focus
                        // traversal run as-is (return false).
                        Key.DirectionLeft -> {
                            when {
                                !drawerEnabled -> false       // pass through to player
                                drawerVisible -> true         // trap focus inside drawer
                                else -> false                  // normal focus traversal
                            }
                        }

                        // DPAD RIGHT — when the drawer is open, treat it as
                        // "close the drawer" rather than letting focus leak
                        // into the underlying screen.
                        Key.DirectionRight -> {
                            if (drawerVisible) {
                                closeDrawer()
                                true
                            } else false
                        }

                        // Back closes drawer if open
                        Key.Back -> {
                            if (drawerVisible) {
                                closeDrawer()
                                true
                            } else false
                        }

                        else -> false
                    }
                }
        ) {
            // Content never moves. While the drawer is visible we cancel
            // any focus traversal that would try to ENTER the content
            // subtree, so DPAD-Down from a drawer item can't grab a
            // geometrically-nearby card behind the overlay.
            content(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusProperties {
                        canFocus = !drawerVisible
                        onEnter = {
                            if (drawerVisible) FocusRequester.Cancel
                            else FocusRequester.Default
                        }
                    },
                ::openDrawer
            )

            // Scrim
            AnimatedVisibility(
                visible = drawerVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                        .clickable { closeDrawer() }
                )
            }

            // Drawer (overlay)
            AnimatedVisibility(
                visible = drawerVisible,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .padding(10.dp)
                        .focusRequester(drawerFocus),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp
                ) {
                    DrawerContent(
                        current = current,
                        watchlists = watchlists,
                        onNavigate = { dest ->
                            onNavigate(dest)
                            scope.launch { yield(); closeDrawer() }
                        },
                        onCreateWatchlist = { showCreateDialog = true },
                        onHoldWatchlist = { wl -> optionsForWatchlist = wl }
                    )
                }
            }

            // Toast popup (fade in/out)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = toastText != null,
                    enter = fadeIn(animationSpec = tween(160)),
                    exit = fadeOut(animationSpec = tween(220))
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 3.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        modifier = Modifier.padding(bottom = 28.dp)
                    ) {
                        Text(
                            text = toastText.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // Create watchlist dialog
        if (showCreateDialog) {
            CreateWatchlistDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name ->
                    scope.launch {
                        val id = watchlistRepo.createWatchlist(name)
                        if (id != null) {
                            showToast("Created \"$name\"")
                        } else {
                            showToast("Name already exists")
                        }
                        showCreateDialog = false
                    }
                }
            )
        }

        // Options dialog (hold-select on watchlist)
        optionsForWatchlist?.let { wl ->
            WatchlistOptionsDialog(
                name = wl.name,
                canEdit = !wl.name.equals(WatchlistRepository.DEFAULT_WATCH_LATER_NAME, ignoreCase = true),
                onDismiss = { optionsForWatchlist = null },
                onRename = {
                    optionsForWatchlist = null
                    pendingRename = wl
                },
                onDelete = {
                    optionsForWatchlist = null
                    pendingDelete = wl
                }
            )
        }

        // Rename dialog
        pendingRename?.let { wl ->
            RenameWatchlistDialog(
                initialName = wl.name,
                onDismiss = { pendingRename = null },
                onRename = { newName ->
                    scope.launch {
                        val ok = watchlistRepo.renameWatchlist(wl.id, newName)
                        if (ok) showToast("Renamed to \"${newName.trim()}\"")
                        else showToast("Name already exists")
                        pendingRename = null
                    }
                }
            )
        }

        // Confirm delete dialog
        pendingDelete?.let { wl ->
            ConfirmDeleteWatchlistDialog(
                name = wl.name,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    scope.launch {
                        watchlistRepo.deleteWatchlist(wl.id)
                        showToast("Deleted \"${wl.name}\"")
                        pendingDelete = null
                    }
                }
            )
        }
    }
}

@Composable
private fun DrawerContent(
    current: NavigationState,
    watchlists: List<WatchlistEntity>,
    onNavigate: (NavigationState) -> Unit,
    onCreateWatchlist: () -> Unit,
    onHoldWatchlist: (WatchlistEntity) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Navigation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // One unified list so focus trapping includes "+ New watchlist"
        data class Entry(
            val title: String,
            val icon: androidx.compose.ui.graphics.vector.ImageVector,
            val dest: NavigationState? = null,          // null means "create"
            val watchlist: WatchlistEntity? = null      // non-null only for real watchlists
        )

        val entries = buildList {
            add(Entry("Hub", Icons.Filled.Apps, NavigationState.Hub))
            add(Entry("Home", Icons.Filled.Home, NavigationState.Home))
            add(Entry("Browse", Icons.Filled.Search, NavigationState.Browse))
            add(Entry("Live TV", Icons.Filled.LiveTv, NavigationState.LiveTV))
            add(Entry("Settings", Icons.Filled.Settings, NavigationState.Settings))

            watchlists.forEach { wl ->
                add(
                    Entry(
                        title = wl.name,
                        icon = Icons.Filled.Home,
                        dest = NavigationState.Watchlist(wl.id, wl.name),
                        watchlist = wl
                    )
                )
            }

            add(Entry("+ New watchlist", Icons.Filled.Add, dest = null, watchlist = null))
        }

        entries.forEachIndexed { index, entry ->
            val blockUp = index == 0
            val blockDown = index == entries.lastIndex

            val isSelected = when (val dest = entry.dest) {
                is NavigationState.Hub -> current is NavigationState.Hub
                is NavigationState.Home -> current is NavigationState.Home
                is NavigationState.Browse -> current is NavigationState.Browse
                is NavigationState.LiveTV -> current is NavigationState.LiveTV
                is NavigationState.Search -> current is NavigationState.Search || current is NavigationState.Browse
                is NavigationState.Settings -> current is NavigationState.Settings
                is NavigationState.Watchlist -> current is NavigationState.Watchlist && current.id == dest.id
                null -> false
                else -> false
            }

            val onClick = {
                if (entry.dest == null) {
                    onCreateWatchlist()
                } else {
                    onNavigate(entry.dest)
                }
            }

            val onHold = entry.watchlist?.let { wl ->
                { onHoldWatchlist(wl) }
            }

            DrawerItem(
                title = entry.title,
                icon = entry.icon,
                selected = isSelected,
                onClick = onClick,
                onHoldSelect = onHold,
                blockUp = blockUp,
                blockDown = blockDown
            )
        }
    }
}

@Composable
private fun DrawerItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onHoldSelect: (() -> Unit)? = null,
    blockUp: Boolean = false,
    blockDown: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    val scope = rememberCoroutineScope()
    var longPressFired by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        focused -> MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    }
    var swallowNextKeyUp by remember { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = containerColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 6.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false

                when (e.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (onHoldSelect == null) {
                            if (e.type == KeyEventType.KeyDown) onClick()
                            true
                        } else {
                            when (e.type) {
                                KeyEventType.KeyDown -> {
                                    if (e.nativeKeyEvent.repeatCount == 0) {
                                        longPressFired = false
                                        swallowNextKeyUp = false
                                        longPressJob?.cancel()
                                        longPressJob = scope.launch {
                                            delay(550)
                                            longPressFired = true
                                            swallowNextKeyUp = true
                                            onHoldSelect()
                                        }
                                    }
                                    true
                                }
                                KeyEventType.KeyUp -> {
                                    longPressJob?.cancel()
                                    longPressJob = null

                                    if (longPressFired || swallowNextKeyUp) {
                                        swallowNextKeyUp = false
                                        longPressFired = false
                                        true
                                    } else {
                                        onClick()
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                    }
                    else -> false
                }
            }

            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(icon, contentDescription = title)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CreateWatchlistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create watchlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate(name) })
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun WatchlistOptionsDialog(
    name: String,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { Text("Watchlist options") },
        confirmButton = {
            if (canEdit) {
                TextButton(onClick = onRename) { Text("Rename") }
            }
        },
        dismissButton = {
            Row {
                if (canEdit) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun RenameWatchlistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename watchlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) })
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfirmDeleteWatchlistDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete watchlist?") },
        text = { Text("Delete \"$name\" and remove all items inside it?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}