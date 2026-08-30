package com.example.signallocator

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.signallocator.repository.SignalRepository
import com.example.signallocator.ui.screens.MainScreen
import com.example.signallocator.utils.StepCounter
import com.example.signallocator.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SignalLocatorApp()
                }
            }
        }
    }
}

@Composable
private fun SignalLocatorApp() {
    val context = LocalContext.current
    val repository = remember { SignalRepository(context) }
    val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = MainViewModelFactory(repository)
    )

    // 创建计步器
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val stepCounter = remember {
        StepCounter(sensorManager) { steps, distance ->
            viewModel.onStepUpdate(steps, distance)
        }
    }
    // 页面销毁时停止计步
    DisposableEffect(Unit) {
        onDispose { stepCounter.stop() }
    }

    // Build the list of permissions needed for this app
    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }

    // Request permissions using the Compose-compatible launcher
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            android.util.Log.w("SignalLocator", "部分权限被拒绝，功能可能受限")
        }
    }

    // Request any missing permissions on first composition
    LaunchedEffect(Unit) {
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) launcher.launch(denied.toTypedArray())
    }

    MainScreen(viewModel = viewModel, stepCounter = stepCounter)
}

class MainViewModelFactory(private val repo: SignalRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
}
