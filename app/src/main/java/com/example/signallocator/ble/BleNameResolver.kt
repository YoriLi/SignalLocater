package com.example.signallocator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 单文件 BLE 设备名称解析器。
 *
 * 解决 Android BLE 扫描中设备名称缺失问题——很多设备不在广播包中携带名称，
 * 或者 Android 13+ 系统不再拼接广播包和扫描响应包，导致 device.getName() 返回 null。
 *
 * 名称解析优先级：
 *  1. ScanRecord.getDeviceName() — 系统从 AD type 0x09/0x08 解析
 *  2. Manufacturer Specific Data (0xFF) — 部分厂商把名称编码在厂商数据中
 *  3. BluetoothDevice.getName() — 蓝牙协议栈缓存（已配对设备可靠）
 *  4. GATT 0x2A00 — 连接后读取 Generic Access → Device Name Characteristic
 *  5. MAC 地址 — 兜底
 *
 * 用法：
 * ```kotlin
 * val resolver = BleNameResolver(context)
 * // 扫描回调中
 * val info = resolver.resolve(device, scanRecord)
 * // 如果 info.name 未知，可异步尝试 GATT
 * if (info.source == "fallback") {
 *     val gattInfo = resolver.resolveViaGatt(device)
 *     // gattInfo.name 为 GATT 读取到的名称
 * }
 * ```
 */
object BleNameResolver {

    private const val TAG = "BLE_Name"

    // ── UUID 常量 ──────────────────────────────────────────
    private val UUID_GENERIC_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val UUID_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    // ── 名称缓存：MAC → 已知名称 ──────────────────────────
    // 一旦某个 MAC 解析出真实名称，后续直接返回，不再重复解析
    private val nameCache = ConcurrentHashMap<String, DeviceInfo>()

    /**
     * 设备信息。
     * @param mac         MAC 地址
     * @param name        设备名称（可能为 MAC 前缀）
     * @param source      名称来源：broadcast / manufacturer / system / gatt / fallback
     * @param hasRealName 是否解析出了真实设备名（非 MAC 兜底）
     */
    data class DeviceInfo(
        val mac: String,
        val name: String,
        val source: String,
        val hasRealName: Boolean = source != "fallback"
    )

