package br.com.ppci.levantamento.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settings by preferencesDataStore("settings")

/** Dados que se repetem entre visitas: empresa e responsável técnico. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val company = stringPreferencesKey("company")
    private val technician = stringPreferencesKey("technician")

    val companyFlow: Flow<String> = ctx.settings.data.map { it[company].orEmpty() }
    val technicianFlow: Flow<String> = ctx.settings.data.map { it[technician].orEmpty() }

    suspend fun save(companyName: String, tech: String) {
        ctx.settings.edit { it[company] = companyName; it[technician] = tech }
    }
}
