package github.rikacelery.v3.hooks

import github.rikacelery.v3.data.Room

interface RoomHook {
    suspend fun onRoomAdded(roomId: Long, name: String)
    suspend fun onRoomRemoved(roomId: Long, name: String)
    suspend fun beforeRoomUpdate(roomId: Long, old: Room, new: Room): Room
}
