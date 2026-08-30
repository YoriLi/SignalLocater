package com.example.signallocator.utils

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 基于加速度计的计步器
 * 检测加速度波峰来识别步伐，估算行走距离
 */
class StepCounter(
    private val sensorManager: SensorManager,
    private val onStepUpdate: (steps: Int, distance: Double) -> Unit
) : SensorEventListener {

    companion object {
        // 加速度波峰检测阈值（去掉重力后的净加速度）
        private const val STEP_THRESHOLD = 1.8f
        // 两步之间的最小时间间隔（防止抖动误判）
        private const val MIN_STEP_INTERVAL_MS = 250L
        // 默认步长（米），后续可按身高调整
        var STEP_LENGTH = 0.70
    }

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var steps = 0
    private var lastStepTime = 0L
    private var lastMagnitude = 0f
    private var isRunning = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.8f // 低通滤波系数，分离重力

    fun start() {
        if (isRunning) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            isRunning = true
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isRunning = false
    }

    fun reset() {
        steps = 0
        lastStepTime = 0L
        lastMagnitude = 0f
        onStepUpdate(0, 0.0)
    }

    fun getSteps(): Int = steps
    fun getDistance(): Double = steps * STEP_LENGTH

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // 低通滤波分离重力
        gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
        gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
        gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]

        // 去掉重力后的净加速度
        val linearX = event.values[0] - gravityX
        val linearY = event.values[1] - gravityY
        val linearZ = event.values[2] - gravityZ

        val magnitude = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)

        // 波峰检测：幅度超过阈值，且距离上一步有足够时间间隔
        val now = System.currentTimeMillis()
        if (magnitude > STEP_THRESHOLD && lastMagnitude <= STEP_THRESHOLD
            && now - lastStepTime > MIN_STEP_INTERVAL_MS
        ) {
            steps++
            lastStepTime = now
            onStepUpdate(steps, steps * STEP_LENGTH)
        }

        lastMagnitude = magnitude
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
