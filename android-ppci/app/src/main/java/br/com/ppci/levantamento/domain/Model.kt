package br.com.ppci.levantamento.domain

enum class Category(val label: String, val short: String, val subtypes: List<String>) {
    SIGN("Placa de Sinalização", "Placa", listOf("Saída S1", "Saída S2", "Equip. E5 (extintor)", "Equip. E6 (hidrante)", "Alarme E2", "Rota de fuga")),
    EMERGENCY_LIGHT("Luminária de Emergência", "Luminária", listOf("Bloco autônomo", "Aclaramento", "Balizamento", "30 LEDs")),
    EXTINGUISHER("Extintor de Incêndio", "Extintor", listOf("ABC 6kg", "ABC 4kg", "PQS 12kg", "CO2 6kg", "Água 10L")),
    HYDRANT("Hidrante / Mangotinho", "Hidrante", listOf("Hidrante c/ mangueira 30m", "Mangotinho", "Registro de recalque")),
    SPRINKLER("SPK (Sprinkler)", "SPK", listOf("Pendente 68°C", "Upright 68°C", "Sidewall")),
    ALARM("Alarme / Acionador Manual", "Alarme", listOf("Acionador manual", "Sirene / avisador", "Central de alarme")),
    OTHER("Outros", "Outros", emptyList())
}

/** Status que decide se o item entra no orçamento. */
enum class ItemStatus(val label: String, val service: String, val budgeted: Boolean) {
    MISSING("NÃO TEM", "Fornecimento + instalação", true),
    IRREGULAR("IRREGULAR", "Manutenção / troca", true),
    OK("TEM / OK", "Existente (não orçar)", false)
}

data class Counts(val missing: Int, val irregular: Int, val ok: Int) {
    val toBudget get() = missing + irregular
    val existing get() = ok
}
