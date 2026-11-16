package br.com.ppci.levantamento.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textos baseados nos carimbos de plantas A0 reais de AutoCAD (valores em fonte de traço, lidos por OCR). */
class TitleBlockParserTest {
    @Test fun ocrOfRealStampWithMissingColonsAndGluedLabel() {
        val ocr = """
            PROGRAMA : PLANTA BAIXA
            RESP. TECNICO:DINARTE AVILA DE OLIVEIRA JR
            ENGENHEIRO CIVIL E SEGURANÇA DO TRABALHO
            REG. CREA N°: RS 120025
            CLIENTE : TATIANE LEIRIA FERNANDES
            ENDEREÇO : RUA DOS PLATANOS 300
            DATA OUT 25 ESCALA 1:100 FOLHA 01
        """.trimIndent()
        val tb = TitleBlockParser.parse(ocr)
        assertEquals("TATIANE LEIRIA FERNANDES", tb.client)
        assertEquals("RUA DOS PLATANOS 300", tb.site)
        assertEquals("DINARTE AVILA DE OLIVEIRA JR — CREA-RS 120025", tb.technician)
    }

    @Test fun ocrThatDroppedColons() {
        val tb = TitleBlockParser.parse("CLIENTE TATIANE LEIRIA FERNANDES ENDEREÇO RUA DOS PLATANOS 300 ESCALA 1:100")
        assertEquals("TATIANE LEIRIA FERNANDES", tb.client)
        assertEquals("RUA DOS PLATANOS 300", tb.site)
    }

    @Test fun realTextLayerWithLabelFarFromValue() {
        // texto vetorial real da planta 2: o nome vem junto do CREA, o rótulo está em outro lugar
        val tb = TitleBlockParser.parse("PROPRIETÁRIO RESPONSÁVEL TÉCNICO Dinarte Avila de Oliveira Júnior CREA 120025 ENGENHEIRO CIVIL E ENGENHEIRO DE SEGURANÇA DO TRABALHO")
        assertEquals("Dinarte Avila de Oliveira Júnior — CREA 120025", tb.technician)
    }

    @Test fun legendTextDoesNotProduceFalseFields() {
        val tb = TitleBlockParser.parse("LEGENDA PPCI EXTINTOR PÓ QUIMICO SECO ( PQS ) - BC PLACA SAIDA 240/120")
        assertTrue(tb.isEmpty)
    }

    @Test fun emptyLabelFollowedByAnotherLabelIsIgnored() {
        val tb = TitleBlockParser.parse("OBRA : RESP. TECNICO: JOAO SILVA CREA 55555")
        assertEquals("", tb.site)
        assertEquals("JOAO SILVA — CREA 55555", tb.technician)
    }
}
