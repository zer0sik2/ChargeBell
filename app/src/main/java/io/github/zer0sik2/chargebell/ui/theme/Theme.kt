package io.github.zer0sik2.chargebell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 시스템의 라이트/다크 설정과 동적 색상 기능을 사용하지 않는 고정 다크 컬러 스킴이다.
 * 배경과 표면은 검정~짙은 회색, 조작 강조색은 주황, 텍스트와 아이콘은 흰색으로 통일한다.
 */
private val ChargeBellDarkColorScheme = darkColorScheme(
    primary = ChargeOrange,
    onPrimary = ChargeBlack,
    primaryContainer = ChargeOrangeContainer,
    onPrimaryContainer = ChargeWhite,

    secondary = ChargeOrange,
    onSecondary = ChargeBlack,
    secondaryContainer = ChargeSurfaceGray,
    onSecondaryContainer = ChargeWhite,

    tertiary = ChargeOrange,
    onTertiary = ChargeBlack,
    tertiaryContainer = ChargeSurfaceGray,
    onTertiaryContainer = ChargeWhite,

    background = ChargeBlack,
    onBackground = ChargeWhite,
    surface = ChargeDarkGray,
    onSurface = ChargeWhite,
    surfaceVariant = ChargeSurfaceGray,
    onSurfaceVariant = ChargeSoftWhite,

    outline = ChargeOutlineGray,
    outlineVariant = ChargeSurfaceGray,

    error = ChargeError,
    onError = ChargeBlack,
    errorContainer = ChargeSurfaceGray,
    onErrorContainer = ChargeWhite,

    inverseSurface = ChargeSoftWhite,
    inverseOnSurface = ChargeBlack,
    inversePrimary = ChargeOrange,
    scrim = ChargeBlack
)

@Composable
fun ChargeBellTheme(content: @Composable () -> Unit) {
    // darkTheme, isSystemInDarkTheme, Dynamic Color 분기를 제거해 항상 같은 다크 테마만 적용한다.
    MaterialTheme(
        colorScheme = ChargeBellDarkColorScheme,
        typography = Typography,
        content = content
    )
}
