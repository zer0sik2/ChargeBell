package io.github.zer0sik2.chargebell.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import io.github.zer0sik2.chargebell.R

/**
 * 뒤로 가기로 앱을 나갈 때 보여주는 종료 확인 팝업.
 *
 * ⚠️ AdMob 정책상 "앱이 닫힌 뒤 나타나는 전면(interstitial) 광고"는 금지되어 있다.
 * 대신 앱이 아직 열려 있는 상태에서 사각형(MREC) 광고와 함께
 * 종료/취소를 묻는 이 팝업 방식은 허용되는 표준 패턴이다.
 *
 * AdsManager 초기화 완료(onAdsReady) 이후에만 composition에 넣어야 한다.
 */
@Composable
fun ExitAdDialog(
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "앱을 종료할까요?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // MREC(300x250) 고정 크기 광고. 로드 전에도 같은 공간을 확보해
                // 광고가 나타날 때 팝업 크기가 튀지 않게 한다.
                Box(
                    modifier = Modifier.size(width = 300.dp, height = 250.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            AdView(context).apply {
                                setAdSize(AdSize.MEDIUM_RECTANGLE)
                                adUnitId = context.getString(R.string.admob_exit_ad_unit_id)
                                loadAd(AdRequest.Builder().build())
                            }
                        },
                        onRelease = { adView -> adView.destroy() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "취소")
                    }
                    Button(
                        onClick = onConfirmExit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "종료")
                    }
                }
            }
        }
    }
}
