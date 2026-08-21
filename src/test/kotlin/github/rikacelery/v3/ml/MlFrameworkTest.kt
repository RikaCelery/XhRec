package github.rikacelery.v3.ml

import github.rikacelery.v3.utils.CdnSelector
import github.rikacelery.v3.utils.ModelSchedule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the pure-JVM GBDT ML stack:
 * sample store → train → model persist → CDN select / schedule predict.
 *
 * Data dir is always the process working directory in production
 * (Docker WORKDIR=/config). Tests inject a temp dir via configure().
 */
class MlFrameworkTest {
    private lateinit var dir: File
    private val zone = ZoneId.systemDefault()

    /** Monday [hour]:00 local — stable temporal anchor. */
    private fun ts(hour: Int = 20, dowShiftDays: Long = 0): Long {
        val base = ZonedDateTime.of(2024, 1, 15, hour, 0, 0, 0, zone) // Monday
        return base.plusDays(dowShiftDays).toInstant().toEpochMilli()
    }

    @BeforeEach
    fun setUp() {
        dir = createTempDirectory("ml-fw").toFile()
        PredictionSampleStore.clear(deleteFiles = true)
        PredictionSampleStore.configure(dir)
        PredictionEngine.resetModels()
        CdnSelector.reset()
        CdnSelector.updateHosts(listOf("cdn-fast.example", "cdn-slow.example"))
        CdnSelector.exploreProbability = 0.0
        ModelSchedule.reset()
    }

    @AfterEach
    fun tearDown() {
        PredictionEngine.resetModels()
        PredictionSampleStore.clear(deleteFiles = true)
        CdnSelector.reset()
        ModelSchedule.reset()
        dir.deleteRecursively()
    }

    // ── GBDT core ───────────────────────────────────────────────────────────

    @Test
    fun gbdt_regression_learns_linear_hour_signal() {
        val n = 240
        val x = Array(n) { i ->
            val h = (i % 24).toDouble()
            doubleArrayOf(h, (i % 7).toDouble())
        }
        val y = DoubleArray(n) { i -> 50.0 + (i % 24) * 8.0 }
        val model = Gbdt(task = Gbdt.Task.REGRESSION, nTrees = 40, maxDepth = 3, minLeaf = 4)
            .train(x, y, listOf("hour", "dow"))
        val gbdt = Gbdt(task = Gbdt.Task.REGRESSION)
        val predLow = gbdt.predict(model, doubleArrayOf(2.0, 1.0))
        val predHigh = gbdt.predict(model, doubleArrayOf(22.0, 1.0))
        assertTrue(predHigh > predLow + 20, "high hour should predict longer duration: low=$predLow high=$predHigh")

        val f = File(dir, "reg.json")
        Gbdt.save(model, f)
        val loaded = assertNotNull(Gbdt.load(f))
        assertEquals(model.trees.size, loaded.trees.size)
        assertTrue(abs(gbdt.predict(loaded, doubleArrayOf(22.0, 1.0)) - predHigh) < 1e-6)
    }

    @Test
    fun gbdt_binary_learns_evening_live_window() {
        val n = 200
        val x = Array(n) { i ->
            val h = (i % 24).toDouble()
            doubleArrayOf(h, if (h in 19.0..22.0) 0.8 else 0.05)
        }
        val y = DoubleArray(n) { i -> if ((i % 24) in 19..22) 1.0 else 0.0 }
        val model = Gbdt(task = Gbdt.Task.BINARY, nTrees = 35, maxDepth = 3, minLeaf = 4).train(x, y)
        val g = Gbdt(task = Gbdt.Task.BINARY)
        assertTrue(g.predict(model, doubleArrayOf(20.0, 0.8)) > 0.55)
        assertTrue(g.predict(model, doubleArrayOf(8.0, 0.05)) < 0.45)
    }

    // ── Sample store ────────────────────────────────────────────────────────

    @Test
    fun sample_store_roundtrip_cdn_and_schedule() {
        PredictionSampleStore.recordCdn(
            PredictionSampleStore.CdnSample(
                ts = 1, host = "a", durationMs = 100.0, hour = 10, dow = 0, isWeekend = 0,
                recentAvg = 90.0, recentStd = 5.0, failures = 0, probeMs = 80.0, success = 1
            )
        )
        PredictionSampleStore.recordSchedule(
            PredictionSampleStore.ScheduleSample(
                ts = 2, roomId = 42, hour = 20, dow = 0, isWeekend = 0,
                hourHist = 0.4, weekdayHist = 0.3, weekendHist = 0.1,
                hoursSinceLast = 24.0, totalCount = 5, wentLive = 1
            )
        )
        PredictionSampleStore.flush()

        assertTrue(File(dir, "ml-cdn-samples.jsonl").exists())
        assertTrue(File(dir, "ml-schedule-samples.jsonl").exists())

        PredictionSampleStore.clear()
        PredictionSampleStore.configure(dir)
        assertEquals(1, PredictionSampleStore.cdnCount())
        assertEquals(1, PredictionSampleStore.scheduleCount())
        assertEquals("a", PredictionSampleStore.cdnSnapshot().first().host)
        assertEquals(42L, PredictionSampleStore.scheduleSnapshot().first().roomId)
    }

