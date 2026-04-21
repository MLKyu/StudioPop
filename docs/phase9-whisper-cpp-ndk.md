# Phase 9 — whisper.cpp NDK 통합 완료

Phase 8 의 scaffolding 단계를 넘어 **whisper.cpp 가 실제로 동작**하도록 NDK + CMake + JNI bridge 통합. 무료 + 온디바이스 + Whisper 동급 정확도.

## 결과 요약

빌드 산출물:
- `app/src/main/.../libstudiopop_native.so`
  - arm64-v8a: 8.5MB (debug) / 2.3MB (stripped)
  - x86_64:    8.1MB (debug) / 2.3MB (stripped)

이제 자막 만들기 화면의 "whisper.cpp" 옵션이 라디오 버튼으로 활성화되며, 실제로 인식 가능합니다.

## 추가/변경된 것

### 빌드 인프라
- `app/build.gradle.kts`:
  - `ndkVersion = "27.0.12077973"`
  - `defaultConfig.ndk.abiFilters` = arm64-v8a, x86_64 (32-bit 제외)
  - `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }`
- `app/src/main/cpp/CMakeLists.txt` (신규):
  - `FetchContent` 로 whisper.cpp v1.7.4 자동 다운로드 (재현성 위해 고정 태그)
  - Android 부적합 옵션 비활성: CUDA / Metal / BLAS / OPENMP / OPENCL / VULKAN
  - `BUILD_SHARED_LIBS=OFF` → whisper / ggml 정적 빌드, 우리 .so 에 흡수

### JNI Bridge
- `app/src/main/cpp/jni_bridge.cpp` (신규):
  - `nativeInit(modelPath)` → whisper_context handle
  - `nativeTranscribe(handle, FloatArray pcm, sampleRate, language)` → segments JSON
  - `nativeRelease(handle)`
  - `whisper_full_default_params(GREEDY)` 기반, no_context/suppress_blank 켬
  - 결과 JSON: `{"segments":[{"t0_ms":..,"t1_ms":..,"text":".."}]}`

### Kotlin
- `data/caption/WhisperCppEngine.kt` (실구현으로 교체):
  - `System.loadLibrary("studiopop_native")` (실패 허용)
  - PcmDecoder.decode (Short PCM) → Float 정규화 → JNI
  - segments JSON 을 Cue 리스트로 변환
- `data/caption/WhisperCppModelManager.kt` (신규):
  - HuggingFace `ggerganov/whisper.cpp` 에서 GGML .bin 자동 다운로드
  - Variant: TINY (75MB) / BASE (142MB, 기본) / SMALL (466MB)
  - `filesDir/whisper-cpp/` 에 캐시
- `AppContainer`: 신규 객체들 wiring

## 동작 흐름

```
사용자 "whisper.cpp" 선택 → 자막 생성
    ↓
WhisperCppModelManager.ensureInstalled()
    └ ggml-base.bin 없으면 142MB 다운로드 (와이파이 권장)
    ↓
PcmDecoder.decode(uri, 16000) → ShortArray
    ↓
ShortArray.map { it / 32768f } → FloatArray
    ↓
nativeInit(modelPath) → context handle
    ↓
nativeTranscribe(handle, pcm, 16000, "ko") → segments JSON
    ↓
nativeRelease(handle)
    ↓
JSON 파싱 → List<Cue>
```

## 처리 시간 가늠

| 영상 길이 | tiny  | base  | small |
|---|---|---|---|
| 1분  | ~5초 | ~10초 | ~30초 |
| 10분 | ~50초 | ~2분 | ~5분 |
| 1시간 | ~5분 | ~12분 | ~30분 |

(실측치는 기기 CPU 에 따라 다름. arm64 + 최신 SoC 기준 추정)

## 첫 빌드가 오래 걸리는 이유

CMake `FetchContent` 가 처음에 GitHub 에서 whisper.cpp 전체를 git clone (shallow) 하고 ggml + whisper 의 .c/.cpp 파일 수십 개를 ABI 별로 전부 컴파일합니다. 이후 빌드는 캐시 사용해서 빠름 (변경 없으면 .so 재사용).

```
첫 빌드      : 약 1~3분 (네트워크 + CXX compile)
이후 빌드     : 5~10초 (캐시 활용)
clean 후 재빌드 : 다시 1~3분
```

## 알려진 경고 (무시 가능)

- `wstring_convert deprecated` — whisper.cpp 내부 C++17 코드, NDK 31+ 표준에서 deprecated 였지만 동작에 문제 없음
- `Unable to strip libjnidispatch.so` — Vosk 의 JNA 라이브러리, 디버그 심볼이 없어서 strip 안 되는 정상 동작

## 모델 비교 (Korean 기준)

| 파일 | 크기 | 한국어 정확도 | 추천 용도 |
|---|---|---|---|
| `ggml-tiny.bin` | 75MB | 낮음 — 키워드 위주 | 테스트용 |
| `ggml-base.bin` | 142MB | 중상 — 문장 의미 통함 (기본값) | 일반 사용 |
| `ggml-small.bin` | 466MB | 상 — Whisper API 와 거의 동급 | 정확도 중시 |

WhisperCppModelManager 의 `Variant` 파라미터로 선택. 현재 기본 = BASE.
사용자가 모델 변경하려면 AppContainer 에서 `Variant.SMALL` 등으로 바꿔서 빌드.

## 한계

- **첫 다운로드**: 142MB (기본 BASE 모델). 와이파이 환경에서 권장
- **메모리**: BASE 모델 로드 시 RAM ~250MB 사용. 저사양 기기에서는 OOM 가능
- **처리 속도**: API 보다 5~10배 느림. 5분짜리 영상 = 1분 처리 대기
- **언어 자동 감지**: `language=""` 로 호출 시 활성화되지만 정확도가 명시 지정보다 낮음

## 다음 개선 방향 (Phase 10 후보)

- 모델 선택 UI (TINY/BASE/SMALL 라디오)
- 진행률 더 세밀하게 (whisper_full 의 progress callback 후킹)
- INT8 양자화 모델 (`ggml-base.q5_0.bin` 등) 옵션 — 정확도 거의 같으면서 50% 작음/빠름
- Cancellation 지원 (현재는 인식 시작하면 끝까지)
