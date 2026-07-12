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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.util.formatHPa
import kotlin.math.max
import kotlin.math.sin

// =============================================================================
// Game constants — all spatial values in dp.
// The CAVE_* constants are shared with Multiplayer Fugu Cave, hence not private.
// =============================================================================

const val CAVE_SCROLL_SPEED_DP = 150f       // dp/s starting speed
const val CAVE_SPEED_INCREMENT = 0.0008f    // speed multiplier increase per distance point
const val CAVE_FISH_RADIUS_DP = 16f         // dp

// Cave generation
private const val SAFE_START_SEGMENTS = 8        // gradual funnel before cave narrows in
private const val SEGMENT_WIDTH_DP = 50f         // dp between cave control points
private const val START_MIN_GAP = 0.22f          // starting minimum gap (fraction of screen)
private const val FINAL_MIN_GAP = 0.15f          // minimum gap at highest difficulty
private const val GAP_NARROW_RATE = 0.00006f     // min gap narrows per distance point
private const val CAVE_DRIFT = 0.14f             // max wall change per segment
private const val MOMENTUM = 0.35f               // bias toward continuing previous direction
private const val CEILING_MIN = -0.10f           // ceiling can go above screen
private const val CEILING_MAX = 0.65f            // ceiling can reach well past halfway
private const val FLOOR_MIN = 0.35f              // floor can reach well past halfway
private const val FLOOR_MAX = 1.15f              // floor can go below screen (natural breathing)

// Jagged edge detail
private const val JITTER_AMP_DP = 4f             // dp amplitude of rocky jitter
private const val JITTER_POINTS_PER_SEGMENT = 4  // sub-points per segment for rocky look

// Colors (reuse Fugu Feast rock style)
private val CaveColor = Color(0xFF4A3728)
private val CaveEdge = Color(0xFF5E4A3A)

// =============================================================================
// Game data
// =============================================================================

/** A single control point defining ceiling and floor height at a given x. */
data class CaveSegment(
    var x: Float,            // dp from left edge (scrolls left over time)
    val ceilingY: Float,     // normalized 0..1 (0 = top of screen)
    val floorY: Float,       // normalized 0..1
    val prevCeilDelta: Float = 0f,  // previous ceiling movement (for momentum)
    val prevFloorDelta: Float = 0f  // previous floor movement (for momentum)
)

// =============================================================================
// Cave terrain — shared with Multiplayer Fugu Cave
// =============================================================================

/** Generate the next cave segment with momentum-biased random walks. */
private fun nextSegment(prev: CaveSegment, score: Int): CaveSegment {
    // Random component + momentum from previous direction
    val ceilRandom = (Math.random().toFloat() - 0.5f) * 2f * CAVE_DRIFT
    val floorRandom = (Math.random().toFloat() - 0.5f) * 2f * CAVE_DRIFT
    val ceilDelta = ceilRandom * (1f - MOMENTUM) + prev.prevCeilDelta * MOMENTUM
    val floorDelta = floorRandom * (1f - MOMENTUM) + prev.prevFloorDelta * MOMENTUM

    var newCeiling = (prev.ceilingY + ceilDelta).coerceIn(CEILING_MIN, CEILING_MAX)
    var newFloor = (prev.floorY + floorDelta).coerceIn(FLOOR_MIN, FLOOR_MAX)

    // Ensure minimum gap (narrows with score)
    val minGap = (START_MIN_GAP - score * GAP_NARROW_RATE).coerceAtLeast(FINAL_MIN_GAP)
    if (newFloor - newCeiling < minGap) {
        val mid = (newCeiling + newFloor) / 2f
        newCeiling = (mid - minGap / 2f).coerceAtLeast(CEILING_MIN)
        newFloor = (mid + minGap / 2f).coerceAtMost(FLOOR_MAX)
    }

    return CaveSegment(
        x = prev.x + SEGMENT_WIDTH_DP,
        ceilingY = newCeiling,
        floorY = newFloor,
        prevCeilDelta = ceilDelta,
        prevFloorDelta = floorDelta
    )
}

/** Build the starting cave: real terrain with a gradual safe-start funnel. */
fun buildInitialCave(screenWDp: Float): List<CaveSegment> {
    val numSegments = ((screenWDp + 400f) / SEGMENT_WIDTH_DP).toInt() + 2

    // Generate the real cave terrain — start tight with momentum already going
    val cave = mutableListOf(
        CaveSegment(
            x = 0f, ceilingY = 0.30f, floorY = 0.62f,
            prevCeilDelta = 0.06f, prevFloorDelta = -0.04f
        )
    )
    repeat(SAFE_START_SEGMENTS + numSegments) {
        cave.add(nextSegment(cave.last(), score = 0))
    }

    // Ceiling blends in quickly, floor stays open much longer
    val ceilSafe = SAFE_START_SEGMENTS / 2       // ceiling: 4 open + 4 blend
    val floorSafe = SAFE_START_SEGMENTS           // floor: 8 open + 6 blend
    val floorBlendLen = 6
    val totalFloorSafe = floorSafe + floorBlendLen
    for (i in 0 until totalFloorSafe.coerceAtMost(cave.size)) {
        val seg = cave[i]

        // Ceiling: first half open, second half blends
        val ceilVal = if (i < ceilSafe) 0f
        else if (i < SAFE_START_SEGMENTS) {
            val t = (i - ceilSafe).toFloat() / (SAFE_START_SEGMENTS - ceilSafe)
            seg.ceilingY * t
        } else seg.ceilingY

        // Floor: first floorSafe segments open, then blend over floorBlendLen
        val floorVal = if (i < floorSafe) 1f
        else if (i < totalFloorSafe) {
            val t = (i - floorSafe).toFloat() / floorBlendLen
            seg.floorY + (1f - seg.floorY) * (1f - t)
        } else seg.floorY

        cave[i] = CaveSegment(x = seg.x, ceilingY = ceilVal, floorY = floorVal)
    }

    return cave
}

