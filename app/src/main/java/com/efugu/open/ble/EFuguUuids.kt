package com.efugu.open.ble

import java.util.UUID

object EFuguUuids {
    // eFugu auth/config service (dcdf = unknown/config, dcdc = RSA auth)
    val PRESSURE_SERVICE: UUID = UUID.fromString("81e7d6e4-6a6e-4561-8a33-af23e095be46")
    val DCDF_CHAR: UUID = UUID.fromString("acf1ab11-2e62-45b7-95d1-44e786c9dcdf")
    val AUTH_CHALLENGE: UUID = UUID.fromString("acf1ab11-2e62-45b7-95d1-44e786c9dcdc") // dcdc

    // Real-time pressure service — ASCII pressure in Pascals, notifications auto-start
    val REALTIME_PRESSURE_SERVICE: UUID = UUID.fromString("071517e5-9b61-4bec-a809-493dbbf6e811")
    val REALTIME_PRESSURE_DATA: UUID = UUID.fromString("5acc8084-d5f9-4439-8c95-51fd73370dd4")

    // Standard services
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor (for enabling notifications)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
