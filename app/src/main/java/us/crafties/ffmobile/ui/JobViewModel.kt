package us.crafties.ffmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import us.crafties.ffmobile.ffmpeg.FfmpegEvent
import us.crafties.ffmobile.ffmpeg.FfmpegSession
import us.crafties.ffmobile.ffmpeg.MediaInfo
import us.crafties.ffmobile.ffmpeg.tokenizeCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LogLine(val id: Long, val text: String)

data class JobUiState(
    val running: Boolean = false,
    val fraction: Float? = null,
    val speed: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val logs: List<LogLine> = emptyList(),
    val lastExitCode: Int? = null,
    val errorMessage: String? = null,
    /** Absolute path of the output file this job wrote (if any) — used for the Open button. */
    val outputPath: String? = null,
)

object JobOwner {
    const val CONVERT = "convert"
    const val FILTERS = "filters"
    const val CONCAT = "concat"
    const val ADVANCED = "advanced"
}

class JobViewModel(app: Application) : AndroidViewModel(app) {

    val session = FfmpegSession(app)

    // Per-tab job state so a progress banner / result / log in one tab never
    // leaks into the other tabs. Each owner gets its own StateFlow, so a screen
    // collecting its tab's flow does NOT recompose when another tab streams logs
    // (that was the main source of tab-switch jank during a conversion).
    // Only one ffmpeg process runs at a time (single session), but events are
    // routed to whichever tab started it.
    private val ownerFlows = mutableMapOf<String, MutableStateFlow<JobUiState>>()
    private var logIdCounter = 0L

    fun stateFlowFor(owner: String): StateFlow<JobUiState> = mutableFlowFor(owner)

    private fun mutableFlowFor(owner: String): MutableStateFlow<JobUiState> = synchronized(ownerFlows) {
        ownerFlows.getOrPut(owner) { MutableStateFlow(JobUiState()) }
    }

    private val _anyRunning = MutableStateFlow(false)
    val anyRunning: StateFlow<Boolean> = _anyRunning.asStateFlow()

    @Volatile
    private var activeOwner: String? = null

    init {
        viewModelScope.launch {
            session.events.collect { event ->
                val owner = activeOwner ?: return@collect
                when (event) {
                    is FfmpegEvent.Log -> updateOwner(owner) {
                        it.copy(logs = (it.logs + LogLine(nextLogId(), event.line)).takeLast(2000))
                    }
                    is FfmpegEvent.Progress -> updateOwner(owner) {
                        it.copy(fraction = event.fraction, speed = event.speed)
                    }
                    is FfmpegEvent.Completed -> {
                        updateOwner(owner) {
                            it.copy(
                                running = false,
                                lastExitCode = event.exitCode,
                                fraction = if (event.exitCode == 0) 1f else it.fraction
                            )
                        }
                        if (activeOwner == owner) activeOwner = null
                        refreshAnyRunning()
                    }
                    is FfmpegEvent.Failed -> {
                        updateOwner(owner) { it.copy(running = false, errorMessage = event.reason) }
                        if (activeOwner == owner) activeOwner = null
                        refreshAnyRunning()
                    }
                }
            }
        }
    }

    fun clearLogs(owner: String) {
        updateOwner(owner) { it.copy(logs = emptyList(), lastExitCode = null, errorMessage = null, outputPath = null) }
    }

    fun clearResult(owner: String) {
        updateOwner(owner) { it.copy(lastExitCode = null, errorMessage = null, fraction = null, outputPath = null) }
    }

    fun runArgs(args: List<String>, totalDurationSeconds: Double? = null, owner: String, outputPath: String? = null) {
        if (_anyRunning.value) return
        activeOwner = owner
        updateOwner(owner) { current ->
            current.copy(
                running = true, fraction = 0f, speed = 0.0,
                lastExitCode = null, errorMessage = null, outputPath = outputPath,
                logs = (current.logs + LogLine(nextLogId(), "▶ ffmpeg ${args.joinToString(" ")}")).takeLast(2000)
            )
        }
        viewModelScope.launch {
            session.runFfmpeg(args, totalDurationSeconds)
        }
    }

