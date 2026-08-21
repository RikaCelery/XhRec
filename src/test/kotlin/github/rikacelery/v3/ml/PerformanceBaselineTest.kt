package github.rikacelery.v3.ml

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class PerformanceBaselineTest {

    private val rng = Random(42)

    private fun cdnDataset(n: Int): Pair<Array<DoubleArray>, DoubleArray> {
        val x = Array(n) { i ->
            doubleArrayOf(
                (i % 24).toDouble(),
                (i % 7).toDouble(),
                if ((i % 7) >= 5) 1.0 else 0.0,
                (rng.nextDouble() * 100).toDouble(),
                rng.nextDouble() * 300,
                rng.nextDouble() * 60,
                rng.nextInt(5).toDouble(),
                if (rng.nextDouble() < 0.5) -1.0 else rng.nextDouble() * 200,
                rng.nextDouble() * 300,
                rng.nextDouble() * 300,
                if (rng.nextDouble() < 0.9) 1.0 else 0.0
            )
        }
        val y = DoubleArray(n) { i ->
            100.0 + (i % 24) * 8.0 + rng.nextDouble() * 40.0
        }
        return x to y
    }

    private fun fmtMs(ms: Long): String = when {
        ms < 1 -> "<1ms"
        ms < 1000 -> ms.toString() + "ms"
        else -> "%.2fs".format(ms / 1000.0)
    }

    @Test
    fun baseline_train_and_infer() {
        val sizes = intArrayOf(500, 1000, 2000, 4000)
        println()
        println("=== GBDT TRAIN BASELINE (regression, 40 trees, depth 4, minLeaf 6) ===")
        var lastModel: Gbdt.Model? = null
        for (n in sizes) {
            val (x, y) = cdnDataset(n)
            val g = Gbdt(task = Gbdt.Task.REGRESSION, nTrees = 40, maxDepth = 4, minLeaf = 6)
            val ms = measureTimeMillis {
                lastModel = g.train(x, y, List(11) { "f" + it })
            }
            println("  train n=" + n.toString().padStart(5) + "  ->  " + fmtMs(ms))
        }

        val model = lastModel!!
        val g = Gbdt(task = Gbdt.Task.REGRESSION)
        val (tx, _) = cdnDataset(1)
        val feature = tx[0]
        repeat(1000) { g.predict(model, feature) }

        val iters = 200_000
        val nanos = measureNanoTime {
            repeat(iters) { g.predict(model, feature) }
        }
        val perPredictNs = nanos.toDouble() / iters
        val perPredictUs = perPredictNs / 1000.0
        val throughput = 1_000_000_000.0 / perPredictNs
        println()
        println("=== GBDT INFER BASELINE ===")
        println("  trees=" + model.trees.size + ", nodes total=" + model.trees.sumOf { it.nodes.size })
        println("  predict: " + "%.2f".format(perPredictUs) + " us/op  (" + "%.0f".format(throughput) + " ops/s)")
    }

    @Test
    fun baseline_binary_and_persist() {
        val n = 2000
        val x = Array(n) { i ->
            val h = (i % 24).toDouble()
            doubleArrayOf(
                h,
                (i % 7).toDouble(),
                if ((i % 7) >= 5) 1.0 else 0.0,
                rng.nextDouble() * 100,
                rng.nextDouble(),
                rng.nextDouble(),
                rng.nextDouble(),
                rng.nextDouble() * 720,
                kotlin.math.ln((rng.nextInt(50) + 1).toDouble())
            )
        }
        val y = DoubleArray(n) { i -> if ((i % 24) in 19..22) 1.0 else 0.0 }

        val dir = createTempDirectory("ml-perf").toFile()
        try {
            val g = Gbdt(task = Gbdt.Task.BINARY, nTrees = 40, maxDepth = 4, minLeaf = 6)
            val ms = measureTimeMillis { g.train(x, y, List(9) { "f" + it }) }
            println()
            println("=== BINARY TRAIN + PERSIST BASELINE (n=" + n + ") ===")
            println("  train binary n=" + n + "  ->  " + fmtMs(ms))

            val model = g.train(x, y, List(9) { "f" + it })
            val saveMs = measureTimeMillis { Gbdt.save(model, File(dir, "m.json")) }
            val fileSize = File(dir, "m.json").length()
            println("  save model  ->  " + fmtMs(saveMs) + " (" + "%.1f".format(fileSize / 1024.0) + " KB)")

            val loadMs = measureTimeMillis { Gbdt.load(File(dir, "m.json")) }
            println("  load model  ->  " + fmtMs(loadMs))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun baseline_sample_store_write() {
        val dir = createTempDirectory("ml-perf-store").toFile()
        try {
            PredictionSampleStore.configure(dir)
            val n = 8000
            val ms = measureTimeMillis {
                repeat(n) { i ->
                    PredictionSampleStore.recordCdn(
                        PredictionSampleStore.CdnSample(
                            ts = i.toLong(), host = "cdn-" + (i % 3), durationMs = 100.0,
                            hour = i % 24, dow = i % 7, isWeekend = 0,
                            recentAvg = 90.0, recentStd = 5.0, failures = 0, probeMs = -1.0, success = 1
                        )
                    )
                }
                PredictionSampleStore.flush()
            }
            println()
            println("=== SAMPLE STORE BASELINE ===")
            println("  record+flush " + n + " samples  ->  " + fmtMs(ms))
            val size = File(dir, "ml-cdn-samples.jsonl").length()
            println("  file size  ->  " + "%.1f".format(size / 1024.0) + " KB")
        } finally {
            PredictionSampleStore.clear(deleteFiles = true)
            dir.deleteRecursively()
        }
    }
}
