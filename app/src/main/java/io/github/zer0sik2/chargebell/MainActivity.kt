package io.github.zer0sik2.chargebell

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.zer0sik2.chargebell.data.SettingsRepository
import io.github.zer0sik2.chargebell.notification.NotificationHelper
import io.github.zer0sik2.chargebell.service.BatteryMonitorService
import io.github.zer0sik2.chargebell.ui.theme.ChargeBellTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 알림 채널은 앱이 처음 켜질 때 미리 만들어 둔다.
        // 서비스에서도 다시 생성하므로 Activity를 거치지 않은 재시작에도 안전하다.
        NotificationHelper.createChannels(this)

        enableEdgeToEdge()
        setContent {
            ChargeBellTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }

    // 화면에서 보여주는 현재 배터리 퍼센트이다.
    var currentPercent by remember { mutableIntStateOf(0) }

    // SharedPreferences에 저장된 값을 화면의 초기값으로 사용한다.
    var targetPercent by remember { mutableIntStateOf(settings.targetPercent) }
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var vibrationEnabled by remember { mutableStateOf(settings.vibrationEnabled) }

    // 서비스가 자동 종료되거나 알림의 해제 버튼으로 종료될 때도 갱신되는 화면 상태이다.
    var isMonitoring by remember { mutableStateOf(BatteryMonitorService.isRunning) }

    // Android 13 이상에서 POST_NOTIFICATIONS 권한을 요청하기 위한 런처이다.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 권한 거부 시에도 소리와 진동 감시는 계속 사용할 수 있다. */ }

    /**
     * 화면이 보이는 동안 두 종류의 브로드캐스트를 한 번씩만 등록한다.
     * 1. ACTION_BATTERY_CHANGED: 현재 배터리 퍼센트 표시
     * 2. ACTION_MONITORING_STATE_CHANGED: 서비스 시작/종료 상태와 버튼 문구 동기화
     */
    DisposableEffect(context) {
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    currentPercent = (level * 100) / scale
                }
            }
        }

        val monitoringStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                isMonitoring = intent.getBooleanExtra(
                    BatteryMonitorService.EXTRA_IS_RUNNING,
                    false
                )
            }
        }

        ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            context,
            monitoringStateReceiver,
            IntentFilter(BatteryMonitorService.ACTION_MONITORING_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 화면에 다시 진입했을 때 서비스의 현재 프로세스 상태를 한 번 더 반영한다.
        isMonitoring = BatteryMonitorService.isRunning

        onDispose {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            runCatching { context.unregisterReceiver(monitoringStateReceiver) }
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 시작 버튼을 여러 번 눌러도 서비스 내부의 멱등 처리로 실제 감시와 리시버는 하나만 유지된다.
    fun startMonitoring() {
        if (isMonitoring || BatteryMonitorService.isRunning) {
            isMonitoring = true
            return
        }

        requestNotificationPermissionIfNeeded()

        settings.targetPercent = targetPercent
        settings.soundEnabled = soundEnabled
        settings.vibrationEnabled = vibrationEnabled

        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        // 서비스의 상태 브로드캐스트가 오기 전까지 빠른 연속 클릭을 막기 위한 즉시 반영이다.
        isMonitoring = true
    }

    // 중지 동작도 ACTION_STOP 한 경로로 통일해 알람음, 진동, 리시버, 알림을 함께 정리한다.
    fun stopMonitoring() {
        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_STOP
        }
        context.startService(serviceIntent)
        isMonitoring = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "ChargeBell",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "현재 배터리: $currentPercent%",
            style = MaterialTheme.typography.titleLarge
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "목표 배터리: $targetPercent%")
            Slider(
                value = targetPercent.toFloat(),
                onValueChange = { newValue ->
                    targetPercent = newValue.roundToInt()
                    settings.targetPercent = targetPercent
                },
                valueRange = 50f..100f,
                steps = 49
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "소리")
            Switch(
                checked = soundEnabled,
                onCheckedChange = {
                    soundEnabled = it
                    settings.soundEnabled = it
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "진동")
            Switch(
                checked = vibrationEnabled,
                onCheckedChange = {
                    vibrationEnabled = it
                    settings.vibrationEnabled = it
                }
            )
        }

        Button(
            onClick = {
                if (isMonitoring) stopMonitoring() else startMonitoring()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (isMonitoring) "감시 중지" else "감시 시작")
        }

        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "배터리 최적화 제외 요청")
        }
    }
}

// 시스템에게 이 앱을 배터리 최적화 대상에서 제외해 달라고 요청하는 화면을 연다.
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val packageName = context.packageName

    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        context.startActivity(intent)
    }
}