    /**
     * Runs a convert job that should prefer a MediaCodec hardware encoder when one applies.
     * If [hwArgs] is non-null, ffmpeg is launched with it first; if that attempt exits non-zero
     * (or fails to launch at all — e.g. this phone/Android build has no hardware encoder for
     * that codec), the job automatically re-runs with [swArgs] instead, with a log line
     * explaining the fallback. If [hwArgs] is null there's no hardware equivalent to try, and
     * this behaves the same as a plain [runArgs] call with [swArgs].
     */
    fun runConvertWithHardwarePreference(
        hwArgs: List<String>?,
        swArgs: List<String>,
        totalDurationSeconds: Double? = null,
        owner: String,
        outputPath: String? = null,
    ) {
        if (_anyRunning.value) return
        activeOwner = owner
        val firstArgs = hwArgs ?: swArgs
        updateOwner(owner) { current ->
            current.copy(
                running = true, fraction = 0f, speed = 0.0,
                lastExitCode = null, errorMessage = null, outputPath = outputPath,
                logs = (current.logs + LogLine(nextLogId(), "▶ ffmpeg ${firstArgs.joinToString(" ")}")).takeLast(2000)
            )
        }
        viewModelScope.launch {
            var exit: Int? = null
            var failReason: String? = null

            if (hwArgs != null) {
                exit = try {
                    session.runFfmpegAndAwaitExit(hwArgs, totalDurationSeconds)
                } catch (_: Exception) {
                    null
                }
                if (exit != 0) {
                    exit = null
                    updateOwner(owner) {
                        it.copy(
                            fraction = 0f,
                            logs = (it.logs +
                                LogLine(nextLogId(), "⚠ Hardware encoder unavailable on this device — falling back to software encoder.") +
                                LogLine(nextLogId(), "▶ ffmpeg ${swArgs.joinToString(" ")}")
                            ).takeLast(2000)
                        )
                    }
                }
            }

            if (exit == null && failReason == null) {
                exit = try {
                    session.runFfmpegAndAwaitExit(swArgs, totalDurationSeconds)
                } catch (e: Exception) {
                    failReason = e.message ?: "Unknown error launching ffmpeg"
                    null
                }
            }

            if (failReason != null) {
                updateOwner(owner) { it.copy(running = false, errorMessage = failReason) }
            } else {
                updateOwner(owner) { it.copy(running = false, lastExitCode = exit, fraction = if (exit == 0) 1f else it.fraction) }
            }
            if (activeOwner == owner) activeOwner = null
            refreshAnyRunning()
        }
    }

    fun runRawCommand(raw: String, owner: String) {
        val args = tokenizeCommand(raw).let {
            // allow users to optionally type a leading "ffmpeg"
            if (it.isNotEmpty() && it[0].equals("ffmpeg", ignoreCase = true)) it.drop(1) else it
        }
        runArgs(args, owner = owner)
    }

    fun cancel() {
        session.cancel()
    }

    suspend fun probe(path: String): MediaInfo? {
        val json = session.runFfprobe(
            listOf("-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path)
        )
        return us.crafties.ffmobile.ffmpeg.FfprobeParser.parse(json)
    }

    /** Preview-only thumbnail for an attached_pic (cover art) stream. See [FfmpegSession.extractCoverArt]. */
    suspend fun extractCoverArt(path: String, streamIndex: Int) = session.extractCoverArt(path, streamIndex)

    private fun updateOwner(owner: String, block: (JobUiState) -> JobUiState) {
        val flow = mutableFlowFor(owner)
        flow.value = block(flow.value)
        refreshAnyRunning()
    }

    private fun refreshAnyRunning() {
        _anyRunning.value = synchronized(ownerFlows) { ownerFlows.values.any { it.value.running } }
    }

    private fun nextLogId(): Long = synchronized(ownerFlows) { ++logIdCounter }
}

private fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
    value = block(value)
}
