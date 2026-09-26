package us.crafties.ffmobile.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import us.crafties.ffmobile.ffmpeg.MediaInfo
import us.crafties.ffmobile.ui.JobOwner
import us.crafties.ffmobile.ui.JobUiState
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.components.LogConsole
import us.crafties.ffmobile.ui.components.ProgressCard
import kotlinx.coroutines.launch
import java.io.File

private val AUDIO_ONLY_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "tta", "wv")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic")

private enum class FilterKind(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isAudio: Boolean,
) {
    SCALE("Resize", Icons.Filled.PhotoSizeSelectLarge, false),
    ROTATE("Rotate", Icons.Filled.RotateRight, false),
    CROP("Crop", Icons.Filled.Crop, false),
    SPEED("Speed", Icons.Filled.Speed, false),
    BRIGHTNESS("Brightness / Contrast", Icons.Filled.Brightness6, false),
    GRAYSCALE("Grayscale", Icons.Filled.FilterBAndW, false),
    DENOISE("Denoise", Icons.Filled.BlurOn, false),
    SHARPEN("Sharpen", Icons.Filled.AutoAwesome, false),
    WATERMARK("Text watermark", Icons.Filled.TextFields, false),
    VIGNETTE("Vignette", Icons.Filled.Vignette, false),
    VOLUME("Volume", Icons.Filled.VolumeUp, true),
    FADE("Fade in/out", Icons.Filled.Timelapse, true),
    BASS("Bass boost", Icons.Filled.GraphicEq, true),
    NORMALIZE("Normalize loudness", Icons.Filled.Equalizer, true),
    ECHO("Echo", Icons.Filled.SurroundSound, true),
    TEMPO("Tempo", Icons.Filled.Audiotrack, true),
}

