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

/**
 * 알림 채널 생성과 알림 발행을 한 곳에서 관리하는 헬퍼.
 *
 * 알림 채널은 두 개를 사용한다.
 * 1) SERVICE_CHANNEL_ID : "지금 감시 중" 이라는 것을 보여주는 조용한 채널 (포그라운드 서비스용)
 * 2) ALERT_CHANNEL_ID   : 목표 % 도달 시 사용자에게 크게 알려주는 채널
 */
object NotificationHelper {

    const val SERVICE_CHANNEL_ID = "battery_watch_service_channel"
    const val ALERT_CHANNEL_ID = "battery_goal_alert_channel"

    const val SERVICE_NOTIFICATION_ID = 1
    const val ALERT_NOTIFICATION_ID = 2

    // 앱이 켜질 때 한 번 호출해서 알림 채널 두 개를 만들어 둔다.
    // 이미 만들어져 있는 채널을 다시 만들려고 하면 시스템이 그냥 무시하므로 여러 번 호출해도 안전하다.
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "배터리 감시 서비스",
            NotificationManager.IMPORTANCE_LOW // 소리 없이 상태표시줄에만 조용히 표시
        ).apply {
            description = "배터리 충전 상태를 감시하고 있음을 보여주는 알림입니다."
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "목표 충전 도달 알림",
            NotificationManager.IMPORTANCE_HIGH // 화면 상단에 팝업으로 뜨도록 높은 중요도
        ).apply {
            description = "설정한 목표 배터리 퍼센트에 도달했을 때 울리는 알림입니다."
            // 알림 소리는 채널이 아니라 우리가 직접 RingtoneManager로 재생하므로
            // 채널 자체의 기본 알림음은 꺼 둔다 (중복으로 울리는 것을 방지)
            setSound(null, null)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alertChannel)
    }

    // 앱을 터치하면 MainActivity로 이동하는 공통 PendingIntent
    private fun contentIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    // 배터리 감시 중임을 보여주는 포그라운드 서비스용 알림 (계속 떠 있음)
    fun buildServiceNotification(context: Context, currentPercent: Int, targetPercent: Int): Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("배터리 감시 중")
            .setContentText("현재 ${currentPercent}% / 목표 ${targetPercent}%")
            .setOngoing(true) // 사용자가 스와이프로 지울 수 없게 함 (포그라운드 서비스 알림)
            .setOnlyAlertOnce(true) // 내용이 갱신될 때마다 다시 소리/진동이 울리지 않도록
            .setContentIntent(contentIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // 목표 퍼센트에 도달했을 때 사용자에게 보여줄 알림
    fun buildGoalReachedNotification(context: Context, targetPercent: Int): Notification {
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("목표 충전량 도달!")
            .setContentText("배터리가 목표치인 ${targetPercent}%에 도달했습니다. 충전기를 분리해주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // 사용자가 탭하면 자동으로 사라짐
            .setContentIntent(contentIntent(context))
            .build()
    }

    // Android 13(API 33) 이상에서는 알림을 띄우기 전에 POST_NOTIFICATIONS 권한이 있는지 확인해야 한다
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 알림 권한이 있을 때만 실제로 알림을 띄운다 (없으면 조용히 무시하고, 소리/진동은 별개로 계속 동작함)
    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (!hasNotificationPermission(context)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
