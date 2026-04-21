package com.mingeek.studiopop.data.caption

import android.net.Uri

/**
 * whisper.cpp 온디바이스 엔진 — 스캐폴딩.
 *
 * 통합 단계:
 *  1. (TODO) whisper.cpp 소스 추가 → app/src/main/cpp/whisper/
 *  2. (TODO) CMakeLists.txt 작성 + app/build.gradle.kts 의 externalNativeBuild
 *     블록 활성화
 *  3. (TODO) JNI bridge: whisper_init / whisper_full / whisper_free
 *  4. (TODO) 모델 다운로드 매니저 (ggml-tiny.bin ~75MB 또는 ggml-base.bin ~150MB)
 *  5. (TODO) PCM 16kHz mono float[] 로 변환 후 inference 호출
 *  6. (TODO) segments 파싱 → Cue 리스트
 *
 * 일단 NDK 빌드 환경 구성 전에는 NotImplementedError 로 명확히 차단.
 * UI 에서 isAvailable() 가 NeedsSetup 을 반환하므로 사용자가 선택해도
 * 친절한 안내가 뜸.
 */
class WhisperCppEngine : SpeechToText {

    override val id: SttEngine = SttEngine.WHISPER_CPP

    override suspend fun isAvailable(): SpeechToText.Availability =
        SpeechToText.Availability.NeedsSetup(
            reason = "whisper.cpp 네이티브 빌드(NDK + CMake) 가 아직 통합되지 않음. " +
                    "다음 작업 단계로 예정. 지금은 Vosk 또는 Whisper API 를 사용하세요.",
            actionable = false,
        )

    override suspend fun transcribe(
        videoUri: Uri,
        language: String?,
        onProgress: (SpeechToText.Progress) -> Unit,
    ): Result<List<Cue>> = Result.failure(
        NotImplementedError("whisper.cpp 엔진 미구현 — Vosk 혹은 Whisper API 선택 필요")
    )
}
