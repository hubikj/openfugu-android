package org.hubik.openfugu.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import org.hubik.openfugu.util.nowMillis

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguCaveScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var segments by remember { mutableStateOf(listOf<CaveSegment>()) }
    var playerStates by remember {
        mutableStateOf(players.map { MultiplayerPlayerState(it) })
    }
    // Actual canvas size in dp, reported from the Canvas draw scope (same
    // pattern as Fugu Cave) — never assume a hardcoded screen size.
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

    val fishMinY = 0.05f
    val fishMaxY = 0.95f

    val allReady = allPlayersReady(players)

    fun resetGame() {
        playerStates = players.map { MultiplayerPlayerState(it) }
        scrollOffset = 0f
        segments = buildInitialCave(canvasSizeDp.first)
        gameStartMs = nowMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        if (gameState is GameState.GameOver && onSessionSave != null) {
            saveMultiplayerSession(
                playerStates,
                org.hubik.openfugu.session.SessionType.MULTIPLAYER_CAVE_GAME,
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

            // The cave is shared, so distance — and with it speed and gap
            // narrowing — is shared too. A player's score freezes at the
            // distance where they died.
            val distance = (scrollOffset / 20f).toInt()
            val speed = CAVE_SCROLL_SPEED_DP * (1f + distance * CAVE_SPEED_INCREMENT)

            // Update each alive player
            alivePlayers.forEach { ps ->
                ps.updateFishFromPressure(clampedDt, fishMinY, fishMaxY)
                ps.score = distance
            }

            // Scroll the cave
            val scrollDp = speed * clampedDt
            scrollOffset += scrollDp
            val (screenWDp, screenHDp) = canvasSizeDp
            val updated = advanceCave(segments, scrollDp, screenWDp, distance)
            segments = updated

            // Collision — all fish share the same x, so the gap is
            // sampled once and compared per player in dp space
            val fishXDp = 0.25f * screenWDp
            val gap = caveGapAt(updated, fishXDp)
            if (gap != null) {
                val ceilingDp = gap.first * screenHDp
                val floorDp = gap.second * screenHDp
                alivePlayers.forEach { ps ->
                    val fishYDp = ps.fishY * screenHDp
                    if (fishYDp - CAVE_FISH_RADIUS_DP < ceilingDp ||
                        fishYDp + CAVE_FISH_RADIUS_DP > floorDp
                    ) {
                        ps.alive = false
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
                title = { Text("Multiplayer Fugu Cave") },
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
                val fishRadiusPx = CAVE_FISH_RADIUS_DP * dpToPx
                val fishX = w * 0.25f

                // Background
                drawRect(GameBgColor)

                if (segments.size >= 2 &&
                    (gameState is GameState.Playing || gameState is GameState.GameOver)
                ) {
                    // Cave ceiling and floor
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = true,
                        scrollOffsetDp = scrollOffset
                    )
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = false,
                        scrollOffsetDp = scrollOffset
                    )

                    // Draw dead fish first (faded), then alive on top
                    playerStates.filter { !it.alive }.forEach { ps ->
                        drawFugu(fishX, h * ps.fishY, fishRadiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        drawFugu(fishX, h * ps.fishY, fishRadiusPx, bodyColor = ps.info.color)
                    }

                    // Shared distance (top center)
                    drawScoreText(textMeasurer, (scrollOffset / 20f).toInt(), w)

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
