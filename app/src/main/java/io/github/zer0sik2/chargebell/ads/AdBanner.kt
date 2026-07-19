package io.github.zer0sik2.chargebell.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import io.github.zer0sik2.chargebell.R

/**
 * 화면 하단에 표시하는 적응형(adaptive) 배너 광고.
 *
 * 적응형 배너는 기기 화면 너비에 맞춰 Google이 최적 높이를 계산해주는 방식으로,
 * 고정 크기 배너보다 수익과 화면 활용 면에서 권장되는 현재 표준이다.
 *
 * 반드시 AdsManager의 초기화가 끝난 뒤(onAdsReady 이후)에만 composition에 넣어야 한다.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        context,
                        screenWidthDp
                    )
                )
                adUnitId = context.getString(R.string.admob_banner_unit_id)
                loadAd(AdRequest.Builder().build())
            }
        },
        // 화면에서 사라질 때 AdView 내부 WebView 리소스를 해제한다.
        onRelease = { adView -> adView.destroy() }
    )
}
