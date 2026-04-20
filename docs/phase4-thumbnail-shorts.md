# Phase 4 — 썸네일 & 숏츠 자동 생성

Phase 4 에서 추가된 것:
- **썸네일 생성**: 영상에서 프레임 추출 → Canvas 로 텍스트 합성 → 1280×720 PNG 저장
- **Claude 카피 제안**: Anthropic API (Haiku 4.5) 로 썸네일 메인 카피 후보 5개 생성
- **숏츠 생성**: ≤60초 구간 + 9:16 center-crop + 선택적 자막 번인 → MP4 내보내기
- Home 메뉴: 5개 진입점 (편집 / 숏츠 / 썸네일 / 자막 / 업로드)

---

## 1. Anthropic API 키 (선택)

Claude 카피 제안 기능을 쓰려면 API 키가 필요합니다. 수동으로 카피를 입력한다면 없어도 됩니다.

1. https://console.anthropic.com/settings/keys 에서 **Create Key**
2. `local.properties` 에 추가:

```properties
ANTHROPIC_API_KEY=sk-ant-여기에_키
```

3. Gradle sync

### 비용 참고 (2026)
- Haiku 4.5: 입력 $0.80/M, 출력 $4/M 토큰
- 썸네일 카피 요청 1건 ≈ 500 입력 + 200 출력 토큰 ≈ **$0.0012** (₩1.6 내외)

---

## 2. 썸네일 만들기

### 플로우
1. **갤러리에서 영상 선택** → 길이/해상도 자동 읽기
2. **슬라이더로 프레임 위치 이동** → `MediaMetadataRetriever.getFrameAtTime` 으로 Bitmap 추출 (키프레임 기준 OPTION_CLOSEST_SYNC)
3. **메인/서브 카피 입력** (또는 **Claude 카피 제안** 탭해서 5개 중 선택)
4. **합성 미리보기** → Canvas 로 텍스트 오버레이 + 하단 그라데이션
5. **PNG 저장** → `getExternalFilesDir(null)/thumbnail_<ts>.png`

### 합성 사양
- 해상도: **1280×720** (YouTube 권장)
- 메인 카피: 하단 중앙, 96px, 굵게, 흰 글씨 + 검은 외곽선, 자동 줄바꿈
- 서브 카피: 상단 중앙, 48px
- 하단 그라데이션: 투명→검정(alpha 200)

### 커스터마이즈
`ThumbnailComposer.Spec` 에서 색상/외곽선 조정. 폰트 크기/여백은 companion object 상수 수정.

---

## 3. 숏츠 만들기

### 플로우
1. 영상 선택 → 길이 자동 감지
2. **RangeSlider** 로 구간 선택 (최대 60초 제한 — 60초 초과 시 자동으로 끝 조정)
3. **(선택) SRT 파일 첨부** + 자막 번인 토글
4. **9:16 내보내기** → Media3 Transformer 로 렌더링

### 9:16 크롭 방식
`Presentation.createForAspectRatio(9f/16f, LAYOUT_SCALE_TO_FIT_WITH_CROP)` 를 비디오 이펙트 첫 단계로 삽입. 원본이 16:9 가로 영상이면 **좌우가 잘리고 세로가 채워짐** (center-crop). 세로 영상이면 NO-OP.

### 이펙트 파이프라인 순서
```
[원본 프레임]
   ↓ Presentation (9:16 center-crop)
[크롭된 프레임]
   ↓ OverlayEffect (CaptionOverlay)
[자막 번인된 프레임]
   ↓ MediaCodec 인코딩
[output.mp4]
```

### 자막 번인과 트림의 타임스탬프 처리
`VideoEditor.buildVideoEffects()` 가 Cue 의 `startMs/endMs` 에서 `trimStartMs` 를 빼서 출력 영상 기준으로 오프셋 보정. 따라서 원본 SRT 를 그대로 넘기면 됨.

---

## 4. 추천 사용 플로우 (수동 연결)

```
① 자막 만들기 → SRT 저장 (경로 메모)
        ↓
② 숏츠 만들기 → 같은 영상 + ①의 SRT 불러오기 → 30초 구간 선택 → 내보내기
        ↓
③ 썸네일 만들기 → 같은 영상 + 대표 프레임 추출 → Claude 카피 제안 → 합성 → 저장
        ↓
④ YouTube 업로드 (썸네일 API 연결은 Phase 5 에서 — 지금은 영상만 업로드됨)
```

Phase 5 에서 이 단계들을 **Project 모델**로 묶어 산출물이 자동 연결되도록 만들 예정.

---

## 5. 제약 및 알려진 이슈

| 이슈 | 원인 | 대응 |
|---|---|---|
| 슬라이더 끝 이동 시 프레임 추출 지연 | MediaMetadataRetriever 동기 호출 | `onValueChangeFinished` 에서만 실행 (현재 구현) |
| 9:16 크롭 후 피사체가 프레임 밖 | center-crop | 현재는 수동 확인. 향후 얼굴 감지로 smart-crop 가능 |
| Claude 제안이 너무 밋밋 | 기본 tone 일반적 | `ClaudeCopywriter.suggestThumbnailCopies(tone = "...")` 에서 톤 파라미터 지정 |
| HDR 영상의 색 흐림 | Transformer 기본 SDR 변환 | 현재는 허용 오차 |
| 한글 폰트 외곽선 두꺼움 | `strokeWidth = size * 0.12` 고정 | 필요 시 `ThumbnailComposer.textPaint()` 수정 |

---

## 다음 Phase 예고

**Phase 5**: 프로젝트 모델 + 원스톱 플로우
- Room DB: `Project`, `ClipAsset`, `CaptionAsset`, `ThumbnailAsset`
- "새 프로젝트" 생성 → 영상 임포트 → 자동으로 자막·썸네일·숏츠 생성 → 업로드까지 한 화면
- WorkManager 로 긴 작업(렌더/업로드) 을 백그라운드 + 알림 진행률 표시
- 자막/썸네일/숏츠 결과가 업로드 화면에 자동 주입 (썸네일은 `videos.setThumbnail` API 연결)
