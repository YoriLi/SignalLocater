package com.example.signallocator.utils

import com.example.signallocator.data.DEFAULT_TX_POWER
import com.example.signallocator.data.TrackingPoint
import com.example.signallocator.data.Position
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Convert BLE RSSI to estimated distance using the log-distance path loss model.
 * @param rssi measured RSSI in dBm
 * @param txPower RSSI at 1 meter (default -50 dBm, 实测校准)
 * @param envFactor path loss exponent (2.0 = free space, 2.5 = typical indoor)
 */
fun rssiToDistance(rssi: Int, txPower: Int = DEFAULT_TX_POWER, envFactor: Double = 2.5): Double {
    return 10.0.pow((txPower - rssi).toDouble() / (10.0 * envFactor)).coerceAtLeast(0.1)
}

/**
 * Gauss-Newton iterative trilateration.
 * Given ≥3 tracking points (each with an estimated phone position + BLE distance to target),
 * compute the target's (x, y) coordinates by minimizing the sum of squared range residuals.
 *
 * The Jacobian is built inline (2×2 normal equations) for efficiency.
 * Convergence is checked by the norm of the update step.
 */
fun calculatePosition(points: List<TrackingPoint>): Position? {
    if (points.size < 3) return null
    val measured = points.map { Triple(it.estimatedX, it.estimatedY, it.distance) }

    // Initial guess: centroid of all measurement points
    var x = measured.map { it.first }.average()
    var y = measured.map { it.second }.average()

    val maxIter = 50
    val convergenceThreshold = 1e-6  // stop when update step < 1mm

    for (iter in 0 until maxIter) {
        // Build the 2×2 normal equation: H * delta = -g
        var h00 = 0.0; var h01 = 0.0; var h11 = 0.0
        var g0 = 0.0; var g1 = 0.0
        for ((xi, yi, di) in measured) {
            val dx = x - xi
            val dy = y - yi
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-10)
            val residual = dist - di
            val j0 = dx / dist  // Jacobian element ∂r/∂x
            val j1 = dy / dist  // Jacobian element ∂r/∂y
            h00 += j0 * j0
            h01 += j0 * j1
            h11 += j1 * j1
            g0 += j0 * residual
            g1 += j1 * residual
        }
        val det = h00 * h11 - h01 * h01
        if (abs(det) < 1e-12) break  // singular Hessian, can't proceed

        val dx = (-g0 * h11 + h01 * g1) / det
        val dy = (-h00 * g1 + g0 * h01) / det
        x += dx
        y += dy

        // Convergence check: stop if the update step is negligible
        if (sqrt(dx * dx + dy * dy) < convergenceThreshold) break
    }

    // Compute RMS residual as the error radius estimate
    var sumSq = 0.0
    for ((xi, yi, di) in measured) {
        sumSq += (sqrt((x - xi).pow(2) + (y - yi).pow(2)) - di).pow(2)
    }
    val error = sqrt(sumSq / measured.size).coerceIn(0.1, 50.0)
    return Position(x, y, error)
}