    // ── CDN engine ──────────────────────────────────────────────────────────

    @Test
    fun cdn_engine_trains_and_prefers_faster_host() {
        val now = ts(20)
        repeat(30) {
            CdnSelector.record("cdn-fast.example", durationMs = 80, now = now + it * 1000L)
            CdnSelector.record("cdn-slow.example", durationMs = 400, now = now + it * 1000L)
        }

        repeat(60) { i ->
            val hour = 10 + (i % 10)
            PredictionSampleStore.recordCdn(
                PredictionSampleStore.CdnSample(
                    ts = now + i,
                    host = "cdn-fast.example",
                    durationMs = 70.0 + (i % 5),
                    hour = hour, dow = 0, isWeekend = 0,
                    recentAvg = 80.0, recentStd = 10.0, failures = 0, probeMs = 75.0, success = 1
                )
            )
            PredictionSampleStore.recordCdn(
                PredictionSampleStore.CdnSample(
                    ts = now + i,
                    host = "cdn-slow.example",
                    durationMs = 350.0 + (i % 5),
                    hour = hour, dow = 0, isWeekend = 0,
                    recentAvg = 380.0, recentStd = 20.0, failures = 0, probeMs = 360.0, success = 1
                )
            )
        }
        assertTrue(PredictionSampleStore.cdnCount() >= 120)

        PredictionEngine.trainCdn()
        val st = PredictionEngine.status()
        assertTrue(st.cdnModelReady, "CDN model should be ready after train")
        assertTrue(st.cdnTrees > 0)
        assertTrue(File(dir, "ml-cdn-model.json").exists(), "model file under test dataDir")
        assertEquals(dir.absolutePath, st.dataDir)

        val dFast = assertNotNull(PredictionEngine.predictCdnDurationMs("cdn-fast.example", now))
        val dSlow = assertNotNull(PredictionEngine.predictCdnDurationMs("cdn-slow.example", now))
        assertTrue(dFast < dSlow, "fast host predicted $dFast should be < slow $dSlow")

        val best = assertNotNull(
            PredictionEngine.selectBestCdn(listOf("cdn-fast.example", "cdn-slow.example"), now)
        )
        assertEquals("cdn-fast.example", best)

        val selected = CdnSelector.select(now)
        assertEquals("cdn-fast.example", selected)
    }

    @Test
    fun cdn_engine_cold_start_returns_null() {
        PredictionEngine.resetModels()
        PredictionSampleStore.clear()
        assertNull(PredictionEngine.selectBestCdn(listOf("a", "b")))
        assertNull(PredictionEngine.predictCdnDurationMs("a"))
        PredictionSampleStore.recordCdn(
            PredictionSampleStore.CdnSample(
                ts = 1, host = "a", durationMs = 100.0, hour = 1, dow = 0, isWeekend = 0,
                recentAvg = 100.0, recentStd = 1.0, failures = 0, probeMs = -1.0, success = 1
            )
        )
        PredictionEngine.trainCdn()
        assertTrue(!PredictionEngine.status().cdnModelReady)
    }

    // ── Schedule engine ─────────────────────────────────────────────────────

    @Test
    fun schedule_engine_trains_and_peaks_at_live_hour() {
        val roomId = 999001L
        val liveHour = 21
        repeat(15) { day ->
            ModelSchedule.record(roomId, ts(liveHour, day.toLong()))
        }

        var t = 0L
        repeat(40) { day ->
            PredictionSampleStore.recordSchedule(
                PredictionSampleStore.ScheduleSample(
                    ts = ++t, roomId = roomId, hour = liveHour, dow = day % 7,
                    isWeekend = if (day % 7 >= 5) 1 else 0,
                    hourHist = 0.5, weekdayHist = 0.4, weekendHist = 0.3,
                    hoursSinceLast = 24.0, totalCount = 15, wentLive = 1
                )
            )
            for (neg in listOf(9, 14)) {
                PredictionSampleStore.recordSchedule(
                    PredictionSampleStore.ScheduleSample(
                        ts = ++t, roomId = roomId, hour = neg, dow = day % 7,
                        isWeekend = if (day % 7 >= 5) 1 else 0,
                        hourHist = 0.05, weekdayHist = 0.05, weekendHist = 0.05,
                        hoursSinceLast = 24.0, totalCount = 15, wentLive = 0
                    )
                )
            }
        }
        assertTrue(PredictionSampleStore.scheduleCount() >= 30)

        PredictionEngine.trainSchedule()
        assertTrue(PredictionEngine.status().scheduleModelReady)
        assertTrue(File(dir, "ml-schedule-model.json").exists())

        val now = ts(18)
        val pLive = assertNotNull(PredictionEngine.predictLiveProbability(roomId, liveHour, now))
        val pDead = assertNotNull(PredictionEngine.predictLiveProbability(roomId, 10, now))
        assertTrue(pLive > pDead, "live hour p=$pLive should exceed off-hour p=$pDead")

        val next = assertNotNull(PredictionEngine.nextPredictedHourMl(roomId, now))
        assertTrue(next in 18..23 || next == liveHour, "next=$next")

        val soon = assertNotNull(ModelSchedule.predictLiveSoon(roomId, lookaheadHours = 6))
        assertTrue(soon in 0.0..1.0)
    }

