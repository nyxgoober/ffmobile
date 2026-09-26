package us.crafties.ffmobile.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import us.crafties.ffmobile.FFMobileApp
import us.crafties.ffmobile.ffmpeg.FfmpegBinaries
import us.crafties.ffmobile.permissions.StoragePermissionHelper
import us.crafties.ffmobile.ui.components.AnimatedSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as FFMobileApp
    val scope = rememberCoroutineScope()

    val advancedEnabled by app.preferences.advancedModeEnabled.collectAsState(initial = false)
    val dynamicColor by app.preferences.dynamicColorEnabled.collectAsState(initial = true)
    var hasAccess by remember { mutableStateOf(StoragePermissionHelper.hasAllFilesAccess(context)) }
    var buildInfo by remember { mutableStateOf<String?>(null) }
    var loadingInfo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        SectionCard(title = "Storage access") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (hasAccess) "All files access granted" else "Not granted")
                    Text(
                        "Uses full filesystem access instead of the document picker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!hasAccess) {
                    Button(onClick = {
                        (context as? Activity)?.let { StoragePermissionHelper.requestAllFilesAccess(it) }
                    }) { Text("Grant") }
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "Advanced mode") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Raw command console")
                    Text(
                        "Adds an Advanced tab where you can type ffmpeg arguments directly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedSwitch(checked = advancedEnabled, onCheckedChange = { scope.launch { app.preferences.setAdvancedMode(it) } })
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "Appearance") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Use dynamic color (Material You)")
                AnimatedSwitch(checked = dynamicColor, onCheckedChange = { scope.launch { app.preferences.setDynamicColor(it) } })
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "FFmpeg build") {
            Column {
                Text("Bundled arm64-v8a build (Termux-derived).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        loadingInfo = true
                        scope.launch {
                            buildInfo = try {
                                withContext(Dispatchers.IO) {
                                    val layout = FfmpegBinaries.ensureInstalled(context)
                                    val pb = ProcessBuilder(layout.ffmpegPath, "-version")
                                    pb.environment()["LD_LIBRARY_PATH"] = layout.libDir
                                    pb.redirectErrorStream(true)
                                    val p = pb.start()
                                    val text = p.inputStream.bufferedReader().readText()
                                    p.waitFor()
                                    text.lineSequence().take(3).joinToString("\n")
                                }
                            } catch (e: Exception) {
                                "Failed to run ffmpeg: ${e.message}"
                            }
                            loadingInfo = false
                        }
                    },
                    enabled = !loadingInfo
                ) { Text(if (loadingInfo) "Checking…" else "Show ffmpeg -version") }

                buildInfo?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "ffmobile",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
