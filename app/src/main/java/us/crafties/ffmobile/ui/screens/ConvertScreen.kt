package us.crafties.ffmobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import us.crafties.ffmobile.ffmpeg.MediaInfo
import us.crafties.ffmobile.ui.JobOwner
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.components.AnimatedSwitch
import us.crafties.ffmobile.ui.components.FilePickerDialog
import us.crafties.ffmobile.ui.components.LogConsole
import us.crafties.ffmobile.ui.components.ProgressCard
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------------------
// Codec matrix. Every (container, encoder) pair below was verified by running
// the bundled ffmpeg 8.1.3 binary — unverified combos are deliberately absent.
// Notes:
//  - APE has NO encoder in this build (decoder only), so it can't be offered.
//  - Raw .aac uses the ADTS muxer; .m4a is written through the mov muxer.
//  - Experimental encoders (dts, truehd, mlp) are excluded: they'd need -strict.
// ---------------------------------------------------------------------------

private data class AudioCodecOption(
    val label: String,
    val encoder: String, // ffmpeg -c:a value; "copy" = stream copy
    val lossless: Boolean = false, // hides the bitrate slider
    val maxBitrateKbps: Int = 320, // slider ceiling for lossy codecs
)

private data class VideoCodecOption(
    val label: String,
    val encoder: String, // ffmpeg -c:v value used when no hardware path applies (or as fallback)
    // MediaCodec hardware encoder that ffmpeg can target for this codec, when this specific
    // phone's Android build exposes one (verified present in this build: h264/hevc/vp9/av1/
    // mpeg4 _mediacodec — see libavcodec strings). Null means no hardware equivalent is even
    // attempted (e.g. GIF, stream copy).
    val hwEncoder: String? = null,
)

private val AAC = AudioCodecOption("AAC", "aac")
private val MP3 = AudioCodecOption("MP3", "libmp3lame")
private val OPUS = AudioCodecOption("Opus", "libopus", maxBitrateKbps = 512)
private val VORBIS = AudioCodecOption("Vorbis", "libvorbis", maxBitrateKbps = 500)
private val FLAC = AudioCodecOption("FLAC (lossless)", "flac", lossless = true)
private val ALAC = AudioCodecOption("ALAC (lossless)", "alac", lossless = true)
private val TTA = AudioCodecOption("TrueAudio (lossless)", "tta", lossless = true)
private val WAVPACK = AudioCodecOption("WavPack (lossless)", "wavpack", lossless = true)
private val PCM16 = AudioCodecOption("PCM 16-bit", "pcm_s16le", lossless = true)
private val PCM24 = AudioCodecOption("PCM 24-bit", "pcm_s24le", lossless = true)
private val PCMF32 = AudioCodecOption("PCM 32-bit float", "pcm_f32le", lossless = true)
private val WMA = AudioCodecOption("WMA v2", "wmav2", maxBitrateKbps = 384)
private val AC3 = AudioCodecOption("AC-3 (Dolby)", "ac3", maxBitrateKbps = 640)
private val ACOPY = AudioCodecOption("Copy (no re-encode)", "copy", lossless = true)

private val AUDIO_CODECS_FOR_CONTAINER: Map<String, List<AudioCodecOption>> = mapOf(
    "mp4" to listOf(AAC, ALAC, MP3, OPUS, ACOPY),
    "mkv" to listOf(AAC, MP3, OPUS, VORBIS, FLAC, ALAC, PCM16, WAVPACK, AC3, ACOPY),
    "webm" to listOf(OPUS, VORBIS, ACOPY),
    "mov" to listOf(AAC, ALAC, MP3, PCM16, AC3, ACOPY),
    "avi" to listOf(MP3, PCM16, AC3, ACOPY),
    "mp3" to listOf(MP3, ACOPY),
    // WAV: PCM family, plus MP3 as the one widely-tolerated niche exception.
    "wav" to listOf(PCM16, PCM24, PCMF32, MP3, ACOPY),
    "flac" to listOf(FLAC, ACOPY),
    "aac" to listOf(AAC, ACOPY),
    "m4a" to listOf(AAC, ALAC, ACOPY),
    "ogg" to listOf(VORBIS, OPUS, FLAC, ACOPY),
    "opus" to listOf(OPUS, ACOPY),
    "tta" to listOf(TTA, ACOPY),
    "wv" to listOf(WAVPACK, ACOPY),
    "wma" to listOf(WMA, ACOPY),
)

