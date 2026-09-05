package com.moneyprinterturbo.android.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moneyprinterturbo.android.core.model.AppSettings
import com.moneyprinterturbo.android.core.model.LlmProvider
import com.moneyprinterturbo.android.core.model.StockKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "mpt_settings")

/** DataStore-backed app settings + provider registry (API keys live in SecureStore). */
class PrefsStore(private val context: Context, private val secure: SecureStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        p[stringPreferencesKey("app_settings")]?.let {
            try { json.decodeFromString<AppSettings>(it) } catch (e: Exception) { AppSettings() }
        } ?: AppSettings()
    }

    suspend fun updateSettings(s: AppSettings) {
        context.dataStore.edit { it[stringPreferencesKey("app_settings")] = json.encodeToString(s) }
    }

    suspend fun settingsNow(): AppSettings = settings.first()

    // ---- LLM providers (without API keys — keys resolved through SecureStore) ----

    val providers: Flow<List<LlmProvider>> = context.dataStore.data.map { p ->
        p[stringPreferencesKey("llm_providers")]?.let {
            try { json.decodeFromString<List<LlmProvider>>(it) } catch (e: Exception) { emptyList() }
        } ?: emptyList()
    }

    suspend fun providersNow(): List<LlmProvider> = providers.first()

    suspend fun saveProvider(provider: LlmProvider) {
        val key = "llm_key_${provider.id}"
        secure.put(key, provider.apiKey)
        val current = providersNow().toMutableList()
        val sanitized = provider.copy(apiKey = "") // never persist the key itself
        current.removeAll { it.id == provider.id }
        current += sanitized
        context.dataStore.edit { it[stringPreferencesKey("llm_providers")] = json.encodeToString(current) }
    }

    suspend fun deleteProvider(id: String) {
        secure.remove("llm_key_$id")
        val current = providersNow().filter { it.id != id }
        context.dataStore.edit { it[stringPreferencesKey("llm_providers")] = json.encodeToString(current) }
    }

    /** Rehydrate the provider with its key from secure storage. */
    suspend fun resolve(provider: LlmProvider): LlmProvider =
        provider.copy(apiKey = secure.get("llm_key_${provider.id}") ?: "")

    suspend fun defaultProvider(): LlmProvider? =
        providersNow().firstOrNull { it.isDefault && it.enabled } ?: providersNow().firstOrNull { it.enabled }

    suspend fun providerFor(settings: AppSettings, function: String): LlmProvider? {
        val id = when (function) {
            "script" -> settings.scriptProviderId
            "terms" -> settings.termsProviderId
            else -> ""
        }
        val list = providersNow()
        return list.firstOrNull { it.id == id && it.enabled }
            ?: list.firstOrNull { it.isDefault && it.enabled }
            ?: list.firstOrNull { it.enabled }
    }

    // ---- Stock keys ----

    suspend fun stockKeys(): StockKeys = StockKeys(
        pexelsApiKey = secure.get("stock_pexels") ?: "",
        pixabayApiKey = secure.get("stock_pixabay") ?: "",
        coverrApiKey = secure.get("stock_coverr") ?: "",
    )

    suspend fun saveStockKeys(keys: StockKeys) {
        secure.put("stock_pexels", keys.pexelsApiKey)
        secure.put("stock_pixabay", keys.pixabayApiKey)
        secure.put("stock_coverr", keys.coverrApiKey)
        context.dataStore.edit { it[stringPreferencesKey("stock_present")] = "1" }
    }
}
