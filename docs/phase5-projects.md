# Phase 5 — 프로젝트 기반 원스톱 플로우 (최종)

Phase 5 에서 추가된 것:
- **Room DB**: Project / Asset 엔티티 + DAO + AppDatabase
- **ProjectRepository** 로 Flow 기반 프로젝트·에셋 CRUD
- **프로젝트 목록 / 상세 화면**: 프로젝트 1개 안에서 모든 단계가 연결됨
- **기존 5개 화면(자막/편집/숏츠/썸네일/업로드) 이 projectId 인식**:
  - 프로젝트 모드에서 진입하면 원본 영상 자동 세팅
  - 편집/숏츠 화면은 **가장 최근 자막(SRT)** 자동 로드
  - 업로드 화면은 **편집본(EXPORT_VIDEO)** 자동 선택 + 썸네일 자동 첨부
  - 각 화면의 결과물이 자동으로 Asset 으로 DB 저장
- **YouTube 썸네일 첨부 API** (`thumbnails.set`) — 업로드 직후 자동 호출

---

## 1. Room DB 스키마

```kotlin
ProjectEntity(id, title, description, sourceVideoUri, createdAt)
AssetEntity(id, projectId, type, value, label, createdAt)

enum AssetType {
    CAPTION_SRT,         // value = 파일 경로
    EXPORT_VIDEO,        // value = MP4 경로
    THUMBNAIL,           // value = PNG 경로
    SHORTS,              // value = MP4 경로
    UPLOADED_VIDEO_ID,   // value = YouTube videoId
}
```

- Asset ↔ Project 는 외래 키 + CASCADE (프로젝트 삭제 시 에셋도 삭제)
- TypeConverter 로 enum ↔ String 변환
- DB 이름: `superapp.db`

---

## 2. 원스톱 사용 플로우

```
홈 → [프로젝트] 탭 → FAB(+) → 제목 + 영상 선택 → 프로젝트 생성
                                                        ↓
                                              프로젝트 상세 열림
                                                        ↓
              ┌───────────────┬─────────────┬─────────────┬──────────────┐
              ↓               ↓             ↓             ↓              ↓
         ① 자막         ② 편집         ③ 숏츠       ④ 썸네일      ⑤ 업로드
         Whisper       트림 + 자막    9:16 + 자막   프레임+카피    편집본 + 썸네일
         전사           번인          번인          합성          자동 첨부
            │              │              │              │              │
            ▼              ▼              ▼              ▼              ▼
       CAPTION_SRT   EXPORT_VIDEO    SHORTS         THUMBNAIL    UPLOADED_VIDEO_ID
            ↓
        (편집/숏츠 화면이 자동으로 불러감)
            ↓              ↓
                    (업로드 화면이 자동으로 불러감)
```

**실제 동작 예시**:

1. "뽑기 12팩 후기" 이라는 이름으로 프로젝트 생성
2. ① 자막 탭 → 원본 영상 자동 세팅 → "자막 생성" → 자동 저장
3. ② 편집 탭 → 원본 영상 + ①의 SRT 자동 로드 → 트림 + 자막 번인 → 내보내기 → 자동 저장
4. ④ 썸네일 탭 → 원본 영상 자동 세팅 → Claude 카피 제안 → 합성 → 저장
5. ⑤ 업로드 탭 → **②의 편집본이 자동으로 선택**, 제목도 프로젝트명으로 자동 입력, ④의 썸네일이 "썸네일 자동 첨부" 카드에 표시됨 → 구글 로그인 → 업로드
6. YouTube 영상 업로드 + 썸네일도 자동 `thumbnails.set`
7. 프로젝트 상세 화면에 `UPLOADED_VIDEO_ID` 에셋이 추가되어 ✅ 표시

---

## 3. 단독 도구 모드 (기존 동작 그대로)

홈의 "빠른 도구" 섹션에서 각 기능을 단독으로 실행 가능. 이 경우:
- projectId 가 없으므로 영상 picker 로 수동 선택
- 산출물은 `getExternalFilesDir(null)` 에 저장되지만 DB 에는 기록되지 않음