    /**
     * 同步解析设备名称（不建立 GATT 连接，不阻塞线程）。
     *
     * AOSP ScanRecord.parseFromBytes() 的 bug：
     * 遇到 length=0 的字节就 break 停止解析，导致 Android 13+ 零填充区域后的
     * 扫描响应数据（常包含设备名）永远不会被解析。
     *
     * 本函数绕过这个限制：先尝试 getDeviceName()，再手动分段解析原始字节。
     */
    @SuppressLint("MissingPermission")
    fun resolve(device: BluetoothDevice, record: ScanRecord?): DeviceInfo {
        val mac = device.address ?: "00:00:00:00:00:00"

        // 缓存命中：已知名称直接返回
        nameCache[mac]?.let { cached ->
            if (cached.source != "fallback") return cached
        }

        // 1. ScanRecord.getDeviceName()
        //    等价于 parseFromBytes() 中解析 AD type 0x09/0x08 的结果
        //    如果广播数据中就有名称，这里能拿到
        record?.deviceName?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "[$mac] ✓ broadcast: $it")
            return cache(mac, it, "broadcast")
        }

        // 2. Manufacturer Specific Data (AD type 0xFF)
        parseNameFromManufacturerData(record?.manufacturerSpecificData)?.let {
            Log.d(TAG, "[$mac] ✓ manufacturer: $it")
            return cache(mac, it, "manufacturer")
        }

        // 3. 手动解析原始字节（绕过 AOSP parseFromBytes 的零填充中断 bug）
        //    Android 13+ getBytes() 格式: [广播 ≤31B] [零填充] [扫描响应 ≤31B]
        //    AOSP 的 parseFromBytes 遇到 0x00 就 break，所以我们自己解析
        parseNameFromRawBytes(record?.bytes)?.let {
            Log.d(TAG, "[$mac] ✓ raw parse: $it")
            return cache(mac, it, "broadcast")
        }

        // 4. BluetoothDevice.getName()
        try {
            device.name?.takeIf { it.isNotEmpty() }?.let {
                Log.d(TAG, "[$mac] ✓ system: $it")
                return cache(mac, it, "system")
            }
        } catch (_: SecurityException) {}

        // 5. 兜底
        Log.d(TAG, "[$mac] ✗ fallback")
        return DeviceInfo(mac, mac.take(8) + "…", "fallback")
    }

    /**
     * 通过 GATT 连接读取 Device Name Characteristic (0x2A00)。
     *
     * 此方法会建立实际的 BLE 连接，耗时约 2-5 秒。
     * 必须在非扫描线程调用（如协程 IO 线程）。
     *
     * @return DeviceInfo，name 来源为 "gatt" 或 "fallback"
     */
    @SuppressLint("MissingPermission")
    suspend fun resolveViaGatt(context: Context, device: BluetoothDevice): DeviceInfo {
        val mac = device.address ?: "00:00:00:00:00:00"

        // 缓存已有非 fallback 名称，跳过 GATT
        nameCache[mac]?.let { if (it.source != "fallback") return it }

        val gattName = readDeviceNameViaGatt(context, device)
        if (gattName != null) {
            Log.d(TAG, "[$mac] ✓ gatt: $gattName")
            return cache(mac, gattName, "gatt")
        }

        Log.d(TAG, "[$mac] ✗ gatt failed, fallback")
        return DeviceInfo(mac, mac.take(8) + "…", "fallback")
    }

    /**
     * 清空名称缓存（通常不需要调用）。
     */
    fun clearCache() = nameCache.clear()

    // ══════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════

    private fun cache(mac: String, name: String, source: String): DeviceInfo {
        val info = DeviceInfo(mac, name, source)
        nameCache[mac] = info
        return info
    }

    /**
     * 从 Manufacturer Specific Data 中尝试解析设备名称。
     */
    private fun parseNameFromManufacturerData(data: SparseArray<ByteArray>?): String? {
        if (data == null || data.size() == 0) return null
        for (i in 0 until data.size()) {
            val bytes = data.valueAt(i) ?: continue
            if (bytes.isEmpty()) continue
            val candidate = String(bytes, Charsets.UTF_8).trim()
            if (candidate.length >= 2 && candidate.all { it.code in 0x20..0x7E || it.code > 0x7F }) {
                return candidate
            }
        }
        return null
    }

    /**
     * 手动解析原始广播数据字节，提取设备名称。
     *
     * 绕过 AOSP ScanRecord.parseFromBytes() 的零填充中断 bug：
     * AOSP 代码遇到 length=0 就 break，但我们跳过连续零字节后继续解析。
     *
     * Android 13+ getBytes() 返回格式:
     *   [广播数据 ≤31B] [零填充若干字节] [扫描响应数据 ≤31B]
     *
     * 设备名称（AD type 0x08/0x09）常在扫描响应数据中。
     */
    private fun parseNameFromRawBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 2) return null

        var i = 0
        while (i < bytes.size - 1) {
            val len = bytes[i].toInt() and 0xFF

            // 跳过零填充区域（Android 13+ 广播/扫描响应分隔区）
            if (len == 0) {
                // 跳过连续的 0x00 字节
                while (i < bytes.size && bytes[i].toInt() and 0xFF == 0) i++
                continue
            }

            if (i + 1 + len > bytes.size) break

            val adType = bytes[i + 1].toInt() and 0xFF

            // 0x09 = Complete Local Name, 0x08 = Shortened Local Name
            if (adType == 0x09 || adType == 0x08) {
                val nameBytes = bytes.copyOfRange(i + 2, i + 1 + len)
                val name = String(nameBytes, Charsets.UTF_8).trim()
                if (name.isNotEmpty() && name.all { it.code in 0x20..0x7E || it.code > 0x7F }) {
                    return name
                }
            }

            i += 1 + len
        }
        return null
    }

    /**
     * 通过 GATT 连接读取 Generic Access Service (0x1800) → Device Name (0x2A00)。
     *
     * 使用 suspendCoroutine 包装回调式 API，超时 5 秒。
     */
    @SuppressLint("MissingPermission")
    private suspend fun readDeviceNameViaGatt(
        context: Context,
        device: BluetoothDevice
    ): String? = suspendCoroutine { cont ->
        val done = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (done.compareAndSet(false, true)) cont.resume(null)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.close()
                    if (done.compareAndSet(false, true)) cont.resume(null)
                    return
                }
                val gas = g.getService(UUID_GENERIC_ACCESS)
                val char = gas?.getCharacteristic(UUID_DEVICE_NAME)
                if (char != null) {
                    g.readCharacteristic(char)
                } else {
                    g.close()
                    if (done.compareAndSet(false, true)) cont.resume(null)
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                value: ByteArray, status: Int
            ) {
                val name = if (status == BluetoothGatt.GATT_SUCCESS) {
                    String(value, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                } else null
                g.close()
                if (done.compareAndSet(false, true)) cont.resume(name)
            }
        }

        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            if (done.compareAndSet(false, true)) cont.resume(null)
            return@suspendCoroutine
        }

        // 超时保护
        Handler(Looper.getMainLooper()).postDelayed({
            if (done.compareAndSet(false, true)) {
                try { gatt?.close() } catch (_: Exception) {}
                cont.resume(null)
            }
        }, 5000)
    }
}
