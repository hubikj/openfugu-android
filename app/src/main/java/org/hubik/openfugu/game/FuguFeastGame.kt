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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.hubik.openfugu.ui.drawCanvasText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.util.formatHPa
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.hubik.openfugu.util.nowMillis

// =============================================================================
// Game constants — all spatial values in dp.
// Shared with Multiplayer Fugu Feast, hence not private.
// =============================================================================

const val FEAST_BASE_SPEED_DP = 140f           // dp/s — starting scroll speed
const val FEAST_SPEED_INCREMENT = 0.003f       // speed multiplier increase per fish eaten
const val FEAST_PLAYER_START_RADIUS_DP = 18f   // starting player radius in dp
const val FEAST_GROWTH_PER_EAT = 1.5f          // dp added to radius per fish eaten
const val FEAST_MAX_PLAYER_RADIUS_DP = 50f     // cap on player size
const val FEAST_ENEMY_MIN_RADIUS_DP = 8f
const val FEAST_ENEMY_MAX_RADIUS_DP = 70f
const val FEAST_ENEMY_SPAWN_INTERVAL = 0.6f    // seconds between spawns (starting)
const val FEAST_ROCK_SPAWN_INTERVAL = 3.0f     // seconds between rock spawns
const val FEAST_ROCK_WIDTH_DP = 160f
const val FEAST_ROCK_MIN_HEIGHT_DP = 60f
const val FEAST_ROCK_MAX_HEIGHT_DP = 140f

// Colors
private val RockColor = Color(0xFF4A3728)
private val RockEdge = Color(0xFF5E4A3A)

// Enemy fish color palette — varies by size tier
private val EnemySmallColor = Color(0xFF66BB6A)   // green — safe to eat
private val EnemySmallFin = Color(0xFF388E3C)
private val EnemyBigColor = Color(0xFFEF5350)      // red — dangerous
private val EnemyBigFin = Color(0xFFB71C1C)
private val EnemyEyeWhite = Color(0xFFFFFFFF)
private val EnemyEyeBlack = Color(0xFF000000)

// =============================================================================
// Game data
// =============================================================================

private data class EnemyFish(
    var x: Float,           // dp from left edge
    val y: Float,           // normalized 0..1
    val radius: Float,      // dp
    val speed: Float,       // dp/s — individual speed modifier
    var eaten: Boolean = false
)

