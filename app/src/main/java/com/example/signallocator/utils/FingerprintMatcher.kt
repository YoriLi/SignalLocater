package com.example.signallocator.utils

import com.example.signallocator.data.SignalFingerprint
import kotlin.math.sqrt

/**
 * 基于固定参考集的信号向量指纹匹配。
 *
 * 核心思想（Lily 提出）：标定和跟踪必须使用相同的参考系。
 *
 * 1. 标定时：记录所有可见 AP/BLE，取所有标定点的 AP 并集作为"参考集"
 * 2. 跟踪时：只看参考集中的 AP，缺失的用 -100 dBm 填充
 * 3. 匹配时：用参考集中每个 AP 的 RSSI 组成固定长度向量，做欧氏距离比较
 *
 * 这样无论什么时候跟踪，比较的都是同一组 AP，参考系一致。
 */

private const val WIFI_WEIGHT = 0.6
private const val BLE_WEIGHT = 0.3
private const val CELL_WEIGHT = 0.1
private const val MISSING_RSSI = -100  // AP 不可见时的默认值

/**
 * 从标定数据库中提取参考 AP 集合（所有标定点可见 AP 的并集）。
 */
fun buildReferenceApSet(database: List<SignalFingerprint>): Set<String> {
    return database.flatMap { it.wifiSignals.keys }.toSet()
}

/**
 * 从标定数据库中提取参考 BLE 集合。
 */
fun buildReferenceBleSet(database: List<SignalFingerprint>): Set<String> {
    return database.flatMap { it.bleSignals.keys }.toSet()
}

/**
 * 将指纹转为固定长度的 RSSI 向量（基于参考集）。
 * 缺失的 AP 用 MISSING_RSSI 填充。
 */
fun fingerprintToVector(
    fp: SignalFingerprint,
    refAps: List<String>,
    refBle: List<String>
): DoubleArray {
    val vector = DoubleArray(refAps.size + refBle.size + 1)
    // WiFi 部分
    for (i in refAps.indices) {
        vector[i] = (fp.wifiSignals[refAps[i]] ?: MISSING_RSSI).toDouble()
    }
    // BLE 部分
    for (i in refBle.indices) {
        vector[refAps.size + i] = (fp.bleSignals[refBle[i]] ?: MISSING_RSSI).toDouble()
    }
    // 基站
    vector[refAps.size + refBle.size] = fp.cellRssi.toDouble()
    return vector
}

/**
 * 两个等长向量的加权欧氏距离。
 */
fun weightedVectorDistance(a: DoubleArray, b: DoubleArray, apCount: Int, bleCount: Int): Double {
    var wifiSumSq = 0.0
    var bleSumSq = 0.0
    var cellDiff = 0.0

    for (i in 0 until apCount) {
        val diff = a[i] - b[i]
        wifiSumSq += diff * diff
    }
    for (i in 0 until bleCount) {
        val diff = a[apCount + i] - b[apCount + i]
        bleSumSq += diff * diff
    }
    cellDiff = a[apCount + bleCount] - b[apCount + bleCount]

    val wifiDist = if (apCount > 0) sqrt(wifiSumSq / apCount) else 0.0
    val bleDist = if (bleCount > 0) sqrt(bleSumSq / bleCount) else 0.0

    return sqrt(WIFI_WEIGHT * wifiDist * wifiDist + BLE_WEIGHT * bleDist * bleDist + CELL_WEIGHT * cellDiff * cellDiff)
}

/**
 * 估算手机当前位置（IDW 反距离加权）。
 * 使用固定参考集向量，保证标定和跟踪的参考系一致。
 */
fun estimatePosition(current: SignalFingerprint, database: List<SignalFingerprint>): Pair<Double, Double>? {
    if (database.size < 3) return null

    // 构建参考集
    val refAps = buildReferenceApSet(database).toList()
    val refBle = buildReferenceBleSet(database).toList()

    // 将当前指纹和所有标定指纹转为向量
    val currentVec = fingerprintToVector(current, refAps, refBle)
    val dbVectors = database.map { it to fingerprintToVector(it, refAps, refBle) }

    // IDW 加权
    val weighted = dbVectors.map { (fp, vec) ->
        val dist = weightedVectorDistance(currentVec, vec, refAps.size, refBle.size)
        val w = if (dist < 0.1) 1000.0 else 1.0 / (dist * dist)
        Triple(fp.x, fp.y, w)
    }

    val totalW = weighted.sumOf { it.third }
    if (totalW == 0.0) return null

    val estX = weighted.sumOf { it.first * it.third } / totalW
    val estY = weighted.sumOf { it.second * it.third } / totalW
    return Pair(estX, estY)
}
