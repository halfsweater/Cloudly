package com.halfblanket.cloudly.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.halfblanket.cloudly.data.drive.SortField
import com.halfblanket.cloudly.data.drive.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("System default"), DARK("Dark"), LIGHT("Light") }
enum class LayoutMode { LIST, GRID }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val layout: LayoutMode = LayoutMode.LIST,
    val sort: SortOrder = SortOrder(),
    val defaultAccountId: String? = null,
    val activeAccountId: String? = null,
    /** SAF tree URI chosen for downloads; null means Downloads/Cloudly. */
    val downloadTreeUri: String? = null,
    val downloadOnWifiOnly: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val layout = stringPreferencesKey("layout")
        val sortField = stringPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val defaultAccount = stringPreferencesKey("default_account")
        val activeAccount = stringPreferencesKey("active_account")
        val downloadTree = stringPreferencesKey("download_tree")
        val wifiOnly = booleanPreferencesKey("wifi_only")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            layout = p[Keys.layout]?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: LayoutMode.LIST,
            sort = SortOrder(
                field = p[Keys.sortField]?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME,
                ascending = p[Keys.sortAsc] ?: true,
            ),
            defaultAccountId = p[Keys.defaultAccount],
            activeAccountId = p[Keys.activeAccount],
            downloadTreeUri = p[Keys.downloadTree],
            downloadOnWifiOnly = p[Keys.wifiOnly] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setLayout(mode: LayoutMode) = context.dataStore.edit { it[Keys.layout] = mode.name }
    suspend fun setSort(order: SortOrder) = context.dataStore.edit {
        it[Keys.sortField] = order.field.name
        it[Keys.sortAsc] = order.ascending
    }
    suspend fun setWifiOnly(value: Boolean) = context.dataStore.edit { it[Keys.wifiOnly] = value }

    suspend fun setDefaultAccount(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.defaultAccount) else it[Keys.defaultAccount] = id
    }

    suspend fun setActiveAccount(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.activeAccount) else it[Keys.activeAccount] = id
    }

    suspend fun setDownloadTree(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.downloadTree) else it[Keys.downloadTree] = uri
    }
}
