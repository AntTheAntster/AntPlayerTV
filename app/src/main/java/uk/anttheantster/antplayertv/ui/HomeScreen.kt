package uk.anttheantster.antplayertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.anttheantster.antplayertv.MediaCard
import uk.anttheantster.antplayertv.data.AntPlayerDatabase
import uk.anttheantster.antplayertv.data.ProgressRepository
import uk.anttheantster.antplayertv.model.MediaItem

/**
 * v2.0 Home — populated with the user's recent activity:
 *  • Continue Watching: titles you've started but not finished.
 *  • History: every title you've watched, most-recent first.
 *
 * Both lists are sourced from [ProgressRepository] which already groups
 * per-episode rows up to a single "series" entry per title, so the rows
 * stay clean even after binge-watching.
 *
 * The data is reloaded each time the screen is mounted (Hub → Home),
 * so a watch session immediately reflects on the next return visit.
 *
 * Owns its own [BackHandler] (single Back press → onBack) so the call
 * site doesn't have to register a separate one.
 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onItemSelected: (MediaItem) -> Unit,
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val repo = remember {
        ProgressRepository(AntPlayerDatabase.getInstance(context).progressDao())
    }

    var continueWatching by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Off the main thread — these query the Room DB.
        val cw = withContext(Dispatchers.IO) { repo.getContinueWatching() }
        val hi = withContext(Dispatchers.IO) { repo.getHistory() }
        continueWatching = cw
        history = hi
        loaded = true
    }

    AntScreenBackground {
        // Wrap in verticalScroll so when both rows are populated and don't
        // fit on a single TV screen, the user can scroll down to History.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            HomeHeader()

            Spacer(Modifier.height(24.dp))

            when {
                !loaded -> { /* initial flash — empty body for ~one frame */ }
                continueWatching.isEmpty() && history.isEmpty() -> {
                    HomeEmptyState()
                }
                else -> HomeContent(
                    continueWatching = continueWatching,
                    history = history,
                    onItemSelected = onItemSelected
                )
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Column {
        Text(
            text = "Home",
            color = AntColors.TextPrimary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Pick up where you left off.",
            color = AntColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun HomeContent(
    continueWatching: List<MediaItem>,
    history: List<MediaItem>,
    onItemSelected: (MediaItem) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(continueWatching.firstOrNull()?.id, history.firstOrNull()?.id) {
        try { firstFocus.requestFocus() } catch (_: Throwable) {}
    }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (continueWatching.isNotEmpty()) {
            HomeRow(
                title = "Continue Watching",
                items = continueWatching,
                firstFocusRequester = firstFocus,
                onItemSelected = onItemSelected,
            )
        }
        if (history.isNotEmpty()) {
            HomeRow(
                title = "History",
                items = history,
                firstFocusRequester = if (continueWatching.isEmpty()) firstFocus else null,
                onItemSelected = onItemSelected,
            )
        }
    }
}

@Composable
private fun HomeRow(
    title: String,
    items: List<MediaItem>,
    firstFocusRequester: FocusRequester?,
    onItemSelected: (MediaItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = AntColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items, key = { it.id }) { item ->
                val rowMod =
                    if (firstFocusRequester != null && item == items.first())
                        Modifier.focusRequester(firstFocusRequester)
                    else
                        Modifier
                Box(modifier = rowMod) {
                    MediaCard(item = item, onClick = { onItemSelected(item) })
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyState() {
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { rootFocus.requestFocus() } catch (_: Throwable) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = null,
                tint = AntColors.AccentSoft,
                modifier = Modifier.height(56.dp)
            )
            Text(
                "Nothing to resume yet",
                color = AntColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Watch something from Browse and your progress will show up here.",
                color = AntColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
