package us.crafties.ffmobile.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ffmobile_settings")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_LOGS = booleanPreferencesKey("keep_logs")
    }

    val advancedModeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ADVANCED_MODE] ?: false }

    val dynamicColorEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    val keepLogsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.KEEP_LOGS] ?: false }

    suspend fun setAdvancedMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADVANCED_MODE] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setKeepLogs(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_LOGS] = enabled }
    }
}
