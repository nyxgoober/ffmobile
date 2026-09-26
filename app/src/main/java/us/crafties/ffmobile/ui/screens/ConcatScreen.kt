package us.crafties.ffmobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import us.crafties.ffmobile.ffmpeg.MediaInfo
import us.crafties.ffmobile.ui.JobOwner
import us.crafties.ffmobile.ui.JobUiState
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.components.LogConsole
import us.crafties.ffmobile.ui.components.ProgressCard
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ConcatScreen(vm: JobViewModel) {
    val owner = JobOwner.CONCAT
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state: JobUiState by vm.stateFlowFor(owner).collectAsState()
    val anyRunning by vm.anyRunning.collectAsState()
    val busyElsewhere = anyRunning && !state.running

    var showPicker by remember { mutableStateOf(false) }
    val files = remember { mutableStateListOf<File>() }
    var infos by remember { mutableStateOf<Map<String, MediaInfo?>>(emptyMap()) }
    var probingPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showLogs by remember { mutableStateOf(false) }

    fun probeFile(file: File) {
        val key = file.absolutePath
        probingPaths = probingPaths + key
        scope.launch {
            val info = vm.probe(key)
            infos = infos + (key to info)
            probingPaths = probingPaths - key
        }
    }

    if (showPicker) {
        us.crafties.ffmobile.ui.components.FilePickerDialog(
            title = "Add file to join",
            onDismiss = { showPicker = false },
            onFileSelected = { file ->
                showPicker = false
                if (files.none { it.absolutePath == file.absolutePath }) {
                    files.add(file)
                    probeFile(file)
                }
            }
        )
    }

    val probing = probingPaths.isNotEmpty()
    val totalDuration = files.sumOf { infos[it.absolutePath]?.durationSeconds ?: 0.0 }
        .takeIf { it > 0 }
    val compatibility = remember(files.toList(), infos, probing) {
        checkConcatCompatibility(files.toList(), infos)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Concat", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Join files end-to-end with stream copy (no re-encode). All files must use the same codecs.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { showPicker = true },
            enabled = !state.running && !busyElsewhere,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (files.isEmpty()) "Add files to join" else "Add another file")
        }
        Spacer(Modifier.height(12.dp))

        if (probing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "Detecting streams…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        files.forEachIndexed { index, file ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${file.name}", style = MaterialTheme.typography.titleMedium)
                        val info = infos[file.absolutePath]
                        when {
                            file.absolutePath in probingPaths -> Text(
                                "Probing…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            info != null -> Text(
                                concatFingerprintLabel(info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> Text(
                                "Probe failed — file will block concat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (index > 0) {
                        IconButton(
                            onClick = {
                                files.removeAt(index)
                                files.add(index - 1, file)
                            },
                            enabled = !state.running
                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
                    }
                    IconButton(
                        onClick = {
                            files.remove(file)
                            infos = infos - file.absolutePath
                        },
                        enabled = !state.running
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (files.size >= 2) {
            Spacer(Modifier.height(4.dp))
            if (compatibility.compatible) {
                AssistChip(
                    onClick = {},
                    label = { Text("Codecs match — ready to join") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            } else {
                AssistChip(
                    onClick = {},
                    label = { Text(compatibility.reason ?: "Files are not compatible") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            compatibility.firstExt?.let { ext ->
                Text(
                    "Output will be a .${ext} next to the first file (stream copy keeps original quality/bitrate).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (files.size < 2) return@Button
                val first = files.first()
                val ext = first.extension.ifBlank { "mp4" }
                val output = concatOutputFileFor(first, ext)
                val listFile = writeConcatListFile(context.cacheDir, files.toList())
                    ?: return@Button
                val args = buildConcatArgs(listFile, output)
                vm.clearLogs(owner)
                vm.runArgs(args, totalDuration, owner, outputPath = output.absolutePath)
            },
            enabled = compatibility.compatible && !anyRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Join files")
        }
        if (busyElsewhere) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Another tab is already converting — wait for it to finish.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
        ProgressCard(state.running, state.fraction, state.speed, "", onCancel = { vm.cancel() })

        if (state.lastExitCode != null) {
            Spacer(Modifier.height(12.dp))
            val resultFile = state.outputPath?.let(::File)?.takeIf { it.exists() }
            ResultBanner(
                success = state.lastExitCode == 0,
                outputFile = resultFile,
                onOpen = { openMediaFile(context, it) },
            )
        }
        state.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (state.logs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "Hide log" else "Show log")
            }
            AnimatedVisibility(visible = showLogs) {
                LogConsole(lines = state.logs, modifier = Modifier.fillMaxWidth().height(240.dp))
            }
        }
    }
}

private data class ConcatCompatibility(
    val compatible: Boolean,
    val reason: String?,
    val firstExt: String?,
)

/**
 * Concat uses the demuxer with `-c copy`, which only works when every input
 * carries the same codecs. Anything else fails (or silently produces a broken
 * file), so the Join button stays disabled until all fingerprints agree.
 */
private fun checkConcatCompatibility(
    files: List<File>,
    infos: Map<String, MediaInfo?>,
): ConcatCompatibility {
    if (files.size < 2) {
        return ConcatCompatibility(
            compatible = false,
            reason = "Add at least two files.",
            firstExt = files.firstOrNull()?.extension?.ifBlank { "mp4" },
        )
    }
    val firstExt = files.first().extension.ifBlank { "mp4" }
    val missing = files.firstOrNull { infos[it.absolutePath] == null }
    if (missing != null) {
        return ConcatCompatibility(
            compatible = false,
            reason = "Still probing (or probe failed) — wait for “${missing.name}”.",
            firstExt = firstExt,
        )
    }
    val fingerprints = files.map { concatFingerprint(infos[it.absolutePath]!!) }
    val first = fingerprints.first()
    val mismatchIndex = fingerprints.indexOfFirst { it != first }
    if (mismatchIndex != -1) {
        return ConcatCompatibility(
            compatible = false,
            reason = "“${files[mismatchIndex].name}” uses different codecs — concat needs identical codecs.",
            firstExt = firstExt,
        )
    }
    return ConcatCompatibility(compatible = true, reason = null, firstExt = firstExt)
}

/** Comparable codec identity: playable video + audio codec names (cover art excluded). */
private fun concatFingerprint(info: MediaInfo): String {
    val video = info.streams
        .filter { it.codecType == "video" && !it.isAttachedPic }
        .map { it.codecName }
        .sorted()
    val audio = info.streams
        .filter { it.codecType == "audio" }
        .map { it.codecName }
        .sorted()
    return "v=${video.joinToString(",")};a=${audio.joinToString(",")}"
}

private fun concatFingerprintLabel(info: MediaInfo): String {
    val video = info.streams
        .filter { it.codecType == "video" && !it.isAttachedPic }
        .map { it.codecName }
    val audio = info.streams
        .filter { it.codecType == "audio" }
        .map { it.codecName }
    val parts = buildList {
        if (video.isNotEmpty()) add("video: ${video.joinToString(", ")}")
        if (audio.isNotEmpty()) add("audio: ${audio.joinToString(", ")}")
        if (isEmpty()) add(info.formatName)
    }
    return parts.joinToString(" · ")
}

/** Writes the ffmpeg concat-demuxer list file. Returns null if it can't be written. */
private fun writeConcatListFile(cacheDir: File, files: List<File>): File? {
    return try {
        val listFile = File(cacheDir, "concat_${System.nanoTime()}.txt")
        listFile.bufferedWriter().use { out ->
            files.forEach { f ->
                // Concat demuxer escaping: wrap in single quotes, escape ' as '\''.
                val escaped = f.absolutePath.replace("'", "'\\''")
                out.write("file '$escaped'\n")
            }
        }
        listFile
    } catch (_: Exception) {
        null
    }
}

fun buildConcatArgs(listFile: File, output: File): List<String> =
    listOf("-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", output.absolutePath)

fun concatOutputFileFor(first: File, container: String): File {
    val base = first.nameWithoutExtension
    val dir = first.parentFile ?: first
    var candidate = File(dir, "${base}_concat.$container")
    var n = 1
    while (candidate.exists()) {
        candidate = File(dir, "${base}_concat_$n.$container")
        n++
    }
    return candidate
}
