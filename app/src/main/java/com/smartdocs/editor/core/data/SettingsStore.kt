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
    val volumeKeysFlip: Boolean = true,
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
    // ---- gaps6 (see MODULE_gaps6_r5.md) ----
    /** Reader: hide the status bar (full-screen top bar). */
    val fullScreenReader: Boolean = false,
    /** Volume keys in the reader: "flip" (pages) or "zoom". Only used when [volumeKeysFlip] is on. */
    val volumeKeysAction: String = "flip",
    /** Layout direction override: "auto" (follows language), "rtl", "ltr". */
    val layoutDirection: String = "auto",
    /** Global switch for automatic cloud sync (the sync engine skips automatic runs when false). */
    val autoSync: Boolean = true,
    /** File transfer network: "wifi" (Wi-Fi only) or "any" (Wi-Fi + mobile data). */
    val transferNetwork: String = "wifi",
    /** AI assistant master switch: false hides every AI entry point. */
    val aiEnabled: Boolean = true,
    /** Reader keeps the locked screen orientation between documents (false = reset on close). */
    val rememberOrientation: Boolean = true,
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
        val G6_FULLSCREEN = booleanPreferencesKey("g6_fullscreen_reader")
        val G6_VOLUME_ACTION = stringPreferencesKey("g6_volume_action")
        val G6_LAYOUT_DIR = stringPreferencesKey("g6_layout_dir")
        val G6_AUTO_SYNC = booleanPreferencesKey("g6_auto_sync")
        val G6_TRANSFER_NET = stringPreferencesKey("g6_transfer_net")
        val G6_AI = booleanPreferencesKey("g6_ai_enabled")
        val G6_REMEMBER_ORIENT = booleanPreferencesKey("g6_remember_orientation")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            language = p[K.LANG] ?: "ar",
            authorName = p[K.AUTHOR] ?: "",
            autoSave = p[K.AUTOSAVE] ?: true,
            restoreSession = p[K.RESTORE] ?: true,
            volumeKeysFlip = p[K.VOLUME] ?: true,
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
            fullScreenReader = p[K.G6_FULLSCREEN] ?: false, volumeKeysAction = p[K.G6_VOLUME_ACTION] ?: "flip",
            layoutDirection = p[K.G6_LAYOUT_DIR] ?: "auto", autoSync = p[K.G6_AUTO_SYNC] ?: true,
            transferNetwork = p[K.G6_TRANSFER_NET] ?: "wifi", aiEnabled = p[K.G6_AI] ?: true,
            rememberOrientation = p[K.G6_REMEMBER_ORIENT] ?: true,
        )
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                theme = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                language = p[K.LANG] ?: "ar", authorName = p[K.AUTHOR] ?: "",
                autoSave = p[K.AUTOSAVE] ?: true, restoreSession = p[K.RESTORE] ?: true,
                volumeKeysFlip = p[K.VOLUME] ?: true, tapEdgesFlip = p[K.EDGES] ?: true,
                keepZoom = p[K.KEEP_ZOOM] ?: true, keepScreenOn = p[K.SCREEN_ON] ?: false,
                highlightFields = p[K.HL_FIELDS] ?: true, highlightLinks = p[K.HL_LINKS] ?: true,
                pressureSensitivity = p[K.PRESSURE] ?: true, continuousScroll = p[K.CONTINUOUS] ?: true,
                nightMode = p[K.NIGHT] ?: false, pageColor = p[K.PAGE_COLOR] ?: 0xFFFFFFFF,
                ttsRate = p[K.TTS_RATE] ?: 1f, ttsPitch = p[K.TTS_PITCH] ?: 1f,
                aiEndpoint = p[K.AI_ENDPOINT] ?: "https://api.openai.com/v1", aiModel = p[K.AI_MODEL] ?: "gpt-4o-mini",
                aiApiKey = p[K.AI_KEY] ?: "", ocrLanguages = p[K.OCR_LANGS] ?: "ara+eng",
                lastOpenedUri = p[K.LAST_URI] ?: "", onboardingDone = p[K.ONBOARDED] ?: false,
                fullScreenReader = p[K.G6_FULLSCREEN] ?: false, volumeKeysAction = p[K.G6_VOLUME_ACTION] ?: "flip",
                layoutDirection = p[K.G6_LAYOUT_DIR] ?: "auto", autoSync = p[K.G6_AUTO_SYNC] ?: true,
                transferNetwork = p[K.G6_TRANSFER_NET] ?: "wifi", aiEnabled = p[K.G6_AI] ?: true,
                rememberOrientation = p[K.G6_REMEMBER_ORIENT] ?: true,
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
            p[K.G6_FULLSCREEN] = n.fullScreenReader; p[K.G6_VOLUME_ACTION] = n.volumeKeysAction; p[K.G6_LAYOUT_DIR] = n.layoutDirection
            p[K.G6_AUTO_SYNC] = n.autoSync; p[K.G6_TRANSFER_NET] = n.transferNetwork; p[K.G6_AI] = n.aiEnabled
            p[K.G6_REMEMBER_ORIENT] = n.rememberOrientation
        }
    }
}