// Every non-copy option below carries its MediaCodec hardware equivalent. The
// actual job (see buildConvertArgs / JobViewModel.runConvertWithHardwarePreference)
// always tries the hardware encoder first on devices that expose it and
// transparently re-runs with the software encoder listed here if the hardware
// path fails to start — so picking, say, "H.264" still means "hardware h264
// if this phone has it, otherwise libx264", not "always software."
private val H264_AUTO = VideoCodecOption("Auto (h264)", "libx264", hwEncoder = "h264_mediacodec")
private val H264 = VideoCodecOption("H.264", "libx264", hwEncoder = "h264_mediacodec")
private val H265 = VideoCodecOption("H.265 (HEVC)", "libx265", hwEncoder = "hevc_mediacodec")
private val VP9_AUTO = VideoCodecOption("Auto (vp9)", "libvpx-vp9", hwEncoder = "vp9_mediacodec")
private val VP9 = VideoCodecOption("VP9", "libvpx-vp9", hwEncoder = "vp9_mediacodec")
private val AV1 = VideoCodecOption("AV1 (slow)", "libaom-av1", hwEncoder = "av1_mediacodec")
private val MPEG4 = VideoCodecOption("MPEG-4", "mpeg4", hwEncoder = "mpeg4_mediacodec")
private val GIF = VideoCodecOption("GIF", "gif")
private val VCOPY = VideoCodecOption("Copy (no re-encode)", "copy")

private val VIDEO_CODECS_FOR_CONTAINER: Map<String, List<VideoCodecOption>> = mapOf(
    "mp4" to listOf(H264_AUTO, H264, H265, VP9, AV1, VCOPY),
    "mkv" to listOf(H264_AUTO, H264, H265, VP9, AV1, VCOPY),
    // WebM only accepts VP8/VP9/AV1 video — H.264/H.265 fail at mux time.
    "webm" to listOf(VP9_AUTO, VP9, AV1, VCOPY),
    "mov" to listOf(H264_AUTO, H264, H265, VCOPY),
    "avi" to listOf(H264_AUTO, H264, MPEG4, VCOPY),
    "gif" to listOf(GIF),
)

private val CONTAINERS = listOf(
    "mp4", "mkv", "webm", "mov", "avi", "gif",
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "tta", "wv", "wma",
)
private val AUDIO_ONLY_CONTAINERS = setOf(
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "tta", "wv", "wma",
)
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "tta", "wv",
)
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic")
private val RESOLUTIONS = listOf("Source", "1920x1080", "1280x720", "854x480", "640x360")
private val METADATA_MODES = listOf("Copy from source", "Strip all")

/**
 * Containers where a stream-copied cover picture is verified to survive the
 * mux (same set Filters uses, plus mkv which also carries attached_pic fine).
 * Everything else — including Opus, which has no attached-picture convention
 * at all in its own container and drops it in ours too — loses the cover on
 * convert, which is normal for those formats rather than an app limitation.
 */
private val COVER_PRESERVABLE_EXTS = setOf("mp3", "m4a", "flac", "mkv")

private fun convertCoverArtNote(outExt: String, audioEncoder: String, outHasRealVideo: Boolean): String = when {
    outHasRealVideo ->
        "The embedded cover art will be dropped — it isn't carried into a re-encoded video track."
    outExt in COVER_PRESERVABLE_EXTS && audioEncoder == "copy" ->
        "The embedded cover art will be kept in the output file."
    outExt in COVER_PRESERVABLE_EXTS ->
        "Note: re-encoding the audio here will drop the cover art. Use “Copy (no re-encode)” to keep it."
    else ->
        "Note: .$outExt can't hold cover art — it will be dropped from the output."
}

