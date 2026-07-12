package org.hubik.openfugu.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hubik.openfugu.util.boolean
import org.hubik.openfugu.util.doubleOrNull
import org.hubik.openfugu.util.long
import org.hubik.openfugu.util.string
import org.hubik.openfugu.util.stringOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

/** Preset colors for identifying eFugu devices (hex ARGB). */
object DeviceColors {
    val presets = listOf(
        0xFFE53935.toLong(), // Red
        0xFFFF9800.toLong(), // Orange
        0xFFFFD54F.toLong(), // Yellow
        0xFF43A047.toLong(), // Green
        0xFF1E88E5.toLong(), // Blue
        0xFF8E24AA.toLong(), // Purple
        0xFFD81B60.toLong(), // Pink
        0xFF00ACC1.toLong(), // Teal
        0xFF6D4C41.toLong(), // Brown
        0xFF546E7A.toLong(), // Slate
    )
}

data class SavedDevice(
    val address: String,
    val name: String,
    val nickname: String?,
    val lastConnectedAt: Long,
    val colorArgb: Long? = null
) {
    val displayName: String get() = nickname ?: name
}

data class PressureReading(
    val pressureHPa: Double,
    val relativeHPa: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/** App-level scan state (not per-device) */
sealed class ScanState {
    data object Idle : ScanState()
    data object Scanning : ScanState()
    data class Error(val message: String) : ScanState()
}

@SuppressLint("MissingPermission")
class EFuguViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "EFugu"
        private const val PREFS_NAME = "efugu_prefs"
        private const val PREF_SAVED_DEVICES = "saved_devices_json"
        private const val PREF_USER_PROFILES = "user_profiles_json"
        private const val PREF_DEVICE_USER_PAIRINGS = "device_user_pairings_json"

        // Session files are at most a few MB (20 Hz traces); anything larger
        // than this is not a session file and must not be buffered into memory.
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }

    private val bluetoothManager =
        application.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- App-level state ---
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<SavedDevice>>(emptyList())
    val savedDevices = _savedDevices.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages = _logMessages.asStateFlow()

    // --- User profiles ---
    private val _userProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val userProfiles = _userProfiles.asStateFlow()

    private val _deviceUserPairings = MutableStateFlow<List<DeviceUserPairing>>(emptyList())
    val deviceUserPairings = _deviceUserPairings.asStateFlow()

    // --- Active connections (address -> PressureSource: BLE device or simulated) ---
    private val _connections = MutableStateFlow<Map<String, PressureSource>>(emptyMap())
    val connections = _connections.asStateFlow()

    // --- Session recording ---
    private val sessionRepository = org.hubik.openfugu.session.SessionRepository(getApplication())
    private val _recentSessions = MutableStateFlow<List<org.hubik.openfugu.session.SessionIndexEntry>>(emptyList())
    val recentSessions = _recentSessions.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    // Devices the user explicitly disconnected this app run. Auto-connect skips
    // these so a manual disconnect sticks while the device is still nearby;
    // an explicit connect (tap) makes the device eligible again.
    private val manuallyDisconnected = mutableSetOf<String>()

    init {
        log("OpenFugu ${org.hubik.openfugu.BuildConfig.VERSION_NAME} (build ${org.hubik.openfugu.BuildConfig.VERSION_CODE})")
        loadSavedDevices()
        loadUserProfiles()
        loadDeviceUserPairings()
        viewModelScope.launch {
            _recentSessions.value = sessionRepository.loadIndex()
        }

        // Periodically bump lastConnectedAt for currently-connected devices
        // so MRU tracking reflects ongoing usage, not just connection start time.
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                val connectedAddresses = _connections.value.keys
                if (connectedAddresses.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val updated = _savedDevices.value.map { device ->
                        if (device.address in connectedAddresses)
                            device.copy(lastConnectedAt = now)
                        else device
                    }.sortedByDescending { it.lastConnectedAt }
                    _savedDevices.value = updated
                    persistSavedDevices()
                }
            }
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val ts = LocalTime.now().format(timeFormat)
        _logMessages.update { (it + "[$ts] $message").takeLast(200) }
    }

    // --- Saved device persistence ---

    private fun loadSavedDevices() {
        val json = prefs.getString(PREF_SAVED_DEVICES, null)
        if (json != null) {
            try {
                val arr = Json.parseToJsonElement(json).jsonArray
                val devices = mutableListOf<SavedDevice>()
                for (el in arr) {
                    // Parse per entry: one unreadable device must not discard the rest.
                    try {
                        val obj = el.jsonObject
                        devices.add(SavedDevice(
                            address = obj.string("address"),
                            name = obj.string("name"),
                            nickname = obj.stringOrNull("nickname")?.takeIf { it.isNotEmpty() && it != "null" },
                            lastConnectedAt = obj.long("lastConnectedAt"),
                            colorArgb = obj.long("colorArgb", 0L).takeIf { it != 0L }
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unreadable saved device entry", e)
                    }
                }
                _savedDevices.value = devices.sortedByDescending { it.lastConnectedAt }
            } catch (e: Exception) {
                // Whole payload unreadable. Back it up: the in-memory list stays
                // empty, and the next persist would otherwise overwrite the pref
                // and silently destroy the user's devices.
                Log.w(TAG, "Failed to load saved devices — backing up raw payload", e)
                prefs.edit().putString(PREF_SAVED_DEVICES + "_backup", json).apply()
            }
        }

        // Migrate old single-device prefs
        val oldAddress = prefs.getString("last_device_address", null)
        val oldName = prefs.getString("last_device_name", null)
        if (oldAddress != null && _savedDevices.value.none { it.address == oldAddress }) {
            val migrated = SavedDevice(oldAddress, oldName ?: "eFugu", null, System.currentTimeMillis())
            _savedDevices.value = listOf(migrated) + _savedDevices.value
            persistSavedDevices()
            prefs.edit().remove("last_device_address").remove("last_device_name").apply()
        }
    }

    private fun persistSavedDevices() {
        val arr = buildJsonArray {
            _savedDevices.value.forEach { dev ->
                addJsonObject {
                    put("address", dev.address)
                    put("name", dev.name)
                    put("nickname", dev.nickname ?: "")
                    put("lastConnectedAt", dev.lastConnectedAt)
                    put("colorArgb", dev.colorArgb ?: 0L)
                }
            }
        }
        prefs.edit().putString(PREF_SAVED_DEVICES, arr.toString()).apply()
    }

    private fun rememberDevice(name: String?, address: String) {
        val current = _savedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == address }
        val device = if (existing >= 0) {
            // Existing device — keep timestamp as-is. The periodic ticker
            // updates lastConnectedAt while a device stays connected, so it
            // already reflects "most recent use". Updating here would mark a
            // just-tapped second device as MRU even though the first device
            // is still in active use.
            val old = current.removeAt(existing)
            old.copy(name = name ?: old.name)
        } else {
            SavedDevice(address, name ?: "eFugu", null, System.currentTimeMillis())
        }
        current.add(0, device)
        _savedDevices.value = current
        persistSavedDevices()
    }

    fun forgetDevice(address: String) {
        _savedDevices.value = _savedDevices.value.filter { it.address != address }
        persistSavedDevices()
        log("Forgot device $address")
    }

    fun setNickname(address: String, nickname: String?) {
        _savedDevices.value = _savedDevices.value.map {
            if (it.address == address) it.copy(nickname = nickname?.takeIf { n -> n.isNotBlank() })
            else it
        }
        persistSavedDevices()
    }

    fun setColor(address: String, colorArgb: Long?) {
        _savedDevices.value = _savedDevices.value.map {
            if (it.address == address) it.copy(colorArgb = colorArgb)
            else it
        }
        persistSavedDevices()
    }

    // --- User profile persistence ---

    private fun loadUserProfiles() {
        val json = prefs.getString(PREF_USER_PROFILES, null) ?: return
        try {
            val arr = Json.parseToJsonElement(json).jsonArray
            val profiles = mutableListOf<UserProfile>()
            for (el in arr) {
                // Parse per entry: one unreadable profile must not discard the rest.
                try {
                    val obj = el.jsonObject
                    profiles.add(UserProfile(
                        id = obj.string("id"),
                        name = obj.string("name"),
                        minEqPressureHPa = obj.doubleOrNull("minEqPressureHPa"),
                        maxPositiveHPa = obj.doubleOrNull("maxPositiveHPa"),
                        maxNegativeHPa = obj.doubleOrNull("maxNegativeHPa"),
                        gamePressureRangeManual = obj.doubleOrNull("gamePressureRangeManual"),
                        gameNegativeRangeManual = obj.doubleOrNull("gameNegativeRangeManual"),
                        useAutoRange = obj.boolean("useAutoRange", true),
                        expertMode = obj.boolean("expertMode", false),
                        lastCalibratedAt = obj.long("lastCalibratedAt", 0L).takeIf { it != 0L }
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable user profile entry", e)
                }
            }
            _userProfiles.value = profiles
        } catch (e: Exception) {
            // Whole payload unreadable — back it up so the calibration data is
            // not destroyed by the next persist (see loadSavedDevices).
            Log.w(TAG, "Failed to load user profiles — backing up raw payload", e)
            prefs.edit().putString(PREF_USER_PROFILES + "_backup", json).apply()
        }
    }

    private fun persistUserProfiles() {
        val arr = buildJsonArray {
            _userProfiles.value.forEach { p ->
                addJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    p.minEqPressureHPa?.let { put("minEqPressureHPa", it) }
                    p.maxPositiveHPa?.let { put("maxPositiveHPa", it) }
                    p.maxNegativeHPa?.let { put("maxNegativeHPa", it) }
                    p.gamePressureRangeManual?.let { put("gamePressureRangeManual", it) }
                    p.gameNegativeRangeManual?.let { put("gameNegativeRangeManual", it) }
                    put("useAutoRange", p.useAutoRange)
                    put("expertMode", p.expertMode)
                    p.lastCalibratedAt?.let { put("lastCalibratedAt", it) }
                }
            }
        }
        prefs.edit()
            .putString(PREF_USER_PROFILES, arr.toString())
            .apply()
    }

    fun addUser(name: String): UserProfile {
        val profile = UserProfile(name = name)
        _userProfiles.value += profile
        persistUserProfiles()
        return profile
    }

    fun updateUser(profile: UserProfile) {
        _userProfiles.value = _userProfiles.value.map {
            if (it.id == profile.id) profile else it
        }
        persistUserProfiles()
    }

    fun deleteUser(userId: String) {
        _userProfiles.value = _userProfiles.value.filter { it.id != userId }
        _deviceUserPairings.value = _deviceUserPairings.value.filter { it.userId != userId }
        persistUserProfiles()
        persistDeviceUserPairings()
    }

    // --- Device-user pairing persistence ---

    private fun loadDeviceUserPairings() {
        val json = prefs.getString(PREF_DEVICE_USER_PAIRINGS, null) ?: return
        try {
            val arr = Json.parseToJsonElement(json).jsonArray
            val pairings = mutableListOf<DeviceUserPairing>()
            for (el in arr) {
                try {
                    val obj = el.jsonObject
                    pairings.add(DeviceUserPairing(
                        deviceAddress = obj.string("deviceAddress"),
                        userId = obj.string("userId")
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable pairing entry", e)
                }
            }
            _deviceUserPairings.value = pairings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load pairings — backing up raw payload", e)
            prefs.edit().putString(PREF_DEVICE_USER_PAIRINGS + "_backup", json).apply()
        }
    }

    private fun persistDeviceUserPairings() {
        val arr = buildJsonArray {
            _deviceUserPairings.value.forEach { p ->
                addJsonObject {
                    put("deviceAddress", p.deviceAddress)
                    put("userId", p.userId)
                }
            }
        }
        prefs.edit().putString(PREF_DEVICE_USER_PAIRINGS, arr.toString()).apply()
    }

    fun pairDeviceToUser(deviceAddress: String, userId: String) {
        _deviceUserPairings.value = _deviceUserPairings.value
            .filter { it.deviceAddress != deviceAddress } + DeviceUserPairing(deviceAddress, userId)
        persistDeviceUserPairings()
    }

    fun unpairDevice(deviceAddress: String) {
        _deviceUserPairings.value = _deviceUserPairings.value
            .filter { it.deviceAddress != deviceAddress }
        persistDeviceUserPairings()
    }

    fun userForDevice(address: String): UserProfile? {
        val userId = _deviceUserPairings.value.find { it.deviceAddress == address }?.userId
            ?: return null
        return _userProfiles.value.find { it.id == userId }
    }

    // --- Scanning ---

    fun startScan() {
        if (_scanState.value is ScanState.Scanning) return

        val adapter = bluetoothAdapter ?: run {
            _scanState.value = ScanState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _scanState.value = ScanState.Error("Bluetooth is disabled")
            return
        }
        if (getApplication<Application>().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            _scanState.value = ScanState.Error("Bluetooth permission not granted")
            return
        }

        scanner = adapter.bluetoothLeScanner
        _scannedDevices.value = emptyList()
        _scanState.value = ScanState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(EFuguUuids.PRESSURE_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        log("Scanning for eFugu...")
        scanner?.startScan(listOf(filter), settings, scanCallback)
        scanner?.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), unfilteredScanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        scanner?.stopScan(unfilteredScanCallback)
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
        log("Scan stopped")
    }

    // --- Connection management ---

    fun connectToDevice(address: String) {
        // Don't double-connect
        if (_connections.value.containsKey(address)) return

        // Simulated devices bypass Bluetooth entirely — they work with the
        // adapter absent or disabled (e.g. on the emulator).
        if (MockDeviceConnection.isMockAddress(address)) {
            connectMockDevice(address)
            return
        }

        val adapter = bluetoothAdapter ?: run {
            log("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is disabled — cannot connect")
            return
        }

        // An explicit connect makes the device eligible for auto-connect again.
        manuallyDisconnected.remove(address)

        // Keep scan running so other saved/unknown devices can still be discovered;
        // it stops when the user leaves the Devices tab.

        // Find or create saved device entry
        val saved = _savedDevices.value.find { it.address == address }
            ?: SavedDevice(address, "eFugu", null, System.currentTimeMillis())

        rememberDevice(saved.name, address)
        // Re-fetch after remember to get updated saved device
        val updatedSaved = _savedDevices.value.find { it.address == address } ?: saved

        val tag = address.takeLast(5)
        val connection = DeviceConnection(
            address = address,
            savedDevice = updatedSaved,
            context = getApplication(),
            onLog = { msg -> log("[$tag] $msg") },
            onUnexpectedDisconnect = { removeConnection(address) }
        )

        _connections.value = _connections.value + (address to connection)
        connection.connect(adapter)
    }

    /**
     * Add a new simulated device and connect it immediately. Saved and paired
     * like a real device, so calibration-dependent features work; several can
     * run at once for multiplayer testing.
     */
    fun addMockDevice() {
        val used = _savedDevices.value
            .filter { MockDeviceConnection.isMockAddress(it.address) }
            .mapNotNull { it.address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX).toIntOrNull() }
            .toSet()
        if (used.size >= MockDeviceConnection.MAX_MOCK_DEVICES) {
            log("Simulated device limit reached (${MockDeviceConnection.MAX_MOCK_DEVICES})")
            return
        }
        val number = generateSequence(1) { it + 1 }.first { it !in used }
        connectMockDevice("${MockDeviceConnection.ADDRESS_PREFIX}$number")
    }

    private fun connectMockDevice(address: String) {
        if (_connections.value.containsKey(address)) return
        manuallyDisconnected.remove(address)

        val number = address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX)
        val saved = _savedDevices.value.find { it.address == address }
            ?: SavedDevice(address, "Simulated $number", null, System.currentTimeMillis())

        rememberDevice(saved.name, address)
        val updatedSaved = _savedDevices.value.find { it.address == address } ?: saved

        val connection = MockDeviceConnection(
            address = address,
            savedDevice = updatedSaved,
            onLog = { msg -> log("[$address] $msg") },
            scope = viewModelScope
        )
        _connections.value = _connections.value + (address to connection)
        connection.start()
    }

    private fun removeConnection(address: String) {
        _connections.value = _connections.value - address
        bumpLastUsed(address)
    }

    fun disconnectDevice(address: String) {
        val connection = _connections.value[address] ?: return
        connection.disconnect()
        _connections.value = _connections.value - address
        manuallyDisconnected.add(address)
        bumpLastUsed(address)
        log("Disconnected ${connection.displayName}")
    }

    fun disconnectAll() {
        val addresses = _connections.value.keys.toList()
        _connections.value.values.forEach { it.disconnect() }
        _connections.value = emptyMap()
        addresses.forEach { bumpLastUsed(it) }
    }

    /** Update lastConnectedAt to now for the given device (final timestamp at disconnect). */
    private fun bumpLastUsed(address: String) {
        val current = _savedDevices.value
        val idx = current.indexOfFirst { it.address == address }
        if (idx < 0) return
        val updated = current.toMutableList()
        updated[idx] = updated[idx].copy(lastConnectedAt = System.currentTimeMillis())
        _savedDevices.value = updated.sortedByDescending { it.lastConnectedAt }
        persistSavedDevices()
    }

    fun resetCalibration(address: String) {
        _connections.value[address]?.resetCalibration()
    }

    // --- Scan callbacks ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan stopped by system (error=$errorCode)")
            _scanState.value = ScanState.Idle
        }
    }

    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.contains("efugu", ignoreCase = true) ||
                name.contains("fugu", ignoreCase = true) ||
                name.contains("go2deep", ignoreCase = true)) {
                addScanResult(result)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            log("Unfiltered scan stopped by system (error=$errorCode)")
        }
    }

    private fun addScanResult(result: ScanResult) {
        val device = ScannedDevice(
            name = result.device.name,
            address = result.device.address,
            rssi = result.rssi
        )
        val current = _scannedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == device.address }
        if (existing >= 0) {
            current[existing] = device
        } else {
            log("Found: ${device.name ?: "unnamed"} [${device.address}] RSSI=${device.rssi}")
            current.add(device)
        }
        _scannedDevices.value = current

        // Auto-connect to the most recently used saved device, but only when
        // nothing else is already connected, and never to a device the user
        // manually disconnected this app run — a manual disconnect must stick
        // even while the scan still sees the device nearby. Simulated devices
        // never appear in scans, so skip them when picking the MRU device or a
        // recently-used mock would block real-device auto-connect forever.
        val mruDevice = _savedDevices.value
            .firstOrNull { !MockDeviceConnection.isMockAddress(it.address) }
        if (mruDevice != null &&
            _connections.value.isEmpty() &&
            _scanState.value is ScanState.Scanning) {
            if (device.address == mruDevice.address &&
                mruDevice.address !in manuallyDisconnected) {
                log("Auto-connecting to ${mruDevice.displayName}...")
                connectToDevice(device.address)
            }
        }
    }

    // --- Session recording ---

    fun saveSession(session: org.hubik.openfugu.session.Session) {
        viewModelScope.launch {
            val saved = sessionRepository.saveSession(session)
            _recentSessions.value = sessionRepository.loadIndex()
            if (!saved) {
                log("Failed to save session!")
                Toast.makeText(getApplication(), "Could not save session", Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun loadSession(id: String): org.hubik.openfugu.session.Session? =
        sessionRepository.loadSession(id)

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(id)
            _recentSessions.value = sessionRepository.loadIndex()
        }
    }

    suspend fun exportSessionJson(id: String): String? = sessionRepository.exportSessionJson(id)

    /**
     * Import a shared session file (content URI from an ACTION_VIEW/ACTION_SEND
     * intent). The session is saved into history so the standard viewer, share,
     * and delete actions all work on it. Returns null if the file is not a
     * readable OpenFugu session.
     */
    suspend fun importSession(uri: android.net.Uri): org.hubik.openfugu.session.Session? {
        val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readNBytes(MAX_IMPORT_BYTES + 1) }
                    ?: return@withContext null
                if (bytes.size > MAX_IMPORT_BYTES) {
                    Log.w(TAG, "Rejecting session import: file exceeds $MAX_IMPORT_BYTES bytes")
                    return@withContext null
                }
                org.hubik.openfugu.session.SessionJson.sessionFromJson(
                    Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import session from $uri", e)
                null
            }
        } ?: return null
        if (!sessionRepository.saveSession(session)) return null
        _recentSessions.value = sessionRepository.loadIndex()
        log("Imported session: ${session.type} from ${session.deviceName}")
        return session
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        disconnectAll()
    }
}
