package io.github.zer0sik2.chargebell.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob 초기화와 사용자 동의(UMP) 흐름을 한 곳에서 관리한다.
 *
 * 동작 순서
 * 1. UMP에 동의 정보 갱신을 요청한다.
 * 2. 동의가 필요한 지역(EEA/영국)의 사용자에게만 동의 폼이 자동으로 표시된다.
 *    한국 등 그 외 지역에서는 폼 없이 바로 광고 요청이 가능해진다.
 * 3. 광고 요청이 가능해지면 Mobile Ads SDK를 한 번만 초기화한다.
 *
 * 이전 실행에서 이미 동의를 마친 사용자는 네트워크 지연 없이 즉시 광고를 볼 수 있도록
 * 동의 갱신과 병렬로 초기화를 진행한다(Google 공식 권장 패턴).
 */
object AdsManager {

    // MobileAds.initialize 중복 호출을 막는다.
    private val initializeCalled = AtomicBoolean(false)

    // 초기화 완료 여부. 화면 회전 등으로 Activity가 재생성되어도 유지된다.
    @Volatile
    private var mobileAdsReady = false

    /**
     * 동의 흐름을 시작하고, 광고를 요청할 수 있게 되면 [onAdsReady]를 메인 스레드에서 호출한다.
     * Activity의 onCreate에서 한 번 호출하면 된다.
     */
    fun gatherConsentAndInitialize(activity: Activity, onAdsReady: () -> Unit) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // 필요한 경우에만 동의 폼을 보여준다. 한국 사용자는 보통 이 폼을 보지 않는다.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAds(activity, onAdsReady)
                    }
                }
            },
            {
                // 네트워크 오류 등으로 동의 정보를 갱신하지 못해도
                // 이전 실행에서 저장된 동의 상태로 광고를 시도할 수 있다.
                if (consentInformation.canRequestAds()) {
                    initializeMobileAds(activity, onAdsReady)
                }
            }
        )

        // 이전 실행에서 이미 동의가 끝났다면 갱신 응답을 기다리지 않고 바로 초기화한다.
        if (consentInformation.canRequestAds()) {
            initializeMobileAds(activity, onAdsReady)
        }
    }

    private fun initializeMobileAds(context: Context, onAdsReady: () -> Unit) {
        if (mobileAdsReady) {
            onAdsReady()
            return
        }

        // 동의 경로와 병렬 경로가 동시에 들어와도 초기화는 한 번만 실행한다.
        // 콜백은 먼저 도착한 초기화 완료 시점에 한 번 전달된다.
        if (!initializeCalled.compareAndSet(false, true)) return

        MobileAds.initialize(context.applicationContext) {
            mobileAdsReady = true
            onAdsReady()
        }
    }
}
