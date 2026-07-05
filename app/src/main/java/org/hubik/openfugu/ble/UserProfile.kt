package org.hubik.openfugu.ble

import java.util.UUID

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val minEqPressureHPa: Double? = null,
    val maxPositiveHPa: Double? = null,
    val maxNegativeHPa: Double? = null,
    val gamePressureRangeManual: Double? = null,
    val gameNegativeRangeManual: Double? = null,
    val useAutoRange: Boolean = true,
    val expertMode: Boolean = false,
    val lastCalibratedAt: Long? = null
) {
    val gamePressureRange: Double
        get() = when {
            // Safety: games must never demand pressure above the calibrated
            // value. Calibration itself measures a COMFORTABLE maximum (the
            // wizard tells the user not to push to their limit), so a manual
            // range may go up to that value; the auto range stays at 80% of
            // it for headroom. Uncalibrated profiles have nothing to clamp
            // against; the user detail screen warns about that case.
            !useAutoRange && gamePressureRangeManual != null ->
                maxPositiveHPa?.let { gamePressureRangeManual.coerceAtMost(it) }
                    ?: gamePressureRangeManual
            useAutoRange && maxPositiveHPa != null -> maxPositiveHPa * 0.8
            else -> 40.0
        }

    val gameNegativeRange: Double
        get() = when {
            !useAutoRange && gameNegativeRangeManual != null ->
                maxNegativeHPa?.let { gameNegativeRangeManual.coerceAtMost(it) }
                    ?: gameNegativeRangeManual
            useAutoRange && maxNegativeHPa != null -> maxNegativeHPa * 0.8
            else -> 0.0
        }
}

data class DeviceUserPairing(
    val deviceAddress: String,
    val userId: String
)
