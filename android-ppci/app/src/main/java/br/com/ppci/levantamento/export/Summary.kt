package br.com.ppci.levantamento.export

import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus

/** Linha agrupada por categoria + especificação (sem diferenciar maiúsculas). */
data class SummaryRow(val category: Category, val spec: String, val count: Map<ItemStatus, Int>, val numbers: Map<ItemStatus, List<Int>>) {
    val missing get() = count[ItemStatus.MISSING] ?: 0
    val irregular get() = count[ItemStatus.IRREGULAR] ?: 0
    val ok get() = count[ItemStatus.OK] ?: 0
}

object Summary {
    fun group(markers: List<MarkerEntity>): List<SummaryRow> {
        class Acc(val cat: Category, val spec: String) {
            val count = mutableMapOf<ItemStatus, Int>(); val nums = mutableMapOf<ItemStatus, MutableList<Int>>()
        }
        val map = LinkedHashMap<String, Acc>()
        markers.forEachIndexed { i, m ->
            val spec = m.spec.trim()
            val acc = map.getOrPut(m.category.name + "|" + spec.lowercase()) { Acc(m.category, spec) }
            acc.count[m.status] = (acc.count[m.status] ?: 0) + 1
            acc.nums.getOrPut(m.status) { mutableListOf() } += i + 1
        }
        return map.values.map { SummaryRow(it.cat, it.spec, it.count, it.nums) }
            .sortedWith(compareBy({ it.category.ordinal }, { it.spec.lowercase() }))
    }
}
