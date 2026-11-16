package br.com.ppci.levantamento.domain

/** Subtipos = nomenclatura das legendas de PPCI das plantas reais (extintores por agente/classe, placas por função, etc.). */
enum class Category(val label: String, val short: String, val subtypes: List<String>) {
    SIGN("Placa de Sinalização", "Placa", listOf("Saída", "Rota de fuga (direita)", "Rota de fuga (esquerda)", "Rota de fuga (em frente)", "Escada", "Extintor de incêndio", "Hidrante", "Alarme de incêndio", "Proibido fumar", "Porta corta-fogo")),
    EMERGENCY_LIGHT("Luminária de Emergência", "Luminária", listOf("Bloco autônomo de LEDs", "Bloco autônomo c/ 2 faroletes", "Aclaramento", "Balizamento (saída)", "Balizamento (direita)", "Balizamento (esquerda)")),
    EXTINGUISHER("Extintor de Incêndio", "Extintor", listOf("PQS BC", "PQS ABC", "CO2 BC", "Água pressurizada (AP) A", "Espuma (ESP) AB", "Sobre rodas (carreta)")),
    HYDRANT("Hidrante / Mangotinho", "Hidrante", listOf("Hidrante 1 saída", "Hidrante 2 saídas", "Hidrante c/ mangotinho", "Mangotinho", "Registro de recalque", "Bomba de pressurização")),
    SPRINKLER("SPK (Sprinkler)", "SPK", listOf("Pendente 68°C", "Upright 68°C", "Sidewall")),
    ALARM("Alarme / Acionador Manual", "Alarme", listOf("Botoeira quebra-vidro", "Sirene", "Sensor de fumaça", "Sensor de temperatura", "Central de alarme")),
    OTHER("Outros", "Outros", listOf("Barra antipânico", "Porta corta-fogo", "Válvula de bloqueio", "Reserva técnica", "Pintura de piso"))
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
