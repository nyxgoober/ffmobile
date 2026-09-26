package us.crafties.ffmobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import us.crafties.ffmobile.ui.JobOwner
import us.crafties.ffmobile.ui.JobUiState
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.components.LogConsole
import us.crafties.ffmobile.ui.components.ProgressCard

@Composable
fun AdvancedScreen(vm: JobViewModel) {
    val owner = JobOwner.ADVANCED
    val state: JobUiState by vm.stateFlowFor(owner).collectAsState()
    val anyRunning by vm.anyRunning.collectAsState()
    var command by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Advanced", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Type arguments exactly as you would after \"ffmpeg\" on a command line. " +
                "This runs directly against the bundled binary with no validation — use with care.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("ffmpeg command") },
            placeholder = { Text("-i /storage/emulated/0/Movies/in.mp4 -c:v libx264 -crf 23 /storage/emulated/0/Movies/out.mp4") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().height(140.dp),
            minLines = 4,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                vm.clearLogs(owner)
                vm.runRawCommand(command, owner)
            },
            enabled = command.isNotBlank() && !anyRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Run")
        }

        Spacer(Modifier.height(16.dp))
        ProgressCard(state.running, state.fraction, state.speed, "", onCancel = { vm.cancel() })

        Spacer(Modifier.height(16.dp))
        Text("Output", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LogConsole(lines = state.logs, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
