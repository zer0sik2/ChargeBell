package io.github.zer0sik2.chargebell.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.CombinedVibration
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.zer0sik2.chargebell.data.SettingsRepository
import io.github.zer0sik2.chargebell.notification.NotificationHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 배터리 충전 상태를 실시간으로 감시하는 포그라운드 서비스.
 *
 * 중요한 동작 원칙
 * 1. ACTION_START가 여러 번 들어와도 포그라운드 서비스와 리시버는 한 번만 준비한다.
 * 2. 목표 도달 알림은 한 충전 세션에서 한 번만 실행한다.
 * 3. 감시를 시작할 때 충전기가 빠져 있으면 종료하지 않고, 충전기가 꽂히기를 기다린다.
 * 4. 충전기가 빠지는 전환을 감지하면 알람(소리, 진동, 목표 도달 알림)만 해제하고 감시는 계속한다.
 * 5. 목표 도달 진동은 사용자가 해제하거나 충전기를 분리할 때까지 반복한다.
 * 6. 어떤 경로로 종료되더라도 리시버, 알람음, 진동, 알림을 모두 정리한다.
 */
class BatteryMonitorService : Service() {

    private lateinit var settings: SettingsRepository

    // 현재 서비스 인스턴스에서 실제 감시 준비가 끝났는지 확인한다.
    // Android의 Service는 원래 한 컴포넌트당 한 인스턴스이지만,
    // 시작 Intent가 여러 번 전달될 수 있으므로 별도 플래그로 멱등성을 보장한다.
    private var monitoringStarted = false

    // registerReceiver를 실제로 호출했는지 기록한다.
    // false인 상태에서는 unregisterReceiver를 호출하지 않는다.
    private var receiverRegistered = false

    // 종료 요청과 정리 로직이 여러 경로에서 동시에 실행되는 것을 막는다.
    private var stopRequested = false
    private var cleanupFinished = false

    // sticky ACTION_BATTERY_CHANGED를 처음 받았는지 확인하는 값이다.
    // 첫 상태가 "충전기 미연결"이어도 자동 종료하지 않고 대기하기 위해 사용한다.
    private var batteryStateInitialized = false

    // 직전 배터리 이벤트에서 충전기가 꽂혀 있었는지 기록한다.
    private var wasPlugged = false

    // 목표 도달 알림이 한 충전 세션에서 한 번만 실행되도록 원자적으로 관리한다.
    // BroadcastReceiver는 보통 메인 스레드에서 호출되지만, 중복 방지 의도를 명확하게 하기 위해 AtomicBoolean을 사용한다.
    private val alreadyNotifiedThisSession = AtomicBoolean(false)

    // 현재 재생 중인 기기 기본 알람음이다.
    // 중지 버튼, 알림의 해제 버튼, 자동 종료, onDestroy에서 모두 정지할 수 있도록 보관한다.
    private var alarmRingtone: Ringtone? = null

    // ACTION_BATTERY_CHANGED는 매니페스트 등록이 아니라 서비스 실행 중 런타임으로 등록한다.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleBatteryChanged(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)

