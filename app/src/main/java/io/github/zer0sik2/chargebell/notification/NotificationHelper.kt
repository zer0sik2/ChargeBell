package io.github.zer0sik2.chargebell.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.zer0sik2.chargebell.MainActivity
import io.github.zer0sik2.chargebell.R
import io.github.zer0sik2.chargebell.service.BatteryMonitorService

/**
 * ChargeBell에서 사용하는 알림 채널, 알림 본문, 액션 버튼을 한 곳에서 관리한다.
 *
 * 알림 채널은 두 개를 사용한다.
 * 1. SERVICE_CHANNEL_ID: 현재 감시 중임을 표시하는 조용한 포그라운드 서비스 채널
 * 2. ALERT_CHANNEL_ID: 목표 충전량에 도달했을 때 보여주는 높은 중요도의 채널
 *
 * 소리와 진동은 사용자의 토글 설정에 따라 서비스 코드가 직접 실행한다.
 * 따라서 알림 채널 자체의 소리와 진동은 반드시 꺼서 중복 실행을 방지한다.
 */
object NotificationHelper {

    const val SERVICE_CHANNEL_ID = "battery_watch_service_channel"

    // 기존 채널은 생성 후 소리/진동 속성을 완전히 바꾸기 어렵다.
    // 중복 진동이 가능했던 이전 채널과 분리하기 위해 새 버전의 채널 ID를 사용한다.
    const val ALERT_CHANNEL_ID = "battery_goal_alert_channel_v2"

    const val SERVICE_NOTIFICATION_ID = 1
    const val ALERT_NOTIFICATION_ID = 2

    private const val STOP_ACTION_REQUEST_CODE = 1001

    /**
     * 앱 또는 서비스가 시작될 때 알림 채널을 준비한다.
     * 같은 ID의 채널을 다시 생성해도 Android가 안전하게 처리하므로 여러 번 호출해도 된다.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "배터리 감시 서비스",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "배터리 충전 상태를 감시하고 있음을 보여주는 알림입니다."
            setSound(null, null)
            enableVibration(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "목표 충전 도달 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "설정한 목표 배터리 퍼센트에 도달했을 때 표시되는 알림입니다."

            // 소리와 진동은 BatteryMonitorService가 토글 설정에 맞춰 직접 한 번만 실행한다.
            // 채널에서도 실행하면 수동 알람과 겹쳐 두 번 울릴 수 있으므로 둘 다 비활성화한다.
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = null
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alertChannel)
    }

    // 알림 본문을 누르면 메인 화면을 연다.
    private fun contentIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 알림의 "해제" 버튼이 BatteryMonitorService에 ACTION_STOP을 전달한다.
     * 서비스는 이 액션을 받으면 알람음과 진동을 정지하고 리시버와 알림을 정리한 뒤 종료한다.
     */
    private fun stopMonitoringIntent(context: Context): PendingIntent {
        val intent = Intent(context, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_STOP
        }

        return PendingIntent.getService(
            context,
            STOP_ACTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // 계속 표시되는 포그라운드 서비스 알림이다.
    fun buildServiceNotification(
        context: Context,
        currentPercent: Int,
        targetPercent: Int
    ): Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("배터리 감시 중")
            .setContentText("현재 ${currentPercent}% / 목표 ${targetPercent}%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .addAction(
                R.drawable.ic_launcher_foreground,
                "해제",
                stopMonitoringIntent(context)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // 목표 퍼센트 도달 시 사용자에게 보여주는 알림이다.
    fun buildGoalReachedNotification(context: Context, targetPercent: Int): Notification {
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("목표 충전량 도달!")
            .setContentText("배터리가 목표치인 ${targetPercent}%에 도달했습니다. 충전기를 분리해주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .addAction(
                R.drawable.ic_launcher_foreground,
                "해제",
                stopMonitoringIntent(context)
            )
            .build()
    }

    // Android 13 이상에서 알림을 게시할 권한이 있는지 확인한다.
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 권한이 있을 때만 일반 알림을 안전하게 갱신하거나 게시한다.
    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (!hasNotificationPermission(context)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    // 서비스가 종료될 때 관련 알림을 모두 제거한다.
    fun cancelMonitoringNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(SERVICE_NOTIFICATION_ID)
        manager.cancel(ALERT_NOTIFICATION_ID)
    }
}