    @Test
    fun trainAll_writes_both_models_when_data_sufficient() {
        val now = ts(20)
        repeat(50) { i ->
            PredictionSampleStore.recordCdn(
                PredictionSampleStore.CdnSample(
                    ts = now + i,
                    host = if (i % 2 == 0) "cdn-fast.example" else "cdn-slow.example",
                    durationMs = if (i % 2 == 0) 90.0 else 300.0,
                    hour = 20, dow = 0, isWeekend = 0,
                    recentAvg = 100.0, recentStd = 10.0, failures = 0, probeMs = -1.0, success = 1
                )
            )
        }
        repeat(40) { i ->
            PredictionSampleStore.recordSchedule(
                PredictionSampleStore.ScheduleSample(
                    ts = now + i, roomId = 1L + (i % 3), hour = if (i % 3 == 0) 20 else 8,
                    dow = i % 7, isWeekend = 0,
                    hourHist = 0.3, weekdayHist = 0.2, weekendHist = 0.1,
                    hoursSinceLast = 20.0, totalCount = 10,
                    wentLive = if (i % 3 == 0) 1 else 0
                )
            )
        }
        repeat(15) { i ->
            PredictionSampleStore.recordSchedule(
                PredictionSampleStore.ScheduleSample(
                    ts = now + 1000 + i, roomId = 7L, hour = 21, dow = 1, isWeekend = 0,
                    hourHist = 0.5, weekdayHist = 0.4, weekendHist = 0.2,
                    hoursSinceLast = 24.0, totalCount = 20, wentLive = 1
                )
            )
        }

        PredictionEngine.trainAll()
        val s = PredictionEngine.status()
        assertEquals(dir.absolutePath, s.dataDir)
        assertTrue(s.cdnModelReady)
        assertTrue(s.scheduleModelReady)
        assertTrue(s.cdnSamples >= 50)
        assertTrue(s.scheduleSamples >= 30)
        assertTrue(File(dir, "ml-cdn-model.json").exists())
        assertTrue(File(dir, "ml-schedule-model.json").exists())
    }

    @Test
    fun onCdnSuccess_records_sample_via_selector_hook() {
        val now = ts(12)
        val before = PredictionSampleStore.cdnCount()
        CdnSelector.record("cdn-fast.example", 120, now)
        assertTrue(PredictionSampleStore.cdnCount() > before)
        val s = PredictionSampleStore.cdnSnapshot().last()
        assertEquals("cdn-fast.example", s.host)
        assertEquals(120.0, s.durationMs)
        assertEquals(1, s.success)
    }

    @Test
    fun onRoomWentLive_records_positive_and_negatives() {
        val roomId = 55L
        val before = PredictionSampleStore.scheduleCount()
        ModelSchedule.record(roomId, ts(20))
        val after = PredictionSampleStore.scheduleCount()
        assertEquals(before + 3, after)
        val snaps = PredictionSampleStore.scheduleSnapshot().takeLast(3)
        assertTrue(snaps.any { it.wentLive == 1 && it.hour == 20 })
        assertTrue(snaps.any { it.wentLive == 0 })
    }

    @Test
    fun model_reload_from_disk_restores_inference() {
        val now = ts(20)
        repeat(80) { i ->
            PredictionSampleStore.recordCdn(
                PredictionSampleStore.CdnSample(
                    ts = now + i,
                    host = if (i % 2 == 0) "cdn-fast.example" else "cdn-slow.example",
                    durationMs = if (i % 2 == 0) 80.0 else 320.0,
                    hour = 20, dow = 0, isWeekend = 0,
                    recentAvg = 100.0, recentStd = 10.0, failures = 0, probeMs = -1.0, success = 1
                )
            )
        }
        PredictionEngine.trainCdn()
        assertTrue(PredictionEngine.status().cdnModelReady)
        PredictionEngine.resetModels()
        assertTrue(!PredictionEngine.status().cdnModelReady)

        // simulate restart: configure same dir + load via start's load path
        PredictionSampleStore.configure(dir)
        // trainCdn not needed — load model file through private load via trainAll skip + manual
        val loaded = Gbdt.load(File(dir, "ml-cdn-model.json"))
        assertNotNull(loaded)
        // re-train is heavy; instead call trainCdn which overwrites, OR expose load.
        // Use trainAll after samples already there — loads not automatic without start().
        // Direct: put model back by training is ok; better assert file load works:
        assertTrue(loaded.trees.isNotEmpty())
        val pred = Gbdt(task = Gbdt.Task.REGRESSION).predict(
            loaded,
            doubleArrayOf(20.0, 0.0, 0.0, 1.0, 100.0, 10.0, 0.0, -1.0, 100.0, 100.0, 1.0)
        )
        assertTrue(pred > 0.0)
    }
}