        // Activity를 거치지 않고 서비스가 재시작되는 경우에도 알림 채널이 반드시 존재해야 한다.
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 메인 화면의 중지 버튼 또는 알림의 "해제" 액션이 이 경로로 들어온다.
                requestStop()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                // 같은 서비스에 시작 Intent가 여러 번 들어와도 실제 준비는 한 번만 수행한다.
                ensureMonitoringStarted()
            }
        }

        // 시스템이 메모리 부족 등으로 서비스를 종료한 경우에는 감시를 복구한다.
        // 사용자가 명시적으로 중지한 경우 requestStop()에서 START_NOT_STICKY 경로로 종료된다.
        return START_STICKY
    }

    /**
     * 포그라운드 전환과 배터리 리시버 등록을 한 번만 수행한다.
     * 이미 감시 중이면 현재 알림 내용만 갱신하고 세션 상태는 초기화하지 않는다.
     */
    private fun ensureMonitoringStarted() {
        if (stopRequested) return

        if (monitoringStarted) {
            updateServiceNotification(getCurrentBatteryPercent())
            return
        }

        val notification = NotificationHelper.buildServiceNotification(
            context = this,
            currentPercent = getCurrentBatteryPercent(),
            targetPercent = settings.targetPercent
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 이상에서는 매니페스트에 선언한 specialUse 타입을 함께 전달한다.
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }

        monitoringStarted = true
        isRunning = true
        sendMonitoringStateChanged(true)

        registerBatteryReceiverOnce()
    }

    /**
     * ACTION_BATTERY_CHANGED 리시버를 한 번만 등록한다.
     * 이 브로드캐스트는 sticky이므로 등록 직후 현재 상태가 즉시 한 번 전달된다.
     */
    private fun registerBatteryReceiverOnce() {
        if (receiverRegistered) return

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        // 등록 직후 sticky 이벤트가 바로 전달될 수 있으므로 먼저 true로 바꾼다.
        // 그래야 콜백 중 종료가 발생해도 정리 로직이 정확히 unregister할 수 있다.
        receiverRegistered = true
        runCatching {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            receiverRegistered = false
            requestStop()
        }
    }

    // 화면과 바인딩해서 사용하는 서비스가 아니므로 null을 반환한다.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // stopService, stopSelf, 시스템 종료 등 어떤 경로로 들어와도 같은 정리 로직을 실행한다.
        cleanupResources()
        super.onDestroy()
    }

    /**
     * 배터리 상태 이벤트를 처리한다.
     * 첫 이벤트는 "초기 상태 확인" 용도이고, 두 번째 이벤트부터 실제 연결 전환을 판단한다.
     */
    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return

        val percent = (level * 100) / scale

        // 0이면 충전기 미연결, 0이 아니면 AC/USB/무선 충전기 중 하나가 연결된 상태이다.
        val pluggedType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isPlugged = pluggedType != 0

        if (!batteryStateInitialized) {
            // 감시 시작 직후 전달되는 sticky 이벤트는 이전 상태가 없으므로 전환으로 보지 않는다.
            batteryStateInitialized = true
            wasPlugged = isPlugged

            // 이미 충전기가 꽂힌 상태에서 감시를 시작했다면 이것을 새 충전 세션으로 본다.
            if (isPlugged) {
                alreadyNotifiedThisSession.set(false)
            }
        } else {
            // 충전기가 새로 꽂힌 순간에만 새 충전 세션으로 초기화한다.
            if (isPlugged && !wasPlugged) {
                alreadyNotifiedThisSession.set(false)
            }

            // 꽂혀 있던 충전기가 빠지는 "true -> false" 전환에서는 알람만 해제한다.
            // 서비스는 계속 살아서 다음 충전 세션을 기다린다.
            val unplugged = wasPlugged && !isPlugged
            wasPlugged = isPlugged

            if (unplugged) {
                stopGoalAlert()
            }
        }

        updateServiceNotification(percent)

        val targetPercent = settings.targetPercent
        if (isPlugged && percent >= targetPercent) {
            // false -> true 변경에 성공한 단 한 번의 이벤트만 실제 알림을 실행한다.
            if (alreadyNotifiedThisSession.compareAndSet(false, true)) {
                triggerGoalReachedAlert(targetPercent)
            }
        }
    }

    private fun updateServiceNotification(percent: Int) {
        if (!monitoringStarted || stopRequested) return

        val notification = NotificationHelper.buildServiceNotification(
            context = this,
            currentPercent = percent,
            targetPercent = settings.targetPercent
        )
        NotificationHelper.notifySafely(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notification
        )
    }

    // 목표 도달 알림 자체는 충전 세션당 한 번만 발행한다.
    // 발행된 뒤 알람음과 진동은 해제, 감시 중지 또는 충전기 분리 시점까지 계속된다.
    private fun triggerGoalReachedAlert(targetPercent: Int) {
        val notification = NotificationHelper.buildGoalReachedNotification(this, targetPercent)
        NotificationHelper.notifySafely(
            this,
            NotificationHelper.ALERT_NOTIFICATION_ID,
            notification
        )

        if (settings.soundEnabled) {
            playAlarmSound()
        }
        if (settings.vibrationEnabled) {
            startRepeatingVibration()
        }
    }

    /**
     * 기기에 내장된 기본 알람음을 재생한다.
     * 혹시 이전 Ringtone 객체가 남아 있더라도 먼저 정지한 뒤 하나만 재생한다.
     */
    private fun playAlarmSound() {
        stopAlarmSound()

        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
            val ringtone = RingtoneManager.getRingtone(this, uri) ?: return

            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // API 28 이상에서는 기본 알람음도 사용자가 해제할 때까지 반복 재생한다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            }

            alarmRingtone = ringtone
            ringtone.play()
        }
    }

    private fun stopAlarmSound() {
        alarmRingtone?.let { ringtone ->
            runCatching {
                if (ringtone.isPlaying) ringtone.stop()
            }
        }
        alarmRingtone = null
    }

    /**
     * 목표 도달 진동을 반복해서 실행한다.
     *
     * 패턴 구성: 0ms 대기 -> 500ms 진동 -> 500ms 휴식.
     * repeatIndex를 0으로 지정했기 때문에 stopVibration()을 호출할 때까지 계속 반복된다.
     */
    private fun startRepeatingVibration() {
        val pattern = longArrayOf(0, 500, 500)
        val repeatingEffect = VibrationEffect.createWaveform(pattern, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java) ?: return
            vibratorManager.vibrate(CombinedVibration.createParallel(repeatingEffect))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator.vibrate(repeatingEffect)
        }
    }

    // 서비스가 종료될 때 반복 중인 진동을 반드시 취소한다.
    private fun stopVibration() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.cancel()
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
        }
    }

    /**
     * 목표 도달 알람만 해제한다. 감시 자체는 계속 유지된다.
     * 충전기 분리 시 알람음, 진동, 목표 도달 알림을 정리하는 용도로 사용한다.
     */
    private fun stopGoalAlert() {
        stopAlarmSound()
        stopVibration()
        NotificationManagerCompat.from(this).cancel(NotificationHelper.ALERT_NOTIFICATION_ID)
    }

    /**
     * 모든 종료 경로가 공통으로 사용하는 메서드이다.
     * 중복 호출되어도 한 번만 정리하고 stopSelf를 요청한다.
     */
    private fun requestStop() {
        if (stopRequested) return
        stopRequested = true
        cleanupResources()
        stopSelf()
    }

    /**
     * 리시버, 알람음, 진동, 포그라운드 알림을 빠짐없이 정리한다.
     * onDestroy와 requestStop 양쪽에서 호출되므로 멱등하게 작성한다.
     */
    private fun cleanupResources() {
        if (cleanupFinished) return
        cleanupFinished = true

        if (receiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            receiverRegistered = false
        }

        stopAlarmSound()
        stopVibration()

        // 포그라운드 서비스 알림과 목표 도달 알림을 모두 제거한다.
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationHelper.cancelMonitoringNotifications(this)
        NotificationManagerCompat.from(this).cancel(NotificationHelper.SERVICE_NOTIFICATION_ID)

        monitoringStarted = false
        isRunning = false
        sendMonitoringStateChanged(false)
    }

    // 서비스 시작 알림에 사용할 현재 배터리 퍼센트를 sticky 브로드캐스트에서 즉시 조회한다.
    private fun getCurrentBatteryPercent(): Int {
        val stickyIntent = ContextCompat.registerReceiver(
            this,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return 0

        val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return (level * 100) / scale
    }

    // 서비스 상태 변경을 앱 내부 브로드캐스트로 보내 메인 화면 버튼과 즉시 동기화한다.
    private fun sendMonitoringStateChanged(running: Boolean) {
        val stateIntent = Intent(ACTION_MONITORING_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, running)
        }
        sendBroadcast(stateIntent)
    }

    companion object {
        const val ACTION_START =
            "io.github.zer0sik2.chargebell.action.START_MONITORING"
        const val ACTION_STOP =
            "io.github.zer0sik2.chargebell.action.STOP_MONITORING"
        const val ACTION_MONITORING_STATE_CHANGED =
            "io.github.zer0sik2.chargebell.action.MONITORING_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "extra_is_running"

        // 같은 앱 프로세스 안에서 Activity가 현재 상태를 즉시 조회할 때 사용하는 값이다.
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
