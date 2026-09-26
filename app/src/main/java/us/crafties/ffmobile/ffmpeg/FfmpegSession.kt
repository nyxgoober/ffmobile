package us.crafties.ffmobile.ffmpeg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

sealed class FfmpegEvent {
    data class Log(val line: String) : FfmpegEvent()
    data class Progress(val timeSeconds: Double, val speed: Double, val fraction: Float?) : FfmpegEvent()
    data class Completed(val exitCode: Int, val durationMs: Long) : FfmpegEvent()
    data class Failed(val reason: String) : FfmpegEvent()
}

/** Splits a raw command string the way a shell would, respecting single/double quotes. */
fun tokenizeCommand(raw: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            quote != null -> {
                if (c == quote) quote = null else current.append(c)
            }
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> {
                if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
            }
            else -> current.append(c)
        }
        i++
    }
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}

class FfmpegSession(private val context: Context) {

    private val runningProcess = AtomicReference<Process?>(null)
    private val timeRegex = Regex("""time=(\d{2}):(\d{2}):(\d{2})\.(\d{2})""")
    private val speedRegex = Regex("""speed=\s*([0-9.]+)x""")

    val events = MutableSharedFlow<FfmpegEvent>(replay = 0, extraBufferCapacity = 256)

    fun isRunning(): Boolean = runningProcess.get() != null

    fun cancel() {
        runningProcess.get()?.destroy()
    }

    /** Runs ffmpeg with [args] (NOT including the "ffmpeg" argv0). [totalDurationSeconds] enables % progress. */
    suspend fun runFfmpeg(args: List<String>, totalDurationSeconds: Double? = null) {
        val startedAt = System.currentTimeMillis()
        try {
            val exit = runCore(args, totalDurationSeconds)
            events.emit(FfmpegEvent.Completed(exit, System.currentTimeMillis() - startedAt))
        } catch (e: Exception) {
            events.emit(FfmpegEvent.Failed(e.message ?: "Unknown error launching ffmpeg"))
        }
    }

    /**
     * Same as [runFfmpeg], but returns the exit code directly (throwing on launch failure)
     * instead of emitting Completed/Failed. Log/Progress events still stream normally so the
     * UI keeps showing live output. Used by callers — namely the hardware-encoder-with-
     * fallback path in JobViewModel — that need to see the result before deciding whether to
     * retry, rather than having the job reported as finished after the first attempt.
     */
    suspend fun runFfmpegAndAwaitExit(args: List<String>, totalDurationSeconds: Double? = null): Int =
        runCore(args, totalDurationSeconds)

    suspend fun runFfprobe(args: List<String>): String = withContext(Dispatchers.IO) {
        val layout = FfmpegBinaries.ensureInstalled(context)
        val cmd = mutableListOf(layout.ffprobePath).apply { addAll(args) }
        val pb = ProcessBuilder(cmd)
        pb.environment()["LD_LIBRARY_PATH"] = layout.libDir
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(false)
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        // Drain stderr so a verbose ffprobe can't block on a full pipe.
        try {
            process.errorStream.bufferedReader().readText()
        } catch (_: Exception) { }
        process.waitFor()
        out
    }

    /**
     * Pulls the embedded cover art (ffprobe disposition attached_pic=1) out of
     * [inputPath] at [streamIndex] and writes it to a small JPEG in the cache
     * dir, for preview only — this never touches the user's actual convert/
     * filter job. Returns null on any failure (no cover, unsupported codec,
     * non-zero exit, etc.); callers should treat that as "no preview available"
     * rather than an error.
     */
    suspend fun extractCoverArt(inputPath: String, streamIndex: Int): File? = withContext(Dispatchers.IO) {
        val layout = FfmpegBinaries.ensureInstalled(context)
        val outFile = File(context.cacheDir, "cover_preview_${System.nanoTime()}.jpg")
        val cmd = listOf(
            layout.ffmpegPath,
            "-y", "-v", "quiet",
            "-i", inputPath,
            "-map", "0:$streamIndex",
            "-frames:v", "1",
            "-c:v", "mjpeg",
            outFile.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.environment()["LD_LIBRARY_PATH"] = layout.libDir
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        return@withContext try {
            val process = pb.start()
            // Drain output so the process can't block on a full pipe.
            process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0 && outFile.exists() && outFile.length() > 0) outFile else {
                outFile.delete()
                null
            }
        } catch (_: Exception) {
            outFile.delete()
            null
        }
    }

    /**
     * Launches ffmpeg with [args], streaming Log/Progress events as it runs, and returns its
     * exit code. Throws whatever exception ProcessBuilder.start() throws if the process can't
     * even be launched — callers decide how to surface that (see [runFfmpeg] and
     * [runFfmpegAndAwaitExit]).
     */
    private suspend fun runCore(args: List<String>, totalDurationSeconds: Double?): Int =
        withContext(Dispatchers.IO) {
            val layout = FfmpegBinaries.ensureInstalled(context)
            val exe = layout.ffmpegPath
            val cmd = mutableListOf(exe).apply { addAll(args) }

            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = layout.libDir
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.redirectErrorStream(true)

            val process = pb.start()
            runningProcess.set(process)
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    events.emit(FfmpegEvent.Log(l))

                    val timeMatch = timeRegex.find(l)
                    if (timeMatch != null) {
                        val (h, m, s, cs) = timeMatch.destructured
                        val seconds = h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble() + cs.toDouble() / 100.0
                        val speed = speedRegex.find(l)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                        val fraction = totalDurationSeconds?.let {
                            if (it > 0) (seconds / it).toFloat().coerceIn(0f, 1f) else null
                        }
                        events.emit(FfmpegEvent.Progress(seconds, speed, fraction))
                    }
                }
                process.waitFor()
            } finally {
                runningProcess.set(null)
            }
        }
}
