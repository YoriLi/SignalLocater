package com.example.signallocator.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalType
import kotlin.math.max
import kotlin.math.min

@Composable
fun SignalCard(data: SignalData) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 信号类型图标 + 标签
            val (icon, typeLabel) = when (data.type) {
                SignalType.CELL -> "📶" to "基站"
                SignalType.WIFI -> "📡" to "WiFi"
                SignalType.BLE -> "🔵" to "BLE"
                SignalType.BT_CLASSIC -> "🎧" to "经典蓝牙"
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                Text(icon, fontSize = 24.sp)
                Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(data.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    "RSSI: ${data.rssi} dBm  |  ${data.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 信号强度进度条
                val progress = ((max(-100, min(-30, data.rssi)) + 100).toFloat() / 70)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = when {
                        progress > 0.7f -> Color(0xFF4CAF50)  // 绿色：强
                        progress > 0.4f -> Color(0xFFFFC107)  // 黄色：中
                        else -> Color(0xFFF44336)              // 红色：弱
                    }
                )
            }
        }
    }
}