---

## 4. 전체 워크플로우 완성도

| 단계 | 원스톱 자동화 | 특이사항 |
|---|---|---|
| 촬영/임포트 | ✅ | SAF/MediaStore URI 영속화 |
| 오디오 추출 | ✅ | MediaExtractor 재인코딩 없음 |
| 긴 영상 청크 | ✅ | 8분 단위 자동 분할 + 오프셋 보정 |
| 자막 전사 | ✅ | OpenAI Whisper |
| 자막 편집 | ✅ | 큐별 텍스트 필드 수정 |
| 트림 편집 | ✅ | RangeSlider |
| 자막 번인 | ✅ | Media3 TextOverlay 동적 렌더 |
| 9:16 크롭 | ✅ | `Presentation.createForAspectRatio` |
| 썸네일 생성 | ✅ | Canvas + Claude Haiku 카피 |
| 영상 업로드 | ✅ | YouTube Data API v3 resumable |
| 썸네일 자동 첨부 | ✅ | `thumbnails.set` API |
| 프로젝트 관리 | ✅ | Room DB |
| 산출물 자동 연결 | ✅ | Asset 조회로 다음 단계 자동 로드 |

---

## 5. 전체 기술 스택 요약

**UI**: Jetpack Compose + Material3 + Navigation Compose
**DB**: Room (KSP)
**네트워크**: OkHttp + Retrofit + Moshi
**영상 처리**: AndroidX Media3 Transformer (하드웨어 가속 MediaCodec)
**오디오 처리**: MediaExtractor + MediaMuxer (기본 플랫폼 API)
**AI**: OpenAI Whisper (자막) + Anthropic Claude Haiku 4.5 (카피)
**인증**: Google Identity Services `AuthorizationClient` (YouTube OAuth 스코프)
**저장**: DataStore (토큰) + Room (프로젝트/에셋)

---

## 6. 남은 개선점 (선택적)

이미 "슈퍼앱"으로 동작하지만 폴리시 레벨로 더 다듬고 싶다면:

| 개선 | 효과 | 복잡도 |
|---|---|---|
| WorkManager 로 업로드/렌더링 이관 | 앱 백그라운드에서도 지속, 알림 진행률 | 중 |
| 얼굴/피사체 감지 기반 스마트 크롭 | 9:16 변환 시 주제 유지 | 중-고 |
| Whisper 하이라이트 자동 추출 | 숏츠 구간 자동 추천 (말의 밀도/키워드) | 중 |
| BGM / SFX 추가 | Media3 AudioProcessor 로 믹스 | 고 |
| 다중 클립 concat | `Composition.Builder` 반복 호출 | 저-중 |
| 리프레시 토큰으로 재로그인 회피 | Google Identity `AuthorizationResult` 영속화 | 저 |
| 썸네일 A/B 테스트용 다중 변형 | Claude 카피 × N → 썸네일 × N | 저 |

---

## 7. 모든 Phase 완료 — 회고

- **Phase 0 (설계)**: 전체 아키텍처, 모듈, 의존성 결정
- **Phase 1 (업로드)**: YouTube OAuth + resumable 업로드 — **가장 끝단부터** 리스크 제거
- **Phase 2 (자막)**: Whisper STT + SRT 편집
- **Phase 3 (편집)**: Media3 Transformer 트림 + 자막 번인 — FFmpeg-Kit 대신 Google 유지 라이브러리 선택
- **Phase 4 (썸네일/숏츠)**: Canvas 합성 + Claude 카피 + 9:16 크롭
- **Phase 5 (프로젝트)**: Room DB + 원스톱 자동 연결

한 번에 다 짜지 않고 **Phase 별로 잘라서 빌드·검증**한 덕에, 중간에 FFmpeg-Kit 아카이브 같은 이슈가 발견돼도 방향 전환(→ Media3)이 가능했습니다.

**이제 `./gradlew installDebug` 로 실기기에 설치 → 홈의 "프로젝트" 에서 시작해 보세요.**
