package br.com.ppci.levantamento.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.ppci.levantamento.R
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import br.com.ppci.levantamento.ui.viewer.MarkerSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MarkerSheetTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun tappingMissingSavesWithEnteredSpecAndNote() {
        var saved: List<Any?>? = null
        rule.setContent {
            PpciTheme {
                MarkerSheet(
                    number = 3, initialCategory = Category.EXTINGUISHER, initialSpec = "", initialNote = "", initialPhoto = null, currentStatus = null,
                    newPhotoFile = { File.createTempFile("p", ".jpg") }, shrinkPhoto = {}, recentSpecs = { kotlinx.coroutines.flow.flowOf(listOf("PQS ABC 6kg (usado antes)")) },
                    onSave = { c, s, st, n, p -> saved = listOf(c, s, st, n, p) }, onDelete = null, onDismiss = {}
                )
            }
        }
        rule.onNodeWithText("PQS ABC 6kg (usado antes)").performClick() // chip aprendido do uso, sem depender de legenda
        rule.onNodeWithText(ctx.getString(R.string.status_missing_btn)).performClick()
        assertEquals(listOf(Category.EXTINGUISHER, "PQS ABC 6kg (usado antes)", ItemStatus.MISSING, "", null), saved)
    }
}
