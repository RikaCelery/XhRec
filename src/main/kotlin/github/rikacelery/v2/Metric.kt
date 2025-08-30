package github.rikacelery.v2

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Hashtable

object Metric {
    val metrics = Hashtable<Long, MetricItem>()
    val updaters = Hashtable<Long, MetricUpdater>()
    val names = Hashtable<Long, String>()
    val lock = Mutex()

    suspend fun newMetric(id: Long, name: String): MetricUpdater = lock.withLock {
        names[id] = name
        if (updaters.containsKey(id)) {
            updaters[id]!!.dispose()
        }
        MetricUpdater(metrics, id).also {
            updaters[id] = it
            metrics[id] = MetricItem()
        }
    }

    suspend fun removeMetric(id: Long) = lock.withLock(NonCancellable) {
        updaters.remove(id)?.dispose()
        metrics.remove(id)
    }

    suspend fun prometheus(): String = lock.withLock {
        val sb = StringBuilder()
        for ((id, metric) in metrics) {
            sb.append(
                """
                # HELP rikacelery_v2_total_segments_total Total segments
                # TYPE rikacelery_v2_total_segments_total counter
                rikacelery_v2_total_segments_total{name="${names.get(id) ?: id}"} ${metric.total}
            """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_downloading_segments_total Downloading segments
                # TYPE rikacelery_v2_downloading_segments_total counter
                rikacelery_v2_downloading_segments_total{name="${names.get(id) ?: id}"} ${metric.downloading}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_segment_id_current Current segment id
                # TYPE rikacelery_v2_segment_id_current gauge
                rikacelery_v2_segment_id_current{name="${names.get(id) ?: id}"} ${metric.segmentID}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_segment_missing_total Missing segments
                # TYPE rikacelery_v2_segment_missing_total counter
                rikacelery_v2_segment_missing_total{name="${names.get(id) ?: id}"} ${metric.segmentMissing}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_done_segments_total Done segments
                # TYPE rikacelery_v2_done_segments_total counter
                rikacelery_v2_done_segments_total{name="${names.get(id) ?: id}"} ${metric.done}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_success_proxied_total Success proxied segments
                # TYPE rikacelery_v2_success_proxied_total counter
                rikacelery_v2_success_proxied_total{name="${names.get(id) ?: id}"} ${metric.successProxied}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_success_direct_total Success direct segments
                # TYPE rikacelery_v2_success_direct_total counter
                rikacelery_v2_success_direct_total{name="${names.get(id) ?: id}"} ${metric.successDirect}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_failed_total Failed segments
                # TYPE rikacelery_v2_failed_total counter
                rikacelery_v2_failed_total{name="${names.get(id) ?: id}"} ${metric.failed}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_bytes_write_total Bytes write
                # TYPE rikacelery_v2_bytes_write_total counter
                rikacelery_v2_bytes_write_total{name="${names.get(id) ?: id}"} ${metric.bytesWrite}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_latency_ms_total Latency ms
                # TYPE rikacelery_v2_latency_ms_total gauge
                rikacelery_v2_latency_ms_total{name="${names.get(id) ?: id}"} ${metric.latencyMS}
                """.trimIndent()+"\n"
            )
            sb.append(
                """
                # HELP rikacelery_v2_refresh_latency_ms_total Refresh Latency ms
                # TYPE rikacelery_v2_refresh_latency_ms_total gauge
                rikacelery_v2_refresh_latency_ms_total{name="${names.get(id) ?: id}"} ${metric.refreshLatencyMS}
                """.trimIndent()+"\n"
            )
//            TODO:Loki
//            sb.append(
//                """
//                # HELP rikacelery_v2_quality_current Quality
//                # TYPE rikacelery_v2_quality_current gauge
//                rikacelery_v2_quality_current{name="${names.get(id) ?: id}"} ${metric.quality}
//                """.trimIndent()+"\n"
//            )
        }
        return sb.toString().trim()
    }
}

