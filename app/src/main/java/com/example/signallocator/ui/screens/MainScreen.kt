package com.example.signallocator.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalType
import com.example.signallocator.ui.components.SignalCard
import com.example.signallocator.utils.StepCounter
import com.example.signallocator.viewmodel.MainViewModel
import com.example.signallocator.viewmodel.UiState

private data class Corner(val id: Int, val label: String, val x: Double, val y: Double)
// 沿墙壁顺时针走：左下→右下→右上→左上
private val ROOM_CORNERS = listOf(
    Corner(0, "左下 (0,0)", 0.0, 0.0),
    Corner(1, "右下 (4,0)", 4.0, 0.0),
    Corner(2, "右上 (4,4)", 4.0, 4.0),
    Corner(3, "左上 (0,4)", 0.0, 4.0),
)
private fun List<SignalData>.sortedByStrength() = sortedByDescending { it.rssi }

@Composable
fun MainScreen(viewModel: MainViewModel, stepCounter: StepCounter? = null) {
    val uiState by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("蓝牙寻物雷达", style = MaterialTheme.typography.headlineSmall)
        if (uiState.targetMac != null) {
            val displayName = uiState.targetName
                ?: uiState.signalList.firstOrNull { it.address == uiState.targetMac }?.name
                ?: uiState.targetMac!!.take(8) + "…"
            Text("已选目标: $displayName", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        if (uiState.errorMessage != null) Text("⚠️ ${uiState.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (uiState.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp))

        Spacer(Modifier.height(4.dp))
        Button(onClick = { viewModel.scanEnvironment() }, enabled = !uiState.isScanning && !uiState.isWorking, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.isScanning) "扫描中..." else "扫描设备")
        }
        if (uiState.isScanning) { Spacer(Modifier.height(4.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("正在扫描...", style = MaterialTheme.typography.bodySmall) } }

        Spacer(Modifier.height(4.dp))
        CollapsibleCard(title = "① 设备选择", initiallyExpanded = uiState.targetMac == null) { DeviceSelectionContent(uiState, viewModel) }
        CollapsibleCard(title = "② 搜到的信号 (${uiState.signalList.size})", initiallyExpanded = false) { SignalListContent(uiState, viewModel) }
        CollapsibleCard(title = "③ 标定测量", initiallyExpanded = uiState.targetMac != null) { CalibrationContent(uiState, viewModel, stepCounter) }

        if (uiState.phase == "result" && uiState.targetPosition != null) {
            ResultCard(uiState.targetPosition!!, uiState.trackMeasurements)
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { viewModel.resetAll() }, modifier = Modifier.fillMaxWidth()) { Text("全部重置") }
    }
}

@Composable
private fun CollapsibleCard(title: String, initiallyExpanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) { Column(Modifier.padding(top = 8.dp)) { content() } }
        }
    }
}

// ── ① 设备选择 ──

@Composable
private fun DeviceSelectionContent(uiState: UiState, viewModel: MainViewModel) {
    val btDevices = uiState.signalList
        .filter { (it.type == SignalType.BLE || it.type == SignalType.BT_CLASSIC) && it.hasRealName }
        .sortedWith(compareByDescending<SignalData> { it.isBonded }.thenByDescending { it.rssi })
    if (btDevices.isNotEmpty()) {
        Text("${btDevices.size} 个蓝牙设备：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(btDevices) { dev ->
                val isSelected = dev.address == uiState.targetMac
                val tag = if (dev.isBonded) "⭐已配对" else if (dev.type == SignalType.BT_CLASSIC) "🎧" else "🔵"
                OutlinedButton(onClick = { viewModel.selectTarget(dev.address) }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("$tag ${dev.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${dev.rssi} dBm  |  ${dev.address}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else if (!uiState.isScanning) Text("暂无蓝牙设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── ② 搜到的信号 ──

@Composable
private fun SignalListContent(uiState: UiState, viewModel: MainViewModel) {
    if (uiState.signalList.isEmpty()) { Text("尚未扫描", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SignalLegendItem("📶", "基站"); SignalLegendItem("📡", "WiFi"); SignalLegendItem("🔵", "BLE"); SignalLegendItem("🎧", "经典蓝牙")
    }
    Spacer(Modifier.height(4.dp))
    val cellS = uiState.signalList.filter { it.type == SignalType.CELL }.sortedByStrength()
    val wifiS = uiState.signalList.filter { it.type == SignalType.WIFI }.sortedByStrength()
    val btNamed = uiState.signalList.filter { (it.type == SignalType.BLE || it.type == SignalType.BT_CLASSIC) && it.hasRealName }.sortedByStrength()
    val btUnnamed = uiState.signalList.filter { (it.type == SignalType.BLE || it.type == SignalType.BT_CLASSIC) && !it.hasRealName }.sortedByStrength()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        if (cellS.isNotEmpty()) { item { GroupHeader("📶 基站 (${cellS.size})") }; items(cellS) { SignalCard(it) } }
        if (wifiS.isNotEmpty()) { item { GroupHeader("📡 WiFi (${wifiS.size})") }; items(wifiS) { SignalCard(it) } }
        if (btNamed.isNotEmpty()) { item { GroupHeader("🔵 蓝牙 (${btNamed.size})") }; items(btNamed) { SignalCard(it) } }
        if (btUnnamed.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = uiState.showAllBle, onCheckedChange = { viewModel.toggleShowAllBle() })
                Text("显示未识别蓝牙 (${btUnnamed.size})", style = MaterialTheme.typography.bodySmall)
            } }
            if (uiState.showAllBle) items(btUnnamed) { SignalCard(it) }
        }
    }
}

@Composable private fun SignalLegendItem(icon: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, style = MaterialTheme.typography.bodyMedium); Text(label, style = MaterialTheme.typography.labelSmall) } }
@Composable private fun GroupHeader(text: String) { Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.primary) }

