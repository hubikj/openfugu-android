package com.efugu.open.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

data class SavedDevice(
    val address: String,
    val name: String,
    val nickname: String?,
    val lastConnectedAt: Long
) {
    val displayName: String get() = nickname ?: name
}

data class PressureReading(
    val pressureHPa: Double,
    val relativeHPa: Double,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@SuppressLint("MissingPermission")
class EFuguViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "EFugu"
        private const val CHART_HISTORY_SIZE = 200  // ~10 seconds at 20 Hz
        private const val PREFS_NAME = "efugu_prefs"
        private const val PREF_SAVED_DEVICES = "saved_devices_json"
    }

    private val bluetoothManager =
        application.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<SavedDevice>>(emptyList())
    val savedDevices = _savedDevices.asStateFlow()

    private val _latestPressure = MutableStateFlow<PressureReading?>(null)
    val latestPressure = _latestPressure.asStateFlow()

    private val _chartData = MutableStateFlow<List<PressureReading>>(emptyList())
    val chartData = _chartData.asStateFlow()

    private val _chartMin = MutableStateFlow<Double?>(null)
    val chartMin = _chartMin.asStateFlow()
    private val _chartMax = MutableStateFlow<Double?>(null)
    val chartMax = _chartMax.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages = _logMessages.asStateFlow()

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated = _isCalibrated.asStateFlow()

    // Track which device we're connected to
    private var connectedAddress: String? = null
    val connectedDevice: SavedDevice?
        get() = connectedAddress?.let { addr ->
            _savedDevices.value.find { it.address == addr }
        }

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var pendingCharacteristics: MutableList<BluetoothGattCharacteristic> = mutableListOf()

    // Auth state (runs silently in background)
    private val secureRandom = SecureRandom()
    private var authChallenge: ByteArray? = null
    private var authResponseBuffer = ByteArray(0)
    private var authComplete = false

    // Calibration
    private var ambientBaselineHPa: Double? = null
    private var calibrationSamples = mutableListOf<Double>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var pressureLogCounter = 0

    init {
        loadSavedDevices()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val ts = timeFormat.format(Date())
        _logMessages.value += "[$ts] $message"
        if (_logMessages.value.size > 200) {
            _logMessages.value = _logMessages.value.takeLast(200)
        }
    }

    // --- Saved device persistence ---

    private fun loadSavedDevices() {
        val json = prefs.getString(PREF_SAVED_DEVICES, null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                val devices = mutableListOf<SavedDevice>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    devices.add(SavedDevice(
                        address = obj.getString("address"),
                        name = obj.getString("name"),
                        nickname = obj.optString("nickname").takeIf { it.isNotEmpty() && it != "null" },
                        lastConnectedAt = obj.getLong("lastConnectedAt")
                    ))
                }
                _savedDevices.value = devices.sortedByDescending { it.lastConnectedAt }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved devices", e)
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
        val arr = JSONArray()
        _savedDevices.value.forEach { dev ->
            arr.put(JSONObject().apply {
                put("address", dev.address)
                put("name", dev.name)
                put("nickname", dev.nickname ?: "")
                put("lastConnectedAt", dev.lastConnectedAt)
            })
        }
        prefs.edit().putString(PREF_SAVED_DEVICES, arr.toString()).apply()
    }

    private fun rememberDevice(name: String?, address: String) {
        val current = _savedDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == address }
        val device = if (existing >= 0) {
            val old = current.removeAt(existing)
            old.copy(name = name ?: old.name, lastConnectedAt = System.currentTimeMillis())
        } else {
            SavedDevice(address, name ?: "eFugu", null, System.currentTimeMillis())
        }
        current.add(0, device) // MRU first
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

    // --- Scanning ---

    fun startScan() {
        // Guard: don't scan if already scanning or connected
        if (_connectionState.value !is ConnectionState.Disconnected &&
            _connectionState.value !is ConnectionState.Error) {
            return
        }

        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        scanner = adapter.bluetoothLeScanner
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

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
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
        log("Scan stopped")
    }

    // --- Connection ---

    fun connectToDevice(address: String) {
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        log("Connecting to $address...")

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            _connectionState.value = ConnectionState.Error("Device not found")
            return
        }
        bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedAddress = null
        _connectionState.value = ConnectionState.Disconnected
        _latestPressure.value = null
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _batteryLevel.value = null
        _deviceInfo.value = emptyMap()
        authChallenge = null
        authResponseBuffer = ByteArray(0)
        authComplete = false
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        pressureLogCounter = 0
        log("Disconnected")
    }

    fun resetCalibration() {
        ambientBaselineHPa = null
        calibrationSamples.clear()
        _isCalibrated.value = false
        _chartData.value = emptyList()
        _chartMin.value = null
        _chartMax.value = null
        _latestPressure.value = null
        log("Calibration reset — establishing new baseline...")
    }

    // --- Scan callbacks ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result, "filtered")
        }
    }

    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (name.contains("efugu", ignoreCase = true) ||
                name.contains("fugu", ignoreCase = true) ||
                name.contains("go2deep", ignoreCase = true)) {
                addScanResult(result, "unfiltered")
            }
        }
    }

    private fun addScanResult(result: ScanResult, source: String) {
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

        // Auto-connect to most recently used saved device
        val saved = _savedDevices.value
        if (saved.isNotEmpty() && _connectionState.value is ConnectionState.Scanning) {
            val mruDevice = saved.first() // sorted by lastConnectedAt desc
            if (device.address == mruDevice.address) {
                log("Auto-connecting to ${mruDevice.displayName}...")
                connectToDevice(device.address)
            }
        }
    }

    // --- GATT callbacks ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected — requesting MTU 517...")
                    connectedAddress = gatt.device.address
                    _connectionState.value = ConnectionState.Connected
                    rememberDevice(gatt.device.name, gatt.device.address)
                    gatt.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status=$status)")
                    connectedAddress = null
                    _connectionState.value = ConnectionState.Disconnected
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("MTU=$mtu — discovering services...")
            } else {
                log("MTU failed (status=$status) — discovering services...")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: status=$status")
                return
            }

            val serviceNames = gatt.services.map { s ->
                when (s.uuid) {
                    EFuguUuids.REALTIME_PRESSURE_SERVICE -> "Pressure"
                    EFuguUuids.PRESSURE_SERVICE -> "Auth"
                    EFuguUuids.BATTERY_SERVICE -> "Battery"
                    EFuguUuids.DEVICE_INFO_SERVICE -> "DeviceInfo"
                    else -> s.uuid.toString().take(8)
                }
            }
            log("Services: ${serviceNames.joinToString(", ")}")

            pendingCharacteristics.clear()
            readDeviceInfoAndSubscribe(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                processNextCharacteristic(gatt)
                return
            }

            when (characteristic.uuid) {
                EFuguUuids.BATTERY_LEVEL -> {
                    val level = value[0].toInt() and 0xFF
                    _batteryLevel.value = level
                    log("Battery: $level%")
                }
                EFuguUuids.FIRMWARE_REVISION -> {
                    val fw = String(value)
                    _deviceInfo.value += ("Firmware" to fw)
                }
                EFuguUuids.HARDWARE_REVISION -> {
                    val hw = String(value)
                    _deviceInfo.value += ("Hardware" to hw)
                }
                EFuguUuids.MANUFACTURER_NAME -> {
                    val mfr = String(value)
                    _deviceInfo.value += ("Manufacturer" to mfr)
                }
                EFuguUuids.SERIAL_NUMBER -> {
                    val serial = String(value)
                    _deviceInfo.value += ("Serial" to serial)
                }
            }
            processNextCharacteristic(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                EFuguUuids.REALTIME_PRESSURE_DATA -> handlePressureNotification(value)
                EFuguUuids.AUTH_CHALLENGE -> handleAuthResponse(value)
                EFuguUuids.BATTERY_LEVEL -> {
                    _batteryLevel.value = value[0].toInt() and 0xFF
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == EFuguUuids.AUTH_CHALLENGE && status != BluetoothGatt.GATT_SUCCESS) {
                log("Auth challenge write failed: status=$status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Failed to enable notifications for ${descriptor.characteristic.uuid}: status=$status")
            }
            processNextCharacteristic(gatt)
        }
    }

    // --- BLE setup ---

    private fun readDeviceInfoAndSubscribe(gatt: BluetoothGatt) {
        authComplete = false
        authResponseBuffer = ByteArray(0)

        gatt.getService(EFuguUuids.DEVICE_INFO_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.MANUFACTURER_NAME)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.SERIAL_NUMBER)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.FIRMWARE_REVISION)?.let { pendingCharacteristics.add(it) }
            service.getCharacteristic(EFuguUuids.HARDWARE_REVISION)?.let { pendingCharacteristics.add(it) }
        }

        gatt.getService(EFuguUuids.BATTERY_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.BATTERY_LEVEL)?.let { pendingCharacteristics.add(it) }
        }

        gatt.getService(EFuguUuids.REALTIME_PRESSURE_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.REALTIME_PRESSURE_DATA)?.let { char ->
                pendingCharacteristics.add(char)
            }
        } ?: log("WARNING: Pressure service not found!")

        gatt.getService(EFuguUuids.PRESSURE_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.AUTH_CHALLENGE)?.let { char ->
                pendingCharacteristics.add(char)
            }
        }

        gatt.getService(EFuguUuids.BATTERY_SERVICE)?.let { service ->
            service.getCharacteristic(EFuguUuids.BATTERY_LEVEL)?.let { char ->
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    pendingCharacteristics.add(char)
                }
            }
        }

        processNextCharacteristic(gatt)
    }

    private fun processNextCharacteristic(gatt: BluetoothGatt) {
        if (pendingCharacteristics.isEmpty()) {
            log("Setup complete — starting auth...")
            startAuthentication(gatt)
            return
        }

        val char = pendingCharacteristics.removeFirst()
        val props = char.properties

        val isCustomChar = char.service.uuid == EFuguUuids.PRESSURE_SERVICE ||
                char.service.uuid == EFuguUuids.REALTIME_PRESSURE_SERVICE ||
                (char.uuid == EFuguUuids.BATTERY_LEVEL && _batteryLevel.value != null)

        if (isCustomChar && (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)) {
            enableNotifications(gatt, char)
        } else if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            gatt.readCharacteristic(char)
        } else {
            processNextCharacteristic(gatt)
        }
    }

    // --- Auth ---

    private fun startAuthentication(gatt: BluetoothGatt) {
        val service = gatt.getService(EFuguUuids.PRESSURE_SERVICE) ?: return
        val authChar = service.getCharacteristic(EFuguUuids.AUTH_CHALLENGE) ?: return

        val challenge = ByteArray(128)
        secureRandom.nextBytes(challenge)
        authChallenge = challenge
        authResponseBuffer = ByteArray(0)

        log("Auth: sending challenge...")
        gatt.writeCharacteristic(authChar, challenge, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun handleAuthResponse(value: ByteArray) {
        authResponseBuffer += value
        if (!authComplete && authResponseBuffer.size >= 336) {
            authComplete = true
            log("Auth complete (${authResponseBuffer.size}B signature)")
        }
    }

    // --- Pressure ---

    private fun handlePressureNotification(value: ByteArray) {
        val ascii = String(value, Charsets.US_ASCII)
        val pressurePa = ascii.toDoubleOrNull() ?: return
        val pressureHPa = pressurePa / 100.0

        // Auto-calibrate from first 20 readings (~1 second)
        if (ambientBaselineHPa == null) {
            calibrationSamples.add(pressureHPa)
            if (calibrationSamples.size >= 20) {
                ambientBaselineHPa = calibrationSamples.average()
                _isCalibrated.value = true
                log("Calibrated: baseline=${"%.1f".format(ambientBaselineHPa)} hPa")
                calibrationSamples.clear()
            }
            return
        }

        val relativeHPa = pressureHPa - (ambientBaselineHPa ?: pressureHPa)
        val reading = PressureReading(
            pressureHPa = pressureHPa,
            relativeHPa = relativeHPa
        )
        _latestPressure.value = reading

        val history = _chartData.value.toMutableList()
        history.add(reading)
        if (history.size > CHART_HISTORY_SIZE) {
            history.removeFirst()
        }
        _chartData.value = history

        // Update min/max over chart window
        val values = history.map { it.relativeHPa }
        _chartMin.value = values.min()
        _chartMax.value = values.max()

        // Log every ~5 seconds
        pressureLogCounter++
        if (pressureLogCounter % 100 == 1) {
            log("Pressure: ${"%.1f".format(relativeHPa)} hPa (abs=${"%.1f".format(pressureHPa)})")
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(EFuguUuids.CCCD)
        if (descriptor != null) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            processNextCharacteristic(gatt)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

/** Format hPa avoiding "-0.0" display */
fun formatHPa(value: Double): String {
    val display = if (abs(value) < 0.05) 0.0 else value
    return "%.1f".format(display)
}
