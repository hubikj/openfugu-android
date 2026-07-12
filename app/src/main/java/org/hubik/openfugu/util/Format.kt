package org.hubik.openfugu.util

import kotlin.math.abs
import kotlin.math.floor

/**
 * Fixed-decimal formatting without JVM String.format, so it works in common
 * multiplatform code. Rounds half away from zero like "%.1f", but negative
 * zero renders as "0.0", never "-0.0".
 */
fun Double.fmt(decimals: Int): String {
    if (isNaN()) return "NaN"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val units = floor(abs(this) * factor + 0.5).toLong()
    val sign = if (this < 0 && units != 0L) "-" else ""
    val intPart = units / factor
    return if (decimals == 0) "$sign$intPart"
    else "$sign$intPart.${(units % factor).toString().padStart(decimals, '0')}"
}

fun Float.fmt(decimals: Int): String = toDouble().fmt(decimals)

/** Format hPa avoiding "-0.0" display */
fun formatHPa(value: Double): String = value.fmt(1)

/** "3:07" — minutes and zero-padded seconds. */
fun formatMinSec(totalSeconds: Int): String =
    "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
