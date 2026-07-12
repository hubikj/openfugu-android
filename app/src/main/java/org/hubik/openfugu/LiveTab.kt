package org.hubik.openfugu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.util.formatHPa

// =============================================================================
// Live tab — shows all connected devices
// =============================================================================

@Composable
fun LiveTab(
    connections: Map<String, PressureSource>,
    viewModel: EFuguViewModel,
    modifier: Modifier = Modifier
) {
    if (connections.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No devices connected.\nGo to the Devices tab to connect.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        return
    }

    val deviceList = connections.values.toList()

    if (deviceList.size == 1) {
        // Single device: full-screen layout
        SingleDevicePanel(
            connection = deviceList.first(),
            viewModel = viewModel,
            modifier = modifier
        )
    } else {
        // Multiple devices: scrollable list of compact panels
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
        ) {
            items(deviceList, key = { it.address }) { connection ->
                CompactDevicePanel(
                    connection = connection,
                    viewModel = viewModel
                )
            }
        }
    }
}

/** Full-screen panel for single device (same layout as before) */
@Composable
fun SingleDevicePanel(
    connection: PressureSource,
    viewModel: EFuguViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by connection.state.collectAsState()
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()
    val chartMin by connection.chartMin.collectAsState()
    val chartMax by connection.chartMax.collectAsState()
    val batteryLevel by connection.batteryLevel.collectAsState()
    val deviceInfo by connection.deviceInfo.collectAsState()
    val isCalibrated by connection.isCalibrated.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val currentSaved = savedDevices.find { it.address == connection.address } ?: connection.savedDevice
    val allUserProfiles by viewModel.userProfiles.collectAsState()
    val allPairings by viewModel.deviceUserPairings.collectAsState()
    val pairedUserId = allPairings.find { it.deviceAddress == connection.address }?.userId

    // Visible min/max from chart (updated when paused + scrolling/zooming)
    var visibleMin by remember { mutableStateOf<Double?>(null) }
    var visibleMax by remember { mutableStateOf<Double?>(null) }
    val displayMin = visibleMin ?: chartMin
    val displayMax = visibleMax ?: chartMax

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        DeviceCard(
            savedDevice = currentSaved,
            batteryLevel = batteryLevel,
            deviceInfo = deviceInfo,
            userProfiles = allUserProfiles,
            pairedUserId = pairedUserId,
            isConnecting = connectionState is DeviceConnectionState.Connecting,
            onDisconnect = { viewModel.disconnectDevice(connection.address) },
            onNicknameSet = { viewModel.setNickname(connection.address, it) },
            onColorSet = { viewModel.setColor(connection.address, it) },
            onPairUser = { userId ->
                if (userId != null) viewModel.pairDeviceToUser(connection.address, userId)
                else viewModel.unpairDevice(connection.address)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (connectionState is DeviceConnectionState.Connecting) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Connecting...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            PressureDisplay(
                reading = latestPressure,
                chartMin = displayMin,
                chartMax = displayMax,
                isCalibrated = isCalibrated,
                onRecalibrate = { viewModel.resetCalibration(connection.address) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (chartData.size >= 2) {
                PressureChart(
                    lines = listOf(ChartLine(chartData, currentSaved.colorArgb?.let { Color(it.toInt()) })),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onVisibleRangeChanged = { min, max -> visibleMin = min; visibleMax = max }
                )
            } else if (isCalibrated) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Waiting for chart data...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Calibrating...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Compact panel for multi-device view */
@Composable
fun CompactDevicePanel(
    connection: PressureSource,
    viewModel: EFuguViewModel
) {
    val connectionState by connection.state.collectAsState()
    val latestPressure by connection.latestPressure.collectAsState()
    val chartData by connection.chartData.collectAsState()
    val chartMin by connection.chartMin.collectAsState()
    val chartMax by connection.chartMax.collectAsState()
    val batteryLevel by connection.batteryLevel.collectAsState()
    val isCalibrated by connection.isCalibrated.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val currentSaved = savedDevices.find { it.address == connection.address } ?: connection.savedDevice
    val allUserProfiles by viewModel.userProfiles.collectAsState()
    val allPairings by viewModel.deviceUserPairings.collectAsState()
    val pairedUserId = allPairings.find { it.deviceAddress == connection.address }?.userId
    val pairedUser = allUserProfiles.find { it.id == pairedUserId }
    var showEditDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: name + user + battery + disconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentSaved.colorArgb != null) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(currentSaved.colorArgb.toInt()), CircleShape)
                    )
                } else {
                    Icon(
                        Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    currentSaved.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                if (pairedUser != null) {
                    Text(
                        "  ·  ${pairedUser.name}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                batteryLevel?.let {
                    Text("$it%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnectDevice(connection.address) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(if (connectionState is DeviceConnectionState.Connecting) "Cancel" else "Disconnect", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connecting state
            if (connectionState is DeviceConnectionState.Connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Pressure value + min/max
            else if (latestPressure != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "${formatHPa(latestPressure!!.relativeHPa)} hPa",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (chartMin != null && chartMax != null) {
                        Text(
                            "min ${formatHPa(chartMin!!)}  max ${formatHPa(chartMax!!)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            } else if (!isCalibrated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Calibrating...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Mini chart
            if (chartData.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                PressureChart(
                    lines = listOf(ChartLine(chartData, currentSaved.colorArgb?.let { Color(it.toInt()) })),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }
        }
    }

    if (showEditDialog) {
        DeviceEditDialog(
            currentNickname = currentSaved.nickname,
            currentColor = currentSaved.colorArgb,
            deviceName = currentSaved.name,
            userProfiles = allUserProfiles,
            currentPairedUserId = pairedUserId,
            onDismiss = { showEditDialog = false },
            onConfirm = { nickname, color, userId ->
                viewModel.setNickname(connection.address, nickname)
                viewModel.setColor(connection.address, color)
                if (userId != null) viewModel.pairDeviceToUser(connection.address, userId)
                else viewModel.unpairDevice(connection.address)
                showEditDialog = false
            }
        )
    }
}

// =============================================================================
// Device card (single-device view)
// =============================================================================

@Composable
fun DeviceCard(
    savedDevice: SavedDevice,
    batteryLevel: Int?,
    deviceInfo: Map<String, String> = emptyMap(),
    userProfiles: List<UserProfile> = emptyList(),
    pairedUserId: String? = null,
    isConnecting: Boolean = false,
    onDisconnect: () -> Unit,
    onNicknameSet: (String?) -> Unit,
    onColorSet: (Long?) -> Unit,
    onPairUser: (String?) -> Unit = {}
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val pairedUser = userProfiles.find { it.id == pairedUserId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color dot or Bluetooth icon
            if (savedDevice.colorArgb != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(savedDevice.colorArgb.toInt()), CircleShape)
                )
            } else {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        savedDevice.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (pairedUser != null) {
                        Text(
                            "  ·  ${pairedUser.name}",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                val statusItems = buildList {
                    batteryLevel?.let { add("Battery: $it%") }
                    deviceInfo["Firmware"]?.let { add("FW $it") }
                    deviceInfo["Hardware"]?.let { add("HW $it") }
                }
                if (statusItems.isNotEmpty()) {
                    Text(
                        statusItems.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (savedDevice.nickname != null) "${savedDevice.name} — ${savedDevice.address}"
                    else savedDevice.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onDisconnect) {
                Text(if (isConnecting) "Cancel" else "Disconnect", fontSize = 12.sp)
            }
        }
    }

    if (showEditDialog) {
        DeviceEditDialog(
            currentNickname = savedDevice.nickname,
            currentColor = savedDevice.colorArgb,
            deviceName = savedDevice.name,
            userProfiles = userProfiles,
            currentPairedUserId = pairedUserId,
            onDismiss = { showEditDialog = false },
            onConfirm = { nickname, color, userId ->
                onNicknameSet(nickname)
                onColorSet(color)
                onPairUser(userId)
                showEditDialog = false
            }
        )
    }
}

// =============================================================================
// Pressure display
// =============================================================================

@Composable
fun PressureDisplay(
    reading: PressureReading?,
    chartMin: Double?,
    chartMax: Double?,
    isCalibrated: Boolean,
    onRecalibrate: () -> Unit
) {
    // surfaceVariant, not primaryContainer: with some dynamic color schemes the
    // dark-mode primaryContainer is light enough to wash out the primary-colored
    // pressure value (same issue as the pattern picker in FuguFlowGame).
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (reading != null) {
                Text(
                    "${formatHPa(reading.relativeHPa)} hPa",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        if (chartMin != null && chartMax != null) {
                            Text(
                                "min ${formatHPa(chartMin)}  max ${formatHPa(chartMax)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            "abs ${formatHPa(reading.pressureHPa)} hPa",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    OutlinedButton(
                        onClick = onRecalibrate,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Recalibrate", fontSize = 12.sp)
                    }
                }
            } else if (!isCalibrated) {
                Text("Calibrating...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            } else {
                Text("Waiting for data...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}
