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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.zer0sik2.chargebell.ads.AdBanner
import io.github.zer0sik2.chargebell.ads.AdsManager
import io.github.zer0sik2.chargebell.ads.ExitAdDialog
import io.github.zer0sik2.chargebell.data.SettingsRepository
import io.github.zer0sik2.chargebell.notification.NotificationHelper
import io.github.zer0sik2.chargebell.service.BatteryMonitorService
import io.github.zer0sik2.chargebell.ui.theme.ChargeBellTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // 광고 SDK 초기화(+ 필요 지역의 동의 폼)가 끝나야 true가 되며, 그 전에는 배너를 그리지 않는다.
    private val adsReadyState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 알림 채널은 앱과 서비스 어느 쪽이 먼저 실행되더라도 사용할 수 있도록 미리 준비한다.
        NotificationHelper.createChannels(this)

        // 광고 동의 확인과 SDK 초기화를 시작한다. 완료되면 하단 배너가 나타난다.
        AdsManager.gatherConsentAndInitialize(this) {
            runOnUiThread { adsReadyState.value = true }
        }

        enableEdgeToEdge()
        setContent {
            ChargeBellTheme {
                val context = this@MainActivity
                val settings = remember { SettingsRepository(context) }

                // 이전 버전 사용자가 이미 필요한 OS 권한을 모두 허용한 상태라면
                // 새 안내 화면을 다시 보여주지 않고 완료 상태로 자동 보정한다.
                val permissionsAlreadyReady = remember {
                    areRequiredOsSettingsReady(context)
                }
                if (permissionsAlreadyReady && !settings.permissionSetupCompleted) {
                    settings.permissionSetupCompleted = true
                }

                var permissionSetupCompleted by remember {
                    mutableStateOf(
                        settings.permissionSetupCompleted || permissionsAlreadyReady
                    )
                }

                val adsReady by adsReadyState

                // 뒤로 가기로 앱을 나갈 때 종료 확인 팝업(광고 포함)을 먼저 보여준다.
                // 광고 준비 전이나 권한 안내 중에는 기본 뒤로 가기 동작을 그대로 둔다.
                var showExitDialog by remember { mutableStateOf(false) }
                BackHandler(enabled = adsReady && permissionSetupCompleted && !showExitDialog) {
                    showExitDialog = true
                }
                if (showExitDialog) {
                    ExitAdDialog(
                        onDismiss = { showExitDialog = false },
                        onConfirmExit = { finish() }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // 광고 준비 완료 전에는 아무것도 그리지 않아 화면 아래 공간이 본문에 쓰인다.
                        if (adsReady) {
                            AdBanner(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                ) { innerPadding ->
                    if (permissionSetupCompleted) {
                        MainScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        PermissionSetupFlow(
                            modifier = Modifier.padding(innerPadding),
                            onCompleted = {
                                // 허용과 거부 중 무엇을 선택했는지와 관계없이
                                // OS 요청을 한 번 진행했다면 다음 실행부터 반복하지 않는다.
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
 * 최초 실행 때 별도의 "권한 설정 시작" 버튼을 요구하지 않고
 * OS 알림 권한 창과 배터리 최적화 제외 화면을 자동으로 순서대로 연다.
 *
 * 화면에는 권한 요청이 준비되는 짧은 시간 동안만 브랜드 안내를 표시한다.
 * 사용자가 한 번 선택한 뒤에는 SharedPreferences 완료값 때문에 다시 나타나지 않는다.
 */
@Composable
private fun PermissionSetupFlow(
    modifier: Modifier = Modifier,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var flowStarted by remember { mutableStateOf(false) }

    // 두 번째 단계인 배터리 최적화 제외 화면을 연다.
    // 외부 OS 화면을 성공적으로 열기 직전에 완료값을 먼저 저장한다.
    // 이렇게 하면 사용자가 OS 화면에서 앱을 종료해도 다음 실행 때 안내가 반복되지 않는다.
    val requestBatteryOptimization: () -> Unit = {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName

        settings.permissionSetupCompleted = true

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }

            // OS 화면을 연 직후 Compose 화면은 메인 화면으로 전환한다.
            // 실제 사용자는 OS 화면을 닫은 뒤 메인 화면을 보게 된다.
            runCatching { context.startActivity(intent) }
        }

        onCompleted()
    }

    // 첫 번째 단계인 알림 권한 요청 결과가 돌아오면 바로 두 번째 단계로 이동한다.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        requestBatteryOptimization()
    }

    // Composable이 처음 화면에 들어온 순간 한 번만 자동으로 권한 요청 흐름을 시작한다.
    LaunchedEffect(Unit) {
        if (flowStarted) return@LaunchedEffect
        flowStarted = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!notificationGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@LaunchedEffect
            }
        }

        // Android 12 이하이거나 알림 권한이 이미 허용된 경우에는 두 번째 단계부터 진행한다.
        requestBatteryOptimization()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
    ) {
        Image(
            // painterResource는 <bitmap> 래퍼 XML(ic_launcher_foreground)을 지원하지 않으므로
            // 원본 PNG 리소스를 직접 참조한다.
            painter = painterResource(id = R.drawable.chargebell_permission_icon),
            contentDescription = "ChargeBell 앱 아이콘",
            modifier = Modifier.size(132.dp)
        )

        Text(
            text = "ChargeBell 사용 준비",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "필요한 OS 권한 화면을 여는 중입니다. 선택을 마치면 메인 화면으로 이동합니다.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
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

/**
 * 기존 설치 사용자가 이미 알림 권한과 배터리 최적화 제외를 모두 허용했다면
 * 신규 완료 플래그가 없어도 안내 화면을 건너뛰기 위한 확인 함수이다.
 */
private fun areRequiredOsSettingsReady(context: Context): Boolean {
    val notificationReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryOptimizationReady = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    return notificationReady && batteryOptimizationReady
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
