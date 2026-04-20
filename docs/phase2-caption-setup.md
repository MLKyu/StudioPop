# Phase 2 — 자막 자동 생성 설정 가이드

Whisper API 로 영상에서 자막(SRT)을 자동 생성하는 기능을 사용하려면 **OpenAI API 키**가 필요합니다.

---

## 1. OpenAI API 키 발급

1. https://platform.openai.com/api-keys 접속 (로그인)
2. **+ Create new secret key** 클릭 → 이름 입력 (예: `yt-creator-android`)
3. 생성된 `sk-...` 로 시작하는 키를 **즉시 복사** (다시 못 봄)
4. Billing 설정: https://platform.openai.com/account/billing — 결제 수단 등록 필요
5. 사용량 제한(Usage limits) 설정 권장 (예: 월 $10 hard limit)

### 요금 참고 (2026 기준)
- Whisper: **$0.006 / 분**
- 10 분짜리 영상 전사 = 약 $0.06 (₩80 내외)
- 25MB 파일 제한 있음 (~2시간 분량 m4a)

---

## 2. 키를 앱에 주입

`local.properties` 파일(프로젝트 루트, `.gitignore` 에 이미 포함됨)에 추가:

```properties
OPENAI_API_KEY=sk-여기에_실제_키_붙여넣기
```

저장 후 Android Studio 에서 **File → Sync Project with Gradle Files** 실행.

`BuildConfig.OPENAI_API_KEY` 로 런타임에서 참조됩니다. 앱 코드엔 키가 하드코딩되지 않습니다.

---

## 3. 사용 방법

1. 앱 실행 → 홈 화면에서 **자막 만들기** 탭
2. **갤러리에서 선택** → 영상 1개 선택
3. 언어 코드 입력 (`ko` / `en` / `ja` 등, 비우면 자동 감지)
4. **자막 생성** 탭 → 오디오 추출 → Whisper 전사 (영상 길이에 따라 수 초~수십 초)
5. 각 자막 큐가 편집 가능한 텍스트 필드로 표시됨
6. 수정 후 **SRT 저장** → 앱 외부 파일 디렉토리에 `.srt` 저장됨
   - 경로 예: `/sdcard/Android/data/com.mingeek.studiopop/files/captions_1714000000000.srt`

---

## 4. 동작 원리 (간단 개요)

```
[영상 Uri]
   ↓ MediaExtractor (오디오 트랙만 추출)
[audio_xxx.m4a] (캐시)
   ↓ OkHttp multipart POST
[Whisper API /v1/audio/transcriptions?response_format=srt]
   ↓
[SRT 문자열]
   ↓ Srt.parse()
[List<Cue>]  ← 편집 UI
   ↓ Srt.write()
[파일 저장]
```

---

## 5. 제한 사항 (현재 Phase 2)

- **25MB 업로드 제한**: 긴 영상은 오디오 파일이 커서 실패. 비트레이트 낮은 m4a 원본만 성공
- **청크 분할 미지원**: 긴 영상은 Phase 3 에서 10 분 단위로 나눠 순차 전사 + 타임스탬프 오프셋 계산
- **화자 구분 없음**: Whisper 는 기본적으로 화자 분리 안 함
- **자막 스타일링 미지원**: 색상/위치 등 ASS 변환은 Phase 4 의 썸네일·렌더링 단계와 함께 진행

---

## 자주 발생하는 에러

| 에러 | 원인 |
|---|---|
| `OPENAI_API_KEY 가 비어 있습니다` | local.properties 에 키 추가 후 Gradle sync 안 함 |
| `파일이 25MB 를 초과합니다` | 원본 영상이 길거나 오디오 비트레이트가 높음 |
| `오디오 트랙이 없습니다` | 영상 파일에 오디오 트랙이 없거나 손상 |
| `Whisper API 401` | API 키 오타 또는 무효화됨 |
| `Whisper API 429` | 분당 요청 한도 초과 (무료 티어 기준 엄격) |

---

## 다음 단계 (Phase 3 예고)

- FFmpeg-Kit 도입 → 오디오 청크 분할, 비트레이트 조정
- 긴 영상 대응: 10 분 단위 분할 전사 + 오프셋 보정
- 무음 구간 자동 감지로 컷 편집 제안
- 자막 스타일 프리셋(폰트/크기/아웃라인)
- 자막을 영상에 번인(burn-in)하여 렌더링
