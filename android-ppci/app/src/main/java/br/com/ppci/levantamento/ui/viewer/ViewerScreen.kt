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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.ppci.levantamento.R
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.export.TitleBlock
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
    val page by vm.page.collectAsState()
    val openFailed by vm.openFailed.collectAsState()
    val viewer = remember { ViewerState() }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var lastCategory by remember { mutableStateOf(Category.EXTINGUISHER) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf<TitleBlock?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun share(f: File?, mime: String) {
        f ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
    }
    fun exporting(block: suspend () -> File?, mime: String) { menu = false; busy = true; scope.launch { try { share(block(), mime) } finally { busy = false } } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // Barra superior: contador em tempo real
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Column(Modifier.weight(1f)) {
                    Text(survey?.client?.ifBlank { null } ?: stringResource(R.string.untitled_survey), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(stringResource(R.string.viewer_counter, counts.toBudget, counts.existing), style = MaterialTheme.typography.bodyMedium)
                }
                Box {
                    if (busy) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, stringResource(R.string.export)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.read_stamp)) }, {
                            val src = source ?: return@DropdownMenuItem
                            menu = false; busy = true
                            scope.launch { try { stamp = vm.readTitleBlock(viewer.visibleRegion(src.aspect)) } finally { busy = false } }
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.export_pdf)) }, { exporting(vm::exportPdf, "application/pdf") })
                        DropdownMenuItem({ Text(stringResource(R.string.export_csv_items)) }, { exporting({ vm.exportCsv(false) }, "text/csv") })
                        DropdownMenuItem({ Text(stringResource(R.string.export_csv_grouped)) }, { exporting({ vm.exportCsv(true) }, "text/csv") })
                        DropdownMenuItem({ Text(stringResource(R.string.export_backup)) }, { exporting(vm::exportBackup, "application/zip") })
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val src = source
            when {
                openFailed -> Text(stringResource(R.string.viewer_open_failed), Modifier.align(Alignment.Center).padding(24.dp))
                src == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    PlanViewer(
                        source = src,
                        // a numeração é global (#1, #2... em todas as pranchas); só os pinos da prancha atual são desenhados
                        markers = markers.withIndex().filter { it.value.page == page }.map { (i, m) -> ViewerMarker(m.id, i + 1, m.x, m.y, m.status) },
                        selectedId = editing?.markerId,
                        draft = editing?.takeIf { it.markerId == null }?.let { Offset(it.x, it.y) },
                        onTapEmpty = { x, y -> editing = Editing(null, x, y) },
                        onTapMarker = { id -> markers.firstOrNull { it.id == id }?.let { editing = Editing(it.id, it.x, it.y) } },
                        state = viewer
                    )
                    Column(Modifier.align(Alignment.CenterEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val center = Offset(viewer.viewport.width / 2f, viewer.viewport.height / 2f)
                        FilledIconButton(onClick = { viewer.zoomAround(center, 1.5f, src.aspect) }) { Icon(Icons.Filled.Add, stringResource(R.string.zoom_in)) }
                        FilledIconButton(onClick = { viewer.zoomAround(center, 1f / 1.5f, src.aspect) }) { Icon(Icons.Filled.Remove, stringResource(R.string.zoom_out)) }
                        FilledIconButton(onClick = { viewer.reset(src.aspect) }) { Icon(Icons.Filled.FitScreen, stringResource(R.string.zoom_fit)) }
                    }
                    Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
                        Text("${(viewer.zoom * 100).toInt()}%", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val pages = survey?.pageCount ?: 1
            if (pages > 1) Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { editing = null; vm.setPage(page - 1) }, enabled = page > 0) { Icon(Icons.Filled.ChevronLeft, stringResource(R.string.page_prev)) }
                    Text(stringResource(R.string.page_label, page + 1, pages), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { editing = null; vm.setPage(page + 1) }, enabled = page < pages - 1) { Icon(Icons.Filled.ChevronRight, stringResource(R.string.page_next)) }
                }
            }
        }
    }

    stamp?.let { tb ->
        AlertDialog(
            onDismissRequest = { stamp = null },
            title = { Text(stringResource(R.string.stamp_title)) },
            text = { Text(if (tb.isEmpty) stringResource(R.string.stamp_none) else stringResource(R.string.stamp_result, tb.client.ifBlank { "—" }, tb.site.ifBlank { "—" }, tb.technician.ifBlank { "—" })) },
            confirmButton = { TextButton({ stamp = null }) { Text(stringResource(R.string.ok)) } }
        )
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
            shrinkPhoto = vm::shrinkPhoto,
            recentSpecs = vm::recentSpecs,
            onSave = { cat, spec, status, note, photo ->
                lastCategory = cat
                val base = existing ?: MarkerEntity(surveyId = surveyId, page = page, x = e.x, y = e.y, category = cat, status = status, createdAt = System.currentTimeMillis())
                vm.save(base.copy(category = cat, spec = spec, status = status, note = note, photoPath = photo))
                editing = null
            },
            onDelete = existing?.let { m -> { vm.delete(m); editing = null } },
            onDismiss = { editing = null }
        )
    }
}
