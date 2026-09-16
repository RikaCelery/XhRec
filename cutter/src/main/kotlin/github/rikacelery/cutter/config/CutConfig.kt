package github.rikacelery.cutter.config

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import java.io.File
import java.time.ZoneId

/** Thrown when a required location was not supplied. */
class MissingOptionException(message: String) : IllegalArgumentException(message)

/**
 * Runtime configuration for XhCut.
 *
 * Everything is overridable from the command line so the container image stays
 * generic; the values below are the ones the deployment on the recording host uses.
 */
data class CutConfig(
    val port: Int,
    /** Directories scanned for recordings. Read-only. */
    val mediaRoots: List<File>,
    /** Where `-cutN.mp4` files and `.llc` projects are written. */
    val outputDir: File,
    /** Scratch space for preview segments, waveform PNGs and the library index. */
    val cacheDir: File,
    /**
     * Timezone used to interpret the local wall-clock stamp inside recording file
     * names. Event payloads carry UTC (`createdAt`), so this offset is what maps an
     * event onto a media timestamp.
     */
    val zone: ZoneId,
    val ffmpeg: String,
    val ffprobe: String,
    /** Seconds of source per HLS preview segment. */
    val previewSegmentSeconds: Int,
    /** Max ffmpeg processes doing preview transcodes at once. */
    val previewConcurrency: Int,
    /** Soft cap for the on-disk cache, in bytes. */
    val cacheLimitBytes: Long,
    /** Refuse exports below this much free space on [outputDir]. */
    val minFreeBytes: Long,
    /**
     * Directory *names* the scan will not descend into. Empty by default.
     *
     * Hidden directories are scanned like any other; this exists for callers who want a
     * specific one excluded without changing the scanner. The recording host's
     * `.unwanted` archive is the motivating case — XhRec routes files there precisely
     * because they were rejected, so a library that lists them is a library padded with
     * 1.1 TB of deliberately-discarded material.
     */
    val skipDirNames: Set<String> = emptySet(),
    val indexFileName: String = "index.json"
) {
    val indexFile: File get() = File(cacheDir, indexFileName)

    companion object {
        private const val DEFAULT_PORT = 8092
        private const val DEFAULT_ZONE = "Asia/Shanghai"

        /**
         * Refuses to continue rather than starting up misconfigured.
         *
         * This throws instead of exiting: a parser that calls `exitProcess` takes the whole
         * process down, which inside a test run means one bad argument kills the executor
         * and every other test with it. The CLI turns this into a usage message and exit
         * code 2 at the top level, where exiting is actually the right thing to do.
         */
        private fun fail(option: String): Nothing =
            throw MissingOptionException("$option (-m/--media, -o/--out and -c/--cache are all required)")

        fun parse(args: Array<String>): CutConfig {
            val options = Options()
                .addOption("p", "port", true, "HTTP port (default $DEFAULT_PORT)")
                .addOption("o", "out", true, "Output directory for cuts")
                .addOption("c", "cache", true, "Cache directory")
                .addOption("m", "media", true, "Media root (repeatable, comma separated)")
                .addOption("tz", "timezone", true, "Timezone for file-name stamps (default $DEFAULT_ZONE)")
                // Short and long form are the same word: the README documents `--ffmpeg` and
                // `--ffprobe`, and a single-name `addOption` only ever registered `-ffmpeg`.
                .addOption("ffmpeg", "ffmpeg", true, "ffmpeg binary")
                .addOption("ffprobe", "ffprobe", true, "ffprobe binary")
                .addOption("seg", "preview-segment", true, "Preview segment seconds")
                .addOption("jobs", "preview-concurrency", true, "Concurrent preview transcodes")
                .addOption("cache-limit-gb", true, "Cache size cap in GB")
                .addOption("min-free-gb", true, "Minimum free GB required to export")
                .addOption("skip-dirs", true, "Directory names the scan must not descend into (comma separated)")

            val cmd = DefaultParser().parse(options, args)

            fun int(name: String, fallback: Int) =
                cmd.getOptionValue(name)?.trim()?.toIntOrNull() ?: fallback

            fun long(name: String, fallback: Long) =
                cmd.getOptionValue(name)?.trim()?.toLongOrNull() ?: fallback

            // The three locations are required.
            //
            // They used to fall back to the paths this tool happens to occupy inside its
            // own container image. That made a missing `-m`/`-o`/`-c` look like a working
            // start: the process came up, scanned a directory that either does not exist
            // or belongs to something else, and served an empty library. A local default
            // standing in for real configuration is worse than no default, so the
            // container supplies these explicitly (see `entrypoint.sh`) and anything else
            // has to say what it means.
            val media = cmd.getOptionValue("media")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map(::File)
                ?.takeIf { it.isNotEmpty() }
                ?: fail("missing or empty -m/--media <dir> (repeatable, comma separated)")

            val outputDir = cmd.getOptionValue("out")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: fail("missing or empty -o/--out <dir>")

            val cacheDir = cmd.getOptionValue("cache")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: fail("missing or empty -c/--cache <dir>")

            return CutConfig(
                port = int("port", DEFAULT_PORT),
                mediaRoots = media,
                outputDir = outputDir,
                cacheDir = cacheDir,
                zone = runCatching { ZoneId.of(cmd.getOptionValue("timezone") ?: DEFAULT_ZONE) }
                    .getOrElse { ZoneId.of(DEFAULT_ZONE) },
                ffmpeg = cmd.getOptionValue("ffmpeg") ?: "ffmpeg",
                ffprobe = cmd.getOptionValue("ffprobe") ?: "ffprobe",
                previewSegmentSeconds = int("preview-segment", 4).coerceIn(2, 10),
                previewConcurrency = int("preview-concurrency", 3).coerceIn(1, 16),
                cacheLimitBytes = long("cache-limit-gb", 8L) * 1024 * 1024 * 1024,
                minFreeBytes = long("min-free-gb", 2L) * 1024 * 1024 * 1024,
                skipDirNames = (cmd.getOptionValue("skip-dirs") ?: System.getenv("XHCUT_SKIP_DIRS").orEmpty())
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
    }
}
