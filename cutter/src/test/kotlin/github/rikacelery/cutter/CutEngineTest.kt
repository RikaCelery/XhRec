package github.rikacelery.cutter

import github.rikacelery.cutter.config.CutConfig
import github.rikacelery.cutter.ffmpeg.CutEngine
import github.rikacelery.cutter.ffmpeg.SnapMode
import github.rikacelery.cutter.media.MediaEntry
import github.rikacelery.cutter.project.CutProject
import github.rikacelery.cutter.project.CutSegment
import github.rikacelery.cutter.project.Llc
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutEngineTest {

    private val engine = CutEngine(
        CutConfig(
            port = 0,
            mediaRoots = emptyList(),
            outputDir = File("."),
            cacheDir = File("."),
            zone = ZoneId.of("UTC"),
            ffmpeg = "ffmpeg",
            ffprobe = "ffprobe",
            previewSegmentSeconds = 4,
            previewConcurrency = 1,
            cacheLimitBytes = 1,
            minFreeBytes = 0
        )
    )

    private fun entry(name: String) = MediaEntry(
        id = "x", relPath = name, fileName = name, room = "r",
        sizeBytes = 0, modifiedAt = 0, startEpochSeconds = null,
        nameDurationSeconds = 100, eventRelPath = null
    )

    @Test
    fun `timecode uses the dotted style of the existing corpus`() {
        assertEquals("00.14.36.939", engine.timecode(876.939))
        assertEquals("02.56.23.000", engine.timecode((2 * 3600 + 56 * 60 + 23).toDouble()))
        assertEquals("01.00.00.500", engine.timecode(3600.5))
    }

    @Test
    fun `output name matches the corpus convention`() {
        val name = engine.outputName(
            entry("Baby_sweet--2026_01_01_12_37_25-00h42m35s.fixed.mp4"),
            876.939, 980.219, 1
        )
        assertEquals(
            "Baby_sweet--2026_01_01_12_37_25-00h42m35s.fixed-00.14.36.939-00.16.20.219-cut1.mp4",
            name
        )
    }

    @Test
    fun `cut numbering restarts at one per source and skips taken numbers`() {
        val dir = Files.createTempDirectory("cuts").toFile().apply { deleteOnExit() }
        val e = entry("room-2026-01-02-030405-00h01m00s.mp4")
        assertEquals(1, engine.nextCutNumber(dir, e))

        File(dir, "room-2026-01-02-030405-00h01m00s-00.00.10.000-00.00.20.000-cut1.mp4").writeText("x")
        File(dir, "room-2026-01-02-030405-00h01m00s-00.00.30.000-00.00.40.000-cut2.mp4").writeText("x")
        assertEquals(3, engine.nextCutNumber(dir, e))

        // A different source keeps its own numbering.
        assertEquals(1, engine.nextCutNumber(dir, entry("other-2026-01-02-030405-00h01m00s.mp4")))
    }

    /**
     * Pins the ffprobe wire format.
     *
     * The replaced implementation used `-skip_frame nokey`, which the container's
     * ffprobe 4.4.2 answers with an empty result while the host's 8.1.2 answers
     * correctly. Nothing noticed, so both the timeline ticks and the cut engine's
     * keyframe snapping quietly did nothing. This test at least pins what a correct
     * answer looks like.
     */
    @Test
    fun `parses keyframes from packet flags`() {
        // Verbatim sample from `ffprobe -show_entries packet=pts_time,flags -of csv=p=0`.
        val csv = """
            11972.066667,K_
            11972.100000,__
            11972.133333,__
            11974.066667,K_
            11976.066667,K_
        """.trimIndent()
        assertEquals(listOf(11972.066667, 11974.066667, 11976.066667), engine.parseKeyframePackets(csv))
        assertEquals(emptyList(), engine.parseKeyframePackets(""))
        // A keyframe with extra flags still counts, and junk lines are skipped.
        assertEquals(listOf(5.0), engine.parseKeyframePackets("5.000000,K__\nnot,a,packet"))
    }

    @Test
    fun `snap modes pick the right keyframe`() {
        val frames = listOf(10.0, 12.0, 14.0, 16.0)
        assertEquals(12.0, engine.snap(frames, 13.0, SnapMode.BEFORE))
        assertEquals(14.0, engine.snap(frames, 13.0, SnapMode.AFTER))
        assertEquals(12.0, engine.snap(frames, 12.9, SnapMode.NEAREST))
        assertEquals(14.0, engine.snap(frames, 13.1, SnapMode.NEAREST))
        // Before the first keyframe clamps forward, past the last clamps back.
        assertEquals(10.0, engine.snap(frames, 5.0, SnapMode.BEFORE))
        assertEquals(16.0, engine.snap(frames, 99.0, SnapMode.AFTER))
        // No keyframes known: the request passes through untouched.
        assertEquals(13.0, engine.snap(emptyList(), 13.0, SnapMode.BEFORE))
    }

    /**
     * The export command must be a pure stream copy. This is the guarantee that the
     * tool never silently re-encodes, so both directions are asserted: the real
     * command passes, and any command with an encoder knob is rejected.
     */
    @Test
    fun `the cut command is a stream copy and encoding is refused`() {
        val command = engine.buildCutCommand(
            File("/src/a.mp4"), File("/out/b.mp4"), 100.0, 30.0, hasAudio = true
        )
        assertContains(command, "copy")
        assertTrue(command.containsAll(listOf("-c:v", "copy", "-c:a", "copy")))
        // -ss must precede -i so the cut starts exactly at the resolved keyframe.
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"))
        engine.assertNoEncoding(command)

        for (bad in listOf(
            listOf("ffmpeg", "-i", "a", "-c:v", "libx264", "out.mp4"),
            listOf("ffmpeg", "-i", "a", "-crf", "23", "out.mp4"),
            listOf("ffmpeg", "-i", "a", "-vf", "scale=640:-2", "out.mp4"),
            listOf("ffmpeg", "-i", "a", "-c:a", "aac", "out.mp4")
        )) {
            assertFailsWith<IllegalArgumentException>("should reject: $bad") {
                engine.assertNoEncoding(bad)
            }
        }
    }

    @Test
    fun `a video without audio gets no audio mapping`() {
        val command = engine.buildCutCommand(
            File("/src/a.mp4"), File("/out/b.mp4"), 0.0, 10.0, hasAudio = false
        )
        assertTrue(command.none { it == "-c:a" })
        engine.assertNoEncoding(command)
    }
}