@Composable
fun FiltersScreen(vm: JobViewModel) {
    val owner = JobOwner.FILTERS
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state: JobUiState by vm.stateFlowFor(owner).collectAsState()
    val anyRunning by vm.anyRunning.collectAsState()
    val busyElsewhere = anyRunning && !state.running

    var showPicker by remember { mutableStateOf(false) }
    var inputFile by remember { mutableStateOf<File?>(null) }
    var mediaInfo by remember { mutableStateOf<MediaInfo?>(null) }
    var probing by remember { mutableStateOf(false) }
    val durationSeconds = mediaInfo?.durationSeconds?.takeIf { it > 0 }
    var kind by remember { mutableStateOf(FilterKind.SCALE) }
    var showLogs by remember { mutableStateOf(false) }

    // per-filter parameters (video)
    var scalePct by remember { mutableStateOf(50f) }
    var rotateDeg by remember { mutableStateOf(90f) }
    var cropPct by remember { mutableStateOf(20f) }
    var speedFactor by remember { mutableStateOf(1.5f) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    var denoiseStrength by remember { mutableStateOf(4f) }
    var sharpenAmount by remember { mutableStateOf(1f) }
    var watermarkText by remember { mutableStateOf("ffmobile") }
    var vignetteAngle by remember { mutableStateOf(1.2f) }
    // per-filter parameters (audio)
    var volumePct by remember { mutableStateOf(100f) }
    var fadeSeconds by remember { mutableStateOf(2f) }
    var bassGainDb by remember { mutableStateOf(8f) }
    var echoMix by remember { mutableStateOf(0.3f) }
    var tempoFactor by remember { mutableStateOf(1.25f) }

    // ---- auto-detected input type: only show filters that apply ----
    // Cover art (attached_pic) is NOT a playable video track: it must neither
    // enable video filters nor be fed to an audio muxer (both fail in ffmpeg).
    val ext = inputFile?.extension?.lowercase().orEmpty()
    // Stills count as "video" for filtering (scale/crop/etc. all work on images).
    val hasVideo = mediaInfo?.streams?.any { it.codecType == "video" && !it.isAttachedPic }
        ?: inputFile?.let { ext !in AUDIO_ONLY_EXTENSIONS }
        ?: true
    val hasAudio = mediaInfo?.streams?.any { it.codecType == "audio" }
        ?: inputFile?.let { ext !in IMAGE_EXTENSIONS && ext != "gif" }
        ?: true
    val hasCoverArt = mediaInfo?.streams?.any { it.isAttachedPic } ?: false
    val mainVideoIndex = mediaInfo?.streams
        ?.firstOrNull { it.codecType == "video" && !it.isAttachedPic }?.index
    val visibleKinds = FilterKind.entries.filter {
        when {
            it.isAudio -> hasAudio
            else -> hasVideo
        }
    }
    // Keep selection valid when the input type changes.
    LaunchedEffect(inputFile, hasVideo, hasAudio) {
        if (visibleKinds.isNotEmpty() && kind !in visibleKinds) {
            kind = if (hasVideo) FilterKind.SCALE else FilterKind.VOLUME
        }
    }

    if (showPicker) {
        us.crafties.ffmobile.ui.components.FilePickerDialog(
            title = "Choose media to filter",
            onDismiss = { showPicker = false },
            onFileSelected = { file ->
                inputFile = file
                mediaInfo = null
                showPicker = false
                probing = true
                scope.launch {
                    mediaInfo = vm.probe(file.absolutePath)
                    probing = false
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Filters", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Apply a single-effect FFmpeg filter chain.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        InputFileCard(inputFile, onPick = { showPicker = true })
        Spacer(Modifier.height(20.dp))

        if (probing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Detecting streams…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }

        mediaInfo?.let { info ->
            val kinds = buildList {
                if (info.streams.any { it.codecType == "video" && !it.isAttachedPic }) add("video")
                if (info.streams.any { it.codecType == "audio" }) add("audio")
                if (info.streams.any { it.isAttachedPic }) add("cover art")
            }.ifEmpty { listOf(info.formatName) }
            AssistChip(
                onClick = {},
                label = { Text(kinds.joinToString(" + ") + " filters available") },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.horizontalScroll(rememberScrollState())) {
            visibleKinds.forEach { f ->
                FilterChip(
                    selected = kind == f,
                    onClick = { kind = f },
                    label = { Text(f.label) },
                    leadingIcon = { Icon(f.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        AnimatedContent(targetState = kind, label = "filterParams", transitionSpec = { fadeIn() togetherWith fadeOut() }) { selected ->
            Column {
                when (selected) {
                    FilterKind.SCALE -> LabeledSlider("Scale to ${scalePct.toInt()}% of source size", scalePct, 10f..150f) { scalePct = it }
                    FilterKind.ROTATE -> LabeledDropdown("Rotation", listOf("90° CW", "90° CCW", "180°"), rotateOptionLabel(rotateDeg)) {
                        rotateDeg = rotateOptionValue(it)
                    }
                    FilterKind.CROP -> LabeledSlider("Crop ${cropPct.toInt()}% off each edge", cropPct, 0f..40f) { cropPct = it }
                    FilterKind.SPEED -> LabeledSlider("Playback speed ×${"%.2f".format(speedFactor)}", speedFactor, 0.25f..4f) { speedFactor = it }
                    FilterKind.BRIGHTNESS -> Column {
                        LabeledSlider("Brightness ${"%.2f".format(brightness)}", brightness, -1f..1f) { brightness = it }
                        Spacer(Modifier.height(10.dp))
                        LabeledSlider("Contrast ${"%.2f".format(contrast)}", contrast, 0f..3f) { contrast = it }
                    }
                    FilterKind.GRAYSCALE -> Text("Converts the video to black & white.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterKind.DENOISE -> LabeledSlider("Denoise strength ${denoiseStrength.toInt()}", denoiseStrength, 1f..12f) { denoiseStrength = it }
                    FilterKind.SHARPEN -> LabeledSlider("Sharpen amount ${"%.2f".format(sharpenAmount)}", sharpenAmount, 0f..3f) { sharpenAmount = it }
                    FilterKind.WATERMARK -> OutlinedTextField(
                        value = watermarkText, onValueChange = { watermarkText = it },
                        label = { Text("Watermark text") }, modifier = Modifier.fillMaxWidth()
                    )
                    FilterKind.VIGNETTE -> LabeledSlider("Vignette strength ${"%.2f".format(vignetteAngle)}", vignetteAngle, 0.3f..2f) { vignetteAngle = it }
                    FilterKind.VOLUME -> LabeledSlider("Volume ${volumePct.toInt()}%", volumePct, 0f..200f) { volumePct = it }
                    FilterKind.FADE -> {
                        LabeledSlider("Fade length ${"%.1f".format(fadeSeconds)}s (in + out)", fadeSeconds, 0.5f..5f) { fadeSeconds = it }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Fades in from silence and fades out to silence.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilterKind.BASS -> LabeledSlider("Bass gain +${"%.0f".format(bassGainDb)} dB", bassGainDb, 0f..20f) { bassGainDb = it }
                    FilterKind.NORMALIZE -> Text(
                        "EBU R128 loudness normalization (single pass). No parameters.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilterKind.ECHO -> LabeledSlider("Echo mix ${"%.2f".format(echoMix)}", echoMix, 0f..0.9f) { echoMix = it }
                    FilterKind.TEMPO -> LabeledSlider("Tempo ×${"%.2f".format(tempoFactor)} (pitch preserved)", tempoFactor, 0.5f..2f) { tempoFactor = it }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val filterExpr = remember(kind, scalePct, rotateDeg, cropPct, speedFactor, brightness, contrast, denoiseStrength, sharpenAmount, watermarkText, vignetteAngle, volumePct, fadeSeconds, bassGainDb, echoMix, tempoFactor) {
            buildFilterExpression(kind, scalePct, rotateDeg, cropPct, speedFactor, brightness, contrast, denoiseStrength, sharpenAmount, watermarkText, vignetteAngle, volumePct, fadeSeconds, bassGainDb, echoMix, tempoFactor)
        }
        Text("-vf ${filterExpr.videoFilter ?: "(none)"}${filterExpr.audioFilter?.let { "   -af $it" } ?: ""}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Re-encoded streams keep the probed source bitrate; show what that resolves to.
        val srcVideoKbpsNote = mediaInfo?.sourceVideoBitrateKbps()
        val srcAudioKbpsNote = mediaInfo?.sourceAudioBitrateKbps()
        if (inputFile != null && (filterExpr.videoFilter != null && hasVideo || filterExpr.audioFilter != null && hasAudio)) {
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Output keeps source bitrate")
                    if (filterExpr.videoFilter != null && hasVideo) {
                        append(": video ${srcVideoKbpsNote?.let { "~${it} kb/s" } ?: "default"}")
                    }
                    if (filterExpr.audioFilter != null && hasAudio && !filterExpr.copyAudio) {
                        if (filterExpr.videoFilter != null && hasVideo) append(", ")
                        else append(": ")
                        append("audio ${srcAudioKbpsNote?.let { "~${it} kb/s" } ?: "default"}")
                    }
                } + ".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val input = inputFile ?: return@Button
                val output = outputFileFor(input, input.extension.ifBlank { "mp4" })
                val srcVideoKbps = mediaInfo?.sourceVideoBitrateKbps()
                val srcAudioKbps = mediaInfo?.sourceAudioBitrateKbps()
                val swArgs = buildFilterArgs(
                    input, output, filterExpr,
                    hasVideo = hasVideo, hasAudio = hasAudio,
                    cover = CoverInfo(hasCoverArt, mainVideoIndex),
                    sourceVideoBitrateKbps = srcVideoKbps,
                    sourceAudioBitrateKbps = srcAudioKbps,
                )
                // Only differs from swArgs when this filter actually re-encodes video (see
                // buildFilterArgs) — otherwise it's identical, so passing it costs nothing.
                val hwArgs = buildFilterArgs(
                    input, output, filterExpr,
                    hasVideo = hasVideo, hasAudio = hasAudio,
                    cover = CoverInfo(hasCoverArt, mainVideoIndex),
                    videoEncoder = "h264_mediacodec",
                    sourceVideoBitrateKbps = srcVideoKbps,
                    sourceAudioBitrateKbps = srcAudioKbps,
                )
                vm.clearLogs(owner)
                vm.runConvertWithHardwarePreference(
                    hwArgs, swArgs, durationSeconds, owner,
                    outputPath = output.absolutePath,
                )
            },
            enabled = inputFile != null && !anyRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Apply filter")
        }
        if (hasCoverArt && inputFile != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                coverArtNote(ext, hasVideo, filterExpr.videoFilter != null),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide log" else "Show log") }
            AnimatedVisibility(visible = showLogs) {
                LogConsole(lines = state.logs, modifier = Modifier.fillMaxWidth().height(240.dp))
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun rotateOptionLabel(deg: Float) = when (deg.toInt()) {
    90 -> "90° CW"
    -90 -> "90° CCW"
    else -> "180°"
}
private fun rotateOptionValue(label: String) = when (label) {
    "90° CW" -> 90f
    "90° CCW" -> -90f
    else -> 180f
}

private data class FilterExpr(
    val videoFilter: String?,
    val audioFilter: String?,
    val copyVideo: Boolean,
    val copyAudio: Boolean,
)

/** Embedded cover art + where it can survive stream-copy (verified on-device). */
private data class CoverInfo(val hasCoverArt: Boolean, val mainVideoIndex: Int?)
private val COVER_PRESERVABLE_EXTS = setOf("mp3", "m4a", "flac")

/** Audio encoder picked to match the output container (filters keep the input extension). */
private val FILTER_AUDIO_ENCODER_FOR_EXT = mapOf(
    "mp3" to "libmp3lame",
    "ogg" to "libvorbis",
    "opus" to "libopus",
    "wav" to "pcm_s16le",
    "flac" to "flac",
    "tta" to "tta",
    "wv" to "wavpack",
    "wma" to "wmav2",
)
private val FILTER_LOSSY_ENCODERS = setOf("aac", "libmp3lame", "libopus", "libvorbis", "wmav2")

/** Max bitrate (kb/s) each lossy filter audio encoder supports. Mirrors Convert's ceilings. */
private fun maxBitrateForFilterAudioEncoder(encoder: String): Int = when (encoder) {
    "libopus" -> 512
    "libvorbis" -> 500
    "wmav2" -> 384
    else -> 320 // aac, libmp3lame
}

/**
 * Source bitrates probed from [MediaInfo], so re-encoded filter output keeps the
 * input's bitrate instead of a hardcoded override. Stream-level bit_rate is
 * preferred; the container-level bit_rate is only used when the file has a single
 * stream kind (otherwise it mixes video+audio together and would mistarget one).
 */
private fun MediaInfo.sourceVideoBitrateKbps(): Int? {
    streams.firstOrNull { it.codecType == "video" && !it.isAttachedPic }
        ?.bitRate?.toLongOrNull()
        ?.let { (it / 1000).toInt().takeIf { kbps -> kbps > 0 } }
        ?.let { return it }
    if (streams.none { it.codecType == "audio" }) {
        bitRate?.toLongOrNull()?.let { return (it / 1000).toInt().takeIf { kbps -> kbps > 0 } }
    }
    return null
}

private fun MediaInfo.sourceAudioBitrateKbps(): Int? {
    streams.firstOrNull { it.codecType == "audio" }
        ?.bitRate?.toLongOrNull()
        ?.let { (it / 1000).toInt().takeIf { kbps -> kbps > 0 } }
        ?.let { return it }
    if (streams.none { it.codecType == "video" && !it.isAttachedPic }) {
        bitRate?.toLongOrNull()?.let { return (it / 1000).toInt().takeIf { kbps -> kbps > 0 } }
    }
    return null
}

private fun coverArtNote(outExt: String, hasVideo: Boolean, videoFilterSelected: Boolean): String =
    when {
        !hasVideo && outExt in COVER_PRESERVABLE_EXTS ->
            "The embedded cover art will be kept in the output file."
        !hasVideo ->
            "Note: .$outExt can't hold cover art — it will be dropped from the output."
        videoFilterSelected ->
            "Note: the embedded cover art will be dropped from the re-encoded video."
        else -> "The embedded cover art will be kept."
    }

private fun buildFilterExpression(
    kind: FilterKind, scalePct: Float, rotateDeg: Float, cropPct: Float, speedFactor: Float,
    brightness: Float, contrast: Float, denoiseStrength: Float, sharpenAmount: Float,
    watermarkText: String, vignetteAngle: Float,
    volumePct: Float, fadeSeconds: Float, bassGainDb: Float, echoMix: Float, tempoFactor: Float,
): FilterExpr = when (kind) {
    FilterKind.SCALE -> {
        val f = scalePct / 100f
        FilterExpr("scale=iw*$f:ih*$f", null, copyVideo = false, copyAudio = true)
    }
    FilterKind.ROTATE -> FilterExpr(
        when (rotateDeg.toInt()) { 90 -> "transpose=1"; -90 -> "transpose=2"; else -> "transpose=1,transpose=1" },
        null, copyVideo = false, copyAudio = true
    )
    FilterKind.CROP -> {
        val f = cropPct / 100f
        FilterExpr("crop=iw*${1 - 2 * f}:ih*${1 - 2 * f}:iw*$f:ih*$f", null, copyVideo = false, copyAudio = true)
    }
    FilterKind.SPEED -> FilterExpr(
        "setpts=PTS/$speedFactor",
        "atempo=${speedFactor.coerceIn(0.5f, 2f)}",
        copyVideo = false, copyAudio = false
    )
    FilterKind.BRIGHTNESS -> FilterExpr("eq=brightness=$brightness:contrast=$contrast", null, copyVideo = false, copyAudio = true)
    FilterKind.GRAYSCALE -> FilterExpr("hue=s=0", null, copyVideo = false, copyAudio = true)
    FilterKind.DENOISE -> FilterExpr("hqdn3d=${denoiseStrength}:${denoiseStrength}:${denoiseStrength * 0.7f}:${denoiseStrength * 0.7f}", null, copyVideo = false, copyAudio = true)
    FilterKind.SHARPEN -> FilterExpr("unsharp=5:5:$sharpenAmount:5:5:0.0", null, copyVideo = false, copyAudio = true)
    FilterKind.WATERMARK -> {
        val escaped = watermarkText.replace("'", "\\'").replace(":", "\\:")
        FilterExpr("drawtext=text='$escaped':fontcolor=white@0.8:fontsize=h/20:x=w-tw-20:y=h-th-20:box=1:boxcolor=black@0.35:boxborderw=8", null, copyVideo = false, copyAudio = true)
    }
    FilterKind.VIGNETTE -> FilterExpr("vignette=PI/$vignetteAngle", null, copyVideo = false, copyAudio = true)
    FilterKind.VOLUME -> FilterExpr(null, "volume=${volumePct / 100f}", copyVideo = true, copyAudio = false)
    FilterKind.FADE -> {
        val d = fadeSeconds.toDouble()
        // Fade out via double-reverse so no total duration is needed.
        FilterExpr(null, "afade=t=in:st=0:d=$d,areverse,afade=t=in:st=0:d=$d,areverse", copyVideo = true, copyAudio = false)
    }
    FilterKind.BASS -> FilterExpr(null, "bass=g=$bassGainDb:f=110:w=0.6", copyVideo = true, copyAudio = false)
    FilterKind.NORMALIZE -> FilterExpr(null, "loudnorm=I=-16:TP=-1.5:LRA=11", copyVideo = true, copyAudio = false)
    FilterKind.ECHO -> FilterExpr(null, "aecho=0.8:0.9:1000:$echoMix", copyVideo = true, copyAudio = false)
    FilterKind.TEMPO -> FilterExpr(null, "atempo=${tempoFactor.coerceIn(0.5f, 2f)}", copyVideo = true, copyAudio = false)
}

private fun buildFilterArgs(
    input: File,
    output: File,
    expr: FilterExpr,
    hasVideo: Boolean,
    hasAudio: Boolean,
    cover: CoverInfo = CoverInfo(false, null),
    // "libx264" (default) or "h264_mediacodec" — the hardware path used when this device has
    // it (see JobViewModel.runConvertWithHardwarePreference, which falls back to a plain
    // libx264 call automatically if it doesn't).
    videoEncoder: String = "libx264",
    // Probed source bitrates (kb/s): re-encoded streams keep the input's bitrate instead
    // of a hardcoded override. Null = unknown, fall back to the previous defaults.
    sourceVideoBitrateKbps: Int? = null,
    sourceAudioBitrateKbps: Int? = null,
): List<String> {
    val args = mutableListOf("-y", "-i", input.absolutePath)
    val outExt = output.extension.lowercase()
    val useVideoFilter = expr.videoFilter != null && hasVideo
    val useAudioFilter = expr.audioFilter != null && hasAudio && !expr.copyAudio
    val imageOut = outExt in IMAGE_EXTENSIONS
    // Filters keep the input extension, so the re-encoded audio must use
    // whatever codec that container accepts (hardcoded AAC broke .mp3/.ogg/…).
    val audioEncoder = FILTER_AUDIO_ENCODER_FOR_EXT[outExt] ?: "aac"

    if (useVideoFilter) {
        if (cover.hasCoverArt && cover.mainVideoIndex != null) {
            // Keep the still out of the re-encoded stream (and out of -vf):
            // map the playable video track and the audio explicitly.
            args += listOf("-map", "0:${cover.mainVideoIndex}", "-map", "0:a")
        }
        args += listOf("-vf", expr.videoFilter!!)
        if (imageOut) {
            // Still-image output needs an image codec, not H.264.
            args += listOf("-c:v", if (outExt == "png") "png" else "mjpeg")
        } else if (videoEncoder == "h264_mediacodec") {
            // MediaCodec has no CRF knob — keep the source bitrate so the filter
            // doesn't override it (5500k only when the source gives us nothing).
            val kbps = sourceVideoBitrateKbps?.takeIf { it > 0 } ?: 5500
            args += listOf("-c:v", "h264_mediacodec", "-pix_fmt", "nv12", "-b:v", "${kbps}k")
        } else {
            // Same deal for software: re-encode at the source bitrate when known,
            // CRF 20 only as the unknown-source fallback.
            val kbps = sourceVideoBitrateKbps?.takeIf { it > 0 }
            if (kbps != null) args += listOf("-c:v", "libx264", "-b:v", "${kbps}k", "-preset", "fast")
            else args += listOf("-c:v", "libx264", "-crf", "20", "-preset", "fast")
        }
    } else if (hasVideo) {
        // Audio-only effect on a file that also has video: keep the picture untouched.
        args += listOf("-c:v", "copy")
    } else if (cover.hasCoverArt) {
        // Audio-only output but the input carries cover art: ffmpeg's default
        // stream selection would feed the mjpeg still into the audio muxer and
        // fail, so map explicitly — preserving the art where the container
        // supports it (verified: mp3/m4a/flac), dropping it elsewhere.
        if (outExt in COVER_PRESERVABLE_EXTS) args += listOf("-map", "0", "-c:v", "copy")
        else args += listOf("-map", "0:a")
    } // else: audio-only input without cover — default mapping selects just audio.

    if (!hasAudio) {
        args += listOf("-an")
    } else if (useAudioFilter) {
        args += listOf("-af", expr.audioFilter!!)
        args += listOf("-c:a", audioEncoder)
        // Keep the source audio bitrate instead of forcing 192k (192k only
        // when the probe gave us nothing), clamped to what the encoder allows.
        if (audioEncoder in FILTER_LOSSY_ENCODERS) {
            val kbps = (sourceAudioBitrateKbps?.takeIf { it > 0 } ?: 192)
                .coerceIn(32, maxBitrateForFilterAudioEncoder(audioEncoder))
            args += listOf("-b:a", "${kbps}k")
        }
    } else if (expr.copyAudio) {
        args += listOf("-c:a", "copy")
    } else {
        // SPEED touches both streams: video already has -vf above, re-encode audio too.
        expr.audioFilter?.let { args += listOf("-af", it) }
        args += listOf("-c:a", audioEncoder)
        if (audioEncoder in FILTER_LOSSY_ENCODERS) {
            val kbps = (sourceAudioBitrateKbps?.takeIf { it > 0 } ?: 192)
                .coerceIn(32, maxBitrateForFilterAudioEncoder(audioEncoder))
            args += listOf("-b:a", "${kbps}k")
        }
    }
    args += output.absolutePath
    return args
}
