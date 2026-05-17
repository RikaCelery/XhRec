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
    data class Failed(val idx: Int, val url: String, val reason: String) : DownloadResult()
    data class Skipped(val idx: Int, val reason: String) : DownloadResult()
}
