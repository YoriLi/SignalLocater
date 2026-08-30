package com.example.signallocator.data

// 标定点的BLE测量记录
data class CalibrationMeasurement(
    val cornerIndex: Int,
    val x: Double,           // 已知坐标
    val y: Double,
    val rawRssi: Int,        // 原始RSSI
    val filteredRssi: Int,   // 卡尔曼滤波后RSSI
    val txPower: Int,
    val distance: Double     // 估算距离
)

// 信号指纹：某位置的完整信号快照
// 存储所有可见 AP 和 BLE 设备的 RSSI，而不是只存最强值
// 在密集环境下（如图书馆），每个位置的"信号向量"是唯一的
data class SignalFingerprint(
    val x: Double, val y: Double,
    val cellRssi: Int,                          // 基站信号（单值）
    val wifiSignals: Map<String, Int>,           // BSSID → RSSI，所有可见 AP
    val bleSignals: Map<String, Int>,            // MAC → RSSI，所有可见 BLE 设备
    val timestamp: Long = System.currentTimeMillis()
) {
    // 兼容旧代码的字段
    val wifiRssi: Int get() = wifiSignals.values.maxOrNull() ?: -100
    val bleRssi: Int get() = bleSignals.values.maxOrNull() ?: -100
    val wifiCount: Int get() = wifiSignals.size
    val cellId: String get() = "CELL"
}

// 跟踪点：手机自定位结果 + BLE 测距
data class TrackingPoint(
    val pointId: Int,
    val estimatedX: Double,
    val estimatedY: Double,
    val bleRssi: Int,
    val txPower: Int,
    val distance: Double
)

// 单次跟踪测量的完整记录（保留所有数据）
data class TrackMeasurement(
    val pointId: Int,
    val phoneEstimatedX: Double,   // 手机估算坐标
    val phoneEstimatedY: Double,
    val targetBleRssi: Int,        // 目标 BLE RSSI
    val filteredRssi: Int,         // 滤波后 RSSI
    val txPower: Int,              // 发射功率
    val distance: Double,          // 估算距离
    val visibleAps: Int,           // 可见 AP 数量
    val visibleBle: Int,           // 可见 BLE 数量
    val timestamp: Long = System.currentTimeMillis()
)

// 定位结果
data class Position(val x: Double, val y: Double, val errorRadius: Double = 0.5)

// 房间实际尺寸（通过计步测量）
data class RoomDimensions(
    val width: Double,   // x方向实际长度（米）
    val height: Double,  // y方向实际长度（米）
    val scaleX: Double,  // x方向比例尺 = 实际米数 / 坐标跨度(4)
    val scaleY: Double   // y方向比例尺 = 实际米数 / 坐标跨度(4)
)

const val DEFAULT_TX_POWER = -50  // 1米处参考RSSI（实测校准）
