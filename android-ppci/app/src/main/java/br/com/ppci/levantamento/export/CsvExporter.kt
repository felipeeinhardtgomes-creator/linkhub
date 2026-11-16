package br.com.ppci.levantamento.export

import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.domain.ItemStatus

/** CSV com ';' e BOM UTF-8: abre direto no Excel em português, com acentos corretos. */
object CsvExporter {
    private fun esc(v: Any?): String {
        val s = v?.toString().orEmpty()
        return if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    private fun build(rows: List<List<Any?>>) = "﻿" + rows.joinToString("\r\n") { r -> r.joinToString(";") { esc(it) } }

    /** Item, Categoria, Especificação, Status, Quantidade, Observação */
    fun items(markers: List<MarkerEntity>): String = build(
        listOf(listOf("Item", "Categoria", "Especificação", "Status", "Quantidade", "Observação")) +
            markers.mapIndexed { i, m -> listOf(i + 1, m.category.label, m.spec, m.status.label, 1, m.note) }
    )

    /** Pronto para cruzar com a tabela de preços: uma linha por categoria + especificação + status. */
    fun grouped(markers: List<MarkerEntity>): String {
        val rows = mutableListOf<List<Any?>>(listOf("Categoria", "Especificação", "Status", "Serviço a orçar", "Quantidade", "Itens (pinos)"))
        Summary.group(markers).forEach { g ->
            ItemStatus.values().forEach { s ->
                val n = g.count[s] ?: 0
                if (n > 0) rows += listOf(g.category.label, g.spec, s.label, s.service, n, g.numbers[s].orEmpty().joinToString(" ") { "#$it" })
            }
        }
        return build(rows)
    }
}
