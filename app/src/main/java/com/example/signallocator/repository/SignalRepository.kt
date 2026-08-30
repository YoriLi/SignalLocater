package com.example.signallocator.repository

import android.content.Context
import com.example.signallocator.data.CalibrationMeasurement
import com.example.signallocator.data.DEFAULT_TX_POWER
import com.example.signallocator.data.Position
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalFingerprint
import com.example.signallocator.data.TrackMeasurement
import com.example.signallocator.data.TrackingPoint
import com.example.signallocator.manager.BluetoothHelper
import com.example.signallocator.manager.TelephonyHelper
import com.example.signallocator.manager.WifiHelper
import com.example.signallocator.utils.calculatePosition
import com.example.signallocator.utils.rssiToDistance
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class SignalRepository(private val context: Context) {

    var targetMac: String? = null
    // 比例尺
    var scaleX: Double = 1.0
    var scaleY: Double = 1.0
    var roomMeasured: Boolean = false

    private val fingerprints = mutableListOf<SignalFingerprint>()
    // 每个角的测量结果
    private val calibMeasurements = mutableListOf<CalibrationMeasurement>()
    private val mutex = Mutex()

    /** 扫描所有信号，已配对设备标⭐ */
    suspend fun scanAllSignals(): List<SignalData> = coroutineScope {
        val cellDeferred = async { TelephonyHelper.getCellSignal(context) }
        val wifiDeferred = async { WifiHelper.scanWifi(context) }
        val bleDeferred = async { BluetoothHelper.scanBle(context) }

        // BLE 扫描和经典蓝牙发现不能同时进行（共享蓝牙射频），
        // 需等 BLE 扫描结束后再启动经典蓝牙发现，避免 startDiscovery() 静默失败
        val bleList = bleDeferred.await()
        val classicDeferred = async { BluetoothHelper.scanClassic(context) }

        val bondedMacs = BluetoothHelper.getBondedDevices(context)
            .filter { it.hasRealName }
            .map { it.address }
            .toSet()

        val result = mutableListOf<SignalData>()
        cellDeferred.await()?.let { result.add(it) }
        result.addAll(wifiDeferred.await())
        result.addAll(bleList)
        result.addAll(classicDeferred.await())

        result.map { if (it.address in bondedMacs) it.copy(isBonded = true) else it }
    }

    /**
     * 标定：在已知位置采集信号 + 测量目标蓝牙距离
     * 同时扫描BLE和经典蓝牙，合并结果找目标
     */
    suspend fun calibrate(x: Double, y: Double, cornerIndex: Int): CalibrationMeasurement? {
        val mac = targetMac ?: return null

        val cell = TelephonyHelper.getCellSignal(context)
        val wifiList = WifiHelper.scanWifi(context)
        // BLE 和经典蓝牙并发扫描
        val bleList = BluetoothHelper.scanBle(context)
        val classicList = BluetoothHelper.scanClassic(context)
        // 合并所有蓝牙设备
        val allBluetooth = bleList + classicList

        if (cell == null && wifiList.isEmpty() && allBluetooth.isEmpty()) return null

        // 保存指纹（用于显示）
        val wifiSignals = wifiList.associate { it.address to it.rssi }
        val bleSignals = allBluetooth.associate { it.address to it.rssi }
        val fp = SignalFingerprint(
            x = x, y = y,
            cellRssi = cell?.rssi ?: -100,
            wifiSignals = wifiSignals,
            bleSignals = bleSignals
        )
        mutex.withLock {
            val idx = fingerprints.indexOfFirst { it.x == x && it.y == y }
            if (idx >= 0) fingerprints[idx] = fp else fingerprints.add(fp)
        }

        // 在所有蓝牙设备中找目标（BLE或经典蓝牙）
        Log.d("SignalRepo", "扫描完成: BLE=${bleList.size}个, 经典=${classicList.size}个, 目标MAC=$mac")
        for (device in allBluetooth) {
            Log.d("SignalRepo", "  发现: ${device.address} ${device.name} RSSI=${device.rssi} type=${device.type}")
        }
        val target = allBluetooth.firstOrNull { it.address == mac }
        if (target == null) {
            Log.w("SignalRepo", "未找到目标设备: $mac")
            return null
        }
        val txPower = if (target.txPower != Int.MIN_VALUE) target.txPower else DEFAULT_TX_POWER
        val distance = rssiToDistance(target.rssi, txPower)

        // 应用比例尺
        val realX = if (roomMeasured) x * scaleX else x
        val realY = if (roomMeasured) y * scaleY else y

        val measurement = CalibrationMeasurement(
            cornerIndex = cornerIndex,
            x = realX, y = realY,
            rawRssi = target.rssi,
            filteredRssi = target.rssi,  // 直接用去极值平均后的值
            txPower = txPower,
            distance = distance
        )

        mutex.withLock {
            val idx = calibMeasurements.indexOfFirst { it.cornerIndex == cornerIndex }
            if (idx >= 0) calibMeasurements[idx] = measurement else calibMeasurements.add(measurement)
        }
        return measurement
    }

    /** 计算目标位置：直接用标定点的测量做三边定位 */
    suspend fun calculateTargetPosition(): Position? {
        val pts = mutex.withLock {
            calibMeasurements.map {
                TrackingPoint(it.cornerIndex, it.x, it.y, it.filteredRssi, it.txPower, it.distance)
            }
        }
        if (pts.size < 3) return null
        return calculatePosition(pts.sortedBy { it.pointId })
    }

    /** 获取完整的测量记录（用于UI显示） */
    fun getTrackMeasurements(): List<TrackMeasurement> {
        return calibMeasurements.map {
            TrackMeasurement(
                pointId = it.cornerIndex,
                phoneEstimatedX = it.x,
                phoneEstimatedY = it.y,
                targetBleRssi = it.rawRssi,
                filteredRssi = it.filteredRssi,
                txPower = it.txPower,
                distance = it.distance,
                visibleAps = 0,
                visibleBle = 0
            )
        }
    }

    fun getFingerprints(): List<SignalFingerprint> = fingerprints.toList()
    fun getCalibMeasurements(): List<CalibrationMeasurement> = calibMeasurements.toList()

    suspend fun clearAll() {
        mutex.withLock {
            fingerprints.clear()
            calibMeasurements.clear()
            scaleX = 1.0
            scaleY = 1.0
            roomMeasured = false
        }
    }

    suspend fun resolveDeviceName(mac: String): String? {
        return BluetoothHelper.readDeviceNameViaGatt(context, mac)
    }
}
