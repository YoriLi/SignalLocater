# SignalLocator — BLE 设备定位 App

基于多信号指纹匹配 + BLE 三边测量的 Android 室内定位应用。

## 整体架构

```
┌─────────────────────────────────────────────────┐
│                   UI Layer                       │
│  MainActivity → MainScreen → SignalCard          │
│  (Compose)     (各子区域 composable)              │
├─────────────────────────────────────────────────┤
│                ViewModel Layer                   │
│  MainViewModel (UiState + 业务逻辑)               │
├─────────────────────────────────────────────────┤
│               Repository Layer                   │
│  SignalRepository (标定 + 跟踪 + 计算)            │
├──────────────────┬──────────────────────────────┤
│   Manager Layer  │       Utils Layer             │
│  TelephonyHelper │  FingerprintMatcher (IDW)     │
│  WifiHelper      │  Trilateration (Gauss-Newton) │
│  BluetoothHelper │  RssiKalmanFilter             │
├──────────────────┴──────────────────────────────┤
│               Data Layer                         │
│  SignalData, SignalFingerprint                   │
│  TrackingPoint, Position, CalibrationMeasurement │
└─────────────────────────────────────────────────┘
```

## 文件说明

### Data Layer (`data/`)

| 文件 | 用途 |
|------|------|
| `SignalData.kt` | 通用信号数据类，包含类型 (CELL/WIFI/BLE)、RSSI、名称、地址 |
| `SignalFingerprint.kt` | 信号指纹：某位置的三种信号快照 (基站 + WiFi + BLE)，用于标定和匹配 |
| `Models.kt` | 包含 `TrackingPoint`、`Position`、`SignalFingerprint`、`CalibrationMeasurement` 等核心数据类及常量 |

### Manager Layer (`manager/`)

| 文件 | 用途 |
|------|------|
| `TelephonyHelper.kt` | 读取蜂窝信号 (LTE/WCDMA/GSM/CDMA/NR)，返回注册小区的 RSSI 和 CellID |
| `WifiHelper.kt` | 触发 WiFi 扫描，通过 BroadcastReceiver 收集结果，返回 AP 列表 |
| `BluetoothHelper.kt` | BLE 扫描：使用 `suspendCancellableCoroutine` 包装回调式 API，对每个设备维护 5 次滚动平均 RSSI |

### Utils Layer (`utils/`)

| 文件 | 用途 |
|------|------|
| `FingerprintMatcher.kt` | **IDW 信号指纹匹配**：计算当前信号与标定数据库的加权欧氏距离，用反距离加权估算手机坐标 |
| `Trilateration.kt` | **高斯-牛顿迭代三边测量**：从 ≥3 个跟踪点（手机坐标 + BLE 距离）计算目标 BLE 设备坐标 |
| `KalmanFilter.kt` | 一维卡尔曼滤波器，平滑 BLE RSSI 的 ±5~10dBm 波动 |

### Repository Layer (`repository/`)

| 文件 | 用途 |
|------|------|
| `SignalRepository.kt` | 两阶段定位核心：标定阶段采集指纹存入数据库，跟踪阶段匹配定位 + BLE 测距 |

### UI Layer (`ui/`)

| 文件 | 用途 |
|------|------|
| `MainActivity.kt` | 入口 Activity，处理权限请求（使用 `rememberLauncherForActivityResult`） |
| `MainScreen.kt` | 主界面，拆分为 Header / ScanTarget / Calibration / Tracking / Result / FingerprintDB 六个子区域 |
| `SignalCard.kt` | 信号列表的单条卡片组件，显示类型图标、名称、RSSI 进度条 |

### ViewModel Layer (`viewmodel/`)

| 文件 | 用途 |
|------|------|
| `MainViewModel.kt` | 管理 `UiState`，协调扫描、标定、跟踪、定位四个阶段的状态流转 |

## 定位算法流程

### 阶段一：标定（Calibration）

```
用户走到房间四角 → 每个角点采集蜂窝/WiFi/BLE 信号 → 存储为 SignalFingerprint
```

- 每个 `SignalFingerprint` 记录：坐标 (x,y) + 三种信号的 RSSI
- 至少需要 3 个标定点才能进入跟踪阶段
- 同一坐标重复标定会覆盖旧数据

### 阶段二：跟踪（Tracking）

```
用户在未知位置 → 采集三种信号 → FingerprintMatcher(IDW) 估算手机坐标
                 → 同时扫描目标 BLE 设备 → Kalman 滤波 → RSSI→距离转换
                 → 生成 TrackingPoint（估算坐标 + BLE 距离）
```

**IDW 匹配算法：**
1. 计算当前信号与每个标定点的"信号距离"：`d = √(w₁·ΔRSSI_BLE² + w₂·ΔRSSI_WIFI² + w₃·ΔRSSI_CELL²)`
2. 权重 `w = 1/d²`（信号越相似，权重越大）
3. 加权平均所有标定点的坐标 → 估算手机位置

**RSSI→距离：** 对数路径损耗模型 `dist = 10^((txPower - rssi) / (10·n))`

### 阶段三：定位（Localization）

```
≥3 个 TrackingPoint → Gauss-Newton 迭代 → 目标 BLE 设备坐标
```

**高斯-牛顿迭代：**
1. 初始猜测：所有测量点的质心
2. 每步构建 2×2 雅可比矩阵和残差向量
3. 求解法方程 `H·δ = -g` 得到更新步长
4. 当步长 < 1mm 或达到 50 次迭代时停止
5. 最终误差 = 均方根残差

## 使用方法

1. **启动** → App 请求蓝牙、位置、WiFi 等权限
2. **扫描** → 点击"扫描设备"发现周围 BLE 设备
3. **选择目标** → 从列表中选择要追踪的 BLE 设备
4. **标定** → 走到房间四个角落，依次点击"标定"按钮（至少 3 个角）
5. **跟踪** → 走到 3 个不同位置，依次点击"测量"按钮
6. **定位** → 点击"计算目标位置"，显示目标坐标和误差半径

## 已知限制

- **信号波动**：BLE RSSI 受人体遮挡、多径效应影响，实测精度约 1~3 米
- **标定依赖**：需要在目标环境中预先标定，换环境需重新标定
- **固定房间尺寸**：UI 中标定角点假设 4m×4m 房间，需手动修改 `ROOM_CORNERS` 适配其他尺寸
- **最少点数**：标定和跟踪各需 ≥3 个点，否则无法计算
- **单目标**：当前仅支持追踪一个 BLE 设备
- **实时性**：WiFi/BLE 扫描有系统级延迟（各 2~5 秒），不适合高速移动场景
- **Android 版本差异**：Android 12+ 需要 BLUETOOTH_SCAN/CONNECT 权限，Android 13+ 需要 NEARBY_WIFI_DEVICES
