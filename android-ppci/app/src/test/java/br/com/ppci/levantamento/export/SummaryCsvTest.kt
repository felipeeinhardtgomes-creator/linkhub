package br.com.ppci.levantamento.export

import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCsvTest {
    private fun m(cat: Category, spec: String, status: ItemStatus, note: String = "", page: Int = 0) =
        MarkerEntity(surveyId = 1, page = page, x = 0.5f, y = 0.5f, category = cat, spec = spec, status = status, note = note, createdAt = 0)

    private val sample = listOf(
        m(Category.EXTINGUISHER, "ABC 6kg", ItemStatus.MISSING),
        m(Category.EXTINGUISHER, " abc 6KG ", ItemStatus.IRREGULAR),   // mesma especificação: maiúsculas e espaços não separam grupos
        m(Category.EXTINGUISHER, "ABC 6kg", ItemStatus.OK),
        m(Category.SIGN, "Saída S1", ItemStatus.MISSING, "pé-direito 6m; precisa \"andaime\""),
    )

    @Test fun groupsBySpecIgnoringCaseAndSpaces() {
        val g = Summary.group(sample)
        assertEquals(2, g.size)
        val ext = g.single { it.category == Category.EXTINGUISHER }
        assertEquals(1, ext.missing); assertEquals(1, ext.irregular); assertEquals(1, ext.ok)
        assertEquals(listOf(1), ext.numbers[ItemStatus.MISSING]); assertEquals(listOf(2), ext.numbers[ItemStatus.IRREGULAR])
    }

    @Test fun groupsAreOrderedByCategory() {
        assertEquals(listOf(Category.SIGN, Category.EXTINGUISHER), Summary.group(sample).map { it.category })
    }

    @Test fun itemsCsvHasBomHeaderAndEscaping() {
        val csv = CsvExporter.items(sample)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("Item;Categoria;Especificação;Status;Quantidade;Observação", lines[0])
        assertEquals(5, lines.size)
        assertTrue(lines[4].endsWith("\"pé-direito 6m; precisa \"\"andaime\"\"\""))
    }

    @Test fun groupedCsvListsPinNumbers() {
        val lines = CsvExporter.grouped(sample).removePrefix("﻿").split("\r\n")
        assertTrue(lines.any { it.contains("NÃO TEM") && it.contains("#1") })
        assertTrue(lines.none { it.contains("Quantidade;0") })
    }

    @Test fun budgetFlagMatchesStatus() {
        assertTrue(ItemStatus.MISSING.budgeted); assertTrue(ItemStatus.IRREGULAR.budgeted); assertTrue(!ItemStatus.OK.budgeted)
    }
}
