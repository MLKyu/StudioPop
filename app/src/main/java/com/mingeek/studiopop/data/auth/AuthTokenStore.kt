package com.mingeek.studiopop.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "auth")

class AuthTokenStore(private val context: Context) {

    private val accessTokenKey = stringPreferencesKey(KEY_ACCESS_TOKEN)

    val accessToken: Flow<String?> =
        context.authDataStore.data.map { it[accessTokenKey] }

    suspend fun save(accessToken: String) {
        context.authDataStore.edit { it[accessTokenKey] = accessToken }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.remove(accessTokenKey) }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "yt_access_token"
    }
}
