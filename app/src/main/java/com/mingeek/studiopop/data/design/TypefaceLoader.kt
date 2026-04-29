package com.mingeek.studiopop.data.design

import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * [FontPack] id 를 [Typeface] 로 변환. 우선순위:
 *  1. AssetSource.Bundled — `assets/fonts/...` 파일이 있으면 [Typeface.createFromAsset]
 *  2. 시스템 폰트 변형 fallback — id 의 의미에 맞춰 가장 가까운 시스템 typeface 매핑
 *
 * R6 자산 라운드 단계에선 ttf 파일 없이도 시각 변화가 보이도록 fallback 매핑을 정성껏:
 *  - DISPLAY_TITLE → Typeface.create(SANS_SERIF, BOLD) + (Typeface.SANS_SERIF_CONDENSED 가능)
 *  - SUBTITLE → DEFAULT_BOLD
 *  - CHARACTER → SERIF / MONOSPACE 등 변형 톤
 *  - BODY → DEFAULT
 *
 * 캐시: 같은 id 반복 조회는 동일 [Typeface] 인스턴스 반환. AssetManager 호출은 비싸므로 첫 회만.
 *
 * 글로벌 ApplicationContext 1개로 충분 — Application 라이프사이클과 동일.
 *
 * ### TODO — OFL 한글 폰트 드롭 (외부 결정 후)
 * 1. SIL OFL 라이선스 한글 ttf 확보 — 권장: Pretendard(SUBTITLE/BODY), Black Han Sans /
 *    Jalnan(DISPLAY_TITLE), Gamja Flower / DOSGothic(CHARACTER)
 * 2. 파일을 `app/src/main/assets/fonts/<family>-Regular.ttf`, `-Bold.ttf` 등으로 배치
 * 3. [FontPack] 정의에서 각 weight 슬롯의 [AssetSource.Bundled] path 를 일치시킴 — 이 클래스의
 *    resolve() 가 자동으로 createFromAsset 시도, 실패 시 시스템 fallback (코드 변경 불필요)
 * 4. ttf 추가 후 [invalidate] 호출 또는 앱 재시작으로 캐시 무효화
 */
class TypefaceLoader(private val appContext: Context) {

    private val cache = ConcurrentHashMap<String, Typeface>()

    /**
     * [pack] 의 weight 에 가장 가까운 Typeface. weight 가 없으면 [defaultWeight] 사용.
     * 자산 로드 실패해도 NPE 없이 시스템 fallback 으로 안전하게.
     */
    fun typeface(pack: FontPack, defaultWeight: Int = 700): Typeface {
        val cacheKey = "${pack.id}@$defaultWeight"
        cache[cacheKey]?.let { return it }
        val resolved = resolve(pack, defaultWeight)
        cache[cacheKey] = resolved
        return resolved
    }

    private fun resolve(pack: FontPack, weight: Int): Typeface {
        // 1) Bundled asset 로드 시도 — weight 에 가장 가까운 항목
        val bundled = pack.weights.entries
            .minByOrNull { kotlin.math.abs(it.key - weight) }
            ?.value as? AssetSource.Bundled
        if (bundled != null) {
            runCatching {
                Typeface.createFromAsset(appContext.assets, bundled.assetPath)
            }.getOrNull()?.let { return it }
        }
        // 2) 시스템 폰트 변형 fallback
        return systemFallback(pack, weight)
    }

    private fun systemFallback(pack: FontPack, weight: Int): Typeface {
        val isBold = weight >= 700
        val recommendedFor = pack.recommendedFor.firstOrNull()
        return when (recommendedFor) {
            FontPack.UseCase.DISPLAY_TITLE -> {
                // 굵고 큰 디스플레이 톤 — sans-serif bold + condensed 가능
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            FontPack.UseCase.SUBTITLE -> {
                Typeface.create(Typeface.DEFAULT, if (isBold) Typeface.BOLD else Typeface.NORMAL)
            }
            FontPack.UseCase.CHARACTER -> {
                // 캐릭터/뽑기 톤 — serif 또는 monospace 변형으로 차별화
                if (pack.id.contains("retro") || pack.id.contains("arcade")) {
                    Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                } else {
                    Typeface.create(Typeface.SERIF, Typeface.BOLD)
                }
            }
            FontPack.UseCase.BODY -> {
                Typeface.create(Typeface.DEFAULT, if (isBold) Typeface.BOLD else Typeface.NORMAL)
            }
            null -> {
                Typeface.create(Typeface.DEFAULT, if (isBold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /** 캐시 비움 — 자산 파일이 동적으로 추가/제거됐을 때 호출. */
    fun invalidate() {
        cache.clear()
    }
}
