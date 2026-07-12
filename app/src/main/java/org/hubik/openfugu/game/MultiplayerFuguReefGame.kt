package org.hubik.openfugu.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.hypot
import kotlin.math.sin
import org.hubik.openfugu.util.nowMillis

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguReefScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var obstacles by remember { mutableStateOf(listOf<ReefObstacle>()) }
    var playerStates by remember {
        mutableStateOf(players.map { MultiplayerPlayerState(it, fishY = 0.85f) })
    }
    // Actual canvas size in dp, reported from the Canvas draw scope (same
    // pattern as Fugu Cave) — never assume a hardcoded screen size.
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

    val fishMinY = 0.05f
    val fishMaxY = 0.95f

    val allReady = allPlayersReady(players)

    fun resetGame() {
        playerStates = players.map { MultiplayerPlayerState(it, fishY = 0.85f) }
        scrollOffset = 0f
        obstacles = emptyList()
        gameStartMs = nowMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        if (gameState is GameState.GameOver && onSessionSave != null) {
            saveMultiplayerSession(
                playerStates,
                org.hubik.openfugu.session.SessionType.MULTIPLAYER_REEF_GAME,
                gameStartMs, onSessionSave
            )
        }
    }

    // Game loop
    LaunchedEffect(gameState) {
        runFrameLoop({ gameState is GameState.Playing }) { clampedDt ->
            val alivePlayers = playerStates.filter { it.alive }
            if (alivePlayers.isEmpty()) {
                val topScore = playerStates.maxOfOrNull { it.score } ?: 0
                gameState = GameState.GameOver(topScore)
                return@runFrameLoop
            }

            // Speed scales with the leader among alive players
            val currentMaxScore = alivePlayers.maxOf { it.score }
            val speed = REEF_SCROLL_SPEED_DP * (1f + currentMaxScore * REEF_SPEED_INCREMENT)

            // Update each alive player
            alivePlayers.forEach { ps ->
                ps.updateFishFromPressure(clampedDt, fishMinY, fishMaxY)
            }

            // Scroll obstacles
            scrollOffset += speed * clampedDt
            val updated = obstacles.toMutableList()
            updated.forEach { it.x -= speed * clampedDt }
            updated.removeAll { it.x < -(REEF_OBSTACLE_WIDTH_DP * 2) }

            // Spawn
            val (screenWidthDp, screenHeightDp) = canvasSizeDp
            val rightEdge = updated.maxOfOrNull { it.x }
            val spawnThreshold = screenWidthDp + REEF_OBSTACLE_WIDTH_DP
            if (rightEdge == null || rightEdge < spawnThreshold) {
                val gapCenter = 0.2f + Math.random().toFloat() * 0.6f
                val spawnX = if (updated.isEmpty()) {
                    screenWidthDp + REEF_FIRST_OBSTACLE_DP
                } else {
                    rightEdge!! + REEF_OBSTACLE_SPACING_DP
                }
                updated.add(ReefObstacle(x = spawnX, gapCenterY = gapCenter))
            }
            obstacles = updated

            // Collision + scoring per player
            val fishX = screenWidthDp * 0.25f
            val fishRadiusDp = REEF_FISH_RADIUS_DP.toFloat()

            updated.forEach { obs ->
                val obsLeftDp = obs.x
                val obsRightDp = obs.x + REEF_OBSTACLE_WIDTH_DP
                val gapTop = obs.gapCenterY - REEF_GAP_SIZE / 2f
                val gapBottom = obs.gapCenterY + REEF_GAP_SIZE / 2f

                // Scoring (once per obstacle)
                if (!obs.scored && obsRightDp < fishX) {
                    obs.scored = true
                    alivePlayers.forEach { it.score++ }
                }

                // Collision per alive player — circle vs the two barrier
                // rectangles, computed in dp space. The vertical axis is
                // denormalized by the real screen height (the fish radius
                // must not be scaled by the screen width).
                if (obsRightDp > fishX - fishRadiusDp && obsLeftDp < fishX + fishRadiusDp) {
                    alivePlayers.forEach { ps ->
                        if (!ps.alive) return@forEach
                        val fishYDp = ps.fishY * screenHeightDp
                        val gapTopDp = gapTop * screenHeightDp
                        val gapBottomDp = gapBottom * screenHeightDp
                        val closestX = fishX.coerceIn(obsLeftDp, obsRightDp)
                        val closestYTop = fishYDp.coerceIn(0f, gapTopDp)
                        val closestYBot = fishYDp.coerceIn(gapBottomDp, screenHeightDp)
                        val hitTop = hypot(fishX - closestX, fishYDp - closestYTop) < fishRadiusDp
                        val hitBottom = hypot(fishX - closestX, fishYDp - closestYBot) < fishRadiusDp
                        if (hitTop || hitBottom) {
                            ps.alive = false
                        }
                    }
                }
            }

            // Trigger recomposition by copying state
            playerStates = playerStates.toList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multiplayer Fugu Reef") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            if (allReady) resetGame()
                        }
                        is GameState.GameOver -> resetGame()
                        else -> {}
                    }
                }
        ) {
            val textMeasurer = rememberTextMeasurer()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dpToPx = density.density
                val wDp = w / dpToPx
                val hDp = h / dpToPx
                if (canvasSizeDp.first != wDp || canvasSizeDp.second != hDp) {
                    canvasSizeDp = Pair(wDp, hDp)
                }
                val fishRadiusPx = REEF_FISH_RADIUS_DP * dpToPx
                val obstacleWidthPx = REEF_OBSTACLE_WIDTH_DP * dpToPx
                val fishX = w * 0.25f

                // Background
                drawRect(GameBgColor)

                // Seabed
                val seabedY = h * 0.85f
                val seabedPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, seabedY)
                    var px = 0f
                    while (px <= w) {
                        val waveOffset = sin((px + scrollOffset * dpToPx * 0.5f) * 0.015f) * h * 0.02f
                        lineTo(px, seabedY + waveOffset)
                        px += 4f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(seabedPath, GameSeabedColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // Obstacles
                    obstacles.forEach { obs ->
                        val obsLeftPx = obs.x * dpToPx
                        val obsRightPx = obsLeftPx + obstacleWidthPx
                        val gapTop = h * (obs.gapCenterY - REEF_GAP_SIZE / 2f)
                        val gapBottom = h * (obs.gapCenterY + REEF_GAP_SIZE / 2f)

                        if (obsRightPx > 0f && obsLeftPx < w) {
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
                    }

                    // Draw dead fish first (faded), then alive on top
                    playerStates.filter { !it.alive }.forEach { ps ->
                        val fishYPx = (h * ps.fishY).coerceIn(fishRadiusPx * 1.5f, h - fishRadiusPx * 1.5f)
                        drawFugu(fishX, fishYPx, fishRadiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        val fishYPx = (h * ps.fishY).coerceIn(fishRadiusPx * 1.5f, h - fishRadiusPx * 1.5f)
                        drawFugu(fishX, fishYPx, fishRadiusPx, bodyColor = ps.info.color)
                    }

                    // Scoreboard (top-right)
                    drawMultiplayerScoreboard(textMeasurer, playerStates, w, dpToPx)
                }

                // Overlays
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        drawWaitingPlayersRow(textMeasurer, players, w, h, fishRadiusPx, dpToPx)
                        drawOverlayText(textMeasurer, 
                            w, h,
                            if (allReady) "Tap to start"
                            else "Waiting for all devices..."
                        )
                    }
                    is GameState.GameOver -> {
                        drawRect(GameOverlayBg)
                        // Ranked scoreboard handled by the Compose overlay below
                    }
                    else -> {}
                }
            }

            if (gameState is GameState.GameOver) {
                MultiplayerGameOverOverlay(playerStates)
            }
        }
    }
}
