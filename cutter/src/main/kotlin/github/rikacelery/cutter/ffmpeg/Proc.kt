package github.rikacelery.cutter.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin ffmpeg/ffprobe process helpers.
 *
 * Every call drains stderr on a separate thread: ffmpeg is chatty on stderr and a
 * full pipe buffer would otherwise deadlock a long-running transcode.
 */
object Proc {

    /** Runs a command and returns stdout, throwing on a non-zero exit. */
    suspend fun capture(vararg cmd: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(*cmd).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        outThread.start(); errThread.start()
        val code = process.waitFor()
        outThread.join(); errThread.join()
        if (code != 0) {
            throw ProcessFailure(cmd.toList(), code, err.toString().trim())
        }
        out.toString()
    }

    /**
     * Runs a command, forwarding stderr lines to [onStderr]. Returns stdout.
     * Cancellation destroys the process rather than leaving it running.
     */
    suspend fun exec(
        onStderr: (String) -> Unit = {},
        onStdout: (String) -> Unit = {},
        vararg cmd: String
    ): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(*cmd).start()
        val out = StringBuilder()
        // Keep the tail of stderr so a failure can be reported with its cause;
        // ffmpeg explains itself there and nowhere else.
        val err = StringBuilder()
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    onStderr(line)
                    if (err.length < 4000) err.appendLine(line)
                }
            }
        }
        val outThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    out.appendLine(it); onStdout(it)
                }
            }
        }
        outThread.start(); errThread.start()

        // Cancelling the coroutine (a proxy job the user aborted, a client that went
        // away) must take the child process with it. Without this the ffmpeg keeps
        // burning a core for its full runtime with nothing left to receive the
        // result — a cancelled six-hour proxy would run for another half hour.
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { process.destroyForcibly() }
        }
        try {
            val code = process.waitFor()
            outThread.join(); errThread.join()
            if (code != 0) throw ProcessFailure(cmd.toList(), code, err.toString().trim())
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        } finally {
            cancellation?.dispose()
            if (!process.isAlive) runCatching { process.destroyForcibly() }
        }
        out.toString()
    }

    /** Best-effort teardown: SIGTERM, then SIGKILL if it will not go quietly. */
    fun kill(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** True when [binary] resolves and answers `-version`. */
    fun probeTool(binary: String): String? = runCatching {
        val process = ProcessBuilder(binary, "-version")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) text.lineSequence().firstOrNull()?.trim() else null
    }.getOrNull()

    fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("cannot create directory ${dir.absolutePath}")
        }
        return dir
    }
}

/** A command failed. [stderr] is truncated because ffmpeg can emit megabytes. */
class ProcessFailure(
    val command: List<String>,
    val exitCode: Int,
    val stderr: String
) : RuntimeException(
    "command failed (exit $exitCode): ${command.joinToString(" ")}\n${stderr.take(4000)}"
)
