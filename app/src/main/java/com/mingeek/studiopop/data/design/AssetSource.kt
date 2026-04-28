package com.mingeek.studiopop.data.design

import android.net.Uri

/**
 * 디자인 자산의 물리적 소스. 자산은 세 가지 경로 중 하나로 로드된다.
 *
 * - Bundled: assets/ 디렉토리에 패킹된 무료 기본 자산. 앱 빌드와 함께 배포.
 * - UserImported: 사용자가 가져온 파일. 라이브러리에 복사된 file:// URI.
 * - Remote: 다운로드 가능한 자산 (저작권 라이센스 확인된 CDN). 첫 사용 시 캐시.
 *
 * R1 단계에선 등록된 자산이 거의 없음 — 자산 큐레이션은 사용자 라이센스 결정 후 진행.
 */
sealed interface AssetSource {

    data class Bundled(val assetPath: String) : AssetSource

    data class UserImported(val uri: Uri) : AssetSource

    data class Remote(val url: String, val checksum: String? = null) : AssetSource
}
