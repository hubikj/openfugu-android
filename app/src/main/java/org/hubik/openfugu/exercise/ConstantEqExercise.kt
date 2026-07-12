package org.hubik.openfugu.exercise

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ChartLine
import org.hubik.openfugu.PressureChart
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.util.formatHPa
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.ui.StatRow

private sealed class ExerciseState {
    data object Setup : ExerciseState()
    data object Running : ExerciseState()
    data class Finished(val percentInRange: Float, val bestStreakMs: Long, val totalTimeMs: Long) : ExerciseState()
}

private enum class Difficulty(val label: String, val lowerPercent: Double, val upperPercent: Double) {
    EASY("Easy", 0.4, 1.3),
    MEDIUM("Medium", 0.6, 1.1),
    HARD("Hard", 0.7, 1.05),
    EXPERT("Expert", 0.8, 1.0)
}

private enum class Duration(val label: String, val seconds: Int) {
    SHORT("30s", 30),
    MEDIUM("60s", 60),
    LONG("2 min", 120),
    UNLIMITED("Unlimited", 0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConstantEqScreen(
    connection: PressureSource,
    lineColor: androidx.compose.ui.graphics.Color? = null,
    minEqPressureHPa: Double,
    deviceName: String = connection.displayName,
    userName: String? = null,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null,
    onBack: () -> Unit
) {
    var state by remember { mutableStateOf<ExerciseState>(ExerciseState.Setup) }
    var difficulty by remember { mutableStateOf(Difficulty.MEDIUM) }
    var duration by remember { mutableStateOf(Duration.MEDIUM) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Constant Equalization") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state is ExerciseState.Running) {
                            state = ExerciseState.Setup
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            is ExerciseState.Setup -> SetupScreen(
                minEqPressureHPa = minEqPressureHPa,
                difficulty = difficulty,
                duration = duration,
                onDifficultyChange = { difficulty = it },
                onDurationChange = { duration = it },
                onStart = { state = ExerciseState.Running },
                modifier = Modifier.padding(padding)
            )
            is ExerciseState.Running -> RunningScreen(
                connection = connection,
                lineColor = lineColor,
                minEqPressureHPa = minEqPressureHPa,
                difficulty = difficulty,
                durationSeconds = duration.seconds,
                onFinish = { percent, bestStreak, totalTime, trace, tracker ->
                    state = ExerciseState.Finished(percent, bestStreak, totalTime)
                    onSessionSave?.invoke(org.hubik.openfugu.session.Session.ConstantEqSession(
                        durationMs = totalTime,
                        deviceName = deviceName,
                        userName = userName,
                        pressureTrace = trace,
                        lowerBound = tracker.lowerBound,
                        upperBound = tracker.upperBound,
                        activationThreshold = tracker.activationThreshold,
                        scoringStartMs = tracker.scoringStartMs,
                        percentInRange = percent,
                        bestStreakMs = bestStreak,
                        difficultyLabel = difficulty.label,
                        durationSetting = duration.label
                    ))
                },
                modifier = Modifier.padding(padding)
            )
            is ExerciseState.Finished -> FinishedScreen(
                percentInRange = s.percentInRange,
                bestStreakMs = s.bestStreakMs,
                totalTimeMs = s.totalTimeMs,
                minEqPressureHPa = minEqPressureHPa,
                difficulty = difficulty,
                onTryAgain = { state = ExerciseState.Running },
                onBack = onBack,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun SetupScreen(
    minEqPressureHPa: Double,
    difficulty: Difficulty,
    duration: Duration,
    onDifficultyChange: (Difficulty) -> Unit,
    onDurationChange: (Duration) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lowerBound = minEqPressureHPa * difficulty.lowerPercent
    val upperBound = minEqPressureHPa * difficulty.upperPercent

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Constant Equalization",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Equalize to activate tracking, then maintain steady pressure within the target range.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Target range info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Target Range", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${"%.1f".format(lowerBound)} – ${"%.1f".format(upperBound)} hPa",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.inRange
                )
                Text(
                    "Activation threshold: ${"%.1f".format(minEqPressureHPa)} hPa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Difficulty
        Text("Difficulty", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Difficulty.entries.forEachIndexed { index, d ->
                SegmentedButton(
                    selected = difficulty == d,
                    onClick = { onDifficultyChange(d) },
                    shape = SegmentedButtonDefaults.itemShape(index, Difficulty.entries.size)
                ) {
                    Text(d.label, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Duration
        Text("Duration", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Duration.entries.forEachIndexed { index, d ->
                SegmentedButton(
                    selected = duration == d,
                    onClick = { onDurationChange(d) },
                    shape = SegmentedButtonDefaults.itemShape(index, Duration.entries.size)
                ) {
                    Text(d.label, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
    }
}

@Composable
private fun RunningScreen(
    connection: PressureSource,
    lineColor: androidx.compose.ui.graphics.Color? = null,
    minEqPressureHPa: Double,
    difficulty: Difficulty,
    durationSeconds: Int,
    onFinish: (percentInRange: Float, bestStreakMs: Long, totalTimeMs: Long, pressureTrace: List<PressureReading>, tracker: RangeTracker) -> Unit,
    modifier: Modifier = Modifier
) {
    val tracker = remember(difficulty) {
        RangeTracker.create(minEqPressureHPa, difficulty.lowerPercent, difficulty.upperPercent)
    }
    val exerciseStartMs = remember { System.currentTimeMillis() }
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()

    // Mutable snapshots of tracker state for recomposition
    var activated by remember { mutableStateOf(false) }
    var scoring by remember { mutableStateOf(false) }
    var isInRange by remember { mutableStateOf(false) }
    var timeInRangeMs by remember { mutableLongStateOf(0L) }
    var totalTimeMs by remember { mutableLongStateOf(0L) }
    var currentStreakMs by remember { mutableLongStateOf(0L) }
    var bestStreakMs by remember { mutableLongStateOf(0L) }
    var percentInRange by remember { mutableFloatStateOf(0f) }
    var graceRemainingMs by remember { mutableLongStateOf(0L) }
    var scoringStartMs by remember { mutableLongStateOf(0L) }

    // Feed pressure to tracker
    LaunchedEffect(latestPressure) {
        val reading = latestPressure ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        tracker.addSample(reading.relativeHPa, now)
        activated = tracker.activated
        scoring = tracker.scoring
        scoringStartMs = tracker.scoringStartMs
        isInRange = tracker.isInRange
        timeInRangeMs = tracker.timeInRangeMs
        totalTimeMs = tracker.totalTimeMs
        currentStreakMs = tracker.currentStreakMs
        bestStreakMs = tracker.bestStreakMs
        percentInRange = tracker.percentageInRange
        graceRemainingMs = tracker.graceRemainingMs(now)
    }

    // Auto-finish when duration reached
    LaunchedEffect(totalTimeMs) {
        if (durationSeconds > 0 && totalTimeMs >= durationSeconds * 1000L) {
            onFinish(percentInRange, bestStreakMs, totalTimeMs, connection.historySnapshot().filter { it.timestamp >= exerciseStartMs }, tracker)
        }
    }

    val inRangeColor = AppColors.inRange
    val outOfRangeColor = AppColors.outOfRange
    val rangeColor = AppColors.inRangeFill
    val currentPressure = latestPressure?.relativeHPa ?: 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        // Status
        if (!activated) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Equalize to start!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Cross ${"%.1f".format(minEqPressureHPa)} hPa to activate tracking",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        } else if (!scoring) {
            // Grace period — settling into the target range
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Get into target range!", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Scoring starts in ${(graceRemainingMs / 1000) + 1}s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            // Live stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("In Range", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${"%.0f".format(percentInRange * 100)}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (percentInRange >= 0.75f) inRangeColor else outOfRangeColor
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Streak", style = MaterialTheme.typography.labelSmall)
                    Text(
                        formatDuration(currentStreakMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isInRange) inRangeColor else MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Best", style = MaterialTheme.typography.labelSmall)
                    Text(
                        formatDuration(bestStreakMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (durationSeconds > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Remaining", style = MaterialTheme.typography.labelSmall)
                        val remainingMs = (durationSeconds * 1000L - totalTimeMs).coerceAtLeast(0)
                        Text(
                            formatDuration(remainingMs),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Pressure display
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${formatHPa(currentPressure)} hPa",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !scoring -> MaterialTheme.colorScheme.onSurface
                        isInRange -> inRangeColor
                        else -> outOfRangeColor
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chart with target range
        if (chartData.size >= 2) {
            PressureChart(
                lines = listOf(ChartLine(chartData, lineColor)),
                activationThreshold = minEqPressureHPa,
                targetRange = Pair(tracker.lowerBound, tracker.upperBound),
                showTargetRange = activated,
                scoringStartMs = scoringStartMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Waiting for data...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Manual stop button
        if (activated) {
            OutlinedButton(
                onClick = { onFinish(percentInRange, bestStreakMs, totalTimeMs, connection.historySnapshot().filter { it.timestamp >= exerciseStartMs }, tracker) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop")
            }
        }
    }
}



@Composable
private fun FinishedScreen(
    percentInRange: Float,
    bestStreakMs: Long,
    totalTimeMs: Long,
    minEqPressureHPa: Double,
    difficulty: Difficulty,
    onTryAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rating = when {
        percentInRange >= 0.90f -> "Excellent!"
        percentInRange >= 0.75f -> "Good"
        percentInRange >= 0.50f -> "Keep practicing"
        else -> "Try again"
    }
    val ratingColor = when {
        percentInRange >= 0.90f -> AppColors.inRange
        percentInRange >= 0.75f -> AppColors.inRange
        percentInRange >= 0.50f -> AppColors.warning
        else -> AppColors.outOfRange
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            rating,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = ratingColor
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Time in range", "${"%.0f".format(percentInRange * 100)}%")
                StatRow("Best streak", formatDuration(bestStreakMs))
                StatRow("Total time", formatDuration(totalTimeMs))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                StatRow("Difficulty", difficulty.label)
                StatRow("Target range", "${"%.1f".format(minEqPressureHPa * difficulty.lowerPercent)} – ${"%.1f".format(minEqPressureHPa * difficulty.upperPercent)} hPa")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Try Again")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}


private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
