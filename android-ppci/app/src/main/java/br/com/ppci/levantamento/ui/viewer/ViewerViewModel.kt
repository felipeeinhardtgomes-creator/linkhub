package br.com.ppci.levantamento.ui.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.data.SurveyEntity
import br.com.ppci.levantamento.data.SurveyRepository
import br.com.ppci.levantamento.domain.Counts
import br.com.ppci.levantamento.domain.ItemStatus
import br.com.ppci.levantamento.export.CsvExporter
import br.com.ppci.levantamento.export.ReportPdfWriter
import br.com.ppci.levantamento.pdf.PdfPageSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val repo: SurveyRepository,
    @ApplicationContext private val app: Context
) : ViewModel() {

    private val surveyId = MutableStateFlow<Long?>(null)
    fun open(id: Long) { surveyId.value = id }

    val survey: StateFlow<SurveyEntity?> = surveyId.filterNotNull().flatMapLatest { repo.observeSurvey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val markers: StateFlow<List<MarkerEntity>> = surveyId.filterNotNull().flatMapLatest { repo.observeMarkers(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Contador em tempo real da barra superior. */
    val counts: StateFlow<Counts> = markers.map { list ->
        Counts(list.count { it.status == ItemStatus.MISSING }, list.count { it.status == ItemStatus.IRREGULAR }, list.count { it.status == ItemStatus.OK })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts(0, 0, 0))

    // A fonte do PDF vive no ViewModel: sobrevive a rotação e não reabre o arquivo à toa.
    private val _source = MutableStateFlow<PdfPageSource?>(null)
    val source: StateFlow<PdfPageSource?> = _source.asStateFlow()

    init {
        viewModelScope.launch {
            survey.filterNotNull().distinctUntilChangedBy { it.pdfPath to it.pageIndex }.collect { s ->
                _source.value?.close()
                _source.value = withContext(Dispatchers.IO) { PdfPageSource.open(File(s.pdfPath), s.pageIndex) }
            }
        }
    }

    fun save(m: MarkerEntity) { viewModelScope.launch { repo.upsert(m) } }
    fun delete(m: MarkerEntity) { viewModelScope.launch { repo.delete(m) } }


    fun newPhotoFile(): File = File(File(app.filesDir, "photos").apply { mkdirs() }, "m_${System.currentTimeMillis()}.jpg")

    private fun exportsDir() = File(app.filesDir, "exports").apply { mkdirs() }
    private fun stamp(s: SurveyEntity) = s.client.ifBlank { "obra" }.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)

    suspend fun exportCsv(grouped: Boolean): File? = withContext(Dispatchers.IO) {
        val s = survey.value ?: return@withContext null
        val list = repo.markerList(s.id)
        File(exportsDir(), "levantamento_${stamp(s)}_${if (grouped) "resumo" else "itens"}.csv")
            .apply { writeText(if (grouped) CsvExporter.grouped(list) else CsvExporter.items(list), Charsets.UTF_8) }
    }

    suspend fun exportPdf(): File? {
        val s = survey.value ?: return null
        val src = source.value ?: return null
        val list = repo.markerList(s.id)
        val plan = src.renderFull(2400) ?: return null // planta do relatório: 2400 px de largura, nítida em A4
        return withContext(Dispatchers.IO) {
            File(exportsDir(), "relatorio_ppci_${stamp(s)}.pdf").also { ReportPdfWriter().write(it, s, list, plan); plan.recycle() }
        }
    }

    override fun onCleared() { _source.value?.close() }
}