/** Scroll the cave left by [scrollDp], drop off-screen segments, spawn new ones. */
fun advanceCave(
    segments: List<CaveSegment>,
    scrollDp: Float,
    screenWDp: Float,
    score: Int
): List<CaveSegment> {
    val updated = segments.toMutableList()
    updated.forEach { seg -> seg.x -= scrollDp }
    updated.removeAll { it.x < -SEGMENT_WIDTH_DP * 2 }
    while (updated.isNotEmpty() && updated.last().x < screenWDp + SEGMENT_WIDTH_DP * 3) {
        updated.add(nextSegment(updated.last(), score))
    }
    return updated
}

/**
 * Ceiling and floor heights (normalized 0..1) at horizontal position [xDp],
 * interpolated between the two bracketing segments. Null when there are none.
 */
fun caveGapAt(segments: List<CaveSegment>, xDp: Float): Pair<Float, Float>? {
    val leftIdx = segments.indexOfLast { it.x <= xDp }
    return if (leftIdx >= 0 && leftIdx < segments.size - 1) {
        val left = segments[leftIdx]
        val right = segments[leftIdx + 1]
        val t = ((xDp - left.x) / (right.x - left.x)).coerceIn(0f, 1f)
        Pair(
            left.ceilingY + (right.ceilingY - left.ceilingY) * t,
            left.floorY + (right.floorY - left.floorY) * t
        )
    } else if (segments.isNotEmpty()) {
        Pair(segments.first().ceilingY, segments.first().floorY)
    } else null
}

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuguCaveScreen(
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
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var segments by remember { mutableStateOf(listOf<CaveSegment>()) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }
    var canvasSizeDp by remember { mutableStateOf(Pair(400f, 800f)) }

    val fishMinY = 0.05f
    val fishMaxY = 0.95f

    fun resetGame() {
        fishY = 0.5f
        scrollOffset = 0f
        score = 0
        segments = buildInitialCave(canvasSizeDp.first)
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
                type = org.hubik.openfugu.session.SessionType.CAVE_GAME,
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
            val speedMultiplier = 1f + score * CAVE_SPEED_INCREMENT
            val speed = CAVE_SCROLL_SPEED_DP * speedMultiplier

            // --- Fish position from pressure ---
            val currentPressure = pressure?.relativeHPa ?: 0.0
            val targetY = calculateTargetY(currentPressure, pressureRange, negativeRange, expertMode)
            fishY += (targetY - fishY) * SMOOTHING_FACTOR * clampedDt
            fishY = fishY.coerceIn(fishMinY, fishMaxY)

            // --- Scroll segments ---
            val scrollDp = speed * clampedDt
            scrollOffset += scrollDp
            val (screenWDp, screenHDp) = canvasSizeDp
            val updated = advanceCave(segments, scrollDp, screenWDp, score)
            segments = updated

            // --- Score (distance based) ---
            score = (scrollOffset / 20f).toInt()

            // --- Collision ---
            val fishXDp = 0.25f * screenWDp
            val (ceilingAtFish, floorAtFish) = caveGapAt(updated, fishXDp)
                ?: return@runFrameLoop

            // Convert to dp positions and check overlap
            val ceilingDp = ceilingAtFish * screenHDp
            val floorDp = floorAtFish * screenHDp
            val fishYDp = fishY * screenHDp

            if (fishYDp - CAVE_FISH_RADIUS_DP < ceilingDp || fishYDp + CAVE_FISH_RADIUS_DP > floorDp) {
                highScore = max(highScore, score)
                gameState = GameState.GameOver(score)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fugu Cave") },
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

                // Report canvas size for game loop
                val wDp = w / dpToPx
                val hDp = h / dpToPx
                if (canvasSizeDp.first != wDp || canvasSizeDp.second != hDp) {
                    canvasSizeDp = Pair(wDp, hDp)
                }

                val fishRadiusPx = CAVE_FISH_RADIUS_DP * dpToPx
                val fishX = w * 0.25f
                val fishYPx = h * fishY

                // Background
                drawRect(GameBgColor)

                if (segments.size >= 2 &&
                    (gameState is GameState.Playing || gameState is GameState.GameOver)
                ) {
                    // --- Draw cave ceiling ---
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = true,
                        scrollOffsetDp = scrollOffset
                    )

                    // --- Draw cave floor ---
                    drawCaveSurface(
                        segments = segments,
                        dpToPx = dpToPx,
                        screenH = h,
                        screenW = w,
                        isCeiling = false,
                        scrollOffsetDp = scrollOffset
                    )

                    // --- Draw fish ---
                    drawFugu(fishX, fishYPx, fishRadiusPx)

                    // --- Score (top center) ---
                    drawScoreText(score, w)

                    // --- Live pressure (bottom-left) ---
                    val pressureText =
                        pressure?.let { "${formatHPa(it.relativeHPa)} hPa" } ?: "-- hPa"
                    drawPressureText(pressureText, h, dpToPx)
                }

                // --- Overlays ---
                when (gameState) {
                    is GameState.WaitingToStart -> {
                        drawFugu(w / 2f, h / 2f, fishRadiusPx * 1.5f)
                        drawOverlayText(
                            w, h,
                            if (isCalibrated && pressure != null)
                                "Tap to start\nNavigate through the cave!"
                            else
                                "Waiting for pressure data..."
                        )
                    }

                    is GameState.GameOver -> {
                        drawRect(GameOverlayBg)
                        val gameOverState = gameState as GameState.GameOver
                        drawOverlayText(
                            w, h,
                            "Game Over\n\nDistance: ${gameOverState.score}\nTap to play again"
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

/**
 * Draw one side of the cave (ceiling or floor) as a filled rocky path
 * with a highlight edge along the passage-facing surface.
 */
fun DrawScope.drawCaveSurface(
    segments: List<CaveSegment>,
    dpToPx: Float,
    screenH: Float,
    screenW: Float,
    isCeiling: Boolean,
    scrollOffsetDp: Float,
    fillColor: Color = CaveColor,
    edgeColor: Color = CaveEdge
) {
    if (segments.size < 2) return
    val jitterAmp = JITTER_AMP_DP * dpToPx

    // Build the jagged edge points along the cave surface
    val edgePoints = mutableListOf<Offset>()

    for (i in 0 until segments.size - 1) {
        val seg = segments[i]
        val next = segments[i + 1]
        val segXPx = seg.x * dpToPx
        val nextXPx = next.x * dpToPx

        // Skip if entirely off-screen
        if (nextXPx < -50f || segXPx > screenW + 50f) continue

        val segY = if (isCeiling) seg.ceilingY else seg.floorY
        val nextY = if (isCeiling) next.ceilingY else next.floorY

        // Sub-divide each segment for rocky jitter
        for (j in 0 until JITTER_POINTS_PER_SEGMENT) {
            val t = j.toFloat() / JITTER_POINTS_PER_SEGMENT
            val screenXPx = segXPx + (nextXPx - segXPx) * t
            val baseY = segY + (nextY - segY) * t

            // World-space x for deterministic jitter (doesn't shift with scrolling)
            val worldXPx = screenXPx + scrollOffsetDp * dpToPx
            val jitterSeed = worldXPx * 0.07f
            val jitter = sin(jitterSeed) * jitterAmp +
                    sin(jitterSeed * 2.3f) * jitterAmp * 0.5f
            val rawPy = screenH * baseY + if (isCeiling) jitter else -jitter
            // Clamp visuals to screen bounds (collision uses actual values)
            val py = if (isCeiling) rawPy.coerceAtLeast(0f) else rawPy.coerceAtMost(screenH)
            edgePoints.add(Offset(screenXPx, py))
        }
    }

    // Add the last segment's endpoint
    val lastSeg = segments.last()
    val lastY = if (isCeiling) lastSeg.ceilingY else lastSeg.floorY
    val lastPy = screenH * lastY
    val clampedLastPy = if (isCeiling) lastPy.coerceAtLeast(0f) else lastPy.coerceAtMost(screenH)
    edgePoints.add(Offset(lastSeg.x * dpToPx, clampedLastPy))

    if (edgePoints.isEmpty()) return

    // Skip drawing if entirely off-screen (e.g., floor below screen = breathing room)
    val allOffScreen = if (isCeiling) edgePoints.all { it.y <= 0f }
    else edgePoints.all { it.y >= screenH }
    if (allOffScreen) return

    // Fill path: edge points + extend to screen boundary (top for ceiling, bottom for floor)
    val fillPath = Path().apply {
        if (isCeiling) {
            moveTo(edgePoints.first().x, 0f)
            edgePoints.forEach { lineTo(it.x, it.y) }
            lineTo(edgePoints.last().x, 0f)
        } else {
            moveTo(edgePoints.first().x, screenH)
            edgePoints.forEach { lineTo(it.x, it.y) }
            lineTo(edgePoints.last().x, screenH)
        }
        close()
    }
    drawPath(fillPath, fillColor)

    // Highlight edge line along the passage surface
    if (edgePoints.size >= 2) {
        val edgePath = Path().apply {
            moveTo(edgePoints.first().x, edgePoints.first().y)
            for (i in 1 until edgePoints.size) {
                lineTo(edgePoints[i].x, edgePoints[i].y)
            }
        }
        drawPath(edgePath, edgeColor, style = Stroke(width = 2.5f))
    }
}

