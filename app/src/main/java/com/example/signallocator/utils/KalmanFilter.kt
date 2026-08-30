package com.example.signallocator.utils

/**
 * 一维卡尔曼滤波器，平滑 BLE RSSI 波动
 * BLE RSSI 典型波动 ±5~10dBm，直接测距会跳变
 */
class RssiKalmanFilter(
    private val processNoise: Double = 3.0,      // 增大 → 更信任测量值，响应更快
    private val measurementNoise: Double = 5.0   // 减小 → 更信任测量值
) {
    private var estimate = 0.0
    private var errorCov = 1.0
    private var initialized = false

    fun update(measurement: Int): Int {
        if (!initialized) { estimate = measurement.toDouble(); initialized = true; return measurement }
        val predictedCov = errorCov + processNoise
        val gain = predictedCov / (predictedCov + measurementNoise)
        estimate += gain * (measurement - estimate)
        errorCov = (1 - gain) * predictedCov
        return estimate.toInt()
    }

    fun reset() { initialized = false; estimate = 0.0; errorCov = 1.0 }
}
