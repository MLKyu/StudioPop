# Phase 8 — STT 엔진 다중화 (무료 옵션 추가)

자막 생성에 **3개 엔진을 선택**할 수 있도록 추상화.

## 엔진 비교

| 엔진 | 비용 | 설치 | 한국어 품질 | 인터넷 | 처리 속도 |
|---|---|---|---|---|---|
| **OpenAI Whisper API** | $0.006/분 (≈₩80/10분) | API 키만 | 최상 | 필요 | 빠름 (서버) |
| **Vosk (온디바이스)** | ₩0 영구 | 모델 50MB 자동 다운로드 | 중상 | 첫 다운로드만 | 보통 (CPU) |
| **whisper.cpp (온디바이스)** | ₩0 영구 | NDK 빌드 + 모델 75MB+ | 상 | 모델만 | 느림 (CPU) |

## 추가된 코드

### 추상화
- `data/caption/SpeechToText.kt` — 인터페이스 + `SttEngine` enum + `Availability` sealed
- `data/caption/SttRegistry.kt` — 엔진 묶음

### 어댑터
- `data/caption/WhisperApiEngine.kt` — 기존 `ChunkedTranscriber` 래핑
- `data/caption/VoskTranscriber.kt` — Vosk Recognizer + JSON 단어 결과를 Cue 로 그룹핑
- `data/caption/WhisperCppEngine.kt` — **스캐폴딩만**. `isAvailable()` 가 `NeedsSetup` 반환

### 지원 코드
- `data/caption/PcmDecoder.kt` — 영상 → PCM 16-bit mono @ 16kHz
  (MediaExtractor + MediaCodec + 멀티채널 mixdown + 선형 보간 리샘플)
- `data/caption/VoskModelManager.kt` — 첫 사용 시 vosk-model-small-ko-0.22.zip
  자동 다운로드 (50MB) + 압축해제 + `filesDir/vosk/` 캐시

### UI
- `CaptionViewModel` — `selectedEngine` 상태 + `engineOptions` (가용성 평가 결과)
- `CaptionScreen` — 라디오 버튼 그룹, 각 엔진 옆에 가용성/안내 문구

### 의존성
- `com.alphacephei:vosk-android:0.3.47` — Maven Central, JNI .so 포함

## Vosk 사용 흐름

```
사용자가 "Vosk" 선택 → "자막 생성" 탭
   ↓
VoskModelManager.ensureInstalled()
   ├─ 모델 없음? → 50MB 다운로드 → 압축해제 → 캐시
   └─ 있음? → 즉시 진행
   ↓
PcmDecoder.decode(uri, 16000)
   → MediaExtractor 로 오디오 트랙 분리
   → MediaCodec 으로 디코드
   → 멀티채널이면 평균 mixdown
   → 선형 보간으로 16kHz 리샘플
   → ShortArray 반환
   ↓
Recognizer(model, 16000f).setWords(true)
4000 샘플(0.25초) 단위로 acceptWaveForm()
세그먼트 끝나면 result JSON 파싱 → 단어 누적
   ↓
groupIntoCues(words):
   - 7단어씩 묶거나
   - 단어 사이 0.8초 이상 무음이면 새 Cue
   ↓
List<Cue> 반환 (기존 SRT/타임라인과 호환)
```

## whisper.cpp 통합 — 다음 단계 (TODO)

스캐폴딩만 들어가 있고 `NotImplementedError` 반환. 완성하려면:

### 1. 네이티브 빌드 환경
```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17"; arguments += listOf("-DGGML_USE_CPU=ON") }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
}
```

### 2. 소스 추가
```
app/src/main/cpp/
├── CMakeLists.txt
├── whisper/                 ← https://github.com/ggerganov/whisper.cpp/ 의 src 일부
│   ├── whisper.cpp / whisper.h
│   └── ggml*.{c,h}
└── jni_bridge.cpp           ← 자체 작성: JNI wrapper
```

### 3. JNI 시그니처 (Kotlin 측)
```kotlin
class WhisperCppEngine {
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, sampleRate: Int): String
    private external fun nativeRelease(handle: Long)

    init { System.loadLibrary("whisper_jni") }
}
```

### 4. 모델 다운로드 매니저
- 옵션: `ggml-tiny.bin` (~75MB) / `ggml-base.bin` (~150MB) / `ggml-small.bin` (~466MB)
- 한국어는 base 이상 권장
- VoskModelManager 와 같은 패턴으로 작성

### 5. 입력 변환
- whisper.cpp 는 **float[] PCM 16kHz mono** 입력
- 현재 `PcmDecoder` 는 `ShortArray` 반환 → `Short.toFloat() / 32768f` 변환 추가

### 6. 출력 파싱
- `whisper_full_n_segments(ctx)` → 세그먼트 수
- `whisper_full_get_segment_text(ctx, i)` → 텍스트
- `whisper_full_get_segment_t0/t1(ctx, i)` → 시작/끝 시각 (단위: 10ms)
- 각 세그먼트가 곧바로 Cue 1개

### 예상 작업량
별도 세션 4~6 시간 (NDK 환경 디버깅 포함). 본격 통합 전 Android Studio 의 NDK 가 설치되어 있는지 (`SDK Manager → SDK Tools → NDK (Side by side)`) 확인 필요.

## 한계 및 주의사항

### Vosk
- **모델은 첫 실행 시 50MB 다운로드** — 와이파이 권장. 이후 캐시
- 인식 정확도: 일상 대화·뉴스 풍은 좋음, 전문 용어/슬랭은 약함
- 발화 사이 0.8초 이상 침묵을 큐 분할 기준으로 사용 — 톤이 빠른 영상은 큐가 길어질 수 있음
- 모델 로드는 RAM 100MB+ 사용 — 저사양 기기에서는 OOM 가능

### Whisper API
- 25MB 파일 한도 → `AudioChunker` 가 8분 단위 자동 분할
- API 호출 비용 발생 (한 영상당 보통 ₩수백 ~ 수천)
- 인터넷 필수

### 공통
- 한국어 외 언어는 `language` 파라미터로 지정 (`en`, `ja` 등). 빈 값이면 자동 감지
