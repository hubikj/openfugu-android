package org.hubik.openfugu.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Epoch milliseconds — the one place the wall clock is read. */
@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "14:03:07.123" — in-app log timestamps. */
val LogTimeFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    hour(); char(':'); minute(); char(':'); second(); char('.'); secondFraction(3)
}

/** "Jul 12, 14:03" — compact session-list dates. */
val ShortDateTimeFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(padding = Padding.NONE)
    chars(", "); hour(); char(':'); minute()
}

/** "Jul 12, 2026  14:03" — session viewer header. */
val LongDateTimeFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(padding = Padding.NONE)
    chars(", "); year(); chars("  "); hour(); char(':'); minute()
}

/** "2026-07-12_1403" — timestamps in shared-file names. */
val FileStampFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day(); char('_'); hour(); minute()
}

/** Format epoch milliseconds in the device's current time zone. */
@OptIn(ExperimentalTime::class)
fun formatTimestamp(epochMillis: Long, format: DateTimeFormat<LocalDateTime>): String =
    format.format(
        Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    )
