package org.hubik.openfugu.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.hypot
import kotlin.math.sin
import org.hubik.openfugu.util.nowMillis

// =============================================================================
// Multiplayer-specific tuning
// =============================================================================

// Everyone shares one screen, so a fish's color must mean the same thing for
// every player: prey spawn no bigger than the smallest alive player (needs no
// margin — players only grow, so that bar can never be undercut), predators
// larger than the largest alive player, with a margin because players grow
// toward them mid-flight.
private const val PREDATOR_MIN_FACTOR = 1.15f    // of the largest alive player's radius

// =============================================================================
// Game data
// =============================================================================

// Predator/prey is fixed at spawn (relative to the players alive at that
// moment) and drives both color and collision outcome. Resolving collisions
// by live size comparison instead would let a growing player outrun the
// coloring — a "red" fish that is suddenly edible for the leader only.
// (This is why the single-player EnemyFish class is NOT reused here.)
private data class FeastEnemy(
    var x: Float,           // dp from left edge
    val y: Float,           // normalized 0..1
    val radius: Float,      // dp
    val speed: Float,       // dp/s
    val isPredator: Boolean,
    var eaten: Boolean = false
)

private class FeastPlayerState(
    info: MultiplayerPlayerInfo
) : MultiplayerPlayerState(info) {
    var radius: Float = FEAST_PLAYER_START_RADIUS_DP
}

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerFuguFeastScreen(
    players: List<MultiplayerPlayerInfo>,
    onBack: () -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null
) {
    val density = LocalDensity.current

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var enemies by remember { mutableStateOf(listOf<FeastEnemy>()) }
    var rocks by remember { mutableStateOf(listOf<FeastRock>()) }
    var enemySpawnTimer by remember { mutableFloatStateOf(0f) }
    var rockSpawnTimer by remember { mutableFloatStateOf(0f) }
    var playerStates by remember {
        mutableStateOf(players.map { FeastPlayerState(it) })
    }
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

    val fishMinY = 0.05f
    val fishMaxY = 0.84f  // keep fish above seabed so rocks are obstacles

    val allReady = allPlayersReady(players)

    fun resetGame() {
        playerStates = players.map { FeastPlayerState(it) }
        scrollOffset = 0f
        enemies = emptyList()
        rocks = emptyList()
        enemySpawnTimer = 0f
        rockSpawnTimer = 0f
        gameStartMs = nowMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        if (gameState is GameState.GameOver && onSessionSave != null) {
            saveMultiplayerSession(
                playerStates,
                org.hubik.openfugu.session.SessionType.MULTIPLAYER_FEAST_GAME,
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

            // Difficulty ramps with the leader among alive players
            val maxAliveScore = alivePlayers.maxOf { it.score }
            val speedMultiplier = 1f + maxAliveScore * FEAST_SPEED_INCREMENT
            val baseSpeed = FEAST_BASE_SPEED_DP * speedMultiplier

            // --- Fish positions from pressure ---
            alivePlayers.forEach { ps ->
                ps.updateFishFromPressure(clampedDt, fishMinY, fishMaxY)
            }

            // --- Scroll ---
            scrollOffset += baseSpeed * clampedDt

            // --- Update enemies ---
            val updatedEnemies = enemies.toMutableList()
            updatedEnemies.forEach { it.x -= it.speed * speedMultiplier * clampedDt }
            updatedEnemies.removeAll { it.x < -it.radius * 3 || it.eaten }

            // --- Spawn enemies ---
            val (screenWDp, screenHDp) = canvasSizeDp
            enemySpawnTimer += clampedDt
            // More mouths need more fish: spawn faster with more alive players
            val spawnInterval = FEAST_ENEMY_SPAWN_INTERVAL /
                    (speedMultiplier.coerceAtLeast(0.5f) * (1f + 0.2f * (alivePlayers.size - 1)))
            if (enemySpawnTimer >= spawnInterval) {
                enemySpawnTimer = 0f

                val minAliveRadius = alivePlayers.minOf { it.radius }
                val maxAliveRadius = alivePlayers.maxOf { it.radius }
                val bigChance = (0.3f + maxAliveScore * 0.005f).coerceAtMost(0.6f)
                val isPredator = Math.random().toFloat() < bigChance
                val enemyRadius = if (isPredator) {
                    // Bigger than every alive player. The cap cannot invert
                    // the relation: players cap at 50 dp, so the minimum
                    // predator size (57.5 dp) stays below the 70 dp cap.
                    (maxAliveRadius * (PREDATOR_MIN_FACTOR + Math.random().toFloat() * 0.5f))
                        .coerceAtMost(FEAST_ENEMY_MAX_RADIUS_DP)
                } else {
                    // Up to the smallest alive player's size, as in single player
                    FEAST_ENEMY_MIN_RADIUS_DP + Math.random().toFloat() *
                            (minAliveRadius - FEAST_ENEMY_MIN_RADIUS_DP)
                }

                val y = 0.08f + Math.random().toFloat() * 0.76f  // avoid seabed
                // Speed must be >= scroll speed so fish never go backwards
                val speed = baseSpeed * (1.0f + Math.random().toFloat() * 0.6f)

                updatedEnemies.add(
                    FeastEnemy(
                        x = screenWDp + enemyRadius,
                        y = y,
                        radius = enemyRadius,
                        speed = speed,
                        isPredator = isPredator
                    )
                )
            }
            enemies = updatedEnemies

            // --- Update rocks ---
            val updatedRocks = rocks.toMutableList()
            updatedRocks.forEach { it.x -= baseSpeed * clampedDt }
            updatedRocks.removeAll { it.x < -FEAST_ROCK_WIDTH_DP * 2 }

            // --- Spawn rocks ---
            rockSpawnTimer += clampedDt
            if (rockSpawnTimer >= FEAST_ROCK_SPAWN_INTERVAL) {
                rockSpawnTimer = 0f
                val rockHeight = FEAST_ROCK_MIN_HEIGHT_DP + Math.random().toFloat() *
                        (FEAST_ROCK_MAX_HEIGHT_DP - FEAST_ROCK_MIN_HEIGHT_DP)
                val rockWidth = FEAST_ROCK_WIDTH_DP * (0.6f + Math.random().toFloat() * 0.8f)
                updatedRocks.add(FeastRock(x = screenWDp + rockWidth, height = rockHeight, width = rockWidth))
            }
            rocks = updatedRocks

            // --- Collisions (all in dp) ---
            val fishXDp = 0.25f * screenWDp
            val seabedYDp = 0.88f * screenHDp

            // Predators and rocks kill first, so a player eliminated this
            // frame cannot also snatch a prey fish
            alivePlayers.forEach { ps ->
                val fishYDp = ps.fishY * screenHDp
                updatedEnemies.forEach { enemy ->
                    if (!enemy.isPredator || enemy.eaten) return@forEach
                    val d = hypot(fishXDp - enemy.x, fishYDp - enemy.y * screenHDp)
                    if (d < (ps.radius + enemy.radius) * 0.7f) {
                        ps.alive = false
                    }
                }
                rocks.forEach { rock ->
                    if (feastRockHit(fishXDp, fishYDp, ps.radius,
                            rock.x, rock.width, rock.height, seabedYDp)) {
                        ps.alive = false
                    }
                }
            }

            // Prey are contested: when several players overlap one fish in
            // the same frame, the closest mouth wins
            updatedEnemies.forEach { enemy ->
                if (enemy.isPredator || enemy.eaten) return@forEach
                val enemyYDp = enemy.y * screenHDp
                val eater = alivePlayers
                    .filter { it.alive }
                    .map { ps -> ps to hypot(fishXDp - enemy.x, ps.fishY * screenHDp - enemyYDp) }
                    .filter { (ps, d) -> d < (ps.radius + enemy.radius) * 0.7f }
                    .minByOrNull { it.second }?.first
                if (eater != null) {
                    enemy.eaten = true
                    eater.score++
                    eater.radius = (eater.radius + FEAST_GROWTH_PER_EAT).coerceAtMost(FEAST_MAX_PLAYER_RADIUS_DP)
                }
            }

            // Trigger recomposition by copying state
            playerStates = playerStates.toList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multiplayer Fugu Feast") },
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
                val fishX = w * 0.25f
                val seabedY = h * 0.88f

                // Background
                drawRect(GameBgColor)

                if (gameState is GameState.Playing || gameState is GameState.GameOver) {
                    // --- Draw rocks (before seabed so sand covers their base) ---
                    rocks.forEach { rock ->
                        val rockLeftPx = rock.x * dpToPx
                        val rockWidthPx = rock.width * dpToPx
                        val rockHeightPx = rock.height * dpToPx
                        if (rockLeftPx + rockWidthPx > 0f && rockLeftPx < w) {
                            drawFeastRock(rockLeftPx, rockWidthPx, rockHeightPx, seabedY, h)
                        }
                    }
                }

                // Wavy seabed (covers rock bases)
                val seabedPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, seabedY)
                    val waveFreq = 0.015f
                    val waveAmp = h * 0.015f
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
                    // --- Draw enemies ---
                    enemies.forEach { enemy ->
                        if (enemy.eaten) return@forEach
                        val ex = enemy.x * dpToPx
                        val ey = h * enemy.y
                        val er = enemy.radius * dpToPx
                        if (ex + er > 0f && ex - er < w) {
                            drawEnemyFish(ex, ey, er, edible = !enemy.isPredator)
                        }
                    }

                    // --- Draw players: dead first (faded), then alive on top ---
                    playerStates.filter { !it.alive }.forEach { ps ->
                        val radiusPx = ps.radius * dpToPx
                        val fishYPx = (h * ps.fishY).coerceIn(radiusPx * 1.5f, seabedY - radiusPx)
                        drawFugu(fishX, fishYPx, radiusPx, bodyColor = ps.info.color, alpha = 0.3f)
                    }
                    playerStates.filter { it.alive }.forEach { ps ->
                        val radiusPx = ps.radius * dpToPx
                        val fishYPx = (h * ps.fishY).coerceIn(radiusPx * 1.5f, seabedY - radiusPx)
                        drawFugu(fishX, fishYPx, radiusPx, bodyColor = ps.info.color)
                    }

                    // Scoreboard (top-right)
                    drawMultiplayerScoreboard(textMeasurer, playerStates, w, dpToPx)
                }

                // Overlays
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        drawWaitingPlayersRow(textMeasurer, 
                            players, w, h,
                            FEAST_PLAYER_START_RADIUS_DP * dpToPx, dpToPx
                        )
                        drawOverlayText(textMeasurer, 
                            w, h,
                            if (allReady)
                                "Tap to start\nEat green fish, avoid red fish and rocks!"
                            else
                                "Waiting for all devices..."
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
