package org.hubik.openfugu.game

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import org.hubik.openfugu.ui.TextAnchor
import org.hubik.openfugu.ui.drawCanvasText

// =============================================================================
// Shared game state
// =============================================================================

sealed class GameState {
    data object WaitingToStart : GameState()
    data object Playing : GameState()
    data class GameOver(val score: Int) : GameState()
}

// =============================================================================
// Shared tuning
// =============================================================================

/** Default full-range pressure when no user profile is paired, in hPa. */
const val DEFAULT_PRESSURE_RANGE = 40.0

/** Exponential smoothing rate for pressure-to-position mapping (all games). */
const val SMOOTHING_FACTOR = 10f

// =============================================================================
// Shared colors
// =============================================================================

val GameScoreColor = Color(0xCCFFFFFF)
val GamePressureColor = Color(0x99FFFFFF)
val GameOverlayBg = Color(0x88000000)
val GameBgColor = Color(0xFF0D1B2A)      // underwater background (Reef, Feast, Cave)
val GameSeabedColor = Color(0xFF1A2D40)

private val FishColor = Color(0xFFFFB347)
private val FishEyeWhite = Color(0xFFFFFFFF)
private val FishEyeBlack = Color(0xFF000000)
private val FishTailColor = Color(0xFFFF8C00)

// =============================================================================
// Shared game logic
// =============================================================================

/**
 * Maps current pressure to a normalized Y position (0=top, 1=bottom).
 * In expert mode with negative range, uses asymmetric mapping (0.5=center).
 */
fun calculateTargetY(
    currentPressure: Double,
    pressureRange: Double,
    negativeRange: Double,
    expertMode: Boolean
): Float {
    return if (expertMode && negativeRange > 0.0) {
        if (currentPressure >= 0) {
            0.5f - (currentPressure / (pressureRange * 2)).toFloat().coerceIn(0f, 0.5f)
        } else {
            0.5f + ((-currentPressure) / (negativeRange * 2)).toFloat().coerceIn(0f, 0.5f)
        }
    } else {
        1f - (currentPressure / pressureRange).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Frame clock for game loops: invokes [onFrame] once per animation frame with
 * the elapsed seconds since the previous frame (0 on the first frame, clamped
 * to 0.05 s so a paused app doesn't teleport the game) while [isRunning]
 * holds. Call from a LaunchedEffect keyed on the game state.
 */
suspend fun runFrameLoop(isRunning: () -> Boolean, onFrame: (clampedDt: Float) -> Unit) {
    var lastNanos = 0L
    while (isRunning()) {
        withInfiniteAnimationFrameNanos { nanos ->
            val dt = if (lastNanos == 0L) 0f
            else (nanos - lastNanos) / 1_000_000_000f
            lastNanos = nanos
            onFrame(dt.coerceAtMost(0.05f))
        }
    }
}

// =============================================================================
// Shared drawing helpers
// =============================================================================

fun DrawScope.drawScoreText(measurer: TextMeasurer, score: Int, canvasWidth: Float) {
    drawCanvasText(measurer, "$score", canvasWidth / 2f, 80f, 64f, GameScoreColor, bold = true)
}

fun DrawScope.drawPressureText(measurer: TextMeasurer, text: String, canvasHeight: Float, dpToPx: Float) {
    drawCanvasText(
        measurer, text, 16f * dpToPx, canvasHeight - 16f * dpToPx,
        13f * dpToPx, GamePressureColor, anchor = TextAnchor.LEFT
    )
}

fun DrawScope.drawOverlayText(measurer: TextMeasurer, w: Float, h: Float, text: String) {
    val lines = text.split("\n")
    val lineHeight = 52f
    val startY = h / 2f + lines.size * lineHeight / 2f + 80f
    lines.forEachIndexed { i, line ->
        drawCanvasText(measurer, line, w / 2f, startY + i * lineHeight, 42f, Color.White, bold = true)
    }
}

/** Darken a color by multiplying RGB by [factor] (0..1). */
fun darkenColor(color: Color, factor: Float = 0.7f): Color =
    Color(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha
    )

fun DrawScope.drawFugu(
    cx: Float,
    cy: Float,
    radius: Float,
    bodyColor: Color = FishColor,
    finColor: Color = darkenColor(bodyColor, 0.7f),
    alpha: Float = 1f
) {
    val tailPath = Path().apply {
        moveTo(cx - radius * 0.8f, cy)
        lineTo(cx - radius * 1.8f, cy - radius * 0.6f)
        lineTo(cx - radius * 1.8f, cy + radius * 0.6f)
        close()
    }
    drawPath(tailPath, finColor.copy(alpha = alpha))

    val dorsalPath = Path().apply {
        moveTo(cx - radius * 0.2f, cy - radius * 0.85f)
        lineTo(cx + radius * 0.1f, cy - radius * 1.4f)
        lineTo(cx + radius * 0.4f, cy - radius * 0.85f)
        close()
    }
    drawPath(dorsalPath, finColor.copy(alpha = alpha))

    drawCircle(bodyColor.copy(alpha = alpha), radius, Offset(cx, cy))

    drawCircle(FishEyeWhite.copy(alpha = alpha), radius * 0.3f, Offset(cx + radius * 0.35f, cy - radius * 0.2f))
    drawCircle(FishEyeBlack.copy(alpha = alpha), radius * 0.15f, Offset(cx + radius * 0.4f, cy - radius * 0.2f))

    drawLine(
        FishEyeBlack.copy(alpha = alpha),
        Offset(cx + radius * 0.8f, cy + radius * 0.1f),
        Offset(cx + radius * 0.6f, cy + radius * 0.15f),
        strokeWidth = 2f
    )
}
