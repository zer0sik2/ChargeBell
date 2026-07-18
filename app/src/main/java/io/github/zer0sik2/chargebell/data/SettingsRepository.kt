package io.github.zer0sik2.chargebell.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱에서 사용하는 간단한 설정값을 SharedPreferences에 저장하고 불러오는 클래스.
 *
 * MainActivity와 BatteryMonitorService가 같은 저장소를 사용하므로
 * 화면에서 변경한 목표 퍼센트, 소리, 진동 설정을 서비스에서도 그대로 읽을 수 있다.
 */
class SettingsRepository(context: Context) {

    // 앱 전용 SharedPreferences 파일을 한 번 열어 계속 재사용한다.
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 목표 배터리 퍼센트. 앱 최초 실행 시 기본값은 80%이다.
    var targetPercent: Int
        get() = prefs.getInt(KEY_TARGET_PERCENT, DEFAULT_TARGET_PERCENT)
        set(value) = prefs.edit().putInt(KEY_TARGET_PERCENT, value).apply()

    // 목표 도달 시 기기 기본 알람음을 재생할지 여부이다.
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    // 목표 도달 시 진동을 반복 실행할지 여부이다.
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    // 최초 실행 권한 안내 흐름을 한 번 완료했는지 저장한다.
    // 권한을 허용했는지가 아니라, 사용자가 OS 권한 화면에서 선택을 마쳤는지를 뜻한다.
    var permissionSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_SETUP_COMPLETED, value).apply()

    companion object {
        private const val PREFS_NAME = "charge_bell_prefs"
        private const val KEY_TARGET_PERCENT = "target_percent"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_PERMISSION_SETUP_COMPLETED = "permission_setup_completed"

        const val DEFAULT_TARGET_PERCENT = 80
    }
}
