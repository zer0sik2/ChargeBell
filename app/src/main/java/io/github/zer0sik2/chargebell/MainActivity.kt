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

        // 알림 채널은 앱이 처음 켜질 때 미리 만들어 둔다 (중복 호출해도 안전함)
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
    // SettingsRepository는 SharedPreferences를 감싼 얇은 클래스라서 매번 새로 만들어도 가볍다
    val settings = remember { SettingsRepository(context) }

    // ---- 화면에서 사용하는 상태값들 ----
    // 현재 배터리 퍼센트: 아래 DisposableEffect의 브로드캐스트 리시버가 실시간으로 갱신해줌
    var currentPercent by remember { mutableIntStateOf(0) }
    // 목표 배터리 퍼센트: 저장된 값을 초깃값으로 사용
    var targetPercent by remember { mutableIntStateOf(settings.targetPercent) }
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var vibrationEnabled by remember { mutableStateOf(settings.vibrationEnabled) }
    // 서비스가 지금 감시 중인지 여부 (같은 프로세스의 static 플래그를 초깃값으로 사용)
    var isMonitoring by remember { mutableStateOf(BatteryMonitorService.isRunning) }

    // Android 13(API 33) 이상에서 알림 권한을 요청하기 위한 런처.
    // 사용자가 거부해도 앱은 계속 동작해야 하므로(소리/진동은 별개) 결과는 별도로 처리하지 않는다.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 사용자의 선택 결과를 그대로 둔다 */ }

    // 화면에 보여줄 "현재 배터리 %"를 실시간으로 갱신하기 위한 브로드캐스트 리시버 등록.
    // ACTION_BATTERY_CHANGED는 sticky 브로드캐스트라서 등록하자마자 현재 상태를 바로 받는다.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    currentPercent = (level * 100) / scale
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // 화면이 사라질 때(Composable이 화면에서 빠질 때) 리시버 해제
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Android 13 미만에서는 알림 권한이 원래 항상 허용되어 있으므로 요청할 필요가 없다
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // "감시 시작" 버튼을 눌렀을 때: 최신 설정값 저장 후 포그라운드 서비스 시작
    fun startMonitoring() {
        requestNotificationPermissionIfNeeded()

        settings.targetPercent = targetPercent
        settings.soundEnabled = soundEnabled
        settings.vibrationEnabled = vibrationEnabled

        val serviceIntent = Intent(context, BatteryMonitorService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        isMonitoring = true
    }

    // "감시 중지" 버튼을 눌렀을 때: 서비스 종료
    fun stopMonitoring() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
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

        // 1) 현재 배터리 퍼센트 표시
        Text(
            text = "현재 배터리: $currentPercent%",
            style = MaterialTheme.typography.titleLarge
        )

        // 2) 목표 퍼센트 슬라이더 (50% ~ 100%, 기본 80%)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "목표 배터리: $targetPercent%")
            Slider(
                value = targetPercent.toFloat(),
                onValueChange = { newValue ->
                    targetPercent = newValue.roundToInt()
                    settings.targetPercent = targetPercent // 슬라이더를 움직일 때마다 바로 저장
                },
                valueRange = 50f..100f,
                // steps는 "양 끝을 제외한 중간 눈금 개수". 50~100을 1%씩 움직이려면 49개가 필요함
                steps = 49
            )
        }

        // 3) 소리 토글
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

        // 4) 진동 토글
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

        // 5) 감시 시작 / 중지 버튼
        Button(
            onClick = { if (isMonitoring) stopMonitoring() else startMonitoring() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (isMonitoring) "감시 중지" else "감시 시작")
        }

        // 6) 배터리 최적화 예외 요청 버튼.
        // 시스템의 배터리 최적화 대상에서 빠지지 않으면, 화면이 꺼진 뒤
        // 브로드캐스트 수신이 지연되거나 서비스가 종료될 수 있어서 별도로 요청한다.
        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "배터리 최적화 제외 요청")
        }
    }
}

// 시스템에게 "이 앱은 배터리 최적화 대상에서 빼달라"고 요청하는 화면을 띄운다.
// 이미 예외 대상이면 다시 요청하지 않는다.
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
