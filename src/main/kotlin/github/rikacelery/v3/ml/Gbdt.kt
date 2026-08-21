package github.rikacelery.v3.ml

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Lightweight gradient-boosted trees (LightGBM-style) in pure JVM.
 * No native libs — works on Alpine/musl Docker images.
 */
class Gbdt(
    private val task: Task = Task.REGRESSION,
    private val nTrees: Int = 40,
    private val maxDepth: Int = 4,
    private val learningRate: Double = 0.08,
    private val minLeaf: Int = 8,
    private val featureFraction: Double = 0.85,
    private val l2: Double = 1.0,
    private val seed: Int = 42
) {
    enum class Task { REGRESSION, BINARY }

    @Serializable
    data class Node(
        val feature: Int = -1,
        val threshold: Double = 0.0,
        val left: Int = -1,
        val right: Int = -1,
        val value: Double = 0.0,
        val isLeaf: Boolean = true
    )

    @Serializable
    data class Tree(val nodes: List<Node>)

    @Serializable
    data class Model(
        val task: String,
        val baseScore: Double,
        val learningRate: Double,
        val trees: List<Tree>,
        val featureNames: List<String> = emptyList()
    )

    fun train(x: Array<DoubleArray>, y: DoubleArray, featureNames: List<String> = emptyList()): Model {
        require(x.isNotEmpty() && x.size == y.size) { "empty or mismatched training set" }
        val n = x.size
        val nFeat = x[0].size
        val rng = Random(seed)

        val base = when (task) {
            Task.REGRESSION -> y.average()
            Task.BINARY -> {
                val p = y.average().coerceIn(1e-6, 1 - 1e-6)
                ln(p / (1 - p))
            }
        }

        val pred = DoubleArray(n) { base }
        val trees = ArrayList<Tree>(nTrees)

        repeat(nTrees) {
            val grad = DoubleArray(n)
            val hess = DoubleArray(n)
            for (i in 0 until n) {
                when (task) {
                    Task.REGRESSION -> {
                        grad[i] = pred[i] - y[i]
                        hess[i] = 1.0
                    }
                    Task.BINARY -> {
                        val p = 1.0 / (1.0 + exp(-pred[i]))
                        grad[i] = p - y[i]
                        hess[i] = max(p * (1 - p), 1e-6)
                    }
                }
            }
            val featMask = BooleanArray(nFeat) { true }
            if (featureFraction < 1.0 && nFeat > 1) {
                val keep = max(1, (nFeat * featureFraction).toInt())
                val order = (0 until nFeat).shuffled(rng)
                featMask.fill(false)
                order.take(keep).forEach { featMask[it] = true }
            }
            val tree = buildTree(x, grad, hess, featMask)
            trees += tree
            for (i in 0 until n) {
                pred[i] += learningRate * predictTree(tree, x[i])
            }
        }
        return Model(task.name, base, learningRate, trees, featureNames)
    }

    fun predict(model: Model, features: DoubleArray): Double {
        var s = model.baseScore
        for (t in model.trees) s += model.learningRate * predictTree(t, features)
        return when (Task.valueOf(model.task)) {
            Task.REGRESSION -> s
            Task.BINARY -> 1.0 / (1.0 + exp(-s))
        }
    }

    private fun predictTree(tree: Tree, x: DoubleArray): Double {
        var i = 0
        while (true) {
            val n = tree.nodes[i]
            if (n.isLeaf) return n.value
            i = if (x[n.feature] <= n.threshold) n.left else n.right
        }
    }

    private fun buildTree(
        x: Array<DoubleArray>,
        grad: DoubleArray,
        hess: DoubleArray,
        featMask: BooleanArray
    ): Tree {
        val nodes = ArrayList<Node>()
        data class Job(val idxs: IntArray, val depth: Int, val parentSlot: Int)

        nodes += Node()
        val queue = ArrayDeque<Job>()
        queue.add(Job(IntArray(x.size) { it }, 0, 0))

        while (queue.isNotEmpty()) {
            val job = queue.removeFirst()
            val idxs = job.idxs
            val gSum = idxs.sumOf { grad[it].toDouble() }
            val hSum = idxs.sumOf { hess[it].toDouble() }
            val leafVal = -gSum / (hSum + l2)

            if (job.depth >= maxDepth || idxs.size < minLeaf * 2) {
                nodes[job.parentSlot] = Node(value = leafVal, isLeaf = true)
                continue
            }

            var bestGain = 0.0
            var bestFeat = -1
            var bestThr = 0.0
            var bestLeft: IntArray? = null
            var bestRight: IntArray? = null

            for (f in featMask.indices) {
                if (!featMask[f]) continue
                val vals = idxs.map { x[it][f] }.distinct().sorted()
                if (vals.size < 2) continue
                val step = max(1, vals.size / 16)
                var t = step
                while (t < vals.size) {
                    val thr = vals[t - 1]
                    val left = idxs.filter { x[it][f] <= thr }.toIntArray()
                    val right = idxs.filter { x[it][f] > thr }.toIntArray()
                    if (left.size < minLeaf || right.size < minLeaf) {
                        t += step
                        continue
                    }
                    val gl = left.sumOf { grad[it].toDouble() }
                    val hl = left.sumOf { hess[it].toDouble() }
                    val gr = right.sumOf { grad[it].toDouble() }
                    val hr = right.sumOf { hess[it].toDouble() }
                    val gain = score(gl, hl) + score(gr, hr) - score(gSum, hSum)
                    if (gain > bestGain) {
                        bestGain = gain
                        bestFeat = f
                        bestThr = thr
                        bestLeft = left
                        bestRight = right
                    }
                    t += step
                }
            }

            if (bestFeat < 0 || bestLeft == null || bestRight == null) {
                nodes[job.parentSlot] = Node(value = leafVal, isLeaf = true)
                continue
            }

            val leftIdx = nodes.size
            nodes += Node()
            val rightIdx = nodes.size
            nodes += Node()
            nodes[job.parentSlot] = Node(
                feature = bestFeat,
                threshold = bestThr,
                left = leftIdx,
                right = rightIdx,
                isLeaf = false
            )
            queue.add(Job(bestLeft, job.depth + 1, leftIdx))
            queue.add(Job(bestRight, job.depth + 1, rightIdx))
        }
        return Tree(nodes)
    }

    private fun score(g: Double, h: Double): Double = g * g / (h + l2)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun save(model: Model, file: java.io.File) {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(model))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }

        fun load(file: java.io.File): Model? {
            if (!file.exists()) return null
            return try {
                json.decodeFromString(Model.serializer(), file.readText())
            } catch (_: Exception) {
                null
            }
        }
    }
}
