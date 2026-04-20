# Phase 6 — 편집기 v2 (CapCut/KineMaster 하이브리드 Tier 1)

기존 "단일 RangeSlider + SRT import" 에디터를 **본격 타임라인 에디터**로 전면 재작성.

## 새로 추가된 핵심 기능 (Tier 1)

| 기능 | 설명 |
|---|---|
| 🎬 **재생 미리보기** | ExoPlayer + `AndroidView(PlayerView)`. 타임라인 세그먼트를 MediaItem 리스트로 자동 concat 재생 |
| 🎞️ **썸네일 타임라인** | `MediaMetadataRetriever.getScaledFrameAtTime` 으로 30프레임 스트립 생성 → 세그먼트별 분배 렌더 |
| ✂️ **플레이헤드 분할 (Split)** | 현재 플레이헤드 출력 시각에서 활성 세그먼트를 두 조각으로 |
| 🗑️ **세그먼트 삭제** | 선택한 세그먼트를 타임라인에서 제거 (최소 1개 유지) |
| 📝 **인라인 자막 에디터** | ModalBottomSheet 로 자막 텍스트·시작·끝 편집. 플레이헤드 위치에 새 자막 추가 |
| ↔️ **플레이헤드 드래그** | 타임라인 탭·드래그로 탐색, ExoPlayer seek 자동 동기화 |

## 데이터 모델

```kotlin
data class TimelineSegment(id, sourceStartMs, sourceEndMs)   // 원본 영상의 한 조각
data class TimelineCaption(id, sourceStartMs, sourceEndMs, text) // 자막 (원본 시각 기준)
data class Timeline(segments, captions) {
    fun splitAtOutputMs(outputMs): Timeline       // 현재 출력 시각에서 세그먼트 분할
    fun deleteSegment(id): Timeline
    fun mapOutputToSource(outputMs): (Segment, Long)?
    fun mapSourceToOutput(sourceMs): Long?
    fun outputDurationMs: Long                    // 남은 세그먼트 합
}
```

- 자막은 **원본 기준(source-time)** 으로 저장되므로 세그먼트를 자르고 지워도 의미가 유지됨
- export 시 `toOutputCues()` 가 세그먼트 오버랩을 계산해 **출력 타임라인 기준으로 재매핑**

## Export 파이프라인 (exportTimeline)

```
Timeline.segments
   ↓ per-segment: MediaItem + ClippingConfiguration(start,end)
List<EditedMediaItem>
   ↓
EditedMediaItemSequence
   ↓
Composition.Builder + Effects(CaptionOverlay(outputCues))
   ↓
Transformer.start(composition, outPath)
   ↓
output.mp4 (세그먼트 concat + 자막 번인)
```

- 캡션은 `CaptionOverlay` (Media3 `TextOverlay` 서브클래스) 가 프레임별 presentationTime 에 맞춰 동적 렌더
- 9:16 등 비율 변환은 `Presentation.createForAspectRatio(ratio, LAYOUT_SCALE_TO_FIT_WITH_CROP)` 를 Composition 이펙트 앞에 추가

## 주요 한계 (Phase 7 에서 해결 예정)

- **세그먼트 순서 변경(드래그 리오더) 없음** — 현재는 시간 순 고정
- **멀티 레이어 없음** — 자막 = 1개 트랙만
- **자막 스타일 프리셋 없음** — 색/폰트는 코드 상수로 고정 (하단중앙/흰글씨+검정외곽)
- **전환 효과 없음** — 세그먼트 사이 컷만
- **BGM / 오디오 레이어 없음**
- 썸네일 스트립은 원본 기준으로 30프레임만 샘플링 → 매우 긴 영상은 세그먼트당 프레임 수가 적을 수 있음

## 호환성 유지

- `VideoEditor.export(EditSpec)` 기존 API 유지 — `ShortsScreen` 이 계속 사용
- `VideoEditor.exportTimeline(...)` 신규 API — `EditorScreen` 에서 호출

## 다음 Phase

- **Phase 7 (Tier 2)**: 멀티 레이어, 자막 스타일 프리셋, 전환 효과, BGM 트랙
- **Phase 8 (Tier 3)**: 키프레임, 크로마키, 블렌드 모드, AI 효과, 템플릿