@Composable
fun ConvertScreen(vm: JobViewModel) {
    val owner = JobOwner.CONVERT
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by vm.stateFlowFor(owner).collectAsState()
    val anyRunning by vm.anyRunning.collectAsState()
    val busyElsewhere = anyRunning && !state.running

    var showPicker by remember { mutableStateOf(false) }
    var inputFile by remember { mutableStateOf<File?>(null) }
    var mediaInfo by remember { mutableStateOf<MediaInfo?>(null) }
    var probing by remember { mutableStateOf(false) }
    val durationSeconds = mediaInfo?.durationSeconds?.takeIf { it > 0 }

    var container by remember { mutableStateOf(CONTAINERS[0]) }
    var videoCodec by remember { mutableStateOf(H264_AUTO) }
    var audioCodec by remember { mutableStateOf(AAC) }
    var resolution by remember { mutableStateOf(RESOLUTIONS[0]) }
    var crf by remember { mutableStateOf(23f) }
    var useVideoBitrate by remember { mutableStateOf(false) }
    var videoBitrateKbps by remember { mutableStateOf(4000f) }
    var audioBitrateKbps by remember { mutableStateOf(192f) }
    var metadataMode by remember { mutableStateOf(METADATA_MODES[0]) }
    var metaTitle by remember { mutableStateOf("") }
    var metaArtist by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    // ---- per-container codec lists; reset selection if it isn't valid here ----
    val videoOptions = VIDEO_CODECS_FOR_CONTAINER[container] ?: listOf(H264_AUTO, VCOPY)
    val audioOptions = AUDIO_CODECS_FOR_CONTAINER[container] ?: listOf(AAC, ACOPY)
    LaunchedEffect(container) {
        if (videoCodec !in videoOptions) videoCodec = videoOptions[0]
        if (audioCodec !in audioOptions) {
            audioCodec = audioOptions[0]
            audioBitrateKbps = audioBitrateKbps.coerceIn(32f, audioOptions[0].maxBitrateKbps.toFloat())
        }
    }
    LaunchedEffect(audioCodec) {
        audioBitrateKbps = audioBitrateKbps.coerceIn(32f, audioCodec.maxBitrateKbps.toFloat())
    }

    // ---- auto-detected input type ----
    // Cover art (attached_pic) is not a playable video track — an MP3 with
    // album art must not enable video options.
    val ext = inputFile?.extension?.lowercase().orEmpty()
    val detectedHasVideo = mediaInfo?.streams?.any { it.codecType == "video" && !it.isAttachedPic }
        ?: inputFile?.let { ext !in AUDIO_ONLY_CONTAINERS && ext !in AUDIO_EXTENSIONS && ext !in IMAGE_EXTENSIONS }
        ?: true
    val detectedHasAudio = mediaInfo?.streams?.any { it.codecType == "audio" }
        ?: inputFile?.let { ext !in IMAGE_EXTENSIONS && ext != "gif" }
        ?: true
    val isImageInput = inputFile?.let { ext in IMAGE_EXTENSIONS } ?: false
    val hasCoverArt = mediaInfo?.streams?.any { it.isAttachedPic } ?: false
    val coverStreamIndex = mediaInfo?.streams?.firstOrNull { it.isAttachedPic }?.index

    var coverBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(inputFile, coverStreamIndex) {
        coverBitmap = null
        val file = inputFile
        if (hasCoverArt && file != null && coverStreamIndex != null) {
            val extracted = vm.extractCoverArt(file.absolutePath, coverStreamIndex)
            coverBitmap = extracted?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }

    val audioOnlyOutput = container in AUDIO_ONLY_CONTAINERS
    val showVideoSection = detectedHasVideo && !audioOnlyOutput && !isImageInput
    // GIF has no audio track; image input has no audio to carry over.
    val showAudioSection = detectedHasAudio && container != "gif" && !isImageInput
    // Single image -> video container: encode the still as a one-frame video.
    val effectiveIncludeVideo = showVideoSection || (isImageInput && !audioOnlyOutput)
    val convertible = effectiveIncludeVideo || showAudioSection

    // Quality controls only apply to real video encoders (not Copy/GIF/MPEG-4-fixed).
    val showVideoQuality = showVideoSection &&
        videoCodec.encoder != "copy" && videoCodec.encoder != "gif"

    if (showPicker) {
        FilePickerDialog(
            title = "Choose media to convert",
            onDismiss = { showPicker = false },
            onFileSelected = { file ->
                inputFile = file
                mediaInfo = null
                showPicker = false
                probing = true
                // Sensible default output: audio file in -> audio container out.
                val fExt = file.extension.lowercase()
                if (fExt in AUDIO_ONLY_CONTAINERS || fExt in AUDIO_EXTENSIONS) container = "mp3"
                scope.launch {
                    mediaInfo = vm.probe(file.absolutePath)
                    probing = false
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Convert", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Pick a file and re-encode it with the bundled FFmpeg build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        InputFileCard(inputFile, onPick = { showPicker = true })
        Spacer(Modifier.height(16.dp))

        if (probing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Detecting streams…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }

        AnimatedVisibility(
            visible = inputFile != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                // Detected type badge — only for this tab's own file.
                mediaInfo?.let { info ->
                    val kinds = buildList {
                        if (info.streams.any { it.codecType == "video" && !it.isAttachedPic }) add("video")
                        if (info.streams.any { it.codecType == "audio" }) add("audio")
                        if (info.streams.any { it.isAttachedPic }) add("cover art")
                    }.ifEmpty { listOf(info.formatName) }
                    AssistChip(
                        onClick = {},
                        label = { Text(kinds.joinToString(" + ") + " · " + info.formatName) },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                LabeledDropdown("Output format", CONTAINERS, container) { container = it }
                Spacer(Modifier.height(12.dp))

                if (showVideoSection) {
                    if (videoOptions.size > 1) {
                        LabeledDropdown(
                            "Video codec",
                            videoOptions.map { it.label },
                            videoCodec.label,
                        ) { label -> videoOptions.firstOrNull { it.label == label }?.let { videoCodec = it } }
                    } else {
                        Text(
                            "Video codec: GIF (fixed for this format)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val copyWithScale = videoCodec.encoder == "copy" && resolution != "Source"
                    LabeledDropdown("Resolution", RESOLUTIONS, resolution) { resolution = it }
                    if (copyWithScale) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "“Copy” can't resize — output will be re-encoded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    if (showVideoQuality) {
                        if (videoCodec.encoder == "mpeg4") {
                            Text(
                                "MPEG-4 uses fixed quality (q:v 3) unless a target bitrate is set.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Target bitrate instead of CRF", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (useVideoBitrate) "On — constant bitrate" else "Off — constant quality (CRF)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AnimatedSwitch(checked = useVideoBitrate, onCheckedChange = { useVideoBitrate = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        if (useVideoBitrate) {
                            Text("Video bitrate (${videoBitrateKbps.toInt()} kb/s)", style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = videoBitrateKbps,
                                onValueChange = { videoBitrateKbps = it },
                                valueRange = 300f..20000f
                            )
                        } else if (videoCodec.encoder != "mpeg4") {
                            Text("Quality (CRF ${crf.toInt()} — lower is better)", style = MaterialTheme.typography.titleMedium)
                            Slider(value = crf, onValueChange = { crf = it }, valueRange = 12f..40f)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (showAudioSection) {
                    LabeledDropdown(
                        "Audio codec",
                        audioOptions.map { it.label },
                        audioCodec.label,
                    ) { label ->
                        audioOptions.firstOrNull { it.label == label }?.let { audioCodec = it }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        audioCodec.encoder == "copy" -> Text(
                            "Audio bitrate (unused with Copy)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        audioCodec.lossless -> Text(
                            "${audioCodec.label} is lossless — no bitrate setting.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> {
                            Text(
                                "Audio bitrate (${audioBitrateKbps.toInt()} kb/s)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = audioBitrateKbps,
                                onValueChange = { audioBitrateKbps = it },
                                valueRange = 32f..audioCodec.maxBitrateKbps.toFloat()
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        if (container == "gif") "GIF has no audio — audio options hidden."
                        else "No audio stream detected — audio options hidden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Shown whenever the source's only "picture" is cover art (no
                // real video track) — regardless of whether the chosen output
                // container is audio-only or a video-capable one like mkv.
                if (hasCoverArt && !detectedHasVideo) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Embedded cover art", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            coverBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Embedded cover art",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                convertCoverArtNote(container, audioCodec.encoder, outHasRealVideo = effectiveIncludeVideo),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                LabeledDropdown("Metadata", METADATA_MODES, metadataMode) { metadataMode = it }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = metaTitle, onValueChange = { metaTitle = it },
                    label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = metaArtist, onValueChange = { metaArtist = it },
                    label = { Text("Artist (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(20.dp))

                if (!convertible) {
                    Text(
                        "Can't convert an image to an audio-only file — pick a video container.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = {
                        val input = inputFile ?: return@Button
                        val outFile = outputFileFor(input, container)
                        fun args(videoEncoder: String) = buildConvertArgs(
                            input = input,
                            output = outFile,
                            container = container,
                            videoEncoder = videoEncoder,
                            audioEncoder = audioCodec.encoder,
                            resolution = resolution,
                            crf = crf.toInt(),
                            useVideoBitrate = useVideoBitrate,
                            videoBitrateKbps = videoBitrateKbps.toInt(),
                            audioBitrateKbps = audioBitrateKbps.toInt(),
                            includeVideo = effectiveIncludeVideo,
                            includeAudio = showAudioSection,
                            stripMetadata = metadataMode != METADATA_MODES[0],
                            metaTitle = metaTitle.ifBlank { null },
                            metaArtist = metaArtist.ifBlank { null },
                            hasCoverArt = hasCoverArt,
                        )
                        val swArgs = args(videoCodec.encoder)
                        // Only worth a hardware attempt when we're actually encoding video
                        // with an encoder that has a MediaCodec equivalent (not Copy/GIF, and
                        // not when there's no video track going out at all).
                        val hwArgs = videoCodec.hwEncoder
                            ?.takeIf { effectiveIncludeVideo && videoCodec.encoder != "copy" }
                            ?.let { hwEncoder -> args(hwEncoder) }
                        vm.clearLogs(owner)
                        vm.runConvertWithHardwarePreference(
                            hwArgs, swArgs, durationSeconds, owner,
                            outputPath = outFile.absolutePath,
                        )
                    },
                    enabled = !anyRunning && convertible,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Convert")
                }
                if (busyElsewhere) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Another tab is already converting — wait for it to finish.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        ProgressCard(
            running = state.running,
            fraction = state.fraction,
            speed = state.speed,
            elapsedLabel = if (durationSeconds != null) "of ${"%.0f".format(durationSeconds)}s" else "",
            onCancel = { vm.cancel() }
        )

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

@Composable
fun ResultBanner(
    success: Boolean,
    outputFile: File? = null,
    onOpen: ((File) -> Unit)? = null,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (success) "Done! Output saved next to the source file."
                    else "FFmpeg exited with an error! Check the log.",
                    modifier = Modifier.weight(1f),
                )
            }
            if (success && outputFile != null && onOpen != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onOpen(outputFile) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
            }
        }
    }
}

/**
 * Opens [file] with whatever app the OS resolves for its type (video player,
 * music player, gallery, …) via a FileProvider content URI + chooser.
 * Failures (no handler, missing file) surface as a Toast, never a crash.
 */
fun openMediaFile(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "Output file not found.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (_: Exception) {
        Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeTypeFor(file: File): String {
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?.let { return it }
    // Fallback for extensions the system map doesn't know (e.g. opus, wv, tta).
    return when (file.extension.lowercase()) {
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "flv", "wmv" -> "video/*"
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "tta", "wv" -> "audio/*"
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "image/*"
        else -> "*/*"
    }
}

@Composable
fun InputFileCard(file: File?, onPick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), onClick = onPick) {
        Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(file?.name ?: "Choose a file", style = MaterialTheme.typography.titleMedium)
                Text(
                    file?.absolutePath ?: "Tap to browse device storage",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledDropdown(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false })
                }
            }
        }
    }
}

/**
 * Rough CRF -> bitrate mapping for MediaCodec's bitrate-only encoders, anchored so the
 * default CRF (23) lands on the same 4000 kb/s the bitrate slider defaults to, then scales
 * by the usual "~2x bitrate per -6 CRF" rule of thumb. Clamped to the slider's own range.
 */
private fun crfToApproxBitrateKbps(crf: Int): Int {
    val kbps = 4000.0 * Math.pow(2.0, (23 - crf) / 6.0)
    return kbps.toInt().coerceIn(300, 20000)
}

fun outputFileFor(input: File, container: String): File {
    val base = input.nameWithoutExtension
    val dir = input.parentFile ?: input
    var candidate = File(dir, "${base}_converted.$container")
    var n = 1
    while (candidate.exists()) {
        candidate = File(dir, "${base}_converted_$n.$container")
        n++
    }
    return candidate
}

fun buildConvertArgs(
    input: File,
    output: File,
    container: String,
    videoEncoder: String,
    audioEncoder: String,
    resolution: String,
    crf: Int,
    useVideoBitrate: Boolean = false,
    videoBitrateKbps: Int = 4000,
    audioBitrateKbps: Int = 192,
    includeVideo: Boolean = true,
    includeAudio: Boolean = true,
    stripMetadata: Boolean = false,
    metaTitle: String? = null,
    metaArtist: String? = null,
    hasCoverArt: Boolean = false,
): List<String> {
    val args = mutableListOf("-y", "-i", input.absolutePath)

    val audioOnlyContainer = container in AUDIO_ONLY_CONTAINERS
    val wantVideo = (includeVideo && !audioOnlyContainer) || (container == "gif" && includeVideo)
    // Cover art with no real video going out: ffmpeg's default stream
    // selection would try to feed the mjpeg still into the audio muxer (or
    // drop it) unpredictably, so map explicitly. Only safe as a straight
    // stream copy (verified: mp3/m4a/flac/mkv); re-encoding the audio here
    // re-invokes the audio encoder on a filtergraph that never asked for the
    // picture, so it's dropped in that case instead. This applies whether the
    // output container is audio-only (mp3) or video-capable but simply has no
    // video to put in it here (mkv from an opus source, say).
    val keepCoverArt = !wantVideo && hasCoverArt &&
        container in COVER_PRESERVABLE_EXTS && audioEncoder == "copy"
    if (!wantVideo) {
        if (keepCoverArt) args += listOf("-map", "0", "-c:v", "copy")
        else args += listOf("-vn")
    } else {
        val copyWithScale = videoEncoder == "copy" && resolution != "Source"
        // "Copy" can't apply -vf scale; fall back to a container-native encoder.
        val effective = if (copyWithScale) {
            if (container == "webm") "libvpx-vp9" else "libx264"
        } else videoEncoder
        when (effective) {
            "copy" -> args += listOf("-c:v", "copy")
            "gif" -> args += listOf("-c:v", "gif")
            "libx265" -> {
                args += listOf("-c:v", "libx265", "-preset", "fast")
                if (useVideoBitrate) args += listOf("-b:v", "${videoBitrateKbps}k")
                else args += listOf("-crf", "$crf")
            }
            "libvpx-vp9", "libaom-av1" -> {
                args += listOf("-c:v", effective)
                if (useVideoBitrate) args += listOf("-b:v", "${videoBitrateKbps}k")
                else args += listOf("-crf", "$crf", "-b:v", "0")
            }
            "mpeg4" -> {
                args += listOf("-c:v", "mpeg4")
                if (useVideoBitrate) args += listOf("-b:v", "${videoBitrateKbps}k")
                else args += listOf("-q:v", "3")
            }
            "h264_mediacodec", "hevc_mediacodec", "vp9_mediacodec", "av1_mediacodec", "mpeg4_mediacodec" -> {
                // Android's MediaCodec encoders take a target bitrate only — there's no
                // CRF-style constant-quality knob like x264/x265 expose, and no "-preset".
                // If the user left the quality slider (CRF) selected instead of an explicit
                // bitrate, approximate an equivalent bitrate from it so switching between
                // hardware and software still gives comparable output size/quality.
                val kbps = if (useVideoBitrate) videoBitrateKbps else crfToApproxBitrateKbps(crf)
                args += listOf("-c:v", effective, "-pix_fmt", "nv12", "-b:v", "${kbps}k")
            }
            else -> {
                args += listOf("-c:v", "libx264", "-preset", "fast")
                if (useVideoBitrate) args += listOf("-b:v", "${videoBitrateKbps}k")
                else args += listOf("-crf", "$crf")
            }
        }
        if (resolution != "Source") {
            val (w, h) = resolution.split("x")
            args += listOf("-vf", "scale=$w:$h")
        }
    }

    if (!includeAudio || container == "gif") {
        args += listOf("-an")
    } else {
        when (audioEncoder) {
            "copy" -> args += listOf("-c:a", "copy")
            "flac", "alac", "tta", "wavpack" -> args += listOf("-c:a", audioEncoder)
            "pcm_s16le", "pcm_s24le", "pcm_f32le" -> args += listOf("-c:a", audioEncoder)
            else -> {
                // Lossy encoders (aac, libmp3lame, libopus, libvorbis, wmav2, ac3).
                val option = AUDIO_CODECS_FOR_CONTAINER[container]?.firstOrNull { it.encoder == audioEncoder }
                val ceiling = option?.maxBitrateKbps ?: 320
                args += listOf("-c:a", audioEncoder, "-b:a", "${audioBitrateKbps.coerceIn(32, ceiling)}k")
            }
        }
    }

    if (stripMetadata) args += listOf("-map_metadata", "-1")
    metaTitle?.let { args += listOf("-metadata", "title=$it") }
    metaArtist?.let { args += listOf("-metadata", "artist=$it") }

    args += output.absolutePath
    return args
}
