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
import androidx.core.content.ContextCompat
import io.github.zer0sik2.chargebell.data.SettingsRepository
import io.github.zer0sik2.chargebell.notification.NotificationHelper

/**
 * 배터리 충전 상태를 실시간으로 감시하는 포그라운드 서비스.
 *
 * - ACTION_BATTERY_CHANGED는 매니페스트에 등록해도 시스템이 무시하기 때문에
 *   반드시 이 서비스가 살아있는 동안 코드(런타임)로 registerReceiver 해야 한다.
 * - 충전 중이면서 목표 % 이상이면 알림을 1회만 울리고,
 *   충전기를 뽑았다가 다시 꽂으면 재알림이 가능하도록 상태를 초기화한다.
 */
class BatteryMonitorService : Service() {

    private lateinit var settings: SettingsRepository

    // 이번 "충전 세션"에서 이미 알림을 울렸는지 여부.
    // true가 되면 같은 세션 동안에는 다시 알림을 울리지 않는다.
    private var alreadyNotifiedThisSession = false

    // 직전에 받은 브로드캐스트에서 충전기가 꽂혀 있었는지 여부.
    // 이 값이 false -> true 로 바뀌는 순간(=새로 충전을 시작한 순간)에 세션을 초기화한다.
    private var wasCharging = false

    // 재생 중인 알람 소리 (서비스가 종료될 때 멈추기 위해 보관)
    private var alarmRingtone: Ringtone? = null

    // 배터리 상태 변화를 받을 리시버. 서비스가 켜져 있는 동안에만 등록되어 있다.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleBatteryChanged(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)

        // 서비스가 시작되자마자 "감시 중" 알림을 포그라운드로 먼저 띄운다.
        // (포그라운드 서비스는 onCreate/onStartCommand 직후 바로 startForeground를 호출해야 함)
        val notification = NotificationHelper.buildServiceNotification(
            context = this,
            currentPercent = getCurrentBatteryPercent(),
            targetPercent = settings.targetPercent
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ : "specialUse" 타입임을 명시해서 startForeground 호출
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }

        // ACTION_BATTERY_CHANGED 브로드캐스트를 런타임으로 등록
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED // 앱 내부(시스템)에서만 오는 브로드캐스트이므로 미노출로 등록
        )

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 시스템이 메모리 부족 등으로 서비스를 죽였다가 다시 살릴 때, 마지막 intent 없이도 재시작
        return START_STICKY
    }

    // 화면(Activity)과 바인딩해서 쓰는 서비스가 아니라 독립적으로 동작하는 서비스이므로 null 반환
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 리시버가 등록 안 된 상태에서 해제를 시도하면 예외가 나므로 runCatching으로 안전하게 처리
        runCatching { unregisterReceiver(batteryReceiver) }
        alarmRingtone?.let { if (it.isPlaying) it.stop() }
        isRunning = false
    }

    // 배터리 상태 브로드캐스트를 받을 때마다 호출되는 핵심 로직
    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return // 값이 이상하면 이번 브로드캐스트는 무시

        val percent = (level * 100) / scale

        // EXTRA_PLUGGED 값이 0이 아니면 AC/USB/무선 충전기 중 하나가 꽂혀 있다는 뜻
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0

        // 충전기가 새로 꽂힌 순간(꽂혀있지 않다가 -> 꽂힘)이면 재알림이 가능하도록 초기화
        if (isCharging && !wasCharging) {
            alreadyNotifiedThisSession = false
        }
        wasCharging = isCharging

        // "감시 중" 알림에 현재 퍼센트를 갱신해서 보여준다
        updateServiceNotification(percent)

        val targetPercent = settings.targetPercent
        if (isCharging && percent >= targetPercent && !alreadyNotifiedThisSession) {
            alreadyNotifiedThisSession = true
            triggerGoalReachedAlert(targetPercent)
        }
    }

    private fun updateServiceNotification(percent: Int) {
        val notification = NotificationHelper.buildServiceNotification(
            context = this,
            currentPercent = percent,
            targetPercent = settings.targetPercent
        )
        NotificationHelper.notifySafely(this, NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
    }

    // 목표 도달 시: 알림 표시 + (설정에 따라) 소리 재생 + (설정에 따라) 진동
    private fun triggerGoalReachedAlert(targetPercent: Int) {
        val notification = NotificationHelper.buildGoalReachedNotification(this, targetPercent)
        NotificationHelper.notifySafely(this, NotificationHelper.ALERT_NOTIFICATION_ID, notification)

        if (settings.soundEnabled) {
            playAlarmSound()
        }
        if (settings.vibrationEnabled) {
            vibrate()
        }
    }

    // 기기에 내장된 기본 "알람" 소리를 재생한다 (커스텀 mp3 파일 없이 시스템 RingtoneManager만 사용)
    private fun playAlarmSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
            val ringtone = RingtoneManager.getRingtone(this, uri) ?: return

            // 알람 소리로 재생되도록 오디오 속성을 명시 (무음 모드 등에서도 알람처럼 취급됨)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            alarmRingtone = ringtone
            ringtone.play()
        }
    }

    // API 31(S) 이상은 VibratorManager, 그 미만은 기존 Vibrator로 분기해서 진동을 울린다
    private fun vibrate() {
        // 패턴: [대기 0ms, 진동 400ms, 대기 200ms, 진동 400ms]
        val pattern = longArrayOf(0, 400, 200, 400)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            val effect = VibrationEffect.createWaveform(pattern, -1) // -1: 반복 없이 한 번만
            vibratorManager.vibrate(CombinedVibration.createParallel(effect))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    // 서비스가 막 시작될 때 알림에 보여줄 "현재 배터리 %"를 한 번 조회한다.
    // (브로드캐스트 리시버 없이도, sticky 브로드캐스트를 즉시 조회하는 방법)
    private fun getCurrentBatteryPercent(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = ContextCompat.registerReceiver(
            this, null, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return 0

        val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return (level * 100) / scale
    }

    companion object {
        // Activity에서 서비스가 현재 실행 중인지 확인할 때 사용 (같은 프로세스이므로 static 필드로 충분)
        var isRunning: Boolean = false
            private set
    }
}
