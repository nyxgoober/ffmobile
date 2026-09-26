package us.crafties.ffmobile.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import us.crafties.ffmobile.ffmpeg.MediaInfo
import us.crafties.ffmobile.ffmpeg.StreamInfo
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.components.FilePickerDialog
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

@Composable
fun ProbeScreen(vm: JobViewModel) {
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loading by remember { mutableStateOf(false) }

    if (showPicker) {
        FilePickerDialog(
            title = "Choose a file to inspect",
            filterMediaOnly = false,
            onDismiss = { showPicker = false },
            onFileSelected = { picked ->
                file = picked
                showPicker = false
                loading = true
                info = null
                scope.launch {
                    info = vm.probe(picked.absolutePath)
                    loading = false
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("Probe", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Inspect a file's format and streams with ffprobe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        InputFileCard(file, onPick = { showPicker = true })
        Spacer(Modifier.height(20.dp))

        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        AnimatedVisibility(visible = info != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            info?.let { MediaInfoView(it, file, vm, scope) }
        }
    }
}

@Composable
private fun MediaInfoView(info: MediaInfo, file: File?, vm: JobViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    Column {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Format", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                InfoRow("Container", info.formatName)
                InfoRow("Duration", formatDuration(info.durationSeconds))
                InfoRow("Size", formatBytes(info.sizeBytes))
                info.bitRate?.toLongOrNull()?.let { InfoRow("Bitrate", "${it / 1000} kb/s") }
            }
        }
        Spacer(Modifier.height(14.dp))
        info.streams.forEach { stream ->
            StreamCard(stream, file, vm, scope)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StreamCard(stream: StreamInfo, file: File?, vm: JobViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    var expanded by remember { mutableStateOf(true) }
    // attached_pic is a still image tucked inside an audio/video container (MP3
    // album art, FLAC cover, etc.) — ffprobe reports it as codec_type "video"
    // and it decodes to yuv420p/rgb like any frame, but it is not a playable
    // video track: no frame rate, nothing to seek through, and most encoders
    // will choke if it's fed into a real video pipeline. Label and preview it
    // separately rather than showing it as an ordinary video stream.
    val coverBitmap = remember(stream.index) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var coverLoading by remember(stream.index) { mutableStateOf(false) }
    var coverFailed by remember(stream.index) { mutableStateOf(false) }

    LaunchedEffect(stream.index, file, expanded) {
        if (stream.isAttachedPic && expanded && file != null &&
            coverBitmap.value == null && !coverLoading && !coverFailed
        ) {
            coverLoading = true
            val extracted = vm.extractCoverArt(file.absolutePath, stream.index)
            coverBitmap.value = extracted?.let { BitmapFactory.decodeFile(it.absolutePath) }
            coverFailed = extracted == null || coverBitmap.value == null
            coverLoading = false
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconForStream(stream), contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            if (stream.isAttachedPic) "Stream #${stream.index} · cover art · ${stream.codecName}"
                            else "Stream #${stream.index} · ${stream.codecType} · ${stream.codecName}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (stream.isAttachedPic) {
                            Text(
                                "Embedded image, not a playable video track",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 10.dp)) {
                    if (stream.isAttachedPic) {
                        Spacer(Modifier.height(4.dp))
                        when {
                            coverBitmap.value != null -> Image(
                                bitmap = coverBitmap.value!!.asImageBitmap(),
                                contentDescription = "Embedded cover art",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            coverLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Loading preview…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            coverFailed -> Text(
                                "Couldn't render a preview for this cover image.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (stream.width != null && stream.height != null) InfoRow("Resolution", "${stream.width}x${stream.height}")
                    if (!stream.isAttachedPic) stream.frameRate?.let { InfoRow("Frame rate", it) }
                    stream.sampleRate?.let { InfoRow("Sample rate", "$it Hz") }
                    stream.channels?.let { InfoRow("Channels", "$it") }
                    stream.bitRate?.toLongOrNull()?.let { InfoRow("Bitrate", "${it / 1000} kb/s") }
                }
            }
        }
    }
}

private fun iconForStream(stream: StreamInfo) = when {
    stream.isAttachedPic -> Icons.Filled.Image
    stream.codecType == "video" -> Icons.Filled.Movie
    stream.codecType == "audio" -> Icons.Filled.MusicNote
    stream.codecType == "subtitle" -> Icons.Filled.Subtitles
    else -> Icons.Filled.InsertDriveFile
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return "${DecimalFormat("#.##").format(value)} ${units[i]}"
}
