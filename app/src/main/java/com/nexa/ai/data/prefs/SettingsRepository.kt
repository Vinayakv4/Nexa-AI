package com.nexa.ai.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val activeModel: String = ""
)

data class AppSettings(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: String = "",
    val systemPrompt: String = "You are Nexa, a helpful AI study assistant. Use markdown formatting for answers.",
    val temperature: Float = 0.7f,
    val workModeEnabled: Boolean = false,
    val workspaceUri: String = ""
) {
    val active: Provider?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    val isConfigured: Boolean get() = active != null && active!!.apiKey.isNotBlank()
    val model: String get() = active?.activeModel ?: active?.models?.firstOrNull() ?: ""
    val apiKey: String get() = active?.apiKey ?: ""
    val baseUrl: String get() = active?.baseUrl?.trimEnd('/') ?: ""
    val providerName: String get() = active?.name ?: ""
    val hasWorkspace: Boolean get() = workspaceUri.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDERS = stringPreferencesKey("providers_json")
        val ACTIVE = stringPreferencesKey("active_provider_id")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val WORK_MODE = booleanPreferencesKey("work_mode")
        val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
        // legacy
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val providers = prefs[Keys.PROVIDERS]?.let { raw ->
            runCatching { json.decodeFromString<List<Provider>>(raw) }.getOrNull()
        } ?: migrateLegacy(prefs[Keys.API_KEY], prefs[Keys.MODEL])
        AppSettings(
            providers = providers,
            activeProviderId = prefs[Keys.ACTIVE] ?: providers.firstOrNull()?.id.orEmpty(),
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: AppSettings().systemPrompt,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            workModeEnabled = prefs[Keys.WORK_MODE] ?: false,
            workspaceUri = prefs[Keys.WORKSPACE_URI] ?: ""
        )
    }

    private fun migrateLegacy(key: String?, model: String?): List<Provider> {
        if (key.isNullOrBlank()) return emptyList()
        val modelId = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
        return listOf(
            Provider(
                id = "p_${System.currentTimeMillis()}",
                name = "Default Provider",
                baseUrl = "https://top-tools-ai.com/api/v1",
                apiKey = key,
                models = listOf(modelId),
                activeModel = modelId
            )
        )
    }

    suspend fun saveProviders(providers: List<Provider>, activeId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROVIDERS] = json.encodeToString(providers)
            prefs[Keys.ACTIVE] = activeId
        }
    }

    suspend fun setActiveProvider(id: String) {
        context.dataStore.edit { it[Keys.ACTIVE] = id }
    }

    suspend fun setSystemPromptAndTemp(systemPrompt: String, temperature: Float) {
        context.dataStore.edit {
            it[Keys.SYSTEM_PROMPT] = systemPrompt
            it[Keys.TEMPERATURE] = temperature
        }
    }

    suspend fun setWorkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WORK_MODE] = enabled }
    }

    suspend fun setWorkspace(uri: String) {
        context.dataStore.edit { it[Keys.WORKSPACE_URI] = uri }
    }

    suspend fun current(): AppSettings = settings.first()
}
