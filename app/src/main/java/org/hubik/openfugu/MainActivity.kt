package org.hubik.openfugu

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hubik.openfugu.ble.DeviceColors
import org.hubik.openfugu.ble.DeviceUserPairing
import org.hubik.openfugu.ble.UserProfile
import org.hubik.openfugu.ble.DeviceConnection
import org.hubik.openfugu.ble.DeviceConnectionState
import org.hubik.openfugu.ble.EFuguViewModel
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
import org.hubik.openfugu.game.drawEnemyFish
import org.hubik.openfugu.game.drawFugu
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.ble.SavedDevice
import org.hubik.openfugu.ble.ScanState
import org.hubik.openfugu.ble.ScannedDevice
import org.hubik.openfugu.ble.formatHPa
import org.hubik.openfugu.session.SessionViewerScreen
import org.hubik.openfugu.ui.AppColors
import org.hubik.openfugu.ui.UserDetailScreen
import org.hubik.openfugu.ui.CalibrationWizard
import org.hubik.openfugu.ui.theme.OpenFuguTheme

class MainActivity : ComponentActivity() {
    private lateinit var efuguViewModel: EFuguViewModel

    // Session file handed to us via ACTION_VIEW/ACTION_SEND. Compose state so
    // EFuguApp picks it up whether it arrives in onCreate or onNewIntent.
    private var importIntent by mutableStateOf<Intent?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            efuguViewModel.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importIntent = intent
        setContent {
            OpenFuguTheme {
                efuguViewModel = viewModel()
                EFuguApp(
                    viewModel = efuguViewModel,
                    onRequestPermissionsAndScan = { requestPermissionsAndScan() },
                    importIntent = importIntent,
                    onImportIntentHandled = { importIntent = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importIntent = intent
    }

    private fun requestPermissionsAndScan() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            efuguViewModel.startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

// =============================================================================
// App root — always shows bottom navigation
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EFuguApp(
    viewModel: EFuguViewModel,
    onRequestPermissionsAndScan: () -> Unit,
    importIntent: Intent? = null,
    onImportIntentHandled: () -> Unit = {}
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
    // "open with" from an email or messenger attachment).
    val importContext = LocalContext.current
    LaunchedEffect(importIntent) {
        val intent = importIntent ?: return@LaunchedEffect
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (uri != null) {
            val session = viewModel.importSession(uri)
            if (session != null) {
                viewingSessionId = session.id
            } else {
                Toast.makeText(importContext, "This file is not an OpenFugu session", Toast.LENGTH_LONG).show()
            }
        }
        onImportIntentHandled()
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
            LogsTab(logMessages = logMessages, modifier = Modifier.padding(padding))
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
        val playerInfos = activeGameDeviceAddresses.mapNotNull { addr ->
            val conn = connections[addr] ?: return@mapNotNull null
            val saved = savedDevices.find { it.address == addr } ?: return@mapNotNull null
            val profile = viewModel.userForDevice(addr)
            val color = saved.colorArgb?.let { Color(it.toInt()) }
                ?: Color(org.hubik.openfugu.ble.DeviceColors.presets[activeGameDeviceAddresses.indexOf(addr) % org.hubik.openfugu.ble.DeviceColors.presets.size].toInt())
            MultiplayerPlayerInfo(
                connection = conn,
                userProfile = profile,
                savedDevice = saved,
                color = color,
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
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                Toast.makeText(context, "Player disconnected — multiplayer game ended", Toast.LENGTH_LONG).show()
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
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                Toast.makeText(context, "Device disconnected — game ended", Toast.LENGTH_LONG).show()
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

// =============================================================================
// Live tab — shows all connected devices
// =============================================================================

@Composable
fun LiveTab(
    connections: Map<String, DeviceConnection>,
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
    connection: DeviceConnection,
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
    connection: DeviceConnection,
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


// =============================================================================
// Devices tab
// =============================================================================

@Composable
fun DevicesTab(
    scanState: ScanState,
    savedDevices: List<SavedDevice>,
    scannedDevices: List<ScannedDevice>,
    connections: Map<String, DeviceConnection>,
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
        val disconnectedDevices = savedDevices.filter { !connections.containsKey(it.address) }
        // Re-evaluate freshness every second so stale entries fade out
        val nowMs by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
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
                    val isNearby = device.address in nearbyAddresses
                    SavedDeviceRow(
                        device = device,
                        userProfiles = userProfiles,
                        pairedUserId = pairedUserId,
                        isNearby = isNearby,
                        onConnect = { onConnect(device.address) },
                        onForget = { onForget(device.address) },
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
    }
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
                    if (device.nickname != null) "${device.name} — ${device.address}"
                    else device.address,
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
    connections: Map<String, DeviceConnection>,
    savedDevices: List<SavedDevice>,
    userProfiles: List<UserProfile>,
    deviceUserPairings: List<DeviceUserPairing>,
    selectedAddress: String? = null,
    onSelect: (DeviceConnection) -> Unit = {},
    onPairUser: (deviceAddress: String, userId: String?) -> Unit,
    onDismiss: () -> Unit,
    multiSelect: Boolean = false,
    onMultiSelect: (List<DeviceConnection>) -> Unit = {},
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


