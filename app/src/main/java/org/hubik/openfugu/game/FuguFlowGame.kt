package org.hubik.openfugu.game

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.util.formatHPa
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// Game constants
// =============================================================================

// Scoring — 2D distance to nearest point on target curve within a time window
private const val SCORING_WINDOW_SEC = 1.5f  // ±1.5s — covers max reachable time offset for OK zone
private const val SCORING_WINDOW_STEPS = 31  // 30 segments over 3s window, ~0.1s resolution

// TIME_WEIGHT is computed dynamically from canvas dimensions so that the 2D
// distance matches visual (pixel) distance on screen. See computeTimeWeight().

// Scoring zones — 2D perpendicular distance to curve in normalized units
private const val PERFECT_ZONE = 0.05f
private const val GOOD_ZONE = 0.10f
private const val OK_ZONE = 0.18f

// Points per second in each zone
private const val PERFECT_PPS = 100
private const val GOOD_PPS = 60
private const val OK_PPS = 20

// Combo
private const val COMBO_THRESHOLD_SEC = 1.0f
private const val MAX_COMBO_MULTIPLIER = 4

// Grace period before scoring starts
private const val GRACE_PERIOD_SEC = 3f

// Visual
private val BgColor = Color(0xFF0A1628)
private val TargetCurveColor = Color(0xFF4FC3F7)
private val TargetCurveAhead = Color(0x664FC3F7)
private val PerfectColor = Color(0xFF66BB6A)
private val GoodColor = Color(0xFFFFEB3B)
private val OkColor = Color(0xFFFF9800)
private val MissColor = Color(0xFFEF5350)
private val GridColor = Color(0x22FFFFFF)
private val CountdownColor = Color(0xCCFFFFFF)

// Timing
private const val LOOKAHEAD_SEC = 3f
private const val LOOKBEHIND_SEC = 2f
private const val CURSOR_X_FRACTION = 0.3f

// =============================================================================
// Patterns
// =============================================================================

data class FlowKeyframe(val timeSec: Float, val targetFraction: Float)

data class FlowPattern(
    val name: String,
    val description: String,
    val durationSec: Float,
    val keyframes: List<FlowKeyframe>
)

private data class PatternSegment(
    val name: String,
    val durationSec: Float,
    val keyframes: List<FlowKeyframe>
)

private fun buildPatterns(): List<FlowPattern> {
    val gentleWave = FlowPattern(
        name = "Gentle Wave",
        description = "Smooth, slow pressure waves",
        durationSec = 30f,
        keyframes = (0..60).map { i ->
            val t = i * 0.5f
            val v = 0.25f + 0.2f * sin(t * 0.8f).toFloat()
            FlowKeyframe(t, v)
        }
    )

    val pulseTrain = run {
        val kf = mutableListOf<FlowKeyframe>()
        var t = 0f
        for (pulse in 0 until 10) {
            kf.add(FlowKeyframe(t, 0.05f))
            kf.add(FlowKeyframe(t + 0.3f, 0.05f))
            kf.add(FlowKeyframe(t + 0.8f, 0.55f))
            kf.add(FlowKeyframe(t + 1.3f, 0.55f))
            kf.add(FlowKeyframe(t + 1.8f, 0.05f))
            t += 3f
        }
        FlowPattern("Pulse Train", "Quick equalization bursts with rests between", t, kf)
    }

    val staircase = run {
        val kf = mutableListOf<FlowKeyframe>()
        val levels = listOf(0.05f, 0.2f, 0.4f, 0.6f, 0.75f, 0.6f, 0.4f, 0.2f, 0.05f)
        var t = 0f
        for (level in levels) {
            kf.add(FlowKeyframe(t, level))
            kf.add(FlowKeyframe(t + 2.5f, level))
            t += 3f
        }
        FlowPattern("Staircase", "Step through increasing pressure levels", t, kf)
    }

    val mountain = run {
        val kf = mutableListOf<FlowKeyframe>()
        val duration = 24f
        for (i in 0..48) {
            val t = i * (duration / 48f)
            val frac = t / duration
            val v = 0.05f + 0.7f * (4f * frac * (1f - frac))
            kf.add(FlowKeyframe(t, v))
        }
        FlowPattern("Mountain", "Gradual climb to peak pressure and back", duration, kf)
    }

    val choppySeas = FlowPattern(
        name = "Choppy Seas",
        description = "Fast, irregular pressure changes",
        durationSec = 30f,
        keyframes = (0..90).map { i ->
            val t = i * (30f / 90f)
            val v = 0.35f + 0.25f * sin(t * 1.5f).toFloat() + 0.1f * sin(t * 3.7f).toFloat()
            FlowKeyframe(t, v.coerceIn(0.05f, 0.95f))
        }
    )

    return listOf(gentleWave, pulseTrain, staircase, mountain, choppySeas)
}

