package org.hubik.openfugu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ble.PeakDetector
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.SustainedPressureDetector
import org.hubik.openfugu.ble.PressureDirection
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.util.formatHPa
import org.hubik.openfugu.DevicePickerDialog
import org.hubik.openfugu.ChartLine
import org.hubik.openfugu.PressureChart
import org.hubik.openfugu.exercise.PeakMarker
import kotlin.math.sqrt

private enum class WizardStep { INTRO, MIN_EQ, MAX_POSITIVE, MAX_NEGATIVE, SUMMARY }

// In-progress step results live at wizard level (not in the step composables)
// so a step unmounting during a connection blip doesn't lose progress.
private class MinEqStepResults {
    var successfulPeaks by mutableStateOf<List<Double>>(emptyList())
    var peakMarkers by mutableStateOf<List<PeakMarker>>(emptyList())
    var failedCount by mutableIntStateOf(0)
}

private class HoldStepResults {
    var completedHolds by mutableStateOf<List<Double>>(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizard(
    viewModel: EFuguViewModel,
    userId: String,
    connections: Map<String, PressureSource>,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    var step by remember { mutableStateOf(WizardStep.INTRO) }

    // Results
    var minEqResult by remember { mutableStateOf<Double?>(null) }
    var maxPositiveResult by remember { mutableStateOf<Double?>(null) }
    var maxNegativeResult by remember { mutableStateOf<Double?>(null) }
    val minEqProgress = remember { MinEqStepResults() }
    val maxPositiveProgress = remember { HoldStepResults() }
    val maxNegativeProgress = remember { HoldStepResults() }

    // Device selection
    val savedDevices by viewModel.savedDevices.collectAsState()
    val userProfiles by viewModel.userProfiles.collectAsState()
    val deviceUserPairings by viewModel.deviceUserPairings.collectAsState()
    val calibratingProfile = userProfiles.find { it.id == userId }

    // Collect each connection's state so Compose observes Connecting→Connected
    // transitions (reading state.value directly would leave this screen stale).
    val connectionStates = mutableMapOf<String, DeviceConnectionState>()
    connections.forEach { (address, conn) ->
        key(address) {
            connectionStates[address] = conn.state.collectAsState().value
        }
    }
    val connectedList = connections.values.filter { connectionStates[it.address] is DeviceConnectionState.Connected }
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    // Once the user leaves the intro step the device is pinned — a connection
    // blip must never silently switch the wizard to another device.
    var pinnedAddress by remember { mutableStateOf<String?>(null) }

    // Auto-select: device paired to this user > first connected
    val autoAddress = remember(connectedList.map { it.address }, deviceUserPairings, userId) {
        val pairedAddress = deviceUserPairings
            .find { it.userId == userId }
            ?.deviceAddress
        if (pairedAddress != null && connectedList.any { it.address == pairedAddress }) {
            pairedAddress
        } else {
            connectedList.firstOrNull()?.address
        }
    }
    val effectiveAddress = pinnedAddress ?: selectedAddress ?: autoAddress
    val selectedConnection = effectiveAddress?.let { addr -> connectedList.find { it.address == addr } }
    val selectedSaved = savedDevices.find { it.address == effectiveAddress }
    val deviceLineColor = selectedSaved?.colorArgb?.let { Color(it.toInt()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration Wizard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            // Summary needs no live connection — a blip must not block saving
            step == WizardStep.SUMMARY -> SummaryStep(
                minEq = minEqResult,
                maxPositive = maxPositiveResult,
                maxNegative = maxNegativeResult,
                hadPriorCalibration = calibratingProfile?.lastCalibratedAt != null,
                modifier = Modifier.padding(padding),
                onSave = {
                    val profile = calibratingProfile ?: return@SummaryStep
                    val allSkipped = minEqResult == null && maxPositiveResult == null && maxNegativeResult == null
                    if (allSkipped) {
                        // All steps skipped — clear calibration data
                        viewModel.updateUser(profile.copy(
                            minEqPressureHPa = null,
                            maxPositiveHPa = null,
                            maxNegativeHPa = null,
                            lastCalibratedAt = null
                        ))
                    } else {
                        viewModel.updateUser(profile.copy(
                            minEqPressureHPa = minEqResult ?: profile.minEqPressureHPa,
                            maxPositiveHPa = maxPositiveResult ?: profile.maxPositiveHPa,
                            maxNegativeHPa = maxNegativeResult ?: profile.maxNegativeHPa,
                            lastCalibratedAt = System.currentTimeMillis()
                        ))
                    }
                    onComplete()
                },
                onBack = onBack
            )
            selectedConnection == null && pinnedAddress == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No device connected.\nConnect a device first, then start calibration.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            selectedConnection == null -> DeviceDisconnectedNotice(
                deviceName = selectedSaved?.displayName
                    ?: pinnedAddress?.let { connections[it]?.displayName }
                    ?: "the device",
                modifier = Modifier.padding(padding)
            )
            else -> when (step) {
                WizardStep.INTRO -> IntroStep(
                    userName = calibratingProfile?.name,
                    deviceName = selectedSaved?.displayName ?: selectedConnection.displayName,
                    deviceColorArgb = selectedSaved?.colorArgb,
                    showDeviceChooser = connectedList.size > 1,
                    connections = connections,
                    savedDevices = savedDevices,
                    userProfiles = userProfiles,
                    deviceUserPairings = deviceUserPairings,
                    selectedAddress = effectiveAddress,
                    onDeviceSelected = { selectedAddress = it },
                    onPairUser = { addr, userId ->
                        if (userId != null) viewModel.pairDeviceToUser(addr, userId)
                        else viewModel.unpairDevice(addr)
                    },
                    modifier = Modifier.padding(padding),
                    onNext = {
                        pinnedAddress = effectiveAddress
                        step = WizardStep.MIN_EQ
                    }
                )
                WizardStep.MIN_EQ -> MinEqStep(
                    connection = selectedConnection,
                    results = minEqProgress,
                    lineColor = deviceLineColor,
                    modifier = Modifier.padding(padding),
                    onNext = { result ->
                        minEqResult = result
                        step = WizardStep.MAX_POSITIVE
                    },
                    onSkip = {
                        minEqResult = null
                        step = WizardStep.MAX_POSITIVE
                    }
                )
                WizardStep.MAX_POSITIVE -> MaxPressureStep(
                    connection = selectedConnection,
                    results = maxPositiveProgress,
                    lineColor = deviceLineColor,
                    title = "Step 2: Maximum Positive Pressure",
                    description = "Apply comfortable maximum positive pressure (Valsalva/Frenzel) and hold for 3 seconds.\n\nDon't push to your absolute maximum — choose a level you could sustain comfortably.",
                    threshold = 30.0,
                    direction = PressureDirection.POSITIVE,
                    modifier = Modifier.padding(padding),
                    onNext = { result ->
                        maxPositiveResult = result
                        step = WizardStep.MAX_NEGATIVE
                    },
                    onSkip = {
                        maxPositiveResult = null
                        step = WizardStep.MAX_NEGATIVE
                    }
                )
                WizardStep.MAX_NEGATIVE -> MaxPressureStep(
                    connection = selectedConnection,
                    results = maxNegativeProgress,
                    lineColor = deviceLineColor,
                    title = "Step 3: Maximum Negative Pressure (Optional)",
                    description = "Apply comfortable negative pressure (reverse pack) and hold for 3 seconds.\n\nSkip if you don't know reverse pack technique.",
                    threshold = 10.0,
                    direction = PressureDirection.NEGATIVE,
                    modifier = Modifier.padding(padding),
                    onNext = { result ->
                        maxNegativeResult = result
                        step = WizardStep.SUMMARY
                    },
                    onSkip = {
                        maxNegativeResult = null
                        step = WizardStep.SUMMARY
                    }
                )
                WizardStep.SUMMARY -> Unit
            }
        }
    }
}

@Composable
private fun IntroStep(
    userName: String?,
    deviceName: String,
    deviceColorArgb: Long?,
    showDeviceChooser: Boolean,
    connections: Map<String, PressureSource>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<org.hubik.openfugu.ble.DeviceUserPairing>,
    selectedAddress: String?,
    onDeviceSelected: (String) -> Unit,
    onPairUser: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    onNext: () -> Unit
) {
    var showDevicePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Pressure Calibration",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This wizard will measure your equalization pressure range in three steps:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("1.  Minimum equalization pressure — gentle equalizations", style = MaterialTheme.typography.bodyMedium)
            Text("2.  Maximum positive pressure — comfortable sustained hold", style = MaterialTheme.typography.bodyMedium)
            Text("3.  Maximum negative pressure — optional, for expert mode", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Make sure the eFugu is positioned correctly and you're comfortable.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Calibration context card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (userName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("User:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(userName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (deviceColorArgb != null) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(deviceColorArgb.toInt()), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (showDeviceChooser) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Change",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showDevicePicker = true }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            connections = connections,
            savedDevices = savedDevices,
            userProfiles = userProfiles,
            deviceUserPairings = deviceUserPairings,
            selectedAddress = selectedAddress,
            onSelect = { conn ->
                onDeviceSelected(conn.address)
                showDevicePicker = false
            },
            onPairUser = onPairUser,
            onDismiss = { showDevicePicker = false }
        )
    }
}

@Composable
private fun MinEqStep(
    connection: PressureSource,
    results: MinEqStepResults,
    lineColor: Color? = null,
    modifier: Modifier = Modifier,
    onNext: (Double?) -> Unit,
    onSkip: () -> Unit
) {
    val detector = remember { PeakDetector(minPeakAmplitude = 5.0) }
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()

    var lastDetectedPeak by remember { mutableStateOf<Double?>(null) }
    var lastDetectedPeakTimestamp by remember { mutableLongStateOf(0L) }
    var showConfirmDialog by remember { mutableStateOf(false) }
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
                showConfirmDialog = true
            }
        }
    }

    val successfulPeaks = results.successfulPeaks
    val mean = if (successfulPeaks.isNotEmpty()) successfulPeaks.average() else null
    val stddev = if (successfulPeaks.size >= 2) {
        val avg = successfulPeaks.average()
        sqrt(successfulPeaks.map { (it - avg) * (it - avg) }.average())
    } else null
    val isStable = successfulPeaks.size >= 5 && stddev != null && stddev < 2.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Step 1: Minimum Equalization Pressure", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
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
                peakMarkers = results.peakMarkers,
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
                StatRow("Failed/Rejected", "${results.failedCount}")
                if (mean != null) {
                    StatRow("Mean", "${"%.1f".format(mean)} hPa")
                }
                if (stddev != null) {
                    StatRow("Standard deviation", "${"%.1f".format(stddev)} hPa")
                }
                if (successfulPeaks.isNotEmpty()) {
                    val rate = successfulPeaks.size.toFloat() / (successfulPeaks.size + results.failedCount)
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
            Text("Peaks: ${successfulPeaks.joinToString(", ") { "%.1f".format(it) }} hPa",
                style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Buttons
        if (successfulPeaks.isNotEmpty()) {
            Button(
                onClick = { onNext(mean) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use this value (${"%.1f".format(mean)} hPa)")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip")
        }
    }

    // Confirm dialog
    if (showConfirmDialog && lastDetectedPeak != null) {
        PeakConfirmDialog(
            peakValueHPa = lastDetectedPeak!!,
            onConfirm = {
                results.successfulPeaks = results.successfulPeaks + lastDetectedPeak!!
                results.peakMarkers = results.peakMarkers + PeakMarker(lastDetectedPeakTimestamp, lastDetectedPeak!!, true)
                // Drop anything half-risen while the dialog was open
                detector.reset()
                showConfirmDialog = false
            },
            onReject = {
                results.failedCount++
                results.peakMarkers = results.peakMarkers + PeakMarker(lastDetectedPeakTimestamp, lastDetectedPeak!!, false)
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
}

@Composable
private fun MaxPressureStep(
    connection: PressureSource,
    results: HoldStepResults,
    lineColor: Color? = null,
    title: String,
    description: String,
    threshold: Double,
    direction: PressureDirection,
    modifier: Modifier = Modifier,
    onNext: (Double?) -> Unit,
    onSkip: () -> Unit
) {
    val detector = remember {
        SustainedPressureDetector(
            minThreshold = threshold,
            holdDurationMs = 3000L,
            direction = direction
        )
    }
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()

    var isHolding by remember { mutableStateOf(false) }
    var holdProgressMs by remember { mutableLongStateOf(0L) }
    var currentBest by remember { mutableStateOf(0.0) }

    // Feed the detector from the flow directly — snapshot state (collectAsState)
    // is conflated per frame and would drop samples.
    LaunchedEffect(connection) {
        connection.latestPressure.collect { reading ->
            if (reading == null) return@collect
            val now = System.currentTimeMillis()
            val result = detector.addSample(reading.relativeHPa, now)

            isHolding = detector.isTracking
            holdProgressMs = detector.currentTrackingDurationMs(now)
            currentBest = detector.currentBestSustained

            if (result != null && result.valueHPa > 0.0) {
                results.completedHolds = results.completedHolds + result.valueHPa
            }
        }
    }

    val completedHolds = results.completedHolds
    val targetHolds = 3
    val average = if (completedHolds.isNotEmpty()) completedHolds.average() else null
    val effectivePressure = when (direction) {
        PressureDirection.POSITIVE -> latestPressure?.relativeHPa ?: 0.0
        PressureDirection.NEGATIVE -> kotlin.math.abs(latestPressure?.relativeHPa ?: 0.0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(24.dp))

        // Live pressure
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current Pressure", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${formatHPa(effectivePressure)} hPa",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isHolding) AppColors.inRange else MaterialTheme.colorScheme.onSurface
                )

                if (isHolding) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Holding...", style = MaterialTheme.typography.labelMedium, color = AppColors.inRange)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (holdProgressMs / 3000f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.inRange
                    )
                    if (currentBest > 0.0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Best sustained: ${"%.1f".format(currentBest)} hPa",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Live chart
        if (chartData.size >= 2) {
            PressureChart(
                lines = listOf(ChartLine(chartData, lineColor)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Completed holds
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Completed Holds", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                if (completedHolds.isEmpty()) {
                    Text(
                        "Hold $targetHolds times above ${"%.0f".format(threshold)} hPa",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    completedHolds.forEachIndexed { index, value ->
                        StatRow("Hold ${index + 1}", "${"%.1f".format(value)} hPa")
                    }
                    if (average != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        StatRow("Average", "${"%.1f".format(average)} hPa")
                    }
                }
                Text(
                    "${completedHolds.size} / $targetHolds",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Buttons
        if (completedHolds.size >= targetHolds) {
            Button(
                onClick = { onNext(average) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use this value (${"%.1f".format(average)} hPa)")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip")
        }
    }
}

@Composable
private fun DeviceDisconnectedNotice(
    deviceName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Device disconnected", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Waiting for $deviceName to reconnect.\nYour calibration progress is kept.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryStep(
    minEq: Double?,
    maxPositive: Double?,
    maxNegative: Double?,
    hadPriorCalibration: Boolean = false,
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val allSkipped = minEq == null && maxPositive == null && maxNegative == null
    val gameRange = when {
        maxPositive != null -> maxPositive * 0.8
        else -> 40.0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (allSkipped) "Calibration Skipped" else "Calibration Complete",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                HpaValueRow("Minimum Equalization Pressure", minEq, nullText = "Skipped")
                HpaValueRow("Maximum Positive", maxPositive, nullText = "Skipped")
                HpaValueRow("Maximum Negative", maxNegative, nullText = "Skipped")
                if (!allSkipped) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("Game Pressure Range", "${"%.0f".format(gameRange)} hPa")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (allSkipped && !hadPriorCalibration) {
            // Nothing to save or lose
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OK")
            }
        } else if (allSkipped) {
            // Had prior calibration — let user choose to clear or keep
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear calibration")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Keep existing")
            }
        } else {
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Discard")
            }
        }
    }
}