/**
 * `.llc` handling. The files are JSON5, and `end` is overloaded as the marker flag —
 * both are easy to get wrong in ways that only show up as silently wrong exports.
 */
class LlcTest {

    /** Verbatim from `/media/nas/.../cuts/anny54784-...-proj.llc`. */
    private val realProject = """
        {
          version: 2,
          mediaFileName: 'anny54784-2026_01_04_13_23_56-01h52m07s.fixed.mp4',
          cutSegments: [
            {
              start: 10972.034844,
              end: 12028.152043,
              name: '',
              selected: true,
            },
            {
              start: 18104.406248,
              end: 18743.083932,
              name: '',
              selected: true,
            },
            {
              start: 20857.434945,
              name: '',
              selected: true,
            },
          ],
        }
    """.trimIndent()

    @Test
    fun `parses a real JSON5 project file`() {
        val project = Llc.parse(realProject)
        assertEquals(2, project.version)
        assertEquals("anny54784-2026_01_04_13_23_56-01h52m07s.fixed.mp4", project.mediaFileName)
        assertEquals(3, project.segments.size)
        assertEquals(10972.034844, project.segments[0].start)
        assertEquals(12028.152043, project.segments[0].end)
    }

    /**
     * The third segment has no `end`. LosslessCut treats that as a marker with zero
     * length, **not** as "cut to end of file" — the distinction decides whether a
     * stray marker becomes a multi-hour export.
     */
    @Test
    fun `a segment without end is a marker and is not exported`() {
        val project = Llc.parse(realProject)
        val marker = project.segments[2]
        assertTrue(marker.isMarker)
        assertEquals(0.0, marker.duration)
        assertEquals(2, project.exportable.size)
        assertTrue(project.exportable.none { it.isMarker })
    }

    @Test
    fun `round trips through the JSON5 writer`() {
        val original = CutProject(
            mediaFileName = "a.mp4",
            segments = listOf(
                CutSegment(0.0, 12.5, "Intro", mapOf("camera" to "A"), true),
                CutSegment(60.0, null, "Marker 1", emptyMap(), true),
                CutSegment(120.0, 130.0, "unselected", emptyMap(), false)
            )
        )
        val reparsed = Llc.parse(Llc.write(original))
        assertEquals(original.mediaFileName, reparsed.mediaFileName)
        assertEquals(3, reparsed.segments.size)
        assertEquals(12.5, reparsed.segments[0].end)
        assertEquals(mapOf("camera" to "A"), reparsed.segments[0].tags)
        assertTrue(reparsed.segments[1].isMarker)
        assertNull(reparsed.segments[1].end)
        assertEquals(false, reparsed.segments[2].selected)
        // Only the selected, non-marker segment exports.
        assertEquals(1, reparsed.exportable.size)
        assertEquals("Intro", reparsed.exportable.single().name)
    }

    @Test
    fun `upgrades a version 1 project by defaulting the missing start`() {
        val v1 = """
            { version: 1, mediaFileName: 'old.mp4', cutSegments: [ { end: 5.5, name: 'x' } ] }
        """.trimIndent()
        val project = Llc.parse(v1)
        assertEquals(1, project.version)
        assertEquals(0.0, project.segments.single().start)
        assertEquals(5.5, project.segments.single().end)
    }

    @Test
    fun `tolerates comments and trailing commas`() {
        val text = """
            {
              // a comment
              version: 2,
              mediaFileName: 'x.mp4',
              cutSegments: [
                { start: 1, end: 2, name: 'ok', }, /* block comment */
              ],
            }
        """.trimIndent()
        val project = Llc.parse(text)
        assertEquals(1, project.segments.size)
        assertEquals("ok", project.segments.single().name)
    }

    /** Quotes and separators inside strings must survive the JSON5 conversion. */
    @Test
    fun `preserves awkward characters in names`() {
        val project = CutProject(
            mediaFileName = "x.mp4",
            segments = listOf(CutSegment(1.0, 2.0, "it's a, \"test\": {ok}", emptyMap(), true))
        )
        val reparsed = Llc.parse(Llc.write(project))
        assertEquals("it's a, \"test\": {ok}", reparsed.segments.single().name)
    }

    @Test
    fun `rejects text that is not a project`() {
        assertFailsWith<IllegalArgumentException> { Llc.parse("""{"version":2}""") }
    }

    @Test
    fun `warns when a project exceeds the LosslessCut segment cap`() {
        val many = CutProject(
            mediaFileName = "x.mp4",
            segments = (1..2001).map { CutSegment(it.toDouble(), it + 1.0) }
        )
        assertNotNull(Llc.segmentCountWarning(many))
        assertNull(Llc.segmentCountWarning(CutProject(mediaFileName = "x.mp4")))
    }
}
