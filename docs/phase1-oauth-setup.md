# Phase 1 — Google Cloud Console OAuth 설정 가이드

YouTube 업로드 기능을 실제로 동작시키려면 **Google Cloud Console 에서 OAuth 클라이언트 ID 발급**이 필요합니다. Android 의 `AuthorizationClient` 는 "패키지명 + SHA-1 지문" 조합으로 OAuth 클라이언트를 자동으로 매칭하므로, 앱 코드에 클라이언트 ID 를 하드코딩하지 않아도 됩니다.

---

## 1. SHA-1 지문 추출

디버그 키스토어의 SHA-1 을 먼저 확인합니다.

```bash
keytool -keystore ~/.android/debug.keystore -list -v \
  -alias androiddebugkey -storepass android -keypass android
```

출력 중 `SHA1:` 로 시작하는 줄을 기록해두세요. (예: `AB:CD:12:...`)

릴리즈 빌드용은 별도 키스토어의 SHA-1 도 등록해야 합니다.

---

## 2. Google Cloud Console 프로젝트 생성

1. https://console.cloud.google.com/ 접속
2. 상단 프로젝트 선택기에서 **New Project** → 이름 지정 (예: `yt-creator-superapp`)
3. 프로젝트 선택 상태 유지

---

## 3. YouTube Data API v3 활성화

1. 좌측 메뉴 → **APIs & Services → Library**
2. 검색창에 `YouTube Data API v3` 입력 후 선택
3. **Enable** 클릭

---

## 4. OAuth 동의 화면 구성

1. **APIs & Services → OAuth consent screen**
2. User Type: **External** 선택 → Create
3. 필수 필드 입력:
   - App name: `YT Creator SuperApp` (임의)
   - User support email: 본인 이메일 (`mingeek@hanssem.com`)
   - Developer contact: 본인 이메일
4. **Scopes** 단계에서 **Add or Remove Scopes**:
   - `https://www.googleapis.com/auth/youtube.upload`
   - `https://www.googleapis.com/auth/youtube.readonly`
5. **Test users** 단계에서 본인 Gmail 계정 추가
   (앱이 "Testing" 상태인 동안에는 Test user 만 로그인 가능)

---

## 5. OAuth 클라이언트 ID 발급 (Android)

1. **APIs & Services → Credentials**
2. **+ CREATE CREDENTIALS → OAuth client ID**
3. Application type: **Android**
4. Name: `MyApplication4 Debug` (자유)
5. Package name: `com.mingeek.studiopop`
6. SHA-1 certificate fingerprint: 1 단계에서 복사한 값 붙여넣기
7. **Create** → 클라이언트 ID 자동 발급 (앱 코드엔 안 넣어도 됨)

---

## 6. (권장) Web 클라이언트 ID 도 하나 생성

일부 Google Identity API (예: ID 토큰 요청) 는 **Web 타입 클라이언트 ID** 를 같이 요구합니다. 지금 당장은 필요 없지만, Phase 2+ 에서 서버 측 처리를 붙일 때 사용하므로 미리 만들어 두면 편합니다.

1. **+ CREATE CREDENTIALS → OAuth client ID**
2. Application type: **Web application**
3. Name: `MyApplication4 Server`
4. Authorized redirect URIs 는 비워둬도 됨
5. Create → 클라이언트 ID 기록 (나중 Phase 에서 사용)

---

## 7. 테스트

위 단계가 모두 끝난 후 앱을 실행하면:

1. **구글 로그인** 버튼 탭 → 계정 선택 & 권한 동의 화면 표시
2. 동의 후 액세스 토큰이 DataStore 에 저장됨
3. **영상 선택** → 제목 입력 → **업로드 시작**
4. 진행률 바가 차오르고, 완료되면 `videoId` 가 표시됨
5. https://studio.youtube.com/ 에서 업로드된 영상 확인 가능 (privacy = private 로 올라감)

---

## 자주 발생하는 에러

| 에러 메시지 | 원인 |
|---|---|
| `DEVELOPER_ERROR` / `10:` | SHA-1 불일치 또는 package name 오타. Credentials 재확인 |
| `403 accessNotConfigured` | YouTube Data API v3 활성화 안 됨 |
| `403 youtubeSignupRequired` | 해당 Google 계정에 YouTube 채널이 없음. studio.youtube.com 에서 채널 생성 |
| `Test users 만 허용` | 동의 화면 Test users 에 계정 추가 안 됨 |
| `401 Unauthorized` (업로드 중) | 액세스 토큰 만료 (보통 1시간). 재로그인 필요 |

---

## 다음 단계 (Phase 2 예고)

- 액세스 토큰 자동 갱신 (refresh token 대신 재authorize)
- 업로드 Foreground Service / WorkManager 로 이관 (앱 백그라운드에서도 업로드 지속)
- 여러 영상 큐 업로드
- 썸네일 설정 (`videos.set` + `thumbnails.set`)
