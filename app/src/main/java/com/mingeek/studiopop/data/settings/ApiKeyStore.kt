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
