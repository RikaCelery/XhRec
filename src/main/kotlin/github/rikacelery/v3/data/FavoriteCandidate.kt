package github.rikacelery.v3.data

/**
 * One platform favorite as offered by the favorites import: the model id, the room name it
 * resolves to, and whether that room is already part of the room list.
 */
data class FavoriteCandidate(
    val modelId: Long,
    val name: String,
    val existing: Boolean
)
