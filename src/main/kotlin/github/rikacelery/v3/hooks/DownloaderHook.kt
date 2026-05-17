package github.rikacelery.v3.hooks

import github.rikacelery.v3.data.DownloadResult

interface DownloaderHook {
    suspend fun beforeDownload(url: String): String
    suspend fun onDownloadResult(roomId: Long, result: DownloadResult): DownloadResult
}
