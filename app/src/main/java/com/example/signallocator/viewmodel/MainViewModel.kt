package com.example.signallocator.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.signallocator.data.CalibrationMeasurement
import com.example.signallocator.data.Position
import com.example.signallocator.data.RoomDimensions
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalFingerprint
import com.example.signallocator.data.TrackMeasurement
import com.example.signallocator.manager.BluetoothHelper
import com.example.signallocator.repository.SignalRepository
import com.example.signallocator.utils.StepCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val phase: String = "calibrate",  // calibrate -> result
    val signalList: List<SignalData> = emptyList(),
    val fingerprints: List<SignalFingerprint> = emptyList(),
    val calibMeasurements: List<CalibrationMeasurement> = emptyList(),
    val trackMeasurements: List<TrackMeasurement> = emptyList(),
    val targetPosition: Position? = null,
    val targetMac: String? = null,
    val targetName: String? = null,
    val isScanning: Boolean = false,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
    val calibratedCount: Int = 0,
    val showAllBle: Boolean = false,
    // 计步标定
    val stepCount: Int = 0,
    val stepDistance: Double = 0.0,
    val isStepCounting: Boolean = false,
    val roomDimensions: RoomDimensions? = null,
    val wallSteps: Map<Int, Int> = emptyMap()
)

class MainViewModel(private val repository: SignalRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun scanEnvironment() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }
            try {
                val signals = repository.scanAllSignals()
                _uiState.update { it.copy(signalList = signals, isScanning = false, showAllBle = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, errorMessage = e.message ?: "扫描失败") }
            }
        }
    }

    fun toggleShowAllBle() {
        _uiState.update { it.copy(showAllBle = !it.showAllBle) }
    }

    fun selectTarget(mac: String) {
        repository.targetMac = mac
        val currentDevice = _uiState.value.signalList.firstOrNull { it.address == mac }
        val hasRealName = currentDevice != null && currentDevice.hasRealName
        val displayName = if (hasRealName) currentDevice!!.name else null
        _uiState.update {
            it.copy(
                targetMac = mac, targetName = displayName,
                calibMeasurements = emptyList(), trackMeasurements = emptyList(),
                calibratedCount = 0, targetPosition = null,
                phase = "calibrate", errorMessage = null
            )
        }
        viewModelScope.launch { repository.clearAll() }
        // 如果设备名未知，尝试GATT读取
        if (!hasRealName) {
            viewModelScope.launch {
                _uiState.update { it.copy(isWorking = true) }
                try {
                    val gattName = repository.resolveDeviceName(mac)
                    if (gattName != null) {
                        _uiState.update { state ->
                            val updatedList = state.signalList.map { s ->
                                if (s.address == mac) s.copy(name = gattName) else s
                            }
                            state.copy(signalList = updatedList, targetName = gattName, isWorking = false)
                        }
                    } else { _uiState.update { it.copy(isWorking = false) } }
                } catch (_: Exception) { _uiState.update { it.copy(isWorking = false) } }
            }
        }
    }

    /** 标定：在角点采集信号 + 测量目标BLE */
    fun calibrate(cornerIndex: Int, x: Double, y: Double) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, errorMessage = null) }
            try {
                val measurement = repository.calibrate(x, y, cornerIndex)
                if (measurement != null) {
                    val calibs = repository.getCalibMeasurements()
                    val tracks = repository.getTrackMeasurements()
                    _uiState.update {
                        it.copy(
                            calibMeasurements = calibs,
                            trackMeasurements = tracks,
                            calibratedCount = calibs.size,
                            isWorking = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isWorking = false, errorMessage = "未扫到目标BLE设备") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isWorking = false, errorMessage = e.message ?: "标定失败") }
            }
        }
    }

    /** 计算目标位置 */
    fun locateTarget() {
        viewModelScope.launch {
            if (_uiState.value.calibratedCount < 3) {
                _uiState.update { it.copy(errorMessage = "需要至少3个标定点") }
                return@launch
            }
            try {
                val pos = repository.calculateTargetPosition()
                if (pos != null) {
                    _uiState.update { it.copy(targetPosition = pos, phase = "result", errorMessage = null) }
                } else {
                    _uiState.update { it.copy(errorMessage = "定位失败") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "计算异常") }
            }
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            repository.clearAll()
            _uiState.update { UiState() }
        }
    }

    // ── 计步标定 ──

    fun onStepUpdate(steps: Int, distance: Double) {
        _uiState.update { it.copy(stepCount = steps, stepDistance = distance) }
    }

    fun setStepCounting(running: Boolean) {
        _uiState.update { it.copy(isStepCounting = running) }
    }

    fun recordCornerSteps(cornerIndex: Int) {
        val currentSteps = _uiState.value.stepCount
        _uiState.update {
            it.copy(wallSteps = it.wallSteps + (cornerIndex to currentSteps))
        }
    }

    fun calculateRoomDimensions() {
        val steps = _uiState.value.wallSteps
        // 走路顺序：0(左下) → 1(右下) → 2(右上) → 3(左上)
        val s0 = steps[0]
        val s1 = steps[1]
        val s2 = steps[2]
        val s3 = steps[3]

        if (s0 == null || s1 == null || s2 == null) {
            _uiState.update { it.copy(errorMessage = "需要至少记录3个角点的步数") }
            return
        }

        val wallBottom = (s1 - s0) * StepCounter.STEP_LENGTH
        val wallRight  = (s2 - s1) * StepCounter.STEP_LENGTH

        val width: Double
        val height: Double
        if (s3 != null) {
            val wallTop = (s3 - s2) * StepCounter.STEP_LENGTH
            val totalSteps = s0
            val leftSteps = totalSteps - (s1 - s0) - (s2 - s1) - (s3 - s2)
            val wallLeft = leftSteps * StepCounter.STEP_LENGTH
            width  = (wallBottom + wallTop) / 2.0
            height = (wallRight + wallLeft) / 2.0
        } else {
            width = wallBottom
            height = wallRight
        }

        val dims = RoomDimensions(
            width = width, height = height,
            scaleX = width / 4.0, scaleY = height / 4.0
        )
        repository.scaleX = dims.scaleX
        repository.scaleY = dims.scaleY
        repository.roomMeasured = true
        _uiState.update { it.copy(roomDimensions = dims) }
    }
}
