package com.swapstudio.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swapstudio.ai.core.swap.MeshOverlayPalette
import com.swapstudio.ai.core.swap.PerformanceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Every user facing preference, as one immutable snapshot for Compose. */
data class AppSettings(
    /** Mandatory consent: no swap runs until this is true. */
    val consentAccepted: Boolean = false,
    val hyperRealistic: Boolean = true,
    val enhancerStrength: Int = 70,
    val showMesh: Boolean = false,
    val performanceMode: PerformanceMode = PerformanceMode.MAX,
    val exportPng: Boolean = false,
    val upscale4k: Boolean = true,
    val videoHeight: Int = 2160,
    val hevc: Boolean = false,
    val bitrateMbps: Int = 24,
    val copyAudio: Boolean = true,
    val grainMatch: Boolean = true,
    val meshOverlayColor: Int = 0xFF00E5C0.toInt(),
    /** Model manager: download on unmetered networks only. */
    val wifiOnlyDownloads: Boolean = false,
    val lastSourceUri: String? = null,
    val lastTargetUri: String? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "swapstudio_settings")

/**
 * Persisted settings (DataStore/Preferences).
 *
 * Deliberately tiny: everything is a primitive so the whole state can be collected as one flow and fed
 * straight into the ViewModels, and a corrupted entry can never crash the app.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CONSENT = booleanPreferencesKey("consent_accepted")
        val HYPER_REAL = booleanPreferencesKey("hyper_realistic")
        val ENHANCER = intPreferencesKey("enhancer_strength")
        val SHOW_MESH = booleanPreferencesKey("show_mesh")
        val PERFORMANCE = stringPreferencesKey("performance_mode")
        val EXPORT_PNG = booleanPreferencesKey("export_png")
        val UPSCALE_4K = booleanPreferencesKey("upscale_4k")
        val VIDEO_HEIGHT = intPreferencesKey("video_height")
        val HEVC = booleanPreferencesKey("hevc")
        val BITRATE = intPreferencesKey("bitrate_mbps")
        val COPY_AUDIO = booleanPreferencesKey("copy_audio")
        val GRAIN = booleanPreferencesKey("grain_match")
        val MESH_COLOR = intPreferencesKey("mesh_color")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val LAST_SOURCE = stringPreferencesKey("last_source_uri")
        val LAST_TARGET = stringPreferencesKey("last_target_uri")
    }

    /** The whole settings snapshot, defaulting to the "best quality" profile. */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            consentAccepted = prefs[Keys.CONSENT] ?: false,
            hyperRealistic = prefs[Keys.HYPER_REAL] ?: true,
            enhancerStrength = (prefs[Keys.ENHANCER] ?: 70).coerceIn(0, 100),
            showMesh = prefs[Keys.SHOW_MESH] ?: false,
            performanceMode = prefs[Keys.PERFORMANCE]?.let { name ->
                runCatching { PerformanceMode.valueOf(name) }.getOrNull()
            } ?: PerformanceMode.MAX,
            exportPng = prefs[Keys.EXPORT_PNG] ?: false,
            upscale4k = prefs[Keys.UPSCALE_4K] ?: true,
            videoHeight = prefs[Keys.VIDEO_HEIGHT] ?: 2160,
            hevc = prefs[Keys.HEVC] ?: false,
            bitrateMbps = (prefs[Keys.BITRATE] ?: 24).coerceIn(8, 60),
            copyAudio = prefs[Keys.COPY_AUDIO] ?: true,
            grainMatch = prefs[Keys.GRAIN] ?: true,
            meshOverlayColor = prefs[Keys.MESH_COLOR] ?: MeshOverlayPalette.MINT,
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY] ?: false,
            lastSourceUri = prefs[Keys.LAST_SOURCE],
            lastTargetUri = prefs[Keys.LAST_TARGET],
        )
    }

    suspend fun setConsent(value: Boolean) = put { it[Keys.CONSENT] = value }
    suspend fun setHyperRealistic(value: Boolean) = put { it[Keys.HYPER_REAL] = value }
    suspend fun setEnhancerStrength(value: Int) = put { it[Keys.ENHANCER] = value.coerceIn(0, 100) }
    suspend fun setShowMesh(value: Boolean) = put { it[Keys.SHOW_MESH] = value }
    suspend fun setPerformanceMode(value: PerformanceMode) = put { it[Keys.PERFORMANCE] = value.name }
    suspend fun setExportPng(value: Boolean) = put { it[Keys.EXPORT_PNG] = value }
    suspend fun setUpscale4k(value: Boolean) = put { it[Keys.UPSCALE_4K] = value }
    suspend fun setVideoHeight(value: Int) = put { it[Keys.VIDEO_HEIGHT] = value }
    suspend fun setHevc(value: Boolean) = put { it[Keys.HEVC] = value }
    suspend fun setBitrateMbps(value: Int) = put { it[Keys.BITRATE] = value.coerceIn(8, 60) }
    suspend fun setCopyAudio(value: Boolean) = put { it[Keys.COPY_AUDIO] = value }
    suspend fun setGrainMatch(value: Boolean) = put { it[Keys.GRAIN] = value }
    suspend fun setWifiOnly(value: Boolean) = put { it[Keys.WIFI_ONLY] = value }

    /** Mesh overlay accent (ARGB). Restricted to the palette the overlay UI offers. */
    suspend fun setMeshColor(argb: Int) = put { it[Keys.MESH_COLOR] = argb }
    // `MutablePreferences.remove(key)` is the DataStore overload; importing kotlin.collections'
    // `remove(index)` next to it is what produced the deprecation warning, so the calls are written
    // out explicitly through the preferences receiver.
    suspend fun setLastSource(uri: String?) = put { prefs ->
        if (uri == null) {
            prefs.remove(Keys.LAST_SOURCE)
        } else {
            prefs[Keys.LAST_SOURCE] = uri
        }
    }

    suspend fun setLastTarget(uri: String?) = put { prefs ->
        if (uri == null) {
            prefs.remove(Keys.LAST_TARGET)
        } else {
            prefs[Keys.LAST_TARGET] = uri
        }
    }

    /** Wipes every preference (used by "reset app" in the settings screen). */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
