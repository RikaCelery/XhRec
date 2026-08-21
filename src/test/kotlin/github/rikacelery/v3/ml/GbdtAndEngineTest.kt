package github.rikacelery.v3.ml

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class GbdtAndEngineTest {
    private lateinit var dir: File

    @BeforeEach
    fun setUp() {
        dir = createTempDirectory("ml-test").toFile()
        PredictionSampleStore.configure(dir)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun gbdt_regression_fits_simple_signal() {
        val n = 200
        val x = Array(n) { i ->
            val h = (i % 24).toDouble()
            doubleArrayOf(h, (i % 7).toDouble(), if (i % 7 >= 5) 1.0 else 0.0)
        }
        val y = DoubleArray(n) { i ->
            val h = (i % 24).toDouble()
            100.0 + h * 10.0 + (i % 7) * 3.0
        }
        val model = Gbdt(task = Gbdt.Task.REGRESSION, nTrees = 30, maxDepth = 3, minLeaf = 4)
            .train(x, y, listOf("h", "d", "w"))
        val gbdt = Gbdt(task = Gbdt.Task.REGRESSION)
        var mae = 0.0
        repeat(50) { i ->
            val pred = gbdt.predict(model, x[i])
            mae += abs(pred - y[i])
        }
        mae /= 50
        assertTrue(mae < 25.0, "MAE too high: $mae")
        val f = File(dir, "m.json")
        Gbdt.save(model, f)
        val loaded = Gbdt.load(f)
        assertNotNull(loaded)
        assertEquals(model.trees.size, loaded.trees.size)
    }

    @Test
    fun gbdt_binary_separates_classes() {
        val n = 160
        val x = Array(n) { i ->
            val h = (i % 24).toDouble()
            doubleArrayOf(h, if (h in 18.0..22.0) 1.0 else 0.0)
        }
        val y = DoubleArray(n) { i ->
            val h = (i % 24).toDouble()
            if (h in 18.0..22.0) 1.0 else 0.0
        }
        val model = Gbdt(task = Gbdt.Task.BINARY, nTrees = 25, maxDepth = 3, minLeaf = 4)
            .train(x, y)
        val gbdt = Gbdt(task = Gbdt.Task.BINARY)
        val pNight = gbdt.predict(model, doubleArrayOf(20.0, 1.0))
        val pMorning = gbdt.predict(model, doubleArrayOf(9.0, 0.0))
        assertTrue(pNight > 0.55, "night p=$pNight")
        assertTrue(pMorning < 0.45, "morning p=$pMorning")
    }

    @Test
    fun sample_store_persists_jsonl() {
        PredictionSampleStore.recordCdn(
            PredictionSampleStore.CdnSample(
                ts = 1, host = "a.com", durationMs = 120.0, hour = 10, dow = 1, isWeekend = 0,
                recentAvg = 100.0, recentStd = 10.0, failures = 0, probeMs = 90.0, success = 1
            )
        )
        // force persist
        repeat(49) {
            PredictionSampleStore.recordCdn(
                PredictionSampleStore.CdnSample(
                    ts = it.toLong(), host = "b.com", durationMs = 200.0, hour = 11, dow = 2, isWeekend = 0,
                    recentAvg = 180.0, recentStd = 20.0, failures = 0, probeMs = -1.0, success = 1
                )
            )
        }
        PredictionSampleStore.flush()
        assertTrue(File(dir, "ml-cdn-samples.jsonl").exists())
        assertTrue(PredictionSampleStore.cdnCount() >= 50)
    }
}