/** Shared with Multiplayer Fugu Feast (identical rock behavior in both). */
data class FeastRock(
    var x: Float,           // dp from left edge
    val height: Float,      // dp
    val width: Float        // dp
)

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuguFeastScreen(
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

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var fishY by remember { mutableFloatStateOf(0.5f) }
    var playerRadius by remember { mutableFloatStateOf(FEAST_PLAYER_START_RADIUS_DP) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var enemies by remember { mutableStateOf(listOf<EnemyFish>()) }
    var rocks by remember { mutableStateOf(listOf<FeastRock>()) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }
    var enemySpawnTimer by remember { mutableFloatStateOf(0f) }
    var rockSpawnTimer by remember { mutableFloatStateOf(0f) }
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }  // updated from Canvas

    val fishMinY = 0.05f
    val fishMaxY = 0.84f  // keep fish above seabed so rocks are obstacles

    fun resetGame() {
        fishY = 0.5f
        playerRadius = FEAST_PLAYER_START_RADIUS_DP
        scrollOffset = 0f
        enemies = emptyList()
        rocks = emptyList()
        score = 0
        enemySpawnTimer = 0f
        rockSpawnTimer = 0f
        gameStartMs = nowMillis()
        gameState = GameState.Playing
    }

    // Save session on game over
    LaunchedEffect(gameState) {
        val gs = gameState
        if (gs is GameState.GameOver && onSessionSave != null) {
            val endMs = nowMillis()
            onSessionSave.invoke(org.hubik.openfugu.session.Session.GameSession(
                durationMs = endMs - gameStartMs,
                deviceName = deviceName,
                userName = userName,
                pressureTrace = connection.historySnapshot().filter { it.timestamp in gameStartMs..endMs },
                type = org.hubik.openfugu.session.SessionType.FEAST_GAME,
                score = gs.score,
                pressureRange = pressureRange,
                negativeRange = negativeRange,
                expertMode = expertMode
            ))
        }
    }

    // Game loop
    LaunchedEffect(gameState) {
        runFrameLoop({ gameState is GameState.Playing }) { clampedDt ->
            val speedMultiplier = 1f + score * FEAST_SPEED_INCREMENT
            val baseSpeed = FEAST_BASE_SPEED_DP * speedMultiplier

            // --- Fish position from pressure ---
            val currentPressure = pressure?.relativeHPa ?: 0.0
            val targetY = calculateTargetY(currentPressure, pressureRange, negativeRange, expertMode)
            fishY += (targetY - fishY) * SMOOTHING_FACTOR * clampedDt
            fishY = fishY.coerceIn(fishMinY, fishMaxY)

            // --- Scroll ---
            scrollOffset += baseSpeed * clampedDt

            // --- Update enemies ---
            val updatedEnemies = enemies.toMutableList()
            updatedEnemies.forEach { it.x -= it.speed * speedMultiplier * clampedDt }
            updatedEnemies.removeAll { it.x < -it.radius * 3 || it.eaten }

            // --- Spawn enemies ---
            val (screenWDp, screenHDp) = canvasSizeDp
            enemySpawnTimer += clampedDt
            val spawnInterval = FEAST_ENEMY_SPAWN_INTERVAL / speedMultiplier.coerceAtLeast(0.5f)
            if (enemySpawnTimer >= spawnInterval) {
                enemySpawnTimer = 0f

                // Size distribution: bigger fish always possible, more frequent with score
                val sizeRoll = Math.random().toFloat()
                val bigChance = (0.3f + score * 0.005f).coerceAtMost(0.6f)
                val maxEnemy = FEAST_ENEMY_MAX_RADIUS_DP  // capped but always > max player
                val enemyRadius = if (sizeRoll < bigChance) {
                    // Bigger than player
                    playerRadius * (1.1f + Math.random().toFloat() * 0.7f)
                } else {
                    // Smaller than player (safe to eat)
                    FEAST_ENEMY_MIN_RADIUS_DP + Math.random().toFloat() *
                            (playerRadius - FEAST_ENEMY_MIN_RADIUS_DP).coerceAtLeast(3f)
                }.coerceIn(FEAST_ENEMY_MIN_RADIUS_DP, maxEnemy)

                val y = 0.08f + Math.random().toFloat() * 0.76f  // avoid seabed
                // Speed must be >= scroll speed so fish never go backwards
                val speed = baseSpeed * (1.0f + Math.random().toFloat() * 0.6f)

                updatedEnemies.add(
                    EnemyFish(
                        x = screenWDp + enemyRadius,
                        y = y,
                        radius = enemyRadius,
                        speed = speed
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
            val fishYDp = fishY * screenHDp

            // Check enemy collisions
            var collided = false
            enemies.forEach { enemy ->
                if (enemy.eaten) return@forEach
                val enemyYDp = enemy.y * screenHDp
                val dx = fishXDp - enemy.x
                val dy = fishYDp - enemyYDp
                val d = sqrt(dx * dx + dy * dy)
                val touchDist = playerRadius + enemy.radius

                if (d < touchDist * 0.7f) {  // overlap threshold
                    if (playerRadius >= enemy.radius) {
                        // Eat it!
                        enemy.eaten = true
                        score++
                        playerRadius = (playerRadius + FEAST_GROWTH_PER_EAT).coerceAtMost(FEAST_MAX_PLAYER_RADIUS_DP)
                    } else {
                        collided = true
                    }
                }
            }

            // Check rock collisions
            val seabedYDp = 0.88f * screenHDp
            rocks.forEach { rock ->
                if (feastRockHit(fishXDp, fishYDp, playerRadius,
                        rock.x, rock.width, rock.height, seabedYDp)) {
                    collided = true
                }
            }

            if (collided) {
                highScore = max(highScore, score)
                gameState = GameState.GameOver(score)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fugu Feast") },
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
            val textMeasurer = rememberTextMeasurer()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dpToPx = density.density

                // Report canvas size in dp for game-loop collision detection
                val wDp = w / dpToPx
                val hDp = h / dpToPx
                if (canvasSizeDp.first != wDp || canvasSizeDp.second != hDp) {
                    canvasSizeDp = Pair(wDp, hDp)
                }

                val playerRadiusPx = playerRadius * dpToPx
                val fishX = w * 0.25f
                val fishYPx = h * fishY
                val fishYClamped = fishYPx.coerceIn(playerRadiusPx * 1.5f, h * 0.88f - playerRadiusPx)

                // Background
                drawRect(GameBgColor)

                val seabedY = h * 0.88f

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
                            val isSmaller = enemy.radius <= playerRadius
                            drawEnemyFish(ex, ey, er, isSmaller)
                        }
                    }

                    // --- Draw player ---
                    drawFugu(fishX, fishYClamped, playerRadiusPx)

                    // --- Score (top center) ---
                    drawScoreText(textMeasurer, score, w)

                    // --- Size indicator (below score) ---
                    val sizeText = "Size: ${playerRadius.toInt()}"
                    drawCanvasText(textMeasurer, sizeText, w / 2f, 120f, 32f, GamePressureColor)

                    // --- Live pressure (bottom-left) ---
                    val pressureText = pressure?.let { "${formatHPa(it.relativeHPa)} hPa" } ?: "-- hPa"
                    drawPressureText(textMeasurer, pressureText, h, dpToPx)
                }

                // --- Overlays ---
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        drawFugu(w / 2f, h / 2f, FEAST_PLAYER_START_RADIUS_DP * dpToPx * 1.5f)
                        drawOverlayText(textMeasurer, 
                            w, h,
                            if (isCalibrated && pressure != null)
                                "Tap to start\nEat smaller fish, avoid bigger ones!"
                            else
                                "Waiting for pressure data..."
                        )
                    }
                    is GameState.GameOver -> {
                        drawRect(GameOverlayBg)
                        val gameOverState = gameState as GameState.GameOver
                        drawOverlayText(textMeasurer, 
                            w, h,
                            "Game Over\n\nFish eaten: ${gameOverState.score}\nTap to play again"
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

// =============================================================================
// Shared Feast geometry (used by single-player and multiplayer)
// =============================================================================

/**
 * Circle-vs-rock collision for the mound-shaped Feast rock, in dp space.
 * Approximates the drawn mound: full width at the base, narrowing to ~30%
 * toward the peak — must stay in sync with [drawFeastRock].
 */
fun feastRockHit(
    fishXDp: Float, fishYDp: Float, playerRadiusDp: Float,
    rockX: Float, rockWidth: Float, rockHeight: Float, seabedYDp: Float
): Boolean {
    val rockTopDp = seabedYDp - rockHeight
    val rockCenterX = rockX + rockWidth * 0.5f

    // How far up the rock is the fish? 0=base, 1=peak
    val heightFrac = ((seabedYDp - fishYDp) / rockHeight).coerceIn(0f, 1f)
    val widthAtFishY = rockWidth * (1f - heightFrac * 0.7f) * 0.45f
    val rockLeftDp = rockCenterX - widthAtFishY
    val rockRightDp = rockCenterX + widthAtFishY

    val closestX = fishXDp.coerceIn(rockLeftDp, rockRightDp)
    val closestY = fishYDp.coerceIn(rockTopDp, seabedYDp)
    val dx = fishXDp - closestX
    val dy = fishYDp - closestY
    return sqrt(dx * dx + dy * dy) < playerRadiusDp * 0.7f
}

/** Draw the mound-shaped Feast rock — extends to the screen bottom so it never floats. Px space. */
fun DrawScope.drawFeastRock(rockLeftPx: Float, rockWidthPx: Float, rockHeightPx: Float, seabedY: Float, canvasHeight: Float) {
    val rockTopPx = seabedY - rockHeightPx  // grow up from seabed
    val rockPath = Path().apply {
        moveTo(rockLeftPx, canvasHeight)
        lineTo(rockLeftPx, rockTopPx + rockHeightPx * 0.4f)
        lineTo(rockLeftPx + rockWidthPx * 0.15f, rockTopPx + rockHeightPx * 0.15f)
        lineTo(rockLeftPx + rockWidthPx * 0.35f, rockTopPx)
        lineTo(rockLeftPx + rockWidthPx * 0.55f, rockTopPx + rockHeightPx * 0.08f)
        lineTo(rockLeftPx + rockWidthPx * 0.75f, rockTopPx + rockHeightPx * 0.05f)
        lineTo(rockLeftPx + rockWidthPx * 0.9f, rockTopPx + rockHeightPx * 0.25f)
        lineTo(rockLeftPx + rockWidthPx, canvasHeight)
        close()
    }
    drawPath(rockPath, RockColor)
    // Highlight ridge
    val edgePath = Path().apply {
        moveTo(rockLeftPx + rockWidthPx * 0.15f, rockTopPx + rockHeightPx * 0.15f)
        lineTo(rockLeftPx + rockWidthPx * 0.35f, rockTopPx)
        lineTo(rockLeftPx + rockWidthPx * 0.55f, rockTopPx + rockHeightPx * 0.08f)
        lineTo(rockLeftPx + rockWidthPx * 0.75f, rockTopPx + rockHeightPx * 0.05f)
    }
    drawPath(edgePath, RockEdge, style = Stroke(width = 2.5f))
}

// =============================================================================
// Drawing helpers
// =============================================================================

/** Draw an enemy fish — green if edible, red if dangerous. Faces left.
 *  Spiky dorsal fins and angry open mouth distinguish them from the friendly fugu. */
fun DrawScope.drawEnemyFish(cx: Float, cy: Float, radius: Float, edible: Boolean) {
    val bodyColor = if (edible) EnemySmallColor else EnemyBigColor
    val finColor = if (edible) EnemySmallFin else EnemyBigFin

    // Draw into a layer so BlendMode.Clear makes a true transparent cutout
    drawContext.canvas.saveLayer(
        androidx.compose.ui.geometry.Rect(
            cx - radius * 2f, cy - radius * 1.6f,
            cx + radius * 2f, cy + radius * 1.6f
        ),
        androidx.compose.ui.graphics.Paint()
    )

    // Tail fin — pointed, facing right (fish swims left)
    val tailPath = Path().apply {
        moveTo(cx + radius * 0.8f, cy)
        lineTo(cx + radius * 1.7f, cy - radius * 0.6f)
        lineTo(cx + radius * 1.3f, cy)
        lineTo(cx + radius * 1.7f, cy + radius * 0.6f)
        close()
    }
    drawPath(tailPath, finColor)

    // Dorsal spikes (bases inside ellipse so body overlaps them)
    val spikePath = Path().apply {
        moveTo(cx + radius * 0.3f, cy - radius * 0.65f)
        lineTo(cx + radius * 0.15f, cy - radius * 1.3f)
        lineTo(cx, cy - radius * 0.7f)
        lineTo(cx - radius * 0.15f, cy - radius * 1.25f)
        lineTo(cx - radius * 0.3f, cy - radius * 0.65f)
        lineTo(cx - radius * 0.45f, cy - radius * 1.15f)
        lineTo(cx - radius * 0.55f, cy - radius * 0.6f)
    }
    drawPath(spikePath, finColor)

    // Body — horizontal ellipse (wider than tall)
    drawOval(
        bodyColor,
        topLeft = Offset(cx - radius * 1.1f, cy - radius * 0.75f),
        size = Size(radius * 2.2f, radius * 1.5f)
    )

    // Eye
    drawCircle(EnemyEyeWhite, radius * 0.26f, Offset(cx - radius * 0.5f, cy - radius * 0.1f))
    drawCircle(EnemyEyeBlack, radius * 0.13f, Offset(cx - radius * 0.55f, cy - radius * 0.1f))
    // Angry brow — above eye, angled DOWN toward nose (scowl)
    drawLine(
        EnemyEyeBlack,
        Offset(cx - radius * 0.25f, cy - radius * 0.52f),
        Offset(cx - radius * 0.72f, cy - radius * 0.38f),
        strokeWidth = (radius * 0.07f).coerceAtLeast(1.5f)
    )

    // Open mouth — true transparent cutout
    val mouthPath = Path().apply {
        moveTo(cx - radius * 1.12f, cy - radius * 0.05f)
        lineTo(cx - radius * 0.7f, cy + radius * 0.12f)
        lineTo(cx - radius * 1.12f, cy + radius * 0.35f)
        close()
    }
    drawPath(mouthPath, Color.Transparent, blendMode = BlendMode.Clear)

    drawContext.canvas.restore()
}

