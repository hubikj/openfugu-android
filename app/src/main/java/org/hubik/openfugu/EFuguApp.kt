package org.hubik.openfugu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.hubik.openfugu.ble.DeviceColors
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.exercise.ConstantEqScreen
import org.hubik.openfugu.exercise.MinEqExerciseScreen
import org.hubik.openfugu.game.FuguCaveScreen
import org.hubik.openfugu.game.FuguFeastScreen
import org.hubik.openfugu.game.FuguFlowScreen
import org.hubik.openfugu.game.FuguReefScreen
import org.hubik.openfugu.game.MultiplayerFuguCaveScreen
import org.hubik.openfugu.game.MultiplayerFuguFeastScreen
import org.hubik.openfugu.game.MultiplayerFuguReefScreen
import org.hubik.openfugu.game.MultiplayerPlayerInfo
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.session.SessionViewerScreen
import org.hubik.openfugu.ui.CalibrationWizard
import org.hubik.openfugu.ui.UserDetailScreen

// =============================================================================
// App root — always shows bottom navigation
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EFuguApp(
    viewModel: EFuguViewModel,
    onRequestPermissionsAndScan: () -> Unit,
    importSession: (suspend () -> Session?)? = null,
    onImportSessionHandled: () -> Unit = {},
    onSaveLogs: (List<String>) -> String = { "" }
) {
    // First run (no saved devices yet): start on the Devices tab — the user
    // must connect a device first, everything else flows from device-user
    // pairing. Derived from state, not a stored flag, so it also recovers
    // sensibly after a data clear.
    var selectedTab by remember {
        mutableIntStateOf(if (viewModel.savedDevices.value.isEmpty()) 2 else 0)
    }
    var activeGame by remember { mutableStateOf<String?>(null) }
    // One address runs the single-player version, two or more the multiplayer one
    var activeGameDeviceAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    // Held here, not in ExercisesTab: the tab leaves composition during games
    // and tab switches, which would reset a locally remembered selection
    var lastExerciseDeviceAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    var showUserDetail by remember { mutableStateOf<String?>(null) }
    var calibratingUserId by remember { mutableStateOf<String?>(null) }
    var viewingSessionId by remember { mutableStateOf<String?>(null) }

    val connections by viewModel.connections.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val logMessages by viewModel.logMessages.collectAsState()
    val userProfiles by viewModel.userProfiles.collectAsState()
    val deviceUserPairings by viewModel.deviceUserPairings.collectAsState()
    val recentSessions by viewModel.recentSessions.collectAsState()

    // Auto-start scan on first composition
    LaunchedEffect(Unit) {
        onRequestPermissionsAndScan()
    }

    // Open a session file handed to us by another app (tap in a file manager,
    // "open with" from an email or messenger attachment). The platform shell
    // resolves the intent to a plain suspend loader, so this composable needs
    // no Intent or Uri types.
    LaunchedEffect(importSession) {
        val load = importSession ?: return@LaunchedEffect
        val session = load()
        if (session != null) {
            viewingSessionId = session.id
        } else {
            viewModel.postUserMessage("This file is not an OpenFugu session")
        }
        onImportSessionHandled()
    }

    // First-run guidance: once a device is connected but no user exists yet,
    // prompt to create the first user, pair it to that device, and offer the
    // calibration wizard. Dialogs are composed after the full-screen early
    // returns below so they never cover a game or the wizard.
    var showFirstUserDialog by remember { mutableStateOf(false) }
    var firstUserPromptDismissed by remember { mutableStateOf(false) }
    var calibrateOfferUser by remember { mutableStateOf<UserProfile?>(null) }
    val rootConnectionStates = connections.mapValues { (_, conn) -> conn.state.collectAsState().value }
    val connectedCount = rootConnectionStates.values.count { it is DeviceConnectionState.Connected }
    val firstConnectedAddress = rootConnectionStates.entries
        .firstOrNull { it.value is DeviceConnectionState.Connected }?.key
    LaunchedEffect(firstConnectedAddress, userProfiles) {
        if (firstConnectedAddress != null && userProfiles.isEmpty() && !firstUserPromptDismissed) {
            showFirstUserDialog = true
        }
    }


    // Full-screen logs
    if (showLogs) {
        BackHandler { showLogs = false }
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = { Text("Logs") },
                    navigationIcon = {
                        IconButton(onClick = { showLogs = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LogsTab(
                logMessages = logMessages,
                onShowMessage = { viewModel.postUserMessage(it) },
                onSaveLogs = { onSaveLogs(logMessages) },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    // Full-screen user detail
    if (showUserDetail != null) {
        BackHandler { showUserDetail = null }
        UserDetailScreen(
            viewModel = viewModel,
            userId = showUserDetail!!,
            onBack = { showUserDetail = null },
            onStartCalibration = { userId ->
                showUserDetail = null
                calibratingUserId = userId
            },
            onDeleted = { showUserDetail = null }
        )
        return
    }

    // Full-screen session viewer
    if (viewingSessionId != null) {
        BackHandler { viewingSessionId = null }
        SessionViewerScreen(
            viewModel = viewModel,
            sessionId = viewingSessionId!!,
            onBack = { viewingSessionId = null }
        )
        return
    }

    // Full-screen calibration wizard
    if (calibratingUserId != null) {
        val returnToUser = calibratingUserId
        BackHandler {
            calibratingUserId = null
            showUserDetail = returnToUser
        }
        CalibrationWizard(
            viewModel = viewModel,
            userId = calibratingUserId!!,
            connections = connections,
            onBack = {
                calibratingUserId = null
                showUserDetail = returnToUser
            },
            onComplete = {
                calibratingUserId = null
                showUserDetail = returnToUser
            }
        )
        return
    }

    // Multiplayer game routing (two or more devices selected at launch)
    if (activeGame != null && activeGameDeviceAddresses.size >= 2 && selectedTab == 1) {
        val onGameBack = { activeGame = null; activeGameDeviceAddresses = emptyList() }
        val onSaveSession = { session: org.hubik.openfugu.session.Session -> viewModel.saveSession(session) }
        BackHandler { onGameBack() }
        // Every player gets a distinct color: the first device with a given
        // saved color keeps it; later duplicates and colorless devices get a
        // free preset for this game only (saved colors are never changed).
        val takenColors = mutableSetOf<Long>()
        val honoredColor = activeGameDeviceAddresses.associateWith { addr ->
            savedDevices.find { it.address == addr }?.colorArgb?.takeIf { takenColors.add(it) }
        }
        val playerInfos = activeGameDeviceAddresses.mapNotNull { addr ->
            val conn = connections[addr] ?: return@mapNotNull null
            val saved = savedDevices.find { it.address == addr } ?: return@mapNotNull null
            val profile = viewModel.userForDevice(addr)
            val colorArgb = honoredColor[addr] ?: run {
                val free = DeviceColors.presets.firstOrNull { it !in takenColors }
                    ?: DeviceColors.presets[activeGameDeviceAddresses.indexOf(addr) % DeviceColors.presets.size]
                takenColors.add(free)
                free
            }
            MultiplayerPlayerInfo(
                connection = conn,
                userProfile = profile,
                color = Color(colorArgb.toInt()),
                displayName = saved.displayName,
                userName = profile?.name
            )
        }
        if (playerInfos.size >= 2) {
            when (activeGame) {
                "reef" -> MultiplayerFuguReefScreen(
                    players = playerInfos,
                    onBack = onGameBack,
                    onSessionSave = onSaveSession
                )
                "feast" -> MultiplayerFuguFeastScreen(
                    players = playerInfos,
                    onBack = onGameBack,
                    onSessionSave = onSaveSession
                )
                "cave" -> MultiplayerFuguCaveScreen(
                    players = playerInfos,
                    onBack = onGameBack,
                    onSessionSave = onSaveSession
                )
            }
            return
        } else {
            // Too few players left. Reset in an effect (never as a side effect
            // of composition) and tell the players what happened.
            LaunchedEffect(Unit) {
                viewModel.postUserMessage("Player disconnected — multiplayer game ended")
                activeGame = null
                activeGameDeviceAddresses = emptyList()
            }
        }
    }

    // When a game is active, render it full-screen (no top/bottom bars).
    // Only the device the game was started with may drive it — falling back to
    // "any connected device" would silently hijack another player's eFugu.
    if (activeGame != null && activeGameDeviceAddresses.size == 1 && selectedTab == 1) {
        val connection = connections[activeGameDeviceAddresses.first()]
        if (connection != null) {
            BackHandler { activeGame = null; activeGameDeviceAddresses = emptyList() }
            val userProfile = viewModel.userForDevice(connection.address)
            val range = userProfile?.gamePressureRange ?: 40.0
            val negRange = userProfile?.gameNegativeRange ?: 0.0
            val expert = userProfile?.expertMode ?: false
            val savedDevice = savedDevices.find { it.address == connection.address }
            val deviceColor = savedDevice?.colorArgb?.let { Color(it.toInt()) }
            val devName = savedDevice?.displayName ?: connection.displayName
            val usrName = userProfile?.name
            val onGameBack = { activeGame = null; activeGameDeviceAddresses = emptyList() }
            val onSaveSession = { session: org.hubik.openfugu.session.Session -> viewModel.saveSession(session) }
            when (activeGame) {
                "reef" -> FuguReefScreen(connection = connection, onBack = onGameBack, pressureRange = range, negativeRange = negRange, expertMode = expert, deviceName = devName, userName = usrName, onSessionSave = onSaveSession)
                "feast" -> FuguFeastScreen(connection = connection, onBack = onGameBack, pressureRange = range, negativeRange = negRange, expertMode = expert, deviceName = devName, userName = usrName, onSessionSave = onSaveSession)
                "cave" -> FuguCaveScreen(connection = connection, onBack = onGameBack, pressureRange = range, negativeRange = negRange, expertMode = expert, deviceName = devName, userName = usrName, onSessionSave = onSaveSession)
                "flow" -> FuguFlowScreen(connection = connection, onBack = onGameBack, pressureRange = range, negativeRange = negRange, expertMode = expert, deviceName = devName, userName = usrName, onSessionSave = onSaveSession)
                "min_eq" -> MinEqExerciseScreen(
                    connection = connection,
                    lineColor = deviceColor,
                    userProfiles = userProfiles,
                    currentProfileId = userProfile?.id,
                    deviceName = devName,
                    userName = usrName,
                    onSave = { profileId, minEqHPa ->
                        val profile = userProfiles.find { it.id == profileId }
                        if (profile != null) {
                            viewModel.updateUser(profile.copy(minEqPressureHPa = minEqHPa))
                        }
                    },
                    onSessionSave = onSaveSession,
                    onBack = onGameBack
                )
                "constant_eq" -> ConstantEqScreen(
                    connection = connection,
                    lineColor = deviceColor,
                    minEqPressureHPa = userProfile?.minEqPressureHPa ?: 15.0,
                    deviceName = devName,
                    userName = usrName,
                    onSessionSave = onSaveSession,
                    onBack = onGameBack
                )
            }
            return
        } else {
            // The game's device disconnected. Reset in an effect (never as a
            // side effect of composition) and tell the user.
            LaunchedEffect(Unit) {
                viewModel.postUserMessage("Device disconnected — game ended")
                activeGame = null
                activeGameDeviceAddresses = emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenFugu") },
                actions = {
                    IconButton(onClick = { showLogs = true }) {
                        Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = "Logs")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "Live") },
                    label = { Text("Live") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.FitnessCenter, contentDescription = "Exercises") },
                    label = { Text("Exercises") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (connectedCount > 0) {
                                    Badge { Text("$connectedCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Sensors, contentDescription = "Devices")
                        }
                    },
                    label = { Text("Devices") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Filled.Person, contentDescription = "Users") },
                    label = { Text("Users") }
                )
            }
        }
    ) { padding ->
        // Auto-scan while on Devices tab; stop when leaving. The tab value is
        // captured here because onDispose runs after selectedTab has already
        // changed to the new tab.
        DisposableEffect(selectedTab) {
            val isDevicesTab = selectedTab == 2
            if (isDevicesTab) onRequestPermissionsAndScan()
            onDispose {
                if (isDevicesTab) viewModel.stopScan()
            }
        }
        when (selectedTab) {
            0 -> LiveTab(
                connections = connections,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            1 -> ExercisesTab(
                connections = connections,
                savedDevices = savedDevices,
                userProfiles = userProfiles,
                deviceUserPairings = deviceUserPairings,
                recentSessions = recentSessions,
                lastUsedDeviceAddresses = lastExerciseDeviceAddresses,
                onGameStart = { game, conns ->
                    activeGame = game
                    activeGameDeviceAddresses = conns.map { it.address }
                    lastExerciseDeviceAddresses = conns.map { it.address }
                },
                onSessionClick = { sessionId -> viewingSessionId = sessionId },
                onDeleteSession = { sessionId -> viewModel.deleteSession(sessionId) },
                onPairUser = { addr, userId ->
                    if (userId != null) viewModel.pairDeviceToUser(addr, userId)
                    else viewModel.unpairDevice(addr)
                },
                modifier = Modifier.padding(padding)
            )
            2 -> DevicesTab(
                scanState = scanState,
                savedDevices = savedDevices,
                scannedDevices = scannedDevices,
                connections = connections,
                userProfiles = userProfiles,
                deviceUserPairings = deviceUserPairings,
                onScan = onRequestPermissionsAndScan,
                onStopScan = { viewModel.stopScan() },
                onConnect = { viewModel.connectToDevice(it) },
                onDisconnect = { viewModel.disconnectDevice(it) },
                onForget = { viewModel.forgetDevice(it) },
                onNicknameSet = { addr, name -> viewModel.setNickname(addr, name) },
                onColorSet = { addr, color -> viewModel.setColor(addr, color) },
                onPairUser = { addr, userId ->
                    if (userId != null) viewModel.pairDeviceToUser(addr, userId)
                    else viewModel.unpairDevice(addr)
                },
                onAddMockDevice = { viewModel.addMockDevice() },
                modifier = Modifier.padding(padding)
            )
            3 -> UsersTab(
                userProfiles = userProfiles,
                savedDevices = savedDevices,
                deviceUserPairings = deviceUserPairings,
                onAddUser = { name -> calibrateOfferUser = viewModel.addUser(name) },
                onSelectUser = { userId -> showUserDetail = userId },
                modifier = Modifier.padding(padding)
            )
        }
    }

    if (showFirstUserDialog) {
        val firstDeviceName = firstConnectedAddress?.let { addr ->
            savedDevices.find { it.address == addr }?.displayName ?: connections[addr]?.displayName
        } ?: "your device"
        FirstUserDialog(
            deviceName = firstDeviceName,
            onDismiss = {
                showFirstUserDialog = false
                firstUserPromptDismissed = true
            },
            onCreate = { name ->
                val profile = viewModel.addUser(name)
                firstConnectedAddress?.let { viewModel.pairDeviceToUser(it, profile.id) }
                showFirstUserDialog = false
                calibrateOfferUser = profile
            }
        )
    }

    calibrateOfferUser?.let { offerUser ->
        AlertDialog(
            onDismissRequest = { calibrateOfferUser = null },
            title = { Text("Calibrate ${offerUser.name}?") },
            text = {
                Text(
                    "The calibration wizard measures a comfortable pressure range, and " +
                        "games and exercises scale to it. You can also run it later from " +
                        "the user detail screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    calibratingUserId = offerUser.id
                    calibrateOfferUser = null
                }) { Text("Calibrate Now") }
            },
            dismissButton = {
                TextButton(onClick = { calibrateOfferUser = null }) { Text("Later") }
            }
        )
    }
}

@Composable
fun FirstUserDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Your User") },
        text = {
            Column {
                Text(
                    "Your device is connected. Create a user to store calibration and " +
                        "settings — it will be assigned to $deviceName."
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}
