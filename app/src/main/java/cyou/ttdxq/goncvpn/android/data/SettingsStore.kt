package cyou.ttdxq.goncvpn.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    companion object {
        val KEY_P2P_SECRET = stringPreferencesKey("p2p_secret")
        val KEY_ROUTE_CIDRS = stringPreferencesKey("route_cidrs")
    }

    val p2pSecret: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[KEY_P2P_SECRET] ?: "" }

    val routeCidrs: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ROUTE_CIDRS] ?: "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16"
        }

    suspend fun setP2pSecret(secret: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_P2P_SECRET] = secret
        }
    }

    suspend fun setRouteCidrs(cidrs: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ROUTE_CIDRS] = cidrs
        }
    }
}
