package io.github.zer0sik2.chargebell.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱의 설정값(목표 배터리 %, 소리/진동 사용 여부)을
 * SharedPreferences에 저장하고 불러오는 클래스.
 *
 * MainActivity(화면)와 BatteryMonitorService(백그라운드 서비스)가
 * 같은 파일을 공유해서 설정을 주고받는다.
 */
class SettingsRepository(context: Context) {

    // 앱 전용 SharedPreferences 파일을 하나 열어서 계속 재사용한다
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 목표 배터리 퍼센트 (기본값 80%)
    var targetPercent: Int
        get() = prefs.getInt(KEY_TARGET_PERCENT, DEFAULT_TARGET_PERCENT)
        set(value) = prefs.edit().putInt(KEY_TARGET_PERCENT, value).apply()

    // 목표 도달 시 소리를 울릴지 여부 (기본값 켜짐)
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    // 목표 도달 시 진동을 울릴지 여부 (기본값 켜짐)
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    companion object {
        private const val PREFS_NAME = "charge_bell_prefs"
        private const val KEY_TARGET_PERCENT = "target_percent"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"

        const val DEFAULT_TARGET_PERCENT = 80
    }
}