private fun buildSegments(): List<PatternSegment> = listOf(
    PatternSegment("wave", 6f, (0..12).map { i ->
        val t = i * 0.5f
        FlowKeyframe(t, 0.25f + 0.2f * sin(t * 1.2f).toFloat())
    }),
    PatternSegment("pulses", 6f, listOf(
        FlowKeyframe(0f, 0.05f), FlowKeyframe(0.3f, 0.05f),
        FlowKeyframe(0.8f, 0.55f), FlowKeyframe(1.3f, 0.55f),
        FlowKeyframe(1.8f, 0.05f), FlowKeyframe(3f, 0.05f),
        FlowKeyframe(3.3f, 0.05f), FlowKeyframe(3.8f, 0.55f),
        FlowKeyframe(4.3f, 0.55f), FlowKeyframe(4.8f, 0.05f),
        FlowKeyframe(6f, 0.05f)
    )),
    PatternSegment("ramp_up", 6f, listOf(
        FlowKeyframe(0f, 0.05f), FlowKeyframe(2f, 0.6f),
        FlowKeyframe(5f, 0.6f), FlowKeyframe(6f, 0.05f)
    )),
    PatternSegment("stairs", 6f, listOf(
        FlowKeyframe(0f, 0.1f), FlowKeyframe(1.5f, 0.1f),
        FlowKeyframe(2f, 0.35f), FlowKeyframe(3.5f, 0.35f),
        FlowKeyframe(4f, 0.6f), FlowKeyframe(5.5f, 0.6f),
        FlowKeyframe(6f, 0.1f)
    )),
    PatternSegment("rest", 4f, listOf(
        FlowKeyframe(0f, 0.05f), FlowKeyframe(4f, 0.05f)
    )),
    PatternSegment("choppy", 6f, (0..18).map { i ->
        val t = i * (6f / 18f)
        val v = 0.35f + 0.25f * sin(t * 2f).toFloat() + 0.1f * sin(t * 5f).toFloat()
        FlowKeyframe(t, v.coerceIn(0.05f, 0.95f))
    })
)

private fun buildRandomMix(targetDurationSec: Float = 45f): FlowPattern {
    val segments = buildSegments()
    val kf = mutableListOf<FlowKeyframe>()
    var t = 0f

    kf.add(FlowKeyframe(0f, 0.05f))
    kf.add(FlowKeyframe(1f, 0.05f))
    t = 1f

    val rng = java.util.Random()
    var lastEndValue = 0.05f

    while (t < targetDurationSec) {
        val seg = segments[rng.nextInt(segments.size)]
        val segStartValue = seg.keyframes.first().targetFraction
        if (abs(lastEndValue - segStartValue) > 0.02f) {
            kf.add(FlowKeyframe(t + 0.5f, segStartValue))
            t += 0.5f
        }
        seg.keyframes.forEach { skf ->
            kf.add(FlowKeyframe(t + skf.timeSec, skf.targetFraction))
        }
        t += seg.durationSec
        lastEndValue = seg.keyframes.last().targetFraction
    }

    if (lastEndValue > 0.1f) {
        kf.add(FlowKeyframe(t + 0.5f, 0.05f))
        t += 0.5f
    }

    return FlowPattern(
        name = "Random Mix",
        description = "Random chain of pattern segments",
        durationSec = t,
        keyframes = kf
    )
}

