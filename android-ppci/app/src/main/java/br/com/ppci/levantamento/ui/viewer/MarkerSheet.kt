package br.com.ppci.levantamento.ui.viewer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.ppci.levantamento.R
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

/**
 * Menu compacto de marcação. Pensado para uma mão: categoria -> (especificação) -> toque no status SALVA.
 * "NÃO TEM" é o botão maior e vem primeiro, porque é o que entra no orçamento.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MarkerSheet(
    number: Int,
    initialCategory: Category,
    initialSpec: String,
    initialNote: String,
    initialPhoto: String?,
    currentStatus: ItemStatus?,
    newPhotoFile: () -> File,
    shrinkPhoto: suspend (String) -> Unit,
    onSave: (Category, String, ItemStatus, String, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var spec by rememberSaveable { mutableStateOf(initialSpec) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var photo by rememberSaveable { mutableStateOf(initialPhoto) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pending
        if (ok && path != null) scope.launch { shrinkPhoto(path); photo = path } // reduz antes de anexar
    }

    fun save(s: ItemStatus) = onSave(category, spec.trim(), s, note.trim(), photo)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (onDelete == null) stringResource(R.string.sheet_new, number) else stringResource(R.string.sheet_edit, number),
                style = MaterialTheme.typography.titleMedium
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.values().forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.short) }) }
            }
            if (category.subtypes.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                category.subtypes.forEach { s -> SuggestionChip(onClick = { spec = s }, label = { Text(s) }) }
            }
            OutlinedTextField(spec, { spec = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.spec_label)) }, singleLine = true)
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.note_label)) }, minLines = 2)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val f = newPhotoFile(); pending = f.absolutePath
                    camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                }) {
                    Icon(Icons.Filled.PhotoCamera, null)
                    Text("  " + stringResource(if (photo == null) R.string.photo_add else R.string.photo_replace))
                }
                photo?.let { AsyncImage(File(it), null, Modifier.size(56.dp)) }
            }

            Text(stringResource(if (onDelete == null) R.string.status_tap_to_save else R.string.status_label), style = MaterialTheme.typography.labelMedium)
            StatusButton(stringResource(R.string.status_missing_btn), ItemStatus.MISSING, Color.White, Modifier.height(64.dp), currentStatus) { save(it) }
            StatusButton(stringResource(R.string.status_irregular_btn), ItemStatus.IRREGULAR, Color(0xFF1A1200), Modifier.height(52.dp), currentStatus) { save(it) }
            StatusButton(stringResource(R.string.status_ok_btn), ItemStatus.OK, Color.White, Modifier.height(52.dp), currentStatus) { save(it) }

            if (onDelete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = ItemStatus.MISSING.color())) { Text(stringResource(R.string.delete)) }
                Button(onClick = { currentStatus?.let(::save) }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun StatusButton(label: String, status: ItemStatus, ink: Color, modifier: Modifier, current: ItemStatus?, onClick: (ItemStatus) -> Unit) {
    Button(
        onClick = { onClick(status) },
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = status.color(), contentColor = ink),
        border = if (current == status) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
}
