package us.crafties.ffmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * Drop-in replacement for Material3's Switch. The stock Switch already
 * animates its own thumb slide, but that read as too subtle on toggle --
 * this adds a brief spring-driven overshoot pop on the whole control on
 * every state change so flipping a setting feels deliberate. Used anywhere
 * a toggle appears (Settings, Convert's bitrate-mode switch, etc.) instead
 * of the plain Switch so toggle feel stays consistent app-wide.
 */
@Composable
fun AnimatedSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var pop by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(checked) {
        pop = 1.15f
    }
    val animatedPop by animateFloatAsState(
        targetValue = pop,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f),
        label = "switch_pop",
        finishedListener = { pop = 1f }
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.scale(animatedPop)
    )
}