/**
 * Shifts all keyframes vertically so the midpoint between the pattern's
 * lowest and highest values sits at 0.5 — zero pressure in the expert-mode
 * mapping. Patterns are authored against the normal-mode mapping (fraction
 * 0 = zero pressure); used unshifted in expert mode they would sit entirely
 * in the negative-pressure half of the screen.
 */
internal fun FlowPattern.centeredOnZeroPressure(): FlowPattern {
    if (keyframes.isEmpty()) return this
    val minFraction = keyframes.minOf { it.targetFraction }
    val maxFraction = keyframes.maxOf { it.targetFraction }
    val offset = 0.5f - (minFraction + maxFraction) / 2f
    return copy(keyframes = keyframes.map { it.copy(targetFraction = it.targetFraction + offset) })
}

private fun FlowPattern.targetAt(timeSec: Float): Float {
    if (keyframes.isEmpty()) return 0f
    if (timeSec <= keyframes.first().timeSec) return keyframes.first().targetFraction
    if (timeSec >= keyframes.last().timeSec) return keyframes.last().targetFraction
    val nextIdx = keyframes.indexOfFirst { it.timeSec >= timeSec }
    if (nextIdx <= 0) return keyframes.first().targetFraction
    val prev = keyframes[nextIdx - 1]
    val next = keyframes[nextIdx]
    val frac = (timeSec - prev.timeSec) / (next.timeSec - prev.timeSec)
    return prev.targetFraction + (next.targetFraction - prev.targetFraction) * frac
}

/**
 * Compute the time weight so 2D distance matches visual (pixel) distance.
 *
 * On screen: 1 second = canvasWidth / totalVisibleSec pixels,
 *            1 Y-unit = canvasHeight pixels.
 *
 * To normalize: timeWeight = (canvasWidth / totalVisibleSec) / canvasHeight
 * This makes dx and dy in the same visual units.
 */
private fun computeTimeWeight(canvasWidth: Float, canvasHeight: Float): Float {
    val totalVisibleSec = LOOKBEHIND_SEC + LOOKAHEAD_SEC
    return (canvasWidth / totalVisibleSec) / canvasHeight
}

/**
 * Find the minimum 2D distance from the player's position to the target curve
 * within a ±[SCORING_WINDOW_SEC] time window.
 *
 * Computes proper point-to-line-segment distance for each segment of the
 * target curve, so steep transitions are scored fairly — being close to a
 * steep line counts the same as being close to a flat line.
 *
 * Both axes are normalized to visual proportions using [timeWeight].
 */
private fun FlowPattern.minDistance2D(timeSec: Float, playerNormY: Float, timeWeight: Float): Float {
    val windowStart = (timeSec - SCORING_WINDOW_SEC).coerceAtLeast(0f)
    val windowEnd = (timeSec + SCORING_WINDOW_SEC).coerceAtMost(durationSec)

    // Player point in visually-proportional 2D space
    val px = timeSec * timeWeight
    val py = playerNormY

    var minDist = Float.MAX_VALUE

    val samples = SCORING_WINDOW_STEPS
    var prevX = 0f
    var prevY = 0f
    for (i in 0..samples) {
        val t = windowStart + (windowEnd - windowStart) * i / samples
        val frac = targetAt(t)
        val sx = t * timeWeight
        val sy = 1f - frac

        if (i > 0) {
            // Point-to-segment distance from (px, py) to segment (prevX, prevY)-(sx, sy)
            val dist = pointToSegmentDistance(px, py, prevX, prevY, sx, sy)
            minDist = min(minDist, dist)
        }
        prevX = sx
        prevY = sy
    }
    return minDist
}

/** Euclidean distance from point (px, py) to the closest point on segment (ax, ay)-(bx, by). */
private fun pointToSegmentDistance(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1e-10f) {
        // Degenerate segment (single point)
        val ex = px - ax
        val ey = py - ay
        return sqrt(ex * ex + ey * ey)
    }
    // Project point onto the line, clamped to segment
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
    val closestX = ax + t * dx
    val closestY = ay + t * dy
    val ex = px - closestX
    val ey = py - closestY
    return sqrt(ex * ex + ey * ey)
}

