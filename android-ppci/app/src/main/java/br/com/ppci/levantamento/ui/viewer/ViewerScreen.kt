package br.com.ppci.levantamento.ui.viewer

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import kotlinx.coroutines.launch
import java.io.File

private data class Editing(val markerId: Long?, val x: Float, val y: Float)

@Composable
fun ViewerScreen(surveyId: Long, onBack: () -> Unit, vm: ViewerViewModel = hiltViewModel()) {
    LaunchedEffect(surveyId) { vm.open(surveyId) }
    val source by vm.source.collectAsState()
    val markers by vm.markers.collectAsState()
    val counts by vm.counts.collectAsState()
    val survey by vm.survey.collectAsState()
    val viewer = remember { ViewerState() }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var lastCategory by remember { mutableStateOf(Category.EXTINGUISHER) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun share(f: File?, mime: String) {
        f ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // Barra superior: contador em tempo real
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                Column(Modifier.weight(1f)) {
                    Text(survey?.client?.ifBlank { "Levantamento" } ?: "Levantamento", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("Orçar: ${counts.toBudget} itens  |  Existentes: ${counts.existing}", style = MaterialTheme.typography.bodyMedium)
                }
                Box {
                    if (busy) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, "Exportar") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Relatório técnico (PDF)") }, {
                            menu = false; busy = true
                            scope.launch { share(vm.exportPdf(), "application/pdf"); busy = false }
                        })
                        DropdownMenuItem({ Text("CSV item a item (Excel)") }, { menu = false; scope.launch { share(vm.exportCsv(false), "text/csv") } })
                        DropdownMenuItem({ Text("CSV resumo agrupado (orçamento)") }, { menu = false; scope.launch { share(vm.exportCsv(true), "text/csv") } })
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val src = source
            if (src == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else {
                PlanViewer(
                    source = src,
                    markers = markers.mapIndexed { i, m -> ViewerMarker(m.id, i + 1, m.x, m.y, m.status) },
                    selectedId = editing?.markerId,
                    draft = editing?.takeIf { it.markerId == null }?.let { Offset(it.x, it.y) },
                    onTapEmpty = { x, y -> editing = Editing(null, x, y) },
                    onTapMarker = { id -> markers.firstOrNull { it.id == id }?.let { editing = Editing(it.id, it.x, it.y) } },
                    state = viewer
                )
                Column(Modifier.align(Alignment.CenterEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val center = Offset(viewer.viewport.width / 2f, viewer.viewport.height / 2f)
                    FilledIconButton(onClick = { viewer.zoomAround(center, 1.5f, src.aspect) }) { Icon(Icons.Filled.Add, "Mais zoom") }
                    FilledIconButton(onClick = { viewer.zoomAround(center, 1f / 1.5f, src.aspect) }) { Icon(Icons.Filled.Remove, "Menos zoom") }
                    FilledIconButton(onClick = { viewer.reset(src.aspect) }) { Icon(Icons.Filled.FitScreen, "Enquadrar planta") }
                }
                Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
                    Text("${(viewer.zoom * 100).toInt()}%", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    editing?.let { e ->
        val existing = markers.firstOrNull { it.id == e.markerId }
        MarkerSheet(
            number = if (existing != null) markers.indexOf(existing) + 1 else markers.size + 1,
            initialCategory = existing?.category ?: lastCategory,
            initialSpec = existing?.spec.orEmpty(),
            initialNote = existing?.note.orEmpty(),
            initialPhoto = existing?.photoPath,
            currentStatus = existing?.status,
            newPhotoFile = vm::newPhotoFile,
            onSave = { cat, spec, status, note, photo ->
                lastCategory = cat
                val base = existing ?: MarkerEntity(surveyId = surveyId, x = e.x, y = e.y, category = cat, status = status, createdAt = System.currentTimeMillis())
                vm.save(base.copy(category = cat, spec = spec, status = status, note = note, photoPath = photo))
                editing = null
            },
            onDelete = existing?.let { m -> { vm.delete(m); editing = null } },
            onDismiss = { editing = null }
        )
    }
}
