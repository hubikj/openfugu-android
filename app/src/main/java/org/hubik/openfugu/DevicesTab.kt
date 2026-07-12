package org.hubik.openfugu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.DeviceColors
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.DeviceUserPairing
import org.hubik.openfugu.ble.MockDeviceConnection
import org.hubik.openfugu.ble.PressureSource
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.ScanState
import org.hubik.openfugu.ble.ScannedDevice
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.util.nowMillis

// =============================================================================
// Devices tab
// =============================================================================

@Composable
fun DevicesTab(
    scanState: ScanState,
    savedDevices: List<SavedDevice>,
    scannedDevices: List<ScannedDevice>,
    connections: Map<String, PressureSource>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onForget: (String) -> Unit,
    onNicknameSet: (String, String?) -> Unit,
    onColorSet: (String, Long?) -> Unit,
    onPairUser: (String, String?) -> Unit,
    onAddMockDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Forgotten devices are hidden immediately but only committed after the
    // undo snackbar times out — same pattern as session history deletes.
    var pendingForgets by remember { mutableStateOf(setOf<String>()) }

    // If the tab leaves composition during the undo window, commit the
    // pending forgets — otherwise they silently reappear on return.
    DisposableEffect(Unit) {
        onDispose { pendingForgets.forEach { onForget(it) } }
    }

    LaunchedEffect(pendingForgets) {
        if (pendingForgets.isEmpty()) return@LaunchedEffect
        val toForget = pendingForgets.toSet()
        val count = toForget.size
        val result = snackbarHostState.showSnackbar(
            message = if (count == 1) "Device forgotten" else "$count devices forgotten",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            pendingForgets = pendingForgets - toForget
        } else {
            toForget.forEach { onForget(it) }
            pendingForgets = pendingForgets - toForget
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // First-run welcome: no device has ever been saved
        if (savedDevices.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Welcome to OpenFugu", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Turn on your eFugu device and it will appear below — tap it to " +
                            "connect. After connecting, you will create a user and " +
                            "calibrate your pressure range.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Scan controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (scanState) {
                is ScanState.Idle -> {
                    Button(onClick = onScan) { Text("Scan") }
                }
                is ScanState.Scanning -> {
                    Button(onClick = onStopScan) { Text("Stop") }
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Scanning...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ScanState.Error -> {
                    Button(onClick = onScan) { Text("Scan") }
                    Text(scanState.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }

        // Collect connection states so Compose observes changes
        val connectionStates = connections.mapValues { (_, conn) ->
            conn.state.collectAsState().value
        }

        // Connecting devices
        val connectingDevices = savedDevices.filter { device ->
            connectionStates[device.address] is DeviceConnectionState.Connecting
        }
        if (connectingDevices.isNotEmpty()) {
            Text(
                "Connecting",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            connectingDevices.forEachIndexed { index, device ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = { onDisconnect(device.address) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Connected devices (fully connected, past the Connecting state)
        val connectedDevices = savedDevices.filter { device ->
            connectionStates[device.address]?.let { it !is DeviceConnectionState.Connecting } == true
        }
        if (connectedDevices.isNotEmpty()) {
            Text(
                "Connected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            connectedDevices.forEachIndexed { index, device ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                // key() ties row state (e.g. an open edit dialog) to the device,
                // not the list position — rows shift sections on (dis)connect.
                key(device.address) {
                    val connection = connections[device.address]!!
                    val batteryLevel = connection.batteryLevel.collectAsState().value
                    val deviceInfo = connection.deviceInfo.collectAsState().value
                    val pairedUserId = deviceUserPairings.find { it.deviceAddress == device.address }?.userId
                    DeviceCard(
                        savedDevice = device,
                        batteryLevel = batteryLevel,
                        deviceInfo = deviceInfo,
                        userProfiles = userProfiles,
                        pairedUserId = pairedUserId,
                        onDisconnect = { onDisconnect(device.address) },
                        onNicknameSet = { onNicknameSet(device.address, it) },
                        onColorSet = { onColorSet(device.address, it) },
                        onPairUser = { userId -> onPairUser(device.address, userId) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Saved devices (not currently connected)
        val disconnectedDevices = savedDevices.filter {
            !connections.containsKey(it.address) && it.address !in pendingForgets
        }
        // Re-evaluate freshness every second so stale entries fade out
        val nowMs by produceState(nowMillis()) {
            while (true) {
                value = nowMillis()
                kotlinx.coroutines.delay(500)
            }
        }
        val nearbyAddresses = scannedDevices
            .filter { nowMs - it.lastSeenMs < 2000 }
            .map { it.address }
            .toSet()
        if (disconnectedDevices.isNotEmpty()) {
            Text(
                "Saved devices",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            disconnectedDevices.forEachIndexed { index, device ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                key(device.address) {
                    val pairedUserId = deviceUserPairings.find { it.deviceAddress == device.address }?.userId
                    // Simulated devices are always connectable, no scan involved
                    val isNearby = device.address in nearbyAddresses ||
                        MockDeviceConnection.isMockAddress(device.address)
                    SavedDeviceRow(
                        device = device,
                        userProfiles = userProfiles,
                        pairedUserId = pairedUserId,
                        isNearby = isNearby,
                        onConnect = { onConnect(device.address) },
                        onForget = { pendingForgets = pendingForgets + device.address },
                        onNicknameSet = { onNicknameSet(device.address, it) },
                        onColorSet = { onColorSet(device.address, it) },
                        onPairUser = { userId -> onPairUser(device.address, userId) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Scanned devices (new ones not in saved list)
        if (scanState is ScanState.Scanning && scannedDevices.isNotEmpty()) {
            val savedAddresses = savedDevices.map { it.address }.toSet()
            val newDevices = scannedDevices.filter { it.address !in savedAddresses }
            if (newDevices.isNotEmpty()) {
                Text(
                    "Nearby devices",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                newDevices.forEachIndexed { index, device ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnect(device.address) }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(device.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Hint when empty
        if (savedDevices.isEmpty() && scanState is ScanState.Idle) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "Tap Scan to find your eFugu device",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Simulated device entry — deliberately unobtrusive. It lets the app
        // be explored without eFugu hardware (development, demos, curiosity),
        // but it is not a path we advertise to new users.
        val canAddMockDevice = savedDevices.count {
            MockDeviceConnection.isMockAddress(it.address)
        } < MockDeviceConnection.MAX_MOCK_DEVICES
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onAddMockDevice,
            enabled = canAddMockDevice,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                "Add simulated device",
                fontSize = 12.sp,
                // When disabled, fall back to the button's greyed content color
                color = if (canAddMockDevice)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                else Color.Unspecified
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    } // Box
}

@Composable
fun SavedDeviceRow(
    device: SavedDevice,
    userProfiles: List<UserProfile> = emptyList(),
    pairedUserId: String? = null,
    isNearby: Boolean = false,
    onConnect: () -> Unit,
    onForget: () -> Unit,
    onNicknameSet: (String?) -> Unit,
    onColorSet: (Long?) -> Unit,
    onPairUser: (String?) -> Unit = {}
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val pairedUser = userProfiles.find { it.id == pairedUserId }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (device.colorArgb != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(device.colorArgb.toInt()), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                    if (pairedUser != null) {
                        Text(
                            "  ·  ${pairedUser.name}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (isNearby) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Available",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.inRange,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    when {
                        MockDeviceConnection.isMockAddress(device.address) ->
                            if (device.nickname != null) device.name else "Simulated device"
                        device.nickname != null -> "${device.name} — ${device.address}"
                        else -> device.address
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showEditDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Close, contentDescription = "Forget", modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showEditDialog) {
        DeviceEditDialog(
            currentNickname = device.nickname,
            currentColor = device.colorArgb,
            deviceName = device.name,
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

@Composable
fun DevicePickerDialog(
    connections: Map<String, PressureSource>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    selectedAddress: String? = null,
    onSelect: (PressureSource) -> Unit = {},
    onPairUser: (deviceAddress: String, userId: String?) -> Unit,
    onDismiss: () -> Unit,
    multiSelect: Boolean = false,
    onMultiSelect: (List<PressureSource>) -> Unit = {},
    preselected: Set<String> = emptySet(),
    minSelect: Int = 1,
    maxSelect: Int = Int.MAX_VALUE,
    disabledReason: (SavedDevice) -> String? = { null }
) {
    // Collect connection states keyed by address so Compose tracks each slot
    // independently — devices connecting or dropping while the dialog is open
    // appear and disappear live
    val connectionStates = mutableMapOf<String, DeviceConnectionState>()
    connections.forEach { (address, conn) ->
        key(address) {
            connectionStates[address] = conn.state.collectAsState().value
        }
    }
    val connectedDevices = savedDevices.filter {
        connectionStates[it.address] is DeviceConnectionState.Connected
    }

    // Multi-select state, seeded with the caller's remembered selection
    var selectedAddresses by remember {
        mutableStateOf(preselected.filter { addr ->
            connectedDevices.any { it.address == addr && disabledReason(it) == null }
        }.take(maxSelect).toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multiSelect && maxSelect > 1) "Select devices" else "Select device") },
        text = {
            Column {
                connectedDevices.forEach { device ->
                    val pairedUserId = deviceUserPairings.find { it.deviceAddress == device.address }?.userId
                    val reason = disabledReason(device)
                    val enabled = reason == null
                    val isSelected = if (multiSelect) device.address in selectedAddresses
                        else device.address == selectedAddress
                    // Selection update shared by row tap and control tap:
                    // radio semantics (replace) when only one pick is allowed,
                    // capped toggle semantics otherwise
                    val select = {
                        selectedAddresses = when {
                            maxSelect == 1 -> setOf(device.address)
                            device.address in selectedAddresses -> selectedAddresses - device.address
                            selectedAddresses.size < maxSelect -> selectedAddresses + device.address
                            else -> selectedAddresses
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                if (multiSelect) select()
                                else connections[device.address]?.let { onSelect(it) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (multiSelect && maxSelect == 1) {
                            RadioButton(
                                selected = isSelected,
                                enabled = enabled,
                                onClick = select
                            )
                        } else if (multiSelect) {
                            Checkbox(
                                checked = isSelected,
                                enabled = enabled,
                                onCheckedChange = { select() }
                            )
                        } else if (selectedAddress != null) {
                            // Immediate-select mode (calibration wizard) shows
                            // a radio only when there is a current selection
                            // to display — it would never check otherwise
                            RadioButton(
                                selected = isSelected,
                                enabled = enabled,
                                onClick = { connections[device.address]?.let { onSelect(it) } }
                            )
                        }
                        if (device.colorArgb != null) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(Color(device.colorArgb.toInt()), CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            if (reason != null) {
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // User assignment pill
                        var expanded by remember { mutableStateOf(false) }
                        val pairedUser = userProfiles.find { it.id == pairedUserId }
                        if (userProfiles.isNotEmpty()) {
                            Box {
                                AssistChip(
                                    onClick = { expanded = true },
                                    label = {
                                        Text(
                                            pairedUser?.name ?: "No user",
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    if (pairedUser != null) {
                                        DropdownMenuItem(
                                            text = { Text("No user") },
                                            onClick = {
                                                onPairUser(device.address, null)
                                                expanded = false
                                            }
                                        )
                                    }
                                    userProfiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text(profile.name) },
                                            onClick = {
                                                onPairUser(device.address, profile.id)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (connectedDevices.isEmpty()) {
                    Text(
                        "No devices connected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (multiSelect) {
                // Devices can disconnect while the dialog is open; count only
                // selections that are still connected, or Start silently no-ops.
                val validSelection = selectedAddresses.filter { it in connections }
                Button(
                    onClick = {
                        onMultiSelect(validSelection.mapNotNull { addr -> connections[addr] })
                    },
                    enabled = validSelection.size >= minSelect
                ) {
                    Text(if (maxSelect == 1) "Start" else "Start (${validSelection.size})")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (multiSelect) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else null
    )
}

@Composable
fun DeviceEditDialog(
    currentNickname: String?,
    currentColor: Long?,
    deviceName: String,
    userProfiles: List<UserProfile> = emptyList(),
    currentPairedUserId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (nickname: String?, color: Long?, pairedUserId: String?) -> Unit
) {
    var text by remember { mutableStateOf(currentNickname ?: "") }
    var selectedColor by remember { mutableStateOf(currentColor) }
    var selectedUserId by remember { mutableStateOf(currentPairedUserId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit device") },
        text = {
            Column {
                Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                if (userProfiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Assigned user", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    val selectedUser = userProfiles.find { it.id == selectedUserId }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedUser?.name ?: "No user")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("No user") },
                                onClick = {
                                    selectedUserId = null
                                    expanded = false
                                }
                            )
                            userProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        selectedUserId = profile.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Color", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                for (row in DeviceColors.presets.chunked(5)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        row.forEach { colorArgb ->
                            val isSelected = colorArgb == selectedColor
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .then(
                                        if (isSelected) Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        ) else Modifier
                                    )
                                    .background(Color(colorArgb.toInt()), CircleShape)
                                    .clickable {
                                        selectedColor = if (selectedColor == colorArgb) null else colorArgb
                                    }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.takeIf { it.isNotBlank() }, selectedColor, selectedUserId) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