// ── ③ 标定测量（合并标定+测量+定位）──

@Composable
private fun CalibrationContent(uiState: UiState, viewModel: MainViewModel, stepCounter: StepCounter?) {
    if (uiState.targetMac == null) { Text("请先选择目标设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }

    // 房间尺寸
    val dims = uiState.roomDimensions
    if (dims != null) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("🏠 房间实际尺寸", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("宽: ${String.format("%.1f", dims.width)}m  ×  高: ${String.format("%.1f", dims.height)}m  (面积: ${String.format("%.1f", dims.width * dims.height)} m²)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // 计步区域
    Text("沿墙壁走一圈，在每个角点按「测量」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("路线：左下→右下→右上→左上", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("步伐: ${uiState.stepCount} 步  距离: ${String.format("%.1f", uiState.stepDistance)} m", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { stepCounter?.reset(); stepCounter?.start(); viewModel.setStepCounting(true) },
            enabled = stepCounter != null && !uiState.isStepCounting, modifier = Modifier.weight(1f)) { Text("开始计步") }
        OutlinedButton(onClick = { stepCounter?.stop(); viewModel.setStepCounting(false) },
            enabled = uiState.isStepCounting, modifier = Modifier.weight(1f)) { Text("停止计步") }
    }
    Spacer(Modifier.height(4.dp))

    // 每个角点：记录步数 + 测量BLE
    for ((idx, corner) in ROOM_CORNERS.withIndex()) {
        val done = uiState.calibMeasurements.any { it.cornerIndex == idx }
        val cornerSteps = uiState.wallSteps[idx]
        val measurement = uiState.calibMeasurements.firstOrNull { it.cornerIndex == idx }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(corner.label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium)
            if (cornerSteps != null) {
                Text("${cornerSteps}步", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp).weight(0.3f))
            }
            if (measurement != null) {
                Text("${measurement.filteredRssi}dBm→${String.format("%.1f", measurement.distance)}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.6f))
            }
            Button(
                onClick = {
                    viewModel.recordCornerSteps(idx)
                    viewModel.calibrate(idx, corner.x, corner.y)
                },
                enabled = !uiState.isWorking
            ) { Text(if (done) "✓" else "测量") }
        }
    }
    Text("已测量: ${uiState.calibratedCount}/4", style = MaterialTheme.typography.bodySmall)

    // 计算房间尺寸
    if (uiState.calibratedCount >= 3 && uiState.roomDimensions == null) {
        Spacer(Modifier.height(4.dp))
        Button(onClick = { viewModel.calculateRoomDimensions() }, modifier = Modifier.fillMaxWidth()) { Text("计算房间尺寸") }
    }

    // 计算目标位置
    if (uiState.calibratedCount >= 3 && uiState.targetPosition == null) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.locateTarget() }, modifier = Modifier.fillMaxWidth()) { Text("计算目标位置") }
    }
}

// ── 结果 ──

@Composable
private fun ResultCard(pos: com.example.signallocator.data.Position, measurements: List<com.example.signallocator.data.TrackMeasurement>) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("🎯 目标坐标: (${String.format("%.2f", pos.x)}, ${String.format("%.2f", pos.y)}) 米",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("定位误差: ±${String.format("%.2f", pos.errorRadius)} m", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("测量详情：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            for (m in measurements) {
                Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("角点 ${m.pointId}  (${String.format("%.1f", m.phoneEstimatedX)}, ${String.format("%.1f", m.phoneEstimatedY)})m", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Text("RSSI: ${m.targetBleRssi} dBm → 滤波: ${m.filteredRssi} dBm  距离: ${String.format("%.2f", m.distance)} m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
