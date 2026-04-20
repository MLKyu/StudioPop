# Phase 3 — 영상 편집 + 자막 번인 내보내기

Phase 3 에서 추가된 것:
- **긴 영상 자막**: 25MB 제한 자동 회피 (오디오를 8분 청크로 분할 → 순차 Whisper 전사 → 타임스탬프 오프셋 보정)
- **Media3 Transformer 기반 영상 편집**: 트림(start/end) + SRT 번인 → MP4 내보내기
- Home 메뉴 확장: 영상 편집 / 자막 / 업로드 3개 진입점

**FFmpeg-Kit 대신 AndroidX Media3 Transformer 를 채택한 이유**: FFmpeg-Kit 는 2025-04 에 아카이브되었고, Media3 Transformer 는 Google 이 활발히 유지하며 하드웨어 가속(MediaCodec) 을 기본으로 씀. APK 크기도 ~10MB 수준으로 훨씬 가벼움.

---

## 1. 긴 영상 자막 (청크 전사)

`AudioChunker` 가 `MediaExtractor` + `MediaMuxer` 로 오디오 트랙을 **재인코딩 없이** 8분 단위 m4a 로 분할. 각 청크를 Whisper 에 보내고, 반환된 SRT 의 타임스탬프에 `chunk.startMs` 를 더해 원본 영상 기준으로 합친다.

### 예상 비용
- 1시간 영상 = 약 8개 청크 = 약 $0.36 (₩500 내외)
- 업로드 대역폭: 청크당 수 MB (m4a 압축된 오디오만)

### 주의
- 매우 긴 청크는 여전히 25MB 를 넘을 수 있음 (비트레이트 높은 원본). 현재는 하드 에러로 실패. 필요 시 `AudioChunker.chunkDurationMs` 를 줄여서 재시도.

---

## 2. 영상 편집 / 내보내기

### 현재 가능한 조작
| 기능 | 설명 |
|---|---|
| 영상 선택 | `ActivityResultContracts.PickVisualMedia` 로 갤러리에서 1개 선택 |
| 길이 읽기 | `MediaMetadataRetriever` 로 ms 단위 길이 자동 감지 |
| 트림 | `RangeSlider` 로 시작/끝 ms 지정 |
| 자막 번인 | SRT 파일 선택 → 파싱된 Cue 를 `TextOverlay` 로 동적 렌더 |
| 내보내기 | `Transformer.start()` → MP4 생성 (진행률 실시간 표시) |
| 출력 경로 | `getExternalFilesDir(null)/edit_<timestamp>.mp4` |

### 자막 스타일 (현재)
- 하단 중앙 (NDC y = -0.8)
- 흰 글씨 + 반투명 검정 배경
- 단일 라인, 폰트 기본

커스터마이즈하려면 `CaptionOverlay.kt` 를 직접 수정:
- 폰트 크기: `TextOverlay.getTextSize()` 오버라이드
- 위치 변경: `OverlaySettings.Builder().setBackgroundFrameAnchor(x, y)` (NDC: -1..1)
- 색상/배경: `BackgroundColorSpan` / `ForegroundColorSpan` 수정

---

## 3. 사용 시나리오 (원스톱 근접)

현재 구성으로 가능한 흐름:

```
① 자막 만들기 → 영상 선택 → 자동 전사 → SRT 저장
         (출력 경로 복사해 둠)
         ↓
② 영상 편집 → 같은 영상 + ①의 SRT 불러오기 → 트림 → 내보내기
         (출력된 MP4 경로 확인)
         ↓
③ YouTube 업로드 → 편집된 MP4 업로드
```

**아직 수동으로 연결**해야 하는 부분 (Phase 5 에서 프로젝트 모델로 통합 예정):
- 자막 생성 결과가 편집 화면에 자동 주입되지 않음
- 편집 결과가 업로드 화면에 자동 주입되지 않음

---

## 4. 동작 원리

### 자막 번인 (핵심)

`CaptionOverlay` 는 `TextOverlay` 를 상속하고 `getText(presentationTimeUs)` 를 오버라이드:

```kotlin
override fun getText(presentationTimeUs: Long): SpannableString {
    val ms = presentationTimeUs / 1000
    val active = cues.firstOrNull { ms in it.startMs..it.endMs } ?: return empty
    return styledSpannable(active.text)
}
```

Transformer 가 프레임을 렌더링하면서 매 프레임의 presentationTime 을 전달 → 해당 시점에 활성화된 Cue 를 반환. 한 번 렌더된 프레임 위에 텍스트가 GPU 로 합성됨.

### 트림 & Cue 오프셋

출력 영상은 항상 `trimStartMs` 부터 시작하므로, 원본 기준 Cue 들을 `-trimStartMs` 만큼 시프트해서 `CaptionOverlay` 에 전달해야 자막이 올바른 시각에 표시됨. `VideoEditor.buildVideoEffects()` 에서 처리.

---

## 5. 제약 및 알려진 이슈

- **HDR / 10-bit 영상**: Transformer 가 SDR 로 변환할 수 있음. 기본 설정으로는 큰 문제 없지만 고급 색재현은 손상 가능
- **자막 폰트 크기**: 기본 시스템 폰트 + 기본 크기. 다국어(특히 이모지/한글 혼용) 시 fallback 이슈 가능
- **오디오 편집 없음**: BGM, 페이드 등은 Phase 4+ 에서. 현재는 원본 오디오 패스스루
- **복수 클립 연결 (concat) 없음**: 단일 영상만 편집. `Composition.Builder().addEditedMediaItem()` 을 반복 호출하면 가능 — Phase 5 에서 "뽑기 여러 컷 이어붙이기" 기능으로 구현 예정

---

## 다음 Phase 예고

**Phase 4**: 썸네일 + 숏츠 자동 생성
- 영상 대표 프레임 추출 (`MediaMetadataRetriever.getFrameAtTime`)
- Claude API 로 썸네일 카피 제안 + 텍스트 합성
- 9:16 크롭 + 하이라이트 구간 자동 추출(Whisper cue 밀도 기준) → 숏츠 MP4 생성
