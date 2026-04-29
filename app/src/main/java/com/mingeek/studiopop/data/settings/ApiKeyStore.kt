package com.mingeek.studiopop.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.apiKeyDataStore by preferencesDataStore(name = "api_keys")

/**
 * 사용자 입력 API 키 저장소. 설정 화면에서 저장·수정되고, 각 기능은 호출 시점에 최신 값을 읽음.
 *
 * BuildConfig 를 쓰지 않고 DataStore 로 옮긴 이유:
 * - 사용자가 설정 화면에서 바로 수정 → 앱 재빌드 없이 변경 가능
 * - 여러 키를 한 곳에서 일관되게 관리
 *
 * 현재 슬롯:
 *  - [gemini]   : Google AI Studio (멀티모달 톤/제목/태그/챕터/하이라이트)
 *  - [openAi]   : OpenAI Whisper (자동 자막 STT) + 보조 텍스트
 *
 * ### TODO — 추가 키/OAuth 슬롯 (외부 인증 필요)
 *  - **anthropic.api_key** : Claude (썸네일 메인 카피 후보 5종 — 현재 README 도 언급).
 *    [BuildConfig] 또는 별도 secret store 로 임시 처리됐을 수 있음 — 설정 화면에 노출 미필.
 *    추가하려면 `stringPreferencesKey("anthropic_api_key")` 슬롯 + Save/Get 메서드 추가.
 *  - **google.oauth.token** : YouTube Data API v3 (videos.upload, thumbnails.set, channels.list)
 *    + Analytics API. OAuth flow 는 [com.google.android.gms.auth.api] 또는 GoogleSignIn 사용.
 *    이 키가 들어와야 (1) 채널 톤 학습([AiAssist.suggestThemeFromHistory]) (2) A/B 썸네일 자동
 *    스케줄러 (3) 진짜 1탭 업로드 (현재는 사용자가 직접 업로드) 가 풀 동작.
 *  - **vosk.model.path** : 화자 분리용 Vosk SpeakerModel — 라이선스 무료지만 100MB+ 다운로드.
 *    현재 [com.mingeek.studiopop.data.speaker.VoskSpeakerModelManager] 가 SD 카드 캐시 관리.
 *    "키" 라기보단 모델 path 인데, 같은 패턴으로 DataStore 슬롯 두면 사용자별 위치 관리 가능.
 *  - **rnnoise.model.path** : 노이즈 제거용 ONNX (선택, 영상 음질 향상). 현재 미구현 —
 *    [com.mingeek.studiopop.data.editor.VideoEditor] 의 originalAudioProcessors 체인 앞에
 *    RnnoiseAudioProcessor (직접 구현 필요) 추가 자리.
 */
class ApiKeyStore(private val context: Context) {

    private val geminiKey = stringPreferencesKey(KEY_GEMINI)
    private val openAiKey = stringPreferencesKey(KEY_OPENAI)

    val gemini: Flow<String> =
        context.apiKeyDataStore.data.map { it[geminiKey].orEmpty() }

    val openAi: Flow<String> =
        context.apiKeyDataStore.data.map { it[openAiKey].orEmpty() }

    suspend fun getGemini(): String = gemini.first()
    suspend fun getOpenAi(): String = openAi.first()

    suspend fun saveGemini(value: String) {
        context.apiKeyDataStore.edit { it[geminiKey] = value.trim() }
    }

    suspend fun saveOpenAi(value: String) {
        context.apiKeyDataStore.edit { it[openAiKey] = value.trim() }
    }

    suspend fun clearGemini() {
        context.apiKeyDataStore.edit { it.remove(geminiKey) }
    }

    suspend fun clearOpenAi() {
        context.apiKeyDataStore.edit { it.remove(openAiKey) }
    }

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_OPENAI = "openai_api_key"
    }
}
