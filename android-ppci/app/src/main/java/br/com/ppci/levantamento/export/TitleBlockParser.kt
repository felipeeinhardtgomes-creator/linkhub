package br.com.ppci.levantamento.export

data class TitleBlock(val client: String = "", val site: String = "", val technician: String = "") {
    val isEmpty get() = client.isBlank() && site.isBlank() && technician.isBlank()
}

/**
 * Interpreta o texto do carimbo (vindo do PDF ou do OCR). Lógica pura, testada em JVM com trechos reais de carimbos de AutoCAD.
 * Tolera: dois-pontos ausentes (OCR costuma perder), "RESP. TECNICO:NOME" colado, maiúsculas/minúsculas,
 * e o registro CREA em outra linha ("REG. CREA Nº: RS 120025").
 */
object TitleBlockParser {
    private const val STOP = "CLIENTE|PROPRIET[ÁA]RIO|OBRA|ENDERE[ÇC]O|LOCAL|RESP\\.?\\s*T[ÉE]CNICO|RESPONS[ÁA]VEL|CREA|REG\\.|ENGENHEIR[OA]|ARQUITET[OA]|T[ÉE]CNICO|PRANCHA|ESCALA|DATA|PROJETO|PROGRAMA|FOLHA|FORMATO|DESENHO|REVIS[ÃA]O|ASSUNTO|DESCRI[ÇC][ÃA]O|EMISS[ÃA]O"
    private val FLAGS = setOf(RegexOption.IGNORE_CASE)

    private fun field(txt: String, vararg labels: String): String {
        for (l in labels) {
            val m = Regex("\\b(?:$l)\\b\\s*[:\\-]?\\s*(.{3,90}?)(?=\\s+(?:$STOP)\\b|$)", FLAGS).find(txt) ?: continue
            val v = m.groupValues[1].trim().trim(':', '-', ' ')
            if (v.length >= 3 && !Regex("^(?:$STOP|RESP)", FLAGS).containsMatchIn(v)) return v // valor que já é o próximo rótulo = campo vazio
        }
        return ""
    }

    private fun crea(txt: String): String? {
        val m = Regex("CREA\\s*(?:N[º°O.]*)?\\s*[:\\-]?\\s*([A-Z]{2})?[\\s-]*(\\d{4,7})", FLAGS).find(txt) ?: return null
        val uf = m.groupValues[1].uppercase()
        return "CREA" + (if (uf.isNotEmpty()) "-$uf" else "") + " " + m.groupValues[2]
    }

    fun parse(raw: String): TitleBlock {
        val txt = raw.replace(Regex("\\s+"), " ")
        var tech = field(txt, "RESP\\.?\\s*T[ÉE]CNICO", "RESPONS[ÁA]VEL T[ÉE]CNICO")
        val reg = crea(txt)
        if (tech.isEmpty() && reg != null) {
            // PDF com texto real e rótulo longe do valor: "Dinarte Avila de Oliveira Júnior CREA 120025"
            Regex("((?:\\p{Lu}[\\p{L}.]*\\s+(?:(?:de|da|do|dos|das|e)\\s+)?){2,6})CREA").find(txt)?.let { tech = it.groupValues[1].trim() }
        }
        if (tech.isNotEmpty() && reg != null && !tech.contains("CREA", ignoreCase = true)) tech = "$tech — $reg"
        return TitleBlock(
            client = field(txt, "CLIENTE", "PROPRIET[ÁA]RIO"),
            site = field(txt, "OBRA", "ENDERE[ÇC]O", "LOCAL"),
            technician = tech
        )
    }
}
