package github.rikacelery.v3.data

/**
 * Why an armed room is not recording, as a stable [code] the dashboard translates plus an
 * optional [detail] (a platform-level reason such as "no account") shown verbatim.
 */
data class RoomHint(val code: RoomHintCode, val detail: String? = null)

/** The reasons the dashboard can explain, and their spelling on the wire. */
enum class RoomHintCode(val wireName: String) {
    /** The room is public, but public recording is switched off. */
    PUBLIC_FILTER_OFF("public_filter_off"),

    /** The room is in a group show, but ticket purchase is switched off. */
    TICKET_PURCHASE_OFF("ticket_purchase_off"),

    /** The room is private and neither free spy nor paid spy recording is allowed. */
    PRIVATE_FILTER_OFF("private_filter_off"),

    /** The room is private, paid spy is off, and this account has no free spy privilege. */
    NO_FREE_SPY("no_free_spy"),

    /** The room should be recordable but preconfig keeps failing; [RoomHint.detail] says why. */
    PRECONFIG_FAILED("preconfig_failed");

    companion object {
        fun fromWire(value: String?): RoomHintCode? = entries.firstOrNull { it.wireName == value }
    }
}
