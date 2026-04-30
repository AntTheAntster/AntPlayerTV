package uk.anttheantster.antplayertv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable "TV remote focus highlight" decoration: tracks its own focus
 * state, animates a small scale-up while focused, and stamps a border in
 * the theme's primary colour.
 *
 * Drop this onto any focusable composable that needs the standard AntPlayer
 * focus look-and-feel — for example a card, a chip, or a button — instead
 * of duplicating `var focused by remember { … }` + `animateFloatAsState` +
 * `border(if (focused) …)` boilerplate at every call site.
 *
 * Existing v1.x composables (`TvButton`, `MediaCard`, `HubCard`, etc.)
 * still inline the equivalent logic for now; new screens should reach for
 * this helper first.
 *
 * Pair with `.focusable()` and `.clickable(onClick)` (or equivalent
 * `onPreviewKeyEvent` handling) to make the element actually receive
 * input — this modifier intentionally only handles the *visuals*.
 *
 * Example:
 * ```
 * Surface(
 *     modifier = Modifier
 *         .tvFocusHighlight(shape = RoundedCornerShape(20.dp))
 *         .clickable(onClick = onSelect)
 *         .focusable()
 * ) { ... }
 * ```
 */
@Composable
fun Modifier.tvFocusHighlight(
    focusedScale: Float = 1.05f,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedBorderWidth: Dp = 3.dp,
    focusedBorderColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        label = "tvFocusScale"
    )
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = if (focused) focusedBorderWidth else 0.dp,
            color = if (focused) focusedBorderColor else Color.Transparent,
            shape = shape
        )
}

/**
 * Convenience wrapper that combines [tvFocusHighlight] with `.focusable()`
 * for the common "card-becomes-focusable-and-glows" case.
 */
@Composable
fun Modifier.tvFocusableHighlight(
    focusedScale: Float = 1.05f,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedBorderWidth: Dp = 3.dp,
    focusedBorderColor: Color = MaterialTheme.colorScheme.primary,
): Modifier = this
    .tvFocusHighlight(focusedScale, shape, focusedBorderWidth, focusedBorderColor)
    .focusable()
