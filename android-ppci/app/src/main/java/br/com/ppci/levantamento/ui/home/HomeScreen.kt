package br.com.ppci.levantamento.ui.home

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.ppci.levantamento.R
import br.com.ppci.levantamento.backup.BackupManager
import br.com.ppci.levantamento.data.SettingsStore
import br.com.ppci.levantamento.data.SurveyEntity
import br.com.ppci.levantamento.data.SurveyForm
import br.com.ppci.levantamento.data.SurveyRepository
import br.com.ppci.levantamento.data.SurveyWithCount
import br.com.ppci.levantamento.export.TitleBlock
import br.com.ppci.levantamento.export.TitleBlockReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: SurveyRepository,
    private val settings: SettingsStore,
    private val backup: BackupManager,
    @ApplicationContext private val ctx: Context
) : ViewModel() {
    private val _pdf = MutableStateFlow<File?>(null); val pdf = _pdf.asStateFlow()
    private val _detected = MutableStateFlow<TitleBlock?>(null); val detected = _detected.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow(false); val error = _error.asStateFlow()

    val surveys: StateFlow<List<SurveyWithCount>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun savedCompany() = settings.companyFlow.first()
    suspend fun savedTechnician() = settings.technicianFlow.first()

    fun onPlanPicked(uri: Uri) = viewModelScope.launch {
        _loading.value = true; _error.value = false; _detected.value = null
        val f = runCatching {
            _pdf.value?.delete() // descarta a importação anterior que não virou levantamento
            repo.importPlan(uri) // PDF ou imagem (convertida em PDF)
        }.onFailure { _error.value = true }.getOrNull()
        _pdf.value = f
        _loading.value = false // o técnico já pode iniciar: cliente, obra e RT são opcionais
        if (f != null) launch {
            // Sugestão em segundo plano, só preenche campos ainda vazios. Pode não achar nada: o carimbo muitas vezes
            // não tem o cliente, ou vem em fonte de traço que nem o PDF nem o OCR garantem ler.
            _detected.value = runCatching { TitleBlockReader(ctx).read(f) }.getOrNull()
        }
    }

    fun onBackupPicked(uri: Uri, onDone: (Long) -> Unit) = viewModelScope.launch {
        _loading.value = true; _error.value = false
        runCatching { backup.import(uri) }.onSuccess(onDone).onFailure { _error.value = true }
        _loading.value = false
    }

    fun start(form: SurveyForm, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val f = _pdf.value ?: return@launch
        settings.save(form.company, form.technician) // empresa e RT são lembrados na próxima visita
        val id = repo.createSurvey(form, f)
        _pdf.value = null; _detected.value = null
        onCreated(id)
    }

    fun delete(s: SurveyEntity) = viewModelScope.launch { repo.deleteSurvey(s) }
}

@Composable
fun HomeScreen(onStarted: (Long) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val pdf by vm.pdf.collectAsState()
    val detected by vm.detected.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val surveys by vm.surveys.collectAsState()
    var company by rememberSaveable { mutableStateOf("") }
    var client by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf("") }
    var tech by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    val planPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::onPlanPicked) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.onBackupPicked(u, onStarted) } }

    LaunchedEffect(Unit) { // empresa e RT da última visita
        if (company.isBlank()) company = vm.savedCompany()
        if (tech.isBlank()) tech = vm.savedTechnician()
    }
    LaunchedEffect(detected) {
        detected?.let { d ->
            if (client.isBlank()) client = d.client
            if (site.isBlank()) site = d.site
            if (tech.isBlank()) tech = d.technician
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.home_title), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedButton({ planPicker.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) }, Modifier.fillMaxWidth()) {
                Text(stringResource(if (pdf == null) R.string.home_import_plan else R.string.home_plan_ready))
            }
        }
        if (loading) item { CircularProgressIndicator() }
        if (error) item { Text(stringResource(R.string.import_error), color = MaterialTheme.colorScheme.error) }
        if (detected?.isEmpty == false) item { Text(stringResource(R.string.home_detected), style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_company)) }, singleLine = true) }
        item { OutlinedTextField(client, { client = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_client)) }, singleLine = true) }
        item { OutlinedTextField(site, { site = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_site)) }) }
        item { OutlinedTextField(tech, { tech = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_tech)) }, singleLine = true) }
        item {
            Button({
                vm.start(SurveyForm(company, client, site, tech)) { id -> client = ""; site = ""; onStarted(id) }
            }, Modifier.fillMaxWidth(), enabled = pdf != null && !loading) { Text(stringResource(R.string.home_start)) }
        }
        item { TextButton({ backupPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text(stringResource(R.string.home_import_backup)) } }
        item { HorizontalDivider() }
        item { Text(stringResource(R.string.home_previous), style = MaterialTheme.typography.titleMedium) }
        if (surveys.isEmpty()) item { Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyMedium) }
        items(surveys, key = { it.survey.id }) { row ->
            val s = row.survey
            Card(Modifier.fillMaxWidth().clickable { onStarted(s.id) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.client.ifBlank { stringResource(R.string.untitled_survey) }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        if (s.site.isNotBlank()) Text(s.site, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        val pages = if (s.pageCount > 1) " · " + stringResource(R.string.home_row_pages, s.pageCount) else ""
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.createdAt)) + " · " +
                                stringResource(R.string.home_row_summary, row.budgetCount, row.markerCount) + pages,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (confirmDelete == s.id) {
                        TextButton({ vm.delete(s); confirmDelete = null }) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
                        TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) }
                    } else IconButton({ confirmDelete = s.id }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}
