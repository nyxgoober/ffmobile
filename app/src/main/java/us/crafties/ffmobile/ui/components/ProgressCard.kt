package us.crafties.ffmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

@Composable
fun ProgressCard(
    running: Boolean,
    fraction: Float?,
    speed: Double,
    elapsedLabel: String,
    onCancel: () -> Unit,
) {
    if (!running) return
    val animatedFraction by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 300),
        label = "progress"
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Encoding…", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(12.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                )
                Spacer(Modifier.height(8.dp))
                Text("${(animatedFraction * 100).toInt()}%  •  ${String.format("%.1f", speed)}x  •  $elapsedLabel")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)))
                Spacer(Modifier.height(8.dp))
                Text("${String.format("%.1f", speed)}x  •  $elapsedLabel")
            }
        }
    }
}
