package org.hubik.openfugu.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.util.formatHPa
import kotlin.math.max
import kotlin.math.sin

// =============================================================================
// Game constants (tunable) — all spatial values in dp.
// Shared with Multiplayer Fugu Reef, hence not private.
// =============================================================================

const val REEF_SCROLL_SPEED_DP = 120f      // dp per second (starting speed)
const val REEF_SPEED_INCREMENT = 0.02f     // multiplier increase per obstacle passed
const val REEF_GAP_SIZE = 0.25f            // fraction of screen height
const val REEF_OBSTACLE_SPACING_DP = 200   // dp between obstacle centers
const val REEF_FIRST_OBSTACLE_DP = 400     // dp — extra distance for first obstacle
const val REEF_FISH_RADIUS_DP = 16         // dp
const val REEF_OBSTACLE_WIDTH_DP = 40      // dp

// Colors (underwater theme, Chrome dino minimal style)
val ReefObstacleColor = Color(0xFF2E8B57)
val ReefObstacleEdge = Color(0xFF3CB371)

data class ReefObstacle(
    var x: Float,               // dp from left edge
    val gapCenterY: Float,      // normalized 0..1
    var scored: Boolean = false
)

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuguReefScreen(
    connection: PressureSource,
    onBack: () -> Unit,
    pressureRange: Double = DEFAULT_PRESSURE_RANGE,
    negativeRange: Double = 0.0,
    expertMode: Boolean = false,
    deviceName: String = connection.displayName,
    userName: String? = null,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val pressure by connection.latestPressure.collectAsState()
    val isCalibrated by connection.isCalibrated.collectAsState()
    val chartData by connection.chartData.collectAsState()
    val density = LocalDensity.current

    // Game state
    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var fishY by remember { mutableFloatStateOf(0.85f) }   // normalized 0=top, 1=bottom; start near bottom
    var scrollOffset by remember { mutableFloatStateOf(0f) }  // dp, for seabed wave animation
    // Actual canvas size in dp, reported from the Canvas draw scope.
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }
    var obstacles by remember { mutableStateOf(listOf<ReefObstacle>()) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }

    // Fish stays fully visible: account for radius as fraction of screen
    // We'll compute exact margin in the Canvas, but for game logic use a safe range
    val fishMinY = 0.05f
    val fishMaxY = 0.95f

    fun resetGame() {
        fishY = 0.85f  // start near bottom
        scrollOffset = 0f
        obstacles = emptyList()
        score = 0
        gameStartMs = System.currentTimeMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        val gs = gameState
        if (gs is GameState.GameOver && onSessionSave != null) {
            val endMs = System.currentTimeMillis()
            onSessionSave.invoke(org.hubik.openfugu.session.Session.GameSession(
                durationMs = endMs - gameStartMs,
                deviceName = deviceName,
                userName = userName,
                pressureTrace = connection.historySnapshot().filter { it.timestamp in gameStartMs..endMs },
                type = org.hubik.openfugu.session.SessionType.REEF_GAME,
                score = gs.score,
                pressureRange = pressureRange,
                negativeRange = negativeRange,
                expertMode = expertMode
            ))
        }
    }

    // Game loop — all positions in dp, converted to px only at draw time
    LaunchedEffect(gameState) {
        runFrameLoop({ gameState is GameState.Playing }) { clampedDt ->
            // Current speed with acceleration
            val speed = REEF_SCROLL_SPEED_DP * (1f + score * REEF_SPEED_INCREMENT)

            // --- Update fish position from pressure ---
            val currentPressure = pressure?.relativeHPa ?: 0.0
            val targetY = calculateTargetY(currentPressure, pressureRange, negativeRange, expertMode)
            fishY += (targetY - fishY) * SMOOTHING_FACTOR * clampedDt
            fishY = fishY.coerceIn(fishMinY, fishMaxY)

            // --- Scroll obstacles (in dp) ---
            scrollOffset += speed * clampedDt
            val updated = obstacles.toMutableList()
            updated.forEach { it.x -= speed * clampedDt }

            // Remove off-screen obstacles
            updated.removeAll { it.x < -(REEF_OBSTACLE_WIDTH_DP * 2) }

            // Spawn new obstacles, based on the real canvas width
            val screenWidthDp = canvasSizeDp.first
            val rightEdge = updated.maxOfOrNull { it.x }
            val spawnThreshold = screenWidthDp + REEF_OBSTACLE_WIDTH_DP
            if (rightEdge == null || rightEdge < spawnThreshold) {
                val gapCenter = 0.2f + Math.random().toFloat() * 0.6f
                val spawnX = if (updated.isEmpty()) {
                    // First obstacle: give player time to swim up
                    screenWidthDp + REEF_FIRST_OBSTACLE_DP
                } else {
                    rightEdge!! + REEF_OBSTACLE_SPACING_DP
                }
                updated.add(ReefObstacle(x = spawnX, gapCenterY = gapCenter))
            }

            obstacles = updated
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fugu Reef") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (highScore > 0) {
                        Text(
                            "Best: $highScore",
                            modifier = Modifier.padding(end = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickable {
                    when (gameState) {
                        is GameState.WaitingToStart -> {
                            if (isCalibrated && pressure != null) resetGame()
                        }
                        is GameState.GameOver -> resetGame()
                        else -> {}
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dpToPx = density.density
                if (canvasSizeDp.first != w / dpToPx || canvasSizeDp.second != h / dpToPx) {
                    canvasSizeDp = Pair(w / dpToPx, h / dpToPx)
                }
                val fishRadiusPx = REEF_FISH_RADIUS_DP * dpToPx
                val obstacleWidthPx = REEF_OBSTACLE_WIDTH_DP * dpToPx
                val fishX = w * 0.25f
                val fishYPx = h * fishY

                // Clamp fish so it's always fully visible
                val fishYClamped = fishYPx.coerceIn(fishRadiusPx * 1.5f, h - fishRadiusPx * 1.5f)

                // Background
                drawRect(GameBgColor)

                // Wavy seabed at bottom — scrolls with obstacles for parallax
                val seabedY = h * 0.85f
                val seabedPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, seabedY)
                    val waveFreq = 0.015f
                    val waveAmp = h * 0.02f
                    var px = 0f
                    while (px <= w) {
                        val waveOffset = sin((px + scrollOffset * dpToPx * 0.5f) * waveFreq) * waveAmp
                        lineTo(px, seabedY + waveOffset)
                        px += 4f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(seabedPath, GameSeabedColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // --- Draw obstacles + collision ---
                    var collided = false
                    obstacles.forEach { obs ->
                        val obsLeftPx = obs.x * dpToPx
                        val gapTop = h * (obs.gapCenterY - REEF_GAP_SIZE / 2f)
                        val gapBottom = h * (obs.gapCenterY + REEF_GAP_SIZE / 2f)
                        val obsRightPx = obsLeftPx + obstacleWidthPx

                        // Only draw if on screen
                        if (obsRightPx > 0f && obsLeftPx < w) {
                            // Top barrier
                            drawRoundRect(
                                ReefObstacleColor,
                                topLeft = Offset(obsLeftPx, 0f),
                                size = Size(obstacleWidthPx, gapTop),
                                cornerRadius = CornerRadius(6f)
                            )
                            drawRoundRect(
                                ReefObstacleEdge,
                                topLeft = Offset(obsLeftPx - 4f, gapTop - 12f),
                                size = Size(obstacleWidthPx + 8f, 12f),
                                cornerRadius = CornerRadius(4f)
                            )

                            // Bottom barrier
                            drawRoundRect(
                                ReefObstacleColor,
                                topLeft = Offset(obsLeftPx, gapBottom),
                                size = Size(obstacleWidthPx, h - gapBottom),
                                cornerRadius = CornerRadius(6f)
                            )
                            drawRoundRect(
                                ReefObstacleEdge,
                                topLeft = Offset(obsLeftPx - 4f, gapBottom),
                                size = Size(obstacleWidthPx + 8f, 12f),
                                cornerRadius = CornerRadius(4f)
                            )
                        }

                        // Scoring
                        if (!obs.scored && obsRightPx < fishX) {
                            obs.scored = true
                            score++
                        }

                        // Collision (circle vs two rects)
                        if (gameState is GameState.Playing && obsRightPx > fishX - fishRadiusPx && obsLeftPx < fishX + fishRadiusPx) {
                            val closestX = fishX.coerceIn(obsLeftPx, obsRightPx)
                            // Top barrier
                            val closestYTop = fishYClamped.coerceIn(0f, gapTop)
                            if (dist(fishX, fishYClamped, closestX, closestYTop) < fishRadiusPx) {
                                collided = true
                            }
                            // Bottom barrier
                            val closestYBot = fishYClamped.coerceIn(gapBottom, h)
                            if (dist(fishX, fishYClamped, closestX, closestYBot) < fishRadiusPx) {
                                collided = true
                            }
                        }
                    }

                    if (collided && gameState is GameState.Playing) {
                        highScore = max(highScore, score)
                        gameState = GameState.GameOver(score)
                    }

                    // --- Draw fish ---
                    drawFugu(fishX, fishYClamped, fishRadiusPx)

                    // --- Score (top center) ---
                    drawScoreText(score, w)

                    // --- Live pressure (bottom-left) ---
                    val pressureText = pressure?.let { "${formatHPa(it.relativeHPa)} hPa" } ?: "-- hPa"
                    drawPressureText(pressureText, h, dpToPx)
                }

                // --- Overlays ---
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        drawFugu(w / 2f, h / 2f, fishRadiusPx * 1.5f)
                        drawOverlayText(
                            w, h,
                            if (isCalibrated && pressure != null)
                                "Tap to start\nControl depth with equalization"
                            else
                                "Waiting for pressure data..."
                        )
                    }
                    is GameState.GameOver -> {
                        drawRect(GameOverlayBg)
                        val gameOverState = gameState as GameState.GameOver
                        drawOverlayText(
                            w, h,
                            "Game Over\n\nScore: ${gameOverState.score}\nTap to play again"
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

// =============================================================================
// Drawing helpers
// =============================================================================

private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
