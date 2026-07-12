package org.hubik.openfugu.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.session.SessionType

// =============================================================================
// Player info passed to every multiplayer game
// =============================================================================

data class MultiplayerPlayerInfo(
    val connection: PressureSource,
    val userProfile: UserProfile?,
    val savedDevice: SavedDevice,
    val color: Color,
    val displayName: String,
    val userName: String?
) {
    val pressureRange: Double get() = userProfile?.gamePressureRange ?: DEFAULT_PRESSURE_RANGE
    val negativeRange: Double get() = userProfile?.gameNegativeRange ?: 0.0
    val expertMode: Boolean get() = userProfile?.expertMode ?: false
}

// =============================================================================
// Mutable per-player game state
// =============================================================================

/**
 * Common per-player state for multiplayer games. Games with extra per-player
 * state (e.g. Feast's growing radius) subclass this.
 */
open class MultiplayerPlayerState(
    val info: MultiplayerPlayerInfo,
    var fishY: Float = 0.5f,
    var alive: Boolean = true,
    var score: Int = 0
)

/**
 * Advance this player's fish toward the pressure-mapped target using the
 * player's own ranges, and eliminate the player if their device disconnected.
 */
fun MultiplayerPlayerState.updateFishFromPressure(
    clampedDt: Float,
    fishMinY: Float,
    fishMaxY: Float
) {
    val currentPressure = info.connection.latestPressure.value?.relativeHPa ?: 0.0
    val targetY = calculateTargetY(
        currentPressure, info.pressureRange, info.negativeRange, info.expertMode
    )
    fishY += (targetY - fishY) * SMOOTHING_FACTOR * clampedDt
    fishY = fishY.coerceIn(fishMinY, fishMaxY)

    if (info.connection.state.value is DeviceConnectionState.Disconnected) {
        alive = false
    }
}

// =============================================================================
// Shared game flow
// =============================================================================

/** True once every player's device is calibrated and delivering pressure. */
@Composable
fun allPlayersReady(players: List<MultiplayerPlayerInfo>): Boolean {
    val havePressure = players.map { p ->
        p.connection.latestPressure.collectAsState().value != null
    }
    val calibrated = players.map { p ->
        p.connection.isCalibrated.collectAsState().value
    }
    return calibrated.all { it } && havePressure.all { it }
}

/**
 * Rank players by score (descending) and save a multiplayer session with
 * per-player results; the winner's trace doubles as the session trace.
 */
fun saveMultiplayerSession(
    playerStates: List<MultiplayerPlayerState>,
    type: SessionType,
    gameStartMs: Long,
    onSessionSave: (Session) -> Unit
) {
    val endMs = System.currentTimeMillis()
    val ranked = playerStates.sortedByDescending { it.score }
    val playerResults = ranked.mapIndexed { idx, ps ->
        val chartData = ps.info.connection.historySnapshot()
        Session.PlayerResult(
            deviceName = ps.info.displayName,
            userName = ps.info.userName,
            colorArgb = ps.info.savedDevice.colorArgb,
            score = ps.score,
            rank = idx + 1,
            pressureTrace = chartData.filter { it.timestamp in gameStartMs..endMs },
            pressureRange = ps.info.pressureRange,
            negativeRange = ps.info.negativeRange,
            expertMode = ps.info.expertMode
        )
    }
    val winner = playerResults.firstOrNull()
    onSessionSave(Session.MultiplayerGameSession(
        type = type,
        durationMs = endMs - gameStartMs,
        pressureTrace = winner?.pressureTrace ?: emptyList(),
        players = playerResults
    ))
}

// =============================================================================
// Shared drawing
// =============================================================================

/**
 * Waiting-to-start screen: all fugus with names, wrapped into evenly filled
 * rows so large lobbies don't overlap, centered around the upper quarter so
 * the overlay text below stays readable. Names are ellipsized to their slot.
 */
fun DrawScope.drawWaitingPlayersRow(
    players: List<MultiplayerPlayerInfo>,
    w: Float,
    h: Float,
    fishRadiusPx: Float,
    dpToPx: Float
) {
    val drawnRadius = fishRadiusPx * 1.5f
    val minSlot = 96f * dpToPx
    val maxPerRow = (w / minSlot).toInt().coerceAtLeast(1)
    val rowCount = (players.size + maxPerRow - 1) / maxPerRow
    val perRow = (players.size + rowCount - 1) / rowCount
    val rows = players.chunked(perRow)
    val rowPitch = drawnRadius * 2f + 24f * dpToPx
    val firstRowCy = (h * 0.25f - (rows.size - 1) * rowPitch / 2f)
        .coerceAtLeast(drawnRadius + 8f * dpToPx)
    val namePaint = android.text.TextPaint().apply {
        textSize = 14f * dpToPx
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    rows.forEachIndexed { rowIdx, rowPlayers ->
        val spacing = w / (rowPlayers.size + 1)
        val cy = firstRowCy + rowIdx * rowPitch
        rowPlayers.forEachIndexed { idx, info ->
            val cx = spacing * (idx + 1)
            drawFugu(cx, cy, drawnRadius, bodyColor = info.color)
            namePaint.color = info.color.toArgb()
            val name = android.text.TextUtils.ellipsize(
                info.userName ?: info.displayName,
                namePaint,
                spacing - 8f * dpToPx,
                android.text.TextUtils.TruncateAt.END
            ).toString()
            drawContext.canvas.nativeCanvas.drawText(
                name,
                cx,
                cy + fishRadiusPx * 2.5f,
                namePaint
            )
        }
    }
}

/** In-game scoreboard (top-right), ranked by score; dead players faded. */
fun DrawScope.drawMultiplayerScoreboard(
    playerStates: List<MultiplayerPlayerState>,
    w: Float,
    dpToPx: Float
) {
    val textPaint = android.graphics.Paint().apply {
        textSize = 14f * dpToPx
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    val lineHeight = 20f * dpToPx
    playerStates.sortedByDescending { it.score }.forEachIndexed { idx, ps ->
        val y = 28f * dpToPx + idx * lineHeight
        val label = "${ps.info.userName ?: ps.info.displayName}: ${ps.score}"
        textPaint.color = if (ps.alive) ps.info.color.toArgb()
            else ps.info.color.copy(alpha = 0.4f).toArgb()
        drawContext.canvas.nativeCanvas.drawText(label, w - 12f * dpToPx, y, textPaint)
    }
}

/** Game-over overlay: ranked scoreboard with medals and "Tap to play again". */
@Composable
fun MultiplayerGameOverOverlay(playerStates: List<MultiplayerPlayerState>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Game Over",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(24.dp))

        val ranked = playerStates.sortedByDescending { it.score }
        ranked.forEachIndexed { idx, ps ->
            val medal = when (idx) {
                0 -> "1st"
                1 -> "2nd"
                2 -> "3rd"
                else -> "${idx + 1}th"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    medal,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (idx == 0) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.width(40.dp)
                )
                Canvas(modifier = Modifier.size(20.dp)) {
                    drawCircle(ps.info.color)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    ps.info.userName ?: ps.info.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${ps.score}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ps.info.color
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Tap to play again",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
