package br.com.ppci.levantamento.export

import android.content.Context
import android.graphics.Bitmap
import br.com.ppci.levantamento.pdf.NormRect
import br.com.ppci.levantamento.pdf.PdfPageSource
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TitleBlock(val client: String = "", val site: String = "", val technician: String = "") {
    val isEmpty get() = client.isBlank() && site.isBlank() && technician.isBlank()
}

/**
 * Tenta preencher Cliente / Obra / Responsável a partir do carimbo da prancha.
 * 1) Texto vetorial (PDFs exportados do AutoCAD costumam ter texto real): PDFBox, só a página 1, com buffer em disco.
 * 2) Se não achar nada (carimbo "explodido" em linhas ou PDF escaneado): OCR offline (ML Kit) no canto inferior direito.
 * É sempre uma sugestão: o técnico confere e edita antes de iniciar.
 */
class TitleBlockReader(private val ctx: Context) {

    suspend fun read(file: File): TitleBlock = withContext(Dispatchers.IO) {
        val fromText = runCatching { parse(extractText(file)) }.getOrDefault(TitleBlock())
        if (!fromText.isEmpty) return@withContext fromText
        runCatching { parse(ocrCorner(file)) }.getOrDefault(TitleBlock())
    }

    private fun extractText(file: File): String =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { d ->
            PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(d)
        }

    private suspend fun ocrCorner(file: File): String {
        val src = PdfPageSource.open(file, 0)
        try {
            // Canto inferior direito (onde fica o carimbo), em ~2000 px para o OCR ler texto pequeno.
            val region = NormRect(0.55f, 0.70f, 1f, 1f)
            val bmp: Bitmap = src.renderRegion(region, 1800, (1800 * (region.height * src.pageHeightPt) / (region.width * src.pageWidthPt)).toInt().coerceAtLeast(200)) ?: return ""
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            return try { rec.process(InputImage.fromBitmap(bmp, 0)).await().text } finally { rec.close(); bmp.recycle() }
        } finally { src.close() }
    }

    private fun parse(raw: String): TitleBlock {
        val txt = raw.replace(Regex("\\s+"), " ")
        return TitleBlock(
            client = grab(txt, "Cliente", "Propriet[aá]rio"),
            site = grab(txt, "Obra", "Endere[cç]o", "Local"),
            technician = grab(txt, "Respons[aá]vel T[eé]cnico", "Resp\\. T[eé]c\\.", "RT")
        )
    }

    private fun grab(txt: String, vararg labels: String): String {
        val stop = "(?=\\s(?:Cliente|Obra|Endere[cç]o|Respons[aá]vel|CREA|Prancha|Escala|Data|Projeto|Folha|Formato|Desenho|Revis[aã]o)\\b|$)"
        for (l in labels) Regex("$l\\s*[:\\-]\\s*(.{3,90}?)$stop", RegexOption.IGNORE_CASE).find(txt)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
    }
}
