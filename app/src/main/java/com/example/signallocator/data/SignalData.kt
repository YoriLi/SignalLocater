package com.example.signallocator.data

enum class SignalType { CELL, WIFI, BLE, BT_CLASSIC }

data class SignalData(
    val type: SignalType,
    val rssi: Int,
    val name: String,
    val address: String,
    val timestamp: Long = System.currentTimeMillis(),
    val txPower: Int = Int.MIN_VALUE,
    val hasRealName: Boolean = true,
    val isBonded: Boolean = false     // 是否为已配对设备
)
