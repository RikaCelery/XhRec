package github.rikacelery.v3.data

/**
 * Room status classification. The platform uses several variants for paid shows
 * (private / p2p / virtualPrivate / ...) and the list can grow, so anything that is
 * not public / groupShow / offline is treated as a paid (private) show. The paid-flow
 * guards (autoPaySpy, price/token availability) bound the risk of unknown statuses.
 */
object RoomStatus {
    const val PUBLIC = "public"
    const val GROUP_SHOW = "groupShow"

    // known private variants (informative; isPrivate() also falls back to "everything else")
    val PRIVATE_VARIANTS = setOf("private", "p2p", "virtualPrivate")

    // statuses meaning the model is not streaming
    val OFFLINE_VARIANTS = setOf("off", "offline", "idle")

    fun isPublic(status: String): Boolean = status == PUBLIC
    fun isGroupShow(status: String): Boolean = status == GROUP_SHOW
    fun isOffline(status: String): Boolean = status in OFFLINE_VARIANTS

    /** Paid-show status: a known private variant, or any unknown non-offline status. */
    fun isPrivate(status: String): Boolean =
        status.isNotEmpty() && !isPublic(status) && !isGroupShow(status) && !isOffline(status)
}
