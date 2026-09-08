package github.rikacelery.v3.data

sealed class DownloadResult {
    data class Success(val data: ByteArray, val meta: DownloadMeta) : DownloadResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return data.contentEquals(other.data) && meta == other.meta
        }
        override fun hashCode(): Int = 31 * data.contentHashCode() + meta.hashCode()
    }
    /**
     * @param transportError true when the request failed at the connection level (timeout,
     *   DNS/TCP/TLS, stream reset, stall) — the CDN host itself is suspect. HTTP status errors
     *   (404 etc.) mean the host is fine and the content is missing, so transportError=false.
     * @param statusCode HTTP status when the server answered with an error (e.g. 404);
     *   null for transport-level failures. A 404 marks the assignment as permanently
     *   expired — callers must NOT retry it.
     */
    data class Failed(
        val idx: Int,
        val url: String,
        val reason: String,
        val transportError: Boolean = false,
        val statusCode: Int? = null
    ) : DownloadResult()
    data class CutPoint(val cut: github.rikacelery.v3.events.CutPoint) : DownloadResult()
}