// =============================================================================
// Screen composable
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuguFlowScreen(
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

    val fixedPatterns = remember { buildPatterns() }

    var gameState by remember { mutableStateOf<GameState>(GameState.WaitingToStart) }
    var selectedPatternIndex by remember { mutableIntStateOf(0) }
    var activePattern by remember { mutableStateOf<FlowPattern?>(null) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var elapsedSec by remember { mutableFloatStateOf(0f) }
    var playerY by remember { mutableFloatStateOf(0.5f) }
    // Accumulated as Float: integer-per-frame truncation would round sub-1
    // per-frame gains down to zero and make scores frame-rate dependent.
    var score by remember { mutableFloatStateOf(0f) }
    var comboTime by remember { mutableFloatStateOf(0f) }
    var comboMultiplier by remember { mutableIntStateOf(1) }
    var currentZone by remember { mutableStateOf("") }
    var perfectTime by remember { mutableFloatStateOf(0f) }
    var goodTime by remember { mutableFloatStateOf(0f) }
    var okTime by remember { mutableFloatStateOf(0f) }
    var missTime by remember { mutableFloatStateOf(0f) }
    var playerTrail by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    var timeWeight by remember { mutableFloatStateOf(0.12f) } // updated from canvas dimensions

    val patternEntries = remember(fixedPatterns) {
        fixedPatterns.map { it.name to it.description } +
            ("Random Mix" to "Random chain of pattern segments")
    }

    fun resetGame() {
        val basePattern = if (selectedPatternIndex < fixedPatterns.size) {
            fixedPatterns[selectedPatternIndex]
        } else {
            buildRandomMix()
        }
        val pattern = if (expertMode && negativeRange > 0.0) {
            basePattern.centeredOnZeroPressure()
        } else {
            basePattern
        }
        activePattern = pattern
        elapsedSec = -GRACE_PERIOD_SEC
        playerY = 1f - pattern.targetAt(0f)
        score = 0f
        comboTime = 0f
        comboMultiplier = 1
        currentZone = ""
        perfectTime = 0f
        goodTime = 0f
        okTime = 0f
        missTime = 0f
        playerTrail = emptyList()
        gameStartMs = System.currentTimeMillis()
        gameState = GameState.Playing
    }

    LaunchedEffect(gameState) {
        val gs = gameState
        if (gs is GameState.GameOver && onSessionSave != null) {
            val endMs = System.currentTimeMillis()
            onSessionSave.invoke(org.hubik.openfugu.session.Session.GameSession(
                durationMs = endMs - gameStartMs,
                deviceName = deviceName,
                userName = userName,
                pressureTrace = connection.historySnapshot().filter { it.timestamp in gameStartMs..endMs },
                type = org.hubik.openfugu.session.SessionType.FLOW_GAME,
                score = gs.score,
                pressureRange = pressureRange,
                negativeRange = negativeRange,
                expertMode = expertMode
            ))
        }
    }

    LaunchedEffect(gameState) {
        val pattern = activePattern ?: return@LaunchedEffect

        runFrameLoop({ gameState is GameState.Playing }) { clampedDt ->
            elapsedSec += clampedDt

            if (elapsedSec >= pattern.durationSec) {
                gameState = GameState.GameOver(score.toInt())
                return@runFrameLoop
            }

            val currentPressure = pressure?.relativeHPa ?: 0.0
            val targetY = calculateTargetY(currentPressure, pressureRange, negativeRange, expertMode)
            playerY += (targetY - playerY) * SMOOTHING_FACTOR * clampedDt
            playerY = playerY.coerceIn(0f, 1f)

            if (elapsedSec >= 0f) {
                playerTrail = (playerTrail + (elapsedSec to playerY))
                    .filter { it.first > elapsedSec - LOOKBEHIND_SEC - 1f }
            }

            // Only score after grace period
            if (elapsedSec < 0f) return@runFrameLoop

            val dist = pattern.minDistance2D(elapsedSec, playerY, timeWeight)

            val zone: String
            val pps: Int
            when {
                dist <= PERFECT_ZONE -> { zone = "Perfect"; pps = PERFECT_PPS }
                dist <= GOOD_ZONE -> { zone = "Good"; pps = GOOD_PPS }
                dist <= OK_ZONE -> { zone = "OK"; pps = OK_PPS }
                else -> { zone = "Miss"; pps = 0 }
            }
            currentZone = zone

            when (zone) {
                "Perfect" -> perfectTime += clampedDt
                "Good" -> goodTime += clampedDt
                "OK" -> okTime += clampedDt
                "Miss" -> missTime += clampedDt
            }

            if (zone == "Perfect" || zone == "Good") {
                comboTime += clampedDt
                comboMultiplier = (1 + (comboTime / COMBO_THRESHOLD_SEC).toInt())
                    .coerceAtMost(MAX_COMBO_MULTIPLIER)
            } else {
                comboTime = 0f
                comboMultiplier = 1
            }

            score += pps * comboMultiplier * clampedDt
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fugu Flow") },
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
        ) {
            when (gameState) {
                is GameState.WaitingToStart -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Choose a Pattern",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        patternEntries.forEachIndexed { index, (name, description) ->
                            val pattern = fixedPatterns.getOrNull(index)
                            val isSelected = index == selectedPatternIndex
                            val previewColor = MaterialTheme.colorScheme.primary
                            // Selection = primary border + subtle tint. A filled
                            // primaryContainer washed out the text and preview
                            // curve with some dynamic color schemes.
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedPatternIndex = index },
                                colors = if (isSelected)
                                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                else CardDefaults.cardColors(),
                                border = if (isSelected)
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Canvas(modifier = Modifier.size(48.dp)) {
                                        if (pattern != null) {
                                            drawPatternPreview(pattern, previewColor)
                                        } else {
                                            drawRandomPreview(previewColor)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (pattern != null) {
                                            Text(
                                                "${pattern.durationSec.toInt()} seconds",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                if (isCalibrated && pressure != null) resetGame()
                            },
                            enabled = isCalibrated && pressure != null
                        ) {
                            Text(
                                if (isCalibrated && pressure != null) "Start"
                                else "Waiting for pressure data..."
                            )
                        }
                    }
                }

                is GameState.Playing -> {
                    val pattern = activePattern
                    if (pattern != null) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { /* absorb taps */ }
                        ) {
                            // Compute visually-proportional time weight from actual canvas size
                            timeWeight = computeTimeWeight(size.width, size.height)
                            drawFlowGame(
                                w = size.width,
                                h = size.height,
                                dpToPx = density.density,
                                pattern = pattern,
                                elapsedSec = elapsedSec,
                                playerY = playerY,
                                playerTrail = playerTrail,
                                score = score.toInt(),
                                comboMultiplier = comboMultiplier,
                                currentZone = currentZone,
                                timeWeight = timeWeight,
                                pressureText = pressure?.let { "${formatHPa(it.relativeHPa)} hPa" } ?: "-- hPa"
                            )
                        }
                    }
                }

                is GameState.GameOver -> {
                    val gs = gameState as GameState.GameOver
                    val patternName = activePattern?.name ?: "Unknown"
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Flow Complete",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            patternName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            "${gs.score}",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "points",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        val totalTime = perfectTime + goodTime + okTime + missTime
                        if (totalTime > 0) {
                            FlowStatRow("Perfect", perfectTime, totalTime, PerfectColor)
                            FlowStatRow("Good", goodTime, totalTime, GoodColor)
                            FlowStatRow("OK", okTime, totalTime, OkColor)
                            FlowStatRow("Miss", missTime, totalTime, MissColor)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { gameState = GameState.WaitingToStart }) {
                                Text("Patterns")
                            }
                            Button(onClick = { resetGame() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowStatRow(label: String, time: Float, total: Float, color: Color) {
    val pct = if (total > 0) (time / total * 100).toInt() else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(12.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = { time / total },
            modifier = Modifier
                .weight(1f)
                .height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$pct%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(36.dp)
        )
    }
}

// =============================================================================
// Drawing
// =============================================================================

private fun DrawScope.drawFlowGame(
    w: Float,
    h: Float,
    dpToPx: Float,
    pattern: FlowPattern,
    elapsedSec: Float,
    playerY: Float,
    playerTrail: List<Pair<Float, Float>>,
    score: Int,
    comboMultiplier: Int,
    currentZone: String,
    timeWeight: Float,
    pressureText: String
) {
    drawRect(BgColor)

    for (i in 1..4) {
        val y = h * i / 5f
        drawLine(GridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }

    val cursorX = w * CURSOR_X_FRACTION
    val totalVisibleSec = LOOKBEHIND_SEC + LOOKAHEAD_SEC
    val leftTime = elapsedSec - LOOKBEHIND_SEC
    val rightTime = elapsedSec + LOOKAHEAD_SEC

    fun tToX(t: Float): Float =
        cursorX + (t - elapsedSec) / totalVisibleSec * w

    // Grace period countdown
    if (elapsedSec < 0f) {
        val countdown = (-elapsedSec).toInt() + 1
        drawContext.canvas.nativeCanvas.drawText(
            "$countdown",
            w / 2f,
            h / 2f + 40f,
            android.graphics.Paint().apply {
                color = CountdownColor.toArgb()
                textSize = 96f
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
        drawContext.canvas.nativeCanvas.drawText(
            "Get ready...",
            w / 2f,
            h / 2f + 100f,
            android.graphics.Paint().apply {
                color = CountdownColor.toArgb()
                textSize = 32f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
    }

    // Scoring zones (only after grace period)
    val scoringTime = elapsedSec.coerceAtLeast(0f)
    val targetFracAtCursor = pattern.targetAt(scoringTime)
    val targetYAtCursor = h * (1f - targetFracAtCursor)

    if (elapsedSec >= 0f) {
        val perfectHalfH = h * PERFECT_ZONE
        val goodHalfH = h * GOOD_ZONE
        val okHalfH = h * OK_ZONE

        drawRect(
            OkColor.copy(alpha = 0.08f),
            Offset(0f, targetYAtCursor - okHalfH),
            androidx.compose.ui.geometry.Size(w, okHalfH * 2)
        )
        drawRect(
            GoodColor.copy(alpha = 0.08f),
            Offset(0f, targetYAtCursor - goodHalfH),
            androidx.compose.ui.geometry.Size(w, goodHalfH * 2)
        )
        drawRect(
            PerfectColor.copy(alpha = 0.1f),
            Offset(0f, targetYAtCursor - perfectHalfH),
            androidx.compose.ui.geometry.Size(w, perfectHalfH * 2)
        )
    }

    // Target curve — full (dimmed)
    val targetPath = Path()
    var started = false
    val steps = 100
    for (i in 0..steps) {
        val t = leftTime + (rightTime - leftTime) * i / steps
        if (t < 0f || t > pattern.durationSec) continue
        val frac = pattern.targetAt(t)
        val x = tToX(t)
        val y = h * (1f - frac)
        if (!started) { targetPath.moveTo(x, y); started = true }
        else targetPath.lineTo(x, y)
    }
    drawPath(targetPath, TargetCurveAhead, style = Stroke(width = 3f * dpToPx, cap = StrokeCap.Round))

    // Target curve — past portion (bright)
    val pastPath = Path()
    var pastStarted = false
    val pastEnd = elapsedSec.coerceAtLeast(0f)
    for (i in 0..steps) {
        val t = leftTime + (rightTime - leftTime) * i / steps
        if (t < 0f || t > pastEnd || t > pattern.durationSec) continue
        val frac = pattern.targetAt(t)
        val x = tToX(t)
        val y = h * (1f - frac)
        if (!pastStarted) { pastPath.moveTo(x, y); pastStarted = true }
        else pastPath.lineTo(x, y)
    }
    drawPath(pastPath, TargetCurveColor, style = Stroke(width = 3f * dpToPx, cap = StrokeCap.Round))

    // Player trail with zone-based coloring (2D distance)
    if (playerTrail.size >= 2) {
        for (i in 1 until playerTrail.size) {
            val (t0, y0) = playerTrail[i - 1]
            val (t1, y1) = playerTrail[i]
            val x0 = tToX(t0)
            val x1 = tToX(t1)
            if (x1 < 0f || x0 > w) continue

            val midT = (t0 + t1) / 2f
            val midPlayerY = (y0 + y1) / 2f
            val dist = pattern.minDistance2D(midT, midPlayerY, timeWeight)

            val lineColor = when {
                dist <= PERFECT_ZONE -> PerfectColor
                dist <= GOOD_ZONE -> GoodColor
                dist <= OK_ZONE -> OkColor
                else -> MissColor
            }

            drawLine(
                lineColor,
                Offset(x0, y0 * h),
                Offset(x1, y1 * h),
                strokeWidth = 2.5f * dpToPx,
                cap = StrokeCap.Round
            )
        }
    }

    // Cursor dot
    val playerPxY = playerY * h
    val zoneColor = when (currentZone) {
        "Perfect" -> PerfectColor
        "Good" -> GoodColor
        "OK" -> OkColor
        else -> if (elapsedSec < 0f) Color.White else MissColor
    }
    drawCircle(zoneColor, 8f * dpToPx, Offset(cursorX, playerPxY))
    drawCircle(Color.White, 4f * dpToPx, Offset(cursorX, playerPxY))

    // Target dot
    if (elapsedSec >= 0f) {
        drawCircle(TargetCurveColor.copy(alpha = 0.5f), 6f * dpToPx, Offset(cursorX, targetYAtCursor))
    }

    // Cursor line
    drawLine(Color.White.copy(alpha = 0.15f), Offset(cursorX, 0f), Offset(cursorX, h), strokeWidth = 1f)

    // Progress bar
    val progress = (elapsedSec / pattern.durationSec).coerceIn(0f, 1f)
    drawRect(Color.White.copy(alpha = 0.1f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, 3f * dpToPx))
    drawRect(TargetCurveColor, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w * progress, 3f * dpToPx))

    // Score + combo (after grace period)
    if (elapsedSec >= 0f) {
        drawScoreText(score, w)

        if (comboMultiplier > 1) {
            drawContext.canvas.nativeCanvas.drawText(
                "${comboMultiplier}x",
                w / 2f,
                120f,
                android.graphics.Paint().apply {
                    color = GoodColor.toArgb()
                    textSize = 36f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }

        if (currentZone.isNotEmpty()) {
            drawContext.canvas.nativeCanvas.drawText(
                currentZone,
                cursorX,
                playerPxY - 16f * dpToPx,
                android.graphics.Paint().apply {
                    color = zoneColor.toArgb()
                    textSize = 14f * dpToPx
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }
    }

    drawPressureText(pressureText, h, dpToPx)
}

private fun DrawScope.drawPatternPreview(pattern: FlowPattern, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path()
    val steps = 40
    for (i in 0..steps) {
        val t = pattern.durationSec * i / steps
        val frac = pattern.targetAt(t)
        val x = w * i / steps
        val y = h * (1f - frac)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2f))
}

private fun DrawScope.drawRandomPreview(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.1f, h * 0.6f)
        lineTo(w * 0.25f, h * 0.3f)
        lineTo(w * 0.4f, h * 0.7f)
        lineTo(w * 0.55f, h * 0.2f)
        lineTo(w * 0.7f, h * 0.5f)
        lineTo(w * 0.9f, h * 0.4f)
    }
    drawPath(path, color, style = Stroke(width = 2f))
    drawCircle(color.copy(alpha = 0.4f), 3f, Offset(w * 0.3f, h * 0.8f))
    drawCircle(color.copy(alpha = 0.4f), 3f, Offset(w * 0.6f, h * 0.85f))
    drawCircle(color.copy(alpha = 0.4f), 3f, Offset(w * 0.8f, h * 0.75f))
}
