package github.rikacelery.v3.hooks

import github.rikacelery.v3.data.DataChannelMsg

interface DataHook {
    suspend fun intercept(msg: DataChannelMsg): DataChannelMsg?
}
