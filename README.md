# StudioPop

Android super-app for YouTube creators — auto-captions, trim & subtitle burn-in, 9:16 shorts, AI thumbnails, one-tap upload.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/AndroidX%20Media3-1.4.1-34A853?logo=android&logoColor=white)](https://developer.android.com/media/media3)
[![minSdk](https://img.shields.io/badge/minSdk-24-A4C639?logo=android&logoColor=white)](https://apilevels.com/)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-A4C639?logo=android&logoColor=white)](https://apilevels.com/)

## Overview

한 프로젝트 안에서 **자막 → 편집 → 숏츠 → 썸네일 → 업로드** 까지 끊김 없이 이어지는 Android 앱입니다. 각 단계의 산출물(SRT·MP4·PNG·videoId) 은 Room DB 에 Asset 으로 저장되고, 다음 단계가 자동으로 불러다 씁니다.

```
촬영본 임포트 → Whisper 자동 자막 → Media3 트림·번인 → 9:16 숏츠 크롭
              ↘                                                   ↘
              썸네일 프레임 추출 + Claude Haiku 카피 ──────────────→ YouTube Data API 업로드 + thumbnails.set
```

## Features

| 기능 | 설명 |
|---|---|
| 📝 **자동 자막** | OpenAI Whisper 로 전사, 긴 영상은 8분 단위 청크 자동 분할 + 타임스탬프 오프셋 보정 |
| ✂️ **트림 편집** | RangeSlider 로 시작/끝 지정, Media3 Transformer 하드웨어 가속 렌더링 |
| 🎯 **자막 번인** | `TextOverlay` 의 `getText(presentationTimeUs)` 오버라이드로 프레임별 동적 자막 |
| 📱 **숏츠** | `Presentation.createForAspectRatio(9f/16f, LAYOUT_SCALE_TO_FIT_WITH_CROP)` center-crop + 60초 제한 |
| 🖼️ **썸네일** | `MediaMetadataRetriever` 프레임 추출 + Canvas 텍스트 합성 (1280×720 PNG) |
| 🤖 **AI 카피 제안** | Anthropic Claude Haiku 4.5 로 메인 카피 후보 5개 자동 생성 |
| ⬆️ **원탭 업로드** | YouTube Data API v3 resumable 업로드 + `thumbnails.set` 자동 첨부 |
| 🗂️ **프로젝트 관리** | Room DB 로 산출물을 Project/Asset 으로 묶어 원스톱 플로우 제공 |

## Tech Stack

- **UI**: Jetpack Compose + Material3 + Navigation Compose
- **DB**: Room (KSP)
- **Network**: OkHttp + Retrofit + Moshi
- **Video**: AndroidX Media3 Transformer (하드웨어 가속 MediaCodec 기반)
- **Audio**: MediaExtractor + MediaMuxer (재인코딩 없는 m4a 분할)
- **AI**: OpenAI Whisper (자막) + Anthropic Claude Haiku 4.5 (카피)
- **Auth**: Google Identity Services `AuthorizationClient` (YouTube OAuth scope)
- **Persistence**: DataStore Preferences (토큰) + Room (프로젝트/에셋)
- **DI**: 수동 (AppContainer) — 복잡도 커지면 Hilt 이관 예정

## Architecture

```
app/
├── AppContainer.kt            ─ 수동 DI 컨테이너 (OkHttp, Moshi, Room, 각 매니저·리포지토리)
├── MainActivity.kt            ─ Compose setContent → AppNavHost
│
├── data/
│   ├── auth/                  ─ GoogleAuthManager, AuthTokenStore (DataStore)
│   ├── caption/               ─ AudioExtractor / AudioChunker / WhisperClient
│   │                             / ChunkedTranscriber / Srt (parser+writer)
│   ├── editor/                ─ VideoEditor (Media3 Transformer 래퍼)
│   │                             / CaptionOverlay (TextOverlay 동적 렌더)
│   ├── thumbnail/             ─ FrameExtractor / ThumbnailComposer (Canvas)
│   │                             / ClaudeCopywriter (Anthropic API)
│   ├── youtube/               ─ YouTubeUploader (resumable + thumbnails.set)
│   │                             / ProgressRequestBody (진행률 추적)
│   └── project/               ─ Room: Project·Asset entity / DAO / DB / Repository
│
└── ui/
    ├── AppNavHost.kt          ─ NavHost + projectId 쿼리 파라미터 라우팅
    ├── home/                  ─ HomeScreen (원스톱 + 빠른 도구 메뉴)
    ├── project/               ─ ProjectList / ProjectDetail
    ├── caption/               ─ CaptionScreen + VM (bindProject 로 프로젝트 연동)
    ├── editor/                ─ EditorScreen + VM
    ├── shorts/                ─ ShortsScreen + VM
    ├── thumbnail/             ─ ThumbnailScreen + VM
    └── upload/                ─ UploadScreen + VM (썸네일 자동 첨부)
```

## Getting Started

### 1. Prerequisites

- Android Studio Koala Feature Drop (2024.1.2) 이상
- JDK 17
- Android SDK 36 (`compileSdk`), minSdk 24

### 2. API keys

`local.properties` 루트에 아래 두 키를 추가하세요 (이 파일은 `.gitignore` 에 포함됩니다):

```properties
# OpenAI Whisper — 자막 전사
OPENAI_API_KEY=sk-...

# Anthropic Claude — 썸네일 카피 제안 (생략 시 "카피 제안" 기능만 비활성화)
ANTHROPIC_API_KEY=sk-ant-...
```

### 3. Google Cloud Console (YouTube 업로드용)

`docs/phase1-oauth-setup.md` 에 단계별 가이드 있습니다. 요약:

1. GCP 프로젝트 생성
2. **YouTube Data API v3** 활성화
3. OAuth 동의 화면 구성 (scope: `youtube.upload`, `youtube.readonly`)
4. OAuth 클라이언트 ID 생성 — type: **Android**, package `com.mingeek.studiopop`, SHA-1 붙여넣기

SHA-1 추출:
```bash
keytool -keystore ~/.android/debug.keystore -list -v \
  -alias androiddebugkey -storepass android -keypass android
```

### 4. Build & Run

```bash
# Android Studio 에서 Sync Project, 또는 CLI:
./gradlew :app:assembleDebug

# 기기 연결 후 설치
./gradlew :app:installDebug
```

> ⚠️ **에뮬레이터는 Google Play Services 가 포함된 시스템 이미지**여야 OAuth 가 동작합니다.

## Cost Reference (2026 기준)

| 항목 | 단가 | 예시 |
|---|---|---|
| Whisper 자막 | $0.006/분 | 10분 영상 = **₩80** |
| Claude Haiku 4.5 카피 | 입력 $0.80/M, 출력 $4/M | 1 요청 ≈ **₩2** |
| YouTube Data API | 무료 (일 10,000 유닛) | 업로드 1건 = 1,600 유닛 |
| 개인 크리에이터 월 사용 | 약 — | 월 **₩1,000 이하** 수준 |

## Roadmap

- [ ] WorkManager 로 업로드/렌더링 이관 (앱 백그라운드에서도 지속, 알림 진행률)
- [ ] Whisper 발화 밀도 기반 숏츠 하이라이트 자동 추천
- [ ] 얼굴/피사체 감지 기반 9:16 스마트 크롭
- [ ] 여러 클립 concat (Media3 `Composition.Builder`)
- [ ] 자막 스타일 프리셋 (폰트/색상/위치)
- [ ] 썸네일 A/B 테스트용 다중 변형
- [ ] Hilt 로 DI 이관 (의존성 트리 커지면)

## Documentation

각 Phase 에 대한 설정·원리·제약 설명이 `docs/` 에 있습니다:

- [`docs/phase1-oauth-setup.md`](docs/phase1-oauth-setup.md) — YouTube OAuth · 업로드
- [`docs/phase2-caption-setup.md`](docs/phase2-caption-setup.md) — Whisper 자막
- [`docs/phase3-editor-setup.md`](docs/phase3-editor-setup.md) — 편집 · 자막 번인
- [`docs/phase4-thumbnail-shorts.md`](docs/phase4-thumbnail-shorts.md) — 썸네일 · 숏츠
- [`docs/phase5-projects.md`](docs/phase5-projects.md) — Room 기반 원스톱 플로우

## Design Decisions

- **FFmpeg-Kit 대신 AndroidX Media3 Transformer**
  FFmpeg-Kit 는 2025-04 에 아카이브되어 유지되지 않습니다. Media3 는 Google 이 적극 유지하고, `MediaCodec` 기반 하드웨어 가속을 기본 제공하며, APK 에 추가되는 크기도 ~10MB 로 FFmpeg-Kit(~30-100MB) 대비 훨씬 가볍습니다.

- **오디오 추출·분할은 네이티브 `MediaExtractor` + `MediaMuxer`**
  AAC 샘플을 재인코딩 없이 MP4 컨테이너에 다시 써 내므로 품질 손실이 없고 훨씬 빠릅니다. FFmpeg 도입을 미룰 수 있는 주요 이유.

- **수동 DI (AppContainer) 사용**
  초기 학습 곡선을 줄이기 위해 Hilt 없이 `lazy` 기반 컨테이너로 시작. 의존성 관리가 복잡해지면 Hilt 로 점진적 이관.

- **Whisper 25MB 제한은 "청크 후 오프셋 보정"으로 회피**
  `AudioChunker` 가 시간 기준으로 m4a 분할 → 청크별 전사 → SRT 타임스탬프에 `chunk.startMs` 를 더해 원본 기준으로 병합.

## License

[MIT](LICENSE) © 2026 MLKyu

---

<sub>Built with ❤️ for creators who'd rather edit less and publish more.</sub>
