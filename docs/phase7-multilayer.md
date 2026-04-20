# Phase 7 — Tier 2 (스타일 프리셋, 멀티 레이어, 전환, BGM)

Phase 6 의 타임라인 편집기 위에 KineMaster/CapCut 의 Tier 2 기능들 적용.

## 새 기능 요약

| 기능 | 설명 |
|---|---|
| 🎨 **자막 스타일 프리셋** | 5개 내장 (Clean / Vlog / 뽑기 / Shorts / Minimal). 색·굵기·배경 투명도 즉시 교체 |
| 📐 **자막 위치·크기 조절** | Slider 로 세로 위치(anchorY) 와 크기 배율 조정 |
| 🖼️ **텍스트 레이어** | 자막과 독립된 오버레이 (타이틀/CTA/강조용). 같은 에디터 시트에서 스타일 적용 |
| 🌓 **세그먼트 전환** | 경계에서 페이드-투-블랙 (기본 400ms). 칩으로 on/off |
| 🎵 **BGM 트랙** | 별도 오디오 파일 선택 → 원본 오디오 제거 + BGM 루프 |

## 데이터 모델 변경

```kotlin
data class CaptionStyle(preset, anchorY, sizeScale)  // 5 presets via CaptionPreset
data class TimelineCaption(..., style: CaptionStyle)
data class TextLayer(id, sourceStart, sourceEnd, text, style)
data class TransitionSettings(enabled, durationMs)
data class AudioTrack(uri, replaceOriginal)

data class Timeline(
    segments, captions, 
    textLayers,        // 신규
    transitions,       // 신규
    audioTrack,        // 신규
)
```

## 렌더 파이프라인 (exportTimeline)

```
[세그먼트들]
   ↓ concat (EditedMediaItemSequence, removeAudio=true if BGM)
[비디오 시퀀스]   + [BGM 시퀀스 (looping)]   ← 2개 sequence 를 Composition 에
   ↓
Composition.Builder(sequences).setEffects(
  - Presentation (aspectRatio 있을 때)
  - OverlayEffect(
      CaptionOverlay(cues_style1, style1),   ← style 별 그룹
      CaptionOverlay(cues_style2, style2),
      ...
      CaptionOverlay(textLayers_style1, ...),
      ...
      FadeAtBoundariesOverlay(boundaries)    ← transitions.enabled 일 때
    )
)
   ↓ Transformer.start
output.mp4
```

## 핵심 구현 포인트

- **스타일별 오버레이 분리**: `TextOverlay` 는 인스턴스당 한 가지 스타일(배경 alpha, 색 등) 만 나타냄. 따라서 같은 스타일의 자막끼리 묶어 하나의 `CaptionOverlay` 로 만든 뒤 `OverlayEffect` 에 여러 개를 `ImmutableList` 로 전달
- **source-time 보존**: 자막/텍스트는 항상 원본 영상 시각 기준으로 저장. 익스포트 시 세그먼트 구성에 맞춰 output-time 으로 재계산 (여러 세그먼트에 걸치면 조각으로 분리)
- **페이드 전환**: 64×64 검정 Bitmap 오버레이의 alphaScale 을 시간에 따라 0→1→0 으로 변화. 스케일 100x로 프레임 전체 덮음. 진짜 크로스페이드(Phase 8) 는 아니고 cut-to-black 방식
- **BGM Phase 7 는 "대체" 만**: 볼륨 믹싱은 Phase 8. `setRemoveAudio(true)` + 오디오 전용 `EditedMediaItem(setRemoveVideo=true)` 를 `EditedMediaItemSequence(isLooping=true)` 로 Composition 에 2번째 시퀀스로 추가

## UI 추가

- **툴바**: 자막 · 텍스트(신규) · 분할 · 삭제 · 재생
- **옵션 행**: 전환 칩 · BGM 선택 칩(+해제) · 텍스트 레이어 수 · 영상 변경 · SRT
- **편집 시트**: 5개 스타일 FilterChip, 세로 위치/크기 Slider
- 편집 시트는 자막/텍스트 공용 (`EditableTextItem`), 상단 제목만 다름

## Phase 7 한계 / Phase 8 로 미룬 것

- **진짜 크로스페이드 전환** (두 클립이 겹치며 점진 전환)
- **BGM 오디오 믹싱** (원본 + BGM 동시, 볼륨 슬라이더)
- **자막 애니메이션** (타이핑 효과, 팝업)
- **키프레임**
- **크로마키 / 블렌드 모드**
- **프리뷰 화면에 자막 렌더** — 현재 PlayerView 에 자막이 보이지 않음. export 결과에만 보임
- **Compose 프리뷰 캡션 오버레이** (별도 과제)

## 다음 Phase

- **Phase 8 (Tier 3)**: 크로마키, 키프레임, 블렌드 모드, AI 효과, 템플릿. Media3 커스텀 `GlEffect`/셰이더 프로그램 수준의 작업이라 별도 세션에서 진행 예정
