package org.hubik.openfugu.exercise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ChartLine
import org.hubik.openfugu.PressureChart
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.PeakDetector
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.util.formatHPa
import androidx.compose.foundation.clickable
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.ui.BaselineDriftDialog
import org.hubik.openfugu.ui.PeakConfirmDialog
import org.hubik.openfugu.ui.StatRow
import kotlin.math.sqrt

data class PeakMarker(
    val timestamp: Long,
    val valueHPa: Double,
    val successful: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinEqExerciseScreen(
    connection: PressureSource,
    lineColor: androidx.compose.ui.graphics.Color? = null,
    userProfiles: List<UserProfile>,
    currentProfileId: String?,
    deviceName: String = connection.displayName,
    userName: String? = null,
    onSave: (profileId: String, minEqHPa: Double) -> Unit,
    onSessionSave: ((org.hubik.openfugu.session.Session) -> Unit)? = null,
    onBack: () -> Unit
) {
    val detector = remember { PeakDetector(minPeakAmplitude = 5.0) }
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()

    var detectedPeaks by remember { mutableStateOf<List<Double>>(emptyList()) }
    var successfulPeaks by remember { mutableStateOf<List<Double>>(emptyList()) }
    var peakMarkers by remember { mutableStateOf<List<PeakMarker>>(emptyList()) }
    var failedCount by remember { mutableIntStateOf(0) }
    var lastDetectedPeak by remember { mutableStateOf<Double?>(null) }
    var lastDetectedPeakTimestamp by remember { mutableLongStateOf(0L) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var detectorStuck by remember { mutableStateOf(false) }
    var stuckDialogDismissed by remember { mutableStateOf(false) }

    // Feed the detector from the flow directly — snapshot state (collectAsState)
    // is conflated per frame and would drop samples.
    LaunchedEffect(connection) {
        connection.latestPressure.collect { reading ->
            if (reading == null) return@collect
            val peak = detector.addSample(reading.relativeHPa)
            detectorStuck = detector.isStuck
            if (detectorStuck && showConfirmDialog) {
                // Baseline drift makes the pending peak value suspect — drop it
                // without counting it as confirmed or rejected
                showConfirmDialog = false
                lastDetectedPeak = null
            }
            if (!detectorStuck) stuckDialogDismissed = false
            if (peak != null && !showConfirmDialog) {
                lastDetectedPeak = peak.peakValueHPa
                lastDetectedPeakTimestamp = peak.timestamp
                detectedPeaks = detectedPeaks + peak.peakValueHPa
                showConfirmDialog = true
            }
        }
    }

    val mean = if (successfulPeaks.isNotEmpty()) successfulPeaks.average() else null
    val stddev = if (successfulPeaks.size >= 2) {
        val avg = successfulPeaks.average()
        sqrt(successfulPeaks.map { (it - avg) * (it - avg) }.average())
    } else null
    val isStable = successfulPeaks.size >= 5 && stddev != null && stddev < 2.0
    val exerciseStartMs = remember { System.currentTimeMillis() }

    // Auto-save session when leaving the exercise (if any peaks were detected)
    DisposableEffect(Unit) {
        onDispose {
            if (peakMarkers.isNotEmpty() && onSessionSave != null) {
                val endMs = System.currentTimeMillis()
                val saveMean = if (successfulPeaks.isNotEmpty()) successfulPeaks.average() else 0.0
                val saveStddev = if (successfulPeaks.size >= 2) {
                    val avg = successfulPeaks.average()
                    sqrt(successfulPeaks.map { (it - avg) * (it - avg) }.average())
                } else null
                onSessionSave.invoke(org.hubik.openfugu.session.Session.MinEqSession(
                    durationMs = endMs - exerciseStartMs,
                    deviceName = deviceName,
                    userName = userName,
                    pressureTrace = connection.historySnapshot().filter { it.timestamp in exerciseStartMs..endMs },
                    peakMarkers = peakMarkers,
                    mean = saveMean,
                    stddev = saveStddev,
                    successCount = successfulPeaks.size,
                    failCount = failedCount
                ))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Minimum Equalization Pressure") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Equalize gently with the minimum pressure needed.\n" +
                    "After each detected peak, confirm if it was a successful equalization.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Live pressure
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Current Pressure", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${formatHPa(latestPressure?.relativeHPa ?: 0.0)} hPa",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (detectorStuck) {
                Text(
                    "Pressure has not returned to zero — tap for options.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { stuckDialogDismissed = false }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Live chart with peak markers
            if (chartData.size >= 2) {
                PressureChart(
                    lines = listOf(ChartLine(chartData, lineColor)),
                    peakMarkers = peakMarkers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Detected Peaks", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Successful equalizations", "${successfulPeaks.size}")
                    StatRow("Failed/Rejected", "$failedCount")
                    if (mean != null) {
                        StatRow("Mean", "${"%.1f".format(mean)} hPa")
                    }
                    if (stddev != null) {
                        StatRow("Std Dev", "${"%.1f".format(stddev)} hPa")
                    }
                    if (successfulPeaks.isNotEmpty()) {
                        val rate = successfulPeaks.size.toFloat() / (successfulPeaks.size + failedCount)
                        StatRow("Success Rate", "${"%.0f".format(rate * 100)}%")
                    }
                    if (isStable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Value looks stable!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.inRange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Peak history
            if (successfulPeaks.isNotEmpty()) {
                Text(
                    "Peaks: ${successfulPeaks.joinToString(", ") { "%.1f".format(it) }} hPa",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            if (mean != null) {
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save result (${"%.1f".format(mean)} hPa)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Reset button
            if (successfulPeaks.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        detector.reset()
                        detectedPeaks = emptyList()
                        successfulPeaks = emptyList()
                        peakMarkers = emptyList()
                        failedCount = 0
                        lastDetectedPeak = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset")
                }
            }
        }
    }

    // Peak confirm dialog
    if (showConfirmDialog && lastDetectedPeak != null) {
        PeakConfirmDialog(
            peakValueHPa = lastDetectedPeak!!,
            onConfirm = {
                successfulPeaks = successfulPeaks + lastDetectedPeak!!
                peakMarkers = peakMarkers + PeakMarker(lastDetectedPeakTimestamp, lastDetectedPeak!!, true)
                // Drop anything half-risen while the dialog was open
                detector.reset()
                showConfirmDialog = false
            },
            onReject = {
                failedCount++
                peakMarkers = peakMarkers + PeakMarker(lastDetectedPeakTimestamp, lastDetectedPeak!!, false)
                detector.reset()
                showConfirmDialog = false
            }
        )
    }

    if (detectorStuck && !stuckDialogDismissed) {
        BaselineDriftDialog(
            onRecalibrate = {
                detector.reset()
                detectorStuck = false
                connection.resetCalibration()
            },
            onDismiss = { stuckDialogDismissed = true }
        )
    }

    // Save dialog — let user choose which profile to save to
    if (showSaveDialog && mean != null) {
        SaveMinEqDialog(
            meanValue = mean,
            userProfiles = userProfiles,
            defaultProfileId = currentProfileId,
            onSave = { profileId ->
                onSave(profileId, mean)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

@Composable
private fun SaveMinEqDialog(
    meanValue: Double,
    userProfiles: List<UserProfile>,
    defaultProfileId: String?,
    onSave: (profileId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedProfileId by remember { mutableStateOf(defaultProfileId ?: userProfiles.firstOrNull()?.id) }
    val selectedProfile = userProfiles.find { it.id == selectedProfileId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Minimum Equalization Pressure") },
        text = {
            Column {
                Text(
                    "Save ${"%.1f".format(meanValue)} hPa as the minimum equalization pressure.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (userProfiles.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Save to profile:", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedProfile?.name ?: "Select profile")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            userProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.name)
                                            if (profile.minEqPressureHPa != null) {
                                                Text(
                                                    "Current: ${"%.1f".format(profile.minEqPressureHPa)} hPa",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedProfileId = profile.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                } else if (selectedProfile != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Profile: ${selectedProfile.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedProfile.minEqPressureHPa != null) {
                        Text(
                            "Current value: ${"%.1f".format(selectedProfile.minEqPressureHPa)} hPa",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedProfileId?.let { onSave(it) } },
                enabled = selectedProfileId != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


