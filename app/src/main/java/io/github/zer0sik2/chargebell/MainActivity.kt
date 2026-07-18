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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

        // 알림 채널은 앱과 서비스 어느 쪽이 먼저 실행되더라도 사용할 수 있도록 미리 준비한다.
        NotificationHelper.createChannels(this)

        enableEdgeToEdge()
        setContent {
            ChargeBellTheme {
                val settings = remember { SettingsRepository(this) }

                // 최초 실행 시에는 메인 화면보다 권한 설정 화면을 먼저 보여준다.
                // 사용자가 OS 권한 화면에서 허용 또는 거부를 선택한 뒤에만 메인 화면으로 이동한다.
                var permissionSetupCompleted by remember {
                    mutableStateOf(settings.permissionSetupCompleted)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (permissionSetupCompleted) {
                        MainScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        PermissionSetupScreen(
                            modifier = Modifier.padding(innerPadding),
                            onCompleted = {
                                settings.permissionSetupCompleted = true
                                permissionSetupCompleted = true
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 앱 최초 실행 시 표시되는 권한 준비 화면이다.
 *
 * 진행 순서
 * 1. Android 13 이상이면 알림 권한을 OS 팝업으로 요청한다.
 * 2. 배터리 최적화 제외 여부를 OS 화면에서 선택하게 한다.
 * 3. 두 선택이 끝난 뒤에만 메인 화면을 표시한다.
 *
 * 사용자가 권한을 거부해도 앱을 강제로 막지는 않는다.
 * 여기서 중요한 것은 허용 여부가 아니라 OS 화면에서 선택을 먼저 마치는 것이다.
 */
@Composable
private fun PermissionSetupScreen(
    modifier: Modifier = Modifier,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    var requestInProgress by remember { mutableStateOf(false) }

    // 배터리 최적화 요청 화면에서 돌아오면 허용/거부 결과와 관계없이 최초 안내를 완료한다.
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onCompleted()
    }

    val requestBatteryOptimization: () -> Unit = {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // 이미 제외되어 있다면 OS 화면을 다시 띄울 필요가 없다.
            onCompleted()
        } else {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }

            // 일부 제조사 기기에서 해당 화면을 제공하지 않는 경우에도 앱이 막히지 않도록 안전하게 처리한다.
            runCatching {
                batteryOptimizationLauncher.launch(intent)
            }.onFailure {
                onCompleted()
            }
        }
    }

    // 알림 권한 결과가 돌아오면 곧바로 다음 단계인 배터리 최적화 선택 화면으로 이동한다.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        requestBatteryOptimization()
    }

    fun startPermissionSetup() {
        if (requestInProgress) return
        requestInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!notificationGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // Android 12 이하이거나 알림 권한이 이미 허용된 경우에는 바로 다음 단계로 이동한다.
        requestBatteryOptimization()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
    ) {
        // Adaptive Icon XML은 Compose painterResource에서 직접 읽을 수 없으므로
        // 런처 아이콘의 벡터 전경 리소스를 사용한다.
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "ChargeBell 앱 아이콘",
            modifier = Modifier.size(132.dp)
        )

        Text(
            text = "ChargeBell 사용 준비",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "배터리 감시를 안정적으로 유지하려면 알림 권한과 배터리 최적화 설정을 먼저 선택해야 합니다.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "1. 알림 권한: 감시 상태와 목표 도달 알림 표시",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "2. 배터리 최적화 제외: 화면이 꺼져도 감시 서비스 유지",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Button(
            onClick = { startPermissionSetup() },
            enabled = !requestInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (requestInProgress) "권한 설정 진행 중" else "권한 설정 시작")
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }

    // 화면에 표시할 현재 배터리 퍼센트이다.
    var currentPercent by remember { mutableIntStateOf(0) }

    // SharedPreferences에 저장된 값을 화면의 초기값으로 사용한다.
    var targetPercent by remember { mutableIntStateOf(settings.targetPercent) }
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var vibrationEnabled by remember { mutableStateOf(settings.vibrationEnabled) }

    // 서비스가 자동 종료되거나 알림의 해제 버튼으로 종료될 때도 갱신되는 화면 상태이다.
    var isMonitoring by remember { mutableStateOf(BatteryMonitorService.isRunning) }

    // 슬라이더를 드래그하는 동안 ACTION_STOP을 매 프레임 반복 전송하지 않도록 막는 플래그이다.
    var optionChangeStopRequested by remember { mutableStateOf(false) }

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

                // 서비스 종료가 확인되면 다음 옵션 변경이나 시작 동작을 정상적으로 받을 수 있게 초기화한다.
                if (!isMonitoring) {
                    optionChangeStopRequested = false
                }
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

        isMonitoring = BatteryMonitorService.isRunning

        onDispose {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            runCatching { context.unregisterReceiver(monitoringStateReceiver) }
        }
    }

    // 시작 버튼을 여러 번 눌러도 서비스 내부의 멱등 처리로 실제 감시와 리시버는 하나만 유지된다.
    fun startMonitoring() {
        if (isMonitoring || BatteryMonitorService.isRunning) {
            isMonitoring = true
            return
        }

        settings.targetPercent = targetPercent
        settings.soundEnabled = soundEnabled
        settings.vibrationEnabled = vibrationEnabled

        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        optionChangeStopRequested = false
        isMonitoring = true
    }

    // 모든 중지 동작은 ACTION_STOP 한 경로로 통일한다.
    fun stopMonitoring() {
        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_STOP
        }
        context.startService(serviceIntent)
        isMonitoring = false
    }

    // 감시 중 옵션을 변경하면 기존 서비스가 예전 설정으로 계속 동작하지 않도록 즉시 종료한다.
    fun stopMonitoringForOptionChange() {
        val serviceIsActive = isMonitoring || BatteryMonitorService.isRunning
        if (!serviceIsActive || optionChangeStopRequested) return

        optionChangeStopRequested = true
        stopMonitoring()
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
                    val newPercent = newValue.roundToInt()
                    if (newPercent != targetPercent) {
                        stopMonitoringForOptionChange()
                        targetPercent = newPercent
                        settings.targetPercent = newPercent
                    }
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
                onCheckedChange = { enabled ->
                    if (enabled != soundEnabled) {
                        stopMonitoringForOptionChange()
                        soundEnabled = enabled
                        settings.soundEnabled = enabled
                    }
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
                onCheckedChange = { enabled ->
                    if (enabled != vibrationEnabled) {
                        stopMonitoringForOptionChange()
                        vibrationEnabled = enabled
                        settings.vibrationEnabled = enabled
                    }
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

        // 최초 안내에서 권한을 거부했거나 나중에 설정을 바꾼 사용자가 다시 요청할 수 있도록 남겨 둔다.
        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "배터리 최적화 제외 다시 요청")
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
        runCatching { context.startActivity(intent) }
    }
}
