package com.example.signallocator.manager

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.signallocator.ble.BleNameResolver
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "BT_Helper"

object BluetoothHelper {

    /**
     * Layer 3：获取已配对（bonded）设备。
     * 只从缓存取名称和类型，RSSI 由实时扫描提供。
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(context: Context): List<SignalData> {
        val hasConnectPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasConnectPerm) return emptyList()

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return adapter.bondedDevices?.map { device ->
            val name = try { device.name?.takeIf { it.isNotEmpty() } } catch (_: SecurityException) { null }
            val addr = device.address ?: return@map null
            val type = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> SignalType.BLE
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> SignalType.BT_CLASSIC
                BluetoothDevice.DEVICE_TYPE_DUAL -> SignalType.BT_CLASSIC
                else -> SignalType.BT_CLASSIC
            }
            SignalData(
                type = type,
                rssi = -100,  // 占位，实际RSSI由扫描提供
                name = name ?: addr.take(8) + "…",
                address = addr,
                hasRealName = name != null,
                isBonded = true
            )
        }?.filterNotNull() ?: emptyList()
    }

    // 设备缓存：MAC → SignalData（实时更新）
    private val deviceCache = mutableMapOf<String, SignalData>()
    private val rssiHistory = mutableMapOf<String, MutableList<Int>>()

    /**
     * Layer 1：BLE 扫描
     * 实时更新：每收到一个数据包就更新对应MAC的RSSI
     * 扫描期间持续回调 onDeviceUpdate
     */
    @SuppressLint("MissingPermission", "DEPRECATION")
    suspend fun scanBle(
        context: Context,
        duration: Duration = 5000.milliseconds,
        onDeviceUpdate: ((List<SignalData>) -> Unit)? = null
    ): List<SignalData> = withContext(Dispatchers.IO) {
        val hasScanPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasScanPerm) return@withContext emptyList()

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)

            // 每次扫描前清空缓存，避免旧数据混入
            deviceCache.clear()
            rssiHistory.clear()

            val leScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
                val addr = device.address ?: return@LeScanCallback
                val history = rssiHistory.getOrPut(addr) { mutableListOf() }
                history.add(rssi)
                if (history.size > 10) history.removeAt(0)
                // 去掉最大最小值再平均，减少异常值影响
                val avgRssi = if (history.size >= 4) {
                    val sorted = history.sorted()
                    sorted.subList(1, sorted.size - 1).average().toInt()
                } else {
                    history.average().toInt()
                }

                // 用 BleNameResolver 解析名称（比 device.name 更可靠）
                val info = BleNameResolver.resolve(device, null)
                val name = if (info.hasRealName) info.name else null
                val hasName = name != null
                val displayName = if (hasName) name!! else addr.take(8) + "…"

                val signalData = SignalData(
                    SignalType.BLE, avgRssi, displayName, addr,
                    txPower = Int.MIN_VALUE, hasRealName = hasName
                )
                deviceCache[addr] = signalData
                // 每收到数据包就回调，UI实时更新
                onDeviceUpdate?.invoke(deviceCache.values.toList())
            }

            val started = adapter.startLeScan(leScanCallback)
            if (!started) {
                if (done.compareAndSet(false, true)) continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) adapter.stopLeScan(leScanCallback)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (done.compareAndSet(false, true)) {
                    adapter.stopLeScan(leScanCallback)
                    continuation.resume(deviceCache.values.toList())
                }
            }, duration.inWholeMilliseconds)
        }
    }

    /**
     * Layer 2：经典蓝牙扫描（BR/EDR Inquiry）
     * 发现耳机、鼠标、键盘等非 BLE 设备
     */
    @SuppressLint("MissingPermission")
    suspend fun scanClassic(context: Context, duration: Duration = 12000.milliseconds): List<SignalData> =
        withContext(Dispatchers.IO) {
            val hasScanPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            val hasConnectPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true
            if (!hasScanPerm) return@withContext emptyList()

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) return@withContext emptyList()

            // 获取已配对设备 MAC 列表（不跳过，只用于标记）
            val bondedMacs = getBondedDevices(context).map { it.address }.toSet()

            suspendCancellableCoroutine { continuation ->
                val resultMap = mutableMapOf<String, SignalData>()
                val resultLock = Any()
                val done = AtomicBoolean(false)

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        when (intent?.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                val addr = device.address ?: return
                                // 不跳过已配对设备，也要拿真实RSSI
                                val name = if (hasConnectPerm) {
                                    try { device.name?.takeIf { it.isNotEmpty() } } catch (_: SecurityException) { null }
                                } else null
                                val hasName = name != null
                                val displayName = name ?: addr.take(8) + "…"
                                Log.d(TAG, "Classic found: $addr name=$displayName rssi=$rssi rssi=$rssi")
                                synchronized(resultLock) {
                                    resultMap[addr] = SignalData(SignalType.BT_CLASSIC, rssi, displayName, addr, hasRealName = hasName, isBonded = addr in bondedMacs)
                                }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                Log.d(TAG, "Classic discovery finished, ${resultMap.size} devices")
                                if (done.compareAndSet(false, true)) {
                                    try { context?.unregisterReceiver(this) } catch (_: Exception) {}
                                    continuation.resume(resultMap.values.toList())
                                }
                            }
                        }
                    }
                }

                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(receiver, filter)

                adapter.cancelDiscovery()
                adapter.startDiscovery()

                continuation.invokeOnCancellation {
                    if (done.compareAndSet(false, true)) {
                        adapter.cancelDiscovery()
                        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    }
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    if (done.compareAndSet(false, true)) {
                        adapter.cancelDiscovery()
                        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                        continuation.resume(resultMap.values.toList())
                    }
                }, duration.inWholeMilliseconds)
            }
        }

    /**
     * Layer 4：GATT 连接读取设备名称
     */
    suspend fun readDeviceNameViaGatt(context: Context, mac: String): String? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return null
        val device = adapter.getRemoteDevice(mac) ?: return null
        val info = com.example.signallocator.ble.BleNameResolver.resolveViaGatt(context, device)
        return if (info.hasRealName) info.name else null
    }
}
