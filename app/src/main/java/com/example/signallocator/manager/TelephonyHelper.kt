package com.example.signallocator.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.*
import androidx.core.app.ActivityCompat
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TelephonyHelper {

    suspend fun getCellSignal(context: Context): SignalData? = withContext(Dispatchers.IO) {
        val hasPhone = ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPhone || !(hasFine || hasCoarse)) return@withContext null

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return@withContext null
        val cellInfoList = try { tm.allCellInfo } catch (_: SecurityException) { return@withContext null }
        if (cellInfoList.isNullOrEmpty()) return@withContext null

        val cellInfo = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull() ?: return@withContext null

        @Suppress("DEPRECATION", "NewApi", "UnnecessarySafeCall")
        val rssi = when (cellInfo) {
            is CellInfoLte -> cellInfo.cellSignalStrength?.getDbm() ?: Int.MIN_VALUE
            is CellInfoWcdma -> cellInfo.cellSignalStrength?.getDbm() ?: Int.MIN_VALUE
            is CellInfoGsm -> cellInfo.cellSignalStrength?.getDbm() ?: Int.MIN_VALUE
            is CellInfoCdma -> cellInfo.cellSignalStrength?.getDbm() ?: Int.MIN_VALUE
            is CellInfoNr -> cellInfo.cellSignalStrength?.getDbm() ?: Int.MIN_VALUE
            else -> return@withContext null
        }
        if (rssi == Int.MIN_VALUE || rssi >= 0) return@withContext null

        @Suppress("DEPRECATION", "NewApi")
        val cellId = when (cellInfo) {
            is CellInfoLte -> cellInfo.cellIdentity?.ci?.toString()
            is CellInfoWcdma -> cellInfo.cellIdentity?.cid?.toString()
            is CellInfoGsm -> cellInfo.cellIdentity?.cid?.toString()
            is CellInfoCdma -> cellInfo.cellIdentity?.basestationId?.toString()
            is CellInfoNr -> (cellInfo.cellIdentity as? CellIdentityNr)?.nci?.toString()
            else -> null
        }

        val mccMnc = tm.networkOperator?.takeIf { it.isNotEmpty() } ?: "00000"
        val address = "CELL_${mccMnc}_${cellId ?: "UNKNOWN"}"
        val operatorName = tm.networkOperatorName?.toString() ?: "Cellular"

        return@withContext SignalData(SignalType.CELL, rssi, operatorName, address)
    }
}
