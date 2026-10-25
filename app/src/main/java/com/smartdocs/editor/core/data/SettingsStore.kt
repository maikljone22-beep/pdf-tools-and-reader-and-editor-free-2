package com.smartdocs.editor.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.smartdocs.editor.core.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "ar",
    val authorName: String = "",
    val autoSave: Boolean = true,
    val restoreSession: Boolean = true,
    val volumeKeysFlip: Boolean = false,
    val tapEdgesFlip: Boolean = true,
    val keepZoom: Boolean = true,
    val keepScreenOn: Boolean = false,
    val highlightFields: Boolean = true,
    val highlightLinks: Boolean = true,
    val pressureSensitivity: Boolean = true,
    val continuousScroll: Boolean = true,
    val nightMode: Boolean = false,
    val pageColor: Long = 0xFFFFFFFF,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val aiEndpoint: String = "https://api.openai.com/v1",
    val aiModel: String = "gpt-4o-mini",
    val aiApiKey: String = "",
    val ocrLanguages: String = "ara+eng",
    val lastOpenedUri: String = "",
    val onboardingDone: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private object K {
        val THEME = stringPreferencesKey("theme")
        val LANG = stringPreferencesKey("lang")
        val AUTHOR = stringPreferencesKey("author")
        val AUTOSAVE = booleanPreferencesKey("autosave")
        val RESTORE = booleanPreferencesKey("restore")
        val VOLUME = booleanPreferencesKey("volume_flip")
        val EDGES = booleanPreferencesKey("edges_flip")
        val KEEP_ZOOM = booleanPreferencesKey("keep_zoom")
        val SCREEN_ON = booleanPreferencesKey("screen_on")
        val HL_FIELDS = booleanPreferencesKey("hl_fields")
        val HL_LINKS = booleanPreferencesKey("hl_links")
        val PRESSURE = booleanPreferencesKey("pressure")
        val CONTINUOUS = booleanPreferencesKey("continuous")
        val NIGHT = booleanPreferencesKey("night")
        val PAGE_COLOR = longPreferencesKey("page_color")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_KEY = stringPreferencesKey("ai_key")
        val OCR_LANGS = stringPreferencesKey("ocr_langs")
        val LAST_URI = stringPreferencesKey("last_uri")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            language = p[K.LANG] ?: "ar",
            authorName = p[K.AUTHOR] ?: "",
            autoSave = p[K.AUTOSAVE] ?: true,
            restoreSession = p[K.RESTORE] ?: true,
            volumeKeysFlip = p[K.VOLUME] ?: false,
            tapEdgesFlip = p[K.EDGES] ?: true,
            keepZoom = p[K.KEEP_ZOOM] ?: true,
            keepScreenOn = p[K.SCREEN_ON] ?: false,
            highlightFields = p[K.HL_FIELDS] ?: true,
            highlightLinks = p[K.HL_LINKS] ?: true,
            pressureSensitivity = p[K.PRESSURE] ?: true,
            continuousScroll = p[K.CONTINUOUS] ?: true,
            nightMode = p[K.NIGHT] ?: false,
            pageColor = p[K.PAGE_COLOR] ?: 0xFFFFFFFF,
            ttsRate = p[K.TTS_RATE] ?: 1f,
            ttsPitch = p[K.TTS_PITCH] ?: 1f,
            aiEndpoint = p[K.AI_ENDPOINT] ?: "https://api.openai.com/v1",
            aiModel = p[K.AI_MODEL] ?: "gpt-4o-mini",
            aiApiKey = p[K.AI_KEY] ?: "",
            ocrLanguages = p[K.OCR_LANGS] ?: "ara+eng",
            lastOpenedUri = p[K.LAST_URI] ?: "",
            onboardingDone = p[K.ONBOARDED] ?: false,
        )
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                theme = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                language = p[K.LANG] ?: "ar", authorName = p[K.AUTHOR] ?: "",
                autoSave = p[K.AUTOSAVE] ?: true, restoreSession = p[K.RESTORE] ?: true,
                volumeKeysFlip = p[K.VOLUME] ?: false, tapEdgesFlip = p[K.EDGES] ?: true,
                keepZoom = p[K.KEEP_ZOOM] ?: true, keepScreenOn = p[K.SCREEN_ON] ?: false,
                highlightFields = p[K.HL_FIELDS] ?: true, highlightLinks = p[K.HL_LINKS] ?: true,
                pressureSensitivity = p[K.PRESSURE] ?: true, continuousScroll = p[K.CONTINUOUS] ?: true,
                nightMode = p[K.NIGHT] ?: false, pageColor = p[K.PAGE_COLOR] ?: 0xFFFFFFFF,
                ttsRate = p[K.TTS_RATE] ?: 1f, ttsPitch = p[K.TTS_PITCH] ?: 1f,
                aiEndpoint = p[K.AI_ENDPOINT] ?: "https://api.openai.com/v1", aiModel = p[K.AI_MODEL] ?: "gpt-4o-mini",
                aiApiKey = p[K.AI_KEY] ?: "", ocrLanguages = p[K.OCR_LANGS] ?: "ara+eng",
                lastOpenedUri = p[K.LAST_URI] ?: "", onboardingDone = p[K.ONBOARDED] ?: false,
            )
            val n = block(cur)
            p[K.THEME] = n.theme.name; p[K.LANG] = n.language; p[K.AUTHOR] = n.authorName
            p[K.AUTOSAVE] = n.autoSave; p[K.RESTORE] = n.restoreSession; p[K.VOLUME] = n.volumeKeysFlip
            p[K.EDGES] = n.tapEdgesFlip; p[K.KEEP_ZOOM] = n.keepZoom; p[K.SCREEN_ON] = n.keepScreenOn
            p[K.HL_FIELDS] = n.highlightFields; p[K.HL_LINKS] = n.highlightLinks; p[K.PRESSURE] = n.pressureSensitivity
            p[K.CONTINUOUS] = n.continuousScroll; p[K.NIGHT] = n.nightMode; p[K.PAGE_COLOR] = n.pageColor
            p[K.TTS_RATE] = n.ttsRate; p[K.TTS_PITCH] = n.ttsPitch; p[K.AI_ENDPOINT] = n.aiEndpoint
            p[K.AI_MODEL] = n.aiModel; p[K.AI_KEY] = n.aiApiKey; p[K.OCR_LANGS] = n.ocrLanguages
            p[K.LAST_URI] = n.lastOpenedUri; p[K.ONBOARDED] = n.onboardingDone
        }
    }
}
