package com.notrace.messenger.selfdestruct.domain

/**
 * The exact set of per-conversation timer values the plan (Section 9)
 * lists, plus the trigger-point decision it explicitly calls for the
 * product to make and document:
 *
 * TIMER START POINT: when the message is DELIVERED - for the sender,
 * that's the moment they send it (they've already seen their own
 * composed text); for the recipient, that's the moment it lands
 * decrypted in their local database. This was chosen over "read" to
 * avoid needing a whole separate read-receipt feature (not otherwise
 * in scope) just to support disappearing messages, and over "opened"
 * for the same reason - a reasonable, simpler, still-honest V1 choice,
 * explicitly documented per the plan's own requirement to pick one.
 */
enum class DisappearingTimerOption(val seconds: Long?, val label: String) {
    OFF(null, "Off"),
    SECONDS_30(30L, "30 seconds"),
    MINUTES_5(5 * 60L, "5 minutes"),
    HOUR_1(60 * 60L, "1 hour"),
    DAY_1(24 * 60 * 60L, "1 day"),
    DAYS_7(7 * 24 * 60 * 60L, "7 days"),
    MONTH_1(30 * 24 * 60 * 60L, "1 month"),
    YEAR_1(365 * 24 * 60 * 60L, "1 year");

    companion object {
        fun fromSeconds(seconds: Long?): DisappearingTimerOption =
            entries.find { it.seconds == seconds } ?: OFF
    }
}
