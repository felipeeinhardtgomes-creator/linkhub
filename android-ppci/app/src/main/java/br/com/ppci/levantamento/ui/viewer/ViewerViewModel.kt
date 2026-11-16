package br.com.ppci.levantamento.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.ppci.levantamento.backup.BackupManager
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.data.SurveyEntity
import br.com.ppci.levantamento.data.SurveyRepository
import br.com.ppci.levantamento.domain.Counts
import br.com.ppci.levantamento.domain.ItemStatus
import br.com.ppci.levantamento.export.CsvExporter
import br.com.ppci.levantamento.export.ReportPdfWriter
import br.com.ppci.levantamento.export.TitleBlock
import br.com.ppci.levantamento.export.TitleBlockParser
import br.com.ppci.levantamento.export.TitleBlockReader
import br.com.ppci.levantamento.pdf.NormRect
import br.com.ppci.levantamento.pdf.PdfPageSource
import br.com.ppci.levantamento.util.PhotoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.SortedMap
import java.util.TreeMap
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val repo: SurveyRepository,
    private val backup: BackupManager,
    @ApplicationContext private val app: Context
) : ViewModel() {

    private val surveyId = MutableStateFlow<Long?>(null)
    fun open(id: Long) { surveyId.value = id }

    val survey: StateFlow<SurveyEntity?> = surveyId.filterNotNull().flatMapLatest { repo.observeSurvey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val markers: StateFlow<List<MarkerEntity>> = surveyId.filterNotNull().flatMapLatest { repo.observeMarkers(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Contador em tempo real (todas as pranchas). */
    val counts: StateFlow<Counts> = markers.map { list ->
        Counts(list.count { it.status == ItemStatus.MISSING }, list.count { it.status == ItemStatus.IRREGULAR }, list.count { it.status == ItemStatus.OK })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts(0, 0, 0))

    // Prancha atual (PDF com várias páginas)
    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()
    fun setPage(p: Int) { _page.value = p.coerceIn(0, ((survey.value?.pageCount ?: 1) - 1).coerceAtLeast(0)) }

    // A fonte do PDF vive no ViewModel: sobrevive a rotação e não reabre o arquivo à toa.
    private val _source = MutableStateFlow<PdfPageSource?>(null)
    val source: StateFlow<PdfPageSource?> = _source.asStateFlow()
    private val _openFailed = MutableStateFlow(false)
    val openFailed: StateFlow<Boolean> = _openFailed.asStateFlow()

    init {
        viewModelScope.launch {
            combine(survey.filterNotNull().distinctUntilChangedBy { it.pdfPath }, _page) { s, p -> s to p }.collect { (s, p) ->
                _source.value?.close()
                _source.value = null
                _openFailed.value = false
                runCatching { withContext(Dispatchers.IO) { PdfPageSource.open(File(s.pdfPath), p) } }
                    .onSuccess { _source.value = it }
                    .onFailure { _openFailed.value = true }
            }
        }
    }

    fun recentSpecs(c: br.com.ppci.levantamento.domain.Category) = repo.recentSpecs(c)

    fun save(m: MarkerEntity) { viewModelScope.launch { repo.upsert(m) } }
    fun delete(m: MarkerEntity) { viewModelScope.launch { repo.delete(m) } }

    fun newPhotoFile(): File = File(File(app.filesDir, "photos").apply { mkdirs() }, "m_${System.currentTimeMillis()}.jpg")

    /** Reduz a foto da câmera (1600 px, EXIF aplicado) antes de anexar ao item. */
    suspend fun shrinkPhoto(path: String) = withContext(Dispatchers.IO) { PhotoUtils.shrinkInPlace(File(path)) }

    private fun exportsDir() = File(app.filesDir, "exports").apply { mkdirs() }
    private fun stamp(s: SurveyEntity) = s.client.ifBlank { "obra" }.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)

    suspend fun exportCsv(grouped: Boolean): File? = withContext(Dispatchers.IO) {
        val s = survey.value ?: return@withContext null
        val list = repo.markerList(s.id)
        File(exportsDir(), "levantamento_${stamp(s)}_${if (grouped) "resumo" else "itens"}.csv")
            .apply { writeText(if (grouped) CsvExporter.grouped(list) else CsvExporter.items(list), Charsets.UTF_8) }
    }

    /** Relatório: uma planta anotada por prancha que tenha marcadores (ou a primeira, se não houver nenhum). */
    suspend fun exportPdf(): File? {
        val s = survey.value ?: return null
        val list = repo.markerList(s.id)
        val pages = list.map { it.page }.distinct().sorted().ifEmpty { listOf(0) }
        val plans: SortedMap<Int, Bitmap> = TreeMap()
        try {
            for (p in pages) {
                val src = withContext(Dispatchers.IO) { PdfPageSource.open(File(s.pdfPath), p) }
                try { src.renderFull(2400)?.let { plans[p] = it } } finally { src.close() }
            }
            return withContext(Dispatchers.IO) {
                File(exportsDir(), "relatorio_ppci_${stamp(s)}.pdf").also { ReportPdfWriter().write(it, s, list, plans) }
            }
        } finally { plans.values.forEach { it.recycle() } }
    }

    /**
     * "Ler carimbo desta tela": OCR só da região visível (o técnico aproxima o zoom do carimbo antes).
     * Nas plantas reais os valores do carimbo são fonte de traço, então esta é a via confiável. Preenche os campos
     * do levantamento que o OCR reconheceu e devolve o que leu para o técnico conferir.
     */
    suspend fun readTitleBlock(region: NormRect): TitleBlock {
        val src = source.value ?: return TitleBlock()
        val s = survey.value ?: return TitleBlock()
        val r = NormRect(region.left.coerceAtLeast(0f), region.top.coerceAtLeast(0f), region.right.coerceAtMost(1f), region.bottom.coerceAtMost(1f))
        if (r.width <= 0f || r.height <= 0f) return TitleBlock()
        val text = runCatching { TitleBlockReader(app).ocrRegion(src, r, 2400) }.getOrDefault("")
        val tb = TitleBlockParser.parse(text)
        if (!tb.isEmpty) repo.updateSurvey(s.copy(client = tb.client.ifBlank { s.client }, site = tb.site.ifBlank { s.site }, technician = tb.technician.ifBlank { s.technician }))
        return tb
    }

    suspend fun exportBackup(): File? = surveyId.value?.let { backup.export(it) }

    override fun onCleared() { _source.value?.close() }
}
