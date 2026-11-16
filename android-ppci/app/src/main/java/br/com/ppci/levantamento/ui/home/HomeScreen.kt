package br.com.ppci.levantamento.ui.home

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.ppci.levantamento.data.SurveyForm
import br.com.ppci.levantamento.data.SurveyRepository
import br.com.ppci.levantamento.export.TitleBlock
import br.com.ppci.levantamento.export.TitleBlockReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: SurveyRepository, @ApplicationContext private val ctx: Context) : ViewModel() {
    private val _pdf = MutableStateFlow<File?>(null); val pdf = _pdf.asStateFlow()
    private val _detected = MutableStateFlow<TitleBlock?>(null); val detected = _detected.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading = _loading.asStateFlow()

    fun onPicked(uri: Uri) = viewModelScope.launch {
        _loading.value = true
        val f = repo.copyPlan(uri)
        _pdf.value = f
        _detected.value = TitleBlockReader(ctx).read(f) // sugestão a partir do carimbo; o técnico confere
        _loading.value = false
    }

    fun start(form: SurveyForm, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val f = _pdf.value ?: return@launch
        onCreated(repo.createSurvey(form, f))
    }
}

@Composable
fun HomeScreen(onStarted: (Long) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val pdf by vm.pdf.collectAsState()
    val detected by vm.detected.collectAsState()
    val loading by vm.loading.collectAsState()
    var company by rememberSaveable { mutableStateOf("") }
    var client by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf("") }
    var tech by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::onPicked) }

    LaunchedEffect(detected) {
        detected?.let { d ->
            if (client.isBlank()) client = d.client
            if (site.isBlank()) site = d.site
            if (tech.isBlank()) tech = d.technician
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Novo levantamento", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton({ picker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth()) { Text(if (pdf == null) "Importar PDF da prancha" else "Prancha importada. Trocar PDF") }
        if (loading) CircularProgressIndicator()
        if (detected?.isEmpty == false) Text("Dados lidos do carimbo da planta. Confira antes de iniciar.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), label = { Text("Empresa") }, singleLine = true)
        OutlinedTextField(client, { client = it }, Modifier.fillMaxWidth(), label = { Text("Cliente") }, singleLine = true)
        OutlinedTextField(site, { site = it }, Modifier.fillMaxWidth(), label = { Text("Obra / Endereço") })
        OutlinedTextField(tech, { tech = it }, Modifier.fillMaxWidth(), label = { Text("Responsável técnico (CREA)") }, singleLine = true)
        Button({ vm.start(SurveyForm(company, client, site, tech), onStarted) }, Modifier.fillMaxWidth(), enabled = pdf != null && !loading) { Text("Iniciar levantamento") }
    }
}
