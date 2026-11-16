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

/**
 * Tenta preencher Cliente / Obra / Responsável a partir do carimbo da prancha.
 * 1) Texto do PDF (PDFBox, só a página 1, buffer em disco). Nas 3 plantas reais testadas quase tudo do carimbo
 *    NÃO é texto (fonte de traço do AutoCAD), então este caminho raramente basta.
 * 2) OCR offline (ML Kit) em uma região ampla da folha.
 * 3) Sempre que errar: o técnico usa "Ler carimbo desta tela" depois de aproximar o zoom do carimbo.
 * É só sugestão: o técnico confere e edita.
 */
class TitleBlockReader(private val ctx: Context) {

    suspend fun read(file: File): TitleBlock = withContext(Dispatchers.IO) {
        val fromText = runCatching { TitleBlockParser.parse(extractText(file)) }.getOrDefault(TitleBlock())
        if (!fromText.isEmpty) return@withContext fromText
        runCatching { TitleBlockParser.parse(ocrWholeSheetRegion(file)) }.getOrDefault(TitleBlock())
    }

    private fun extractText(file: File): String =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { d ->
            PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(d)
        }

    /**
     * Nas plantas reais o carimbo ficou no canto (A0 cheia) ou no meio-direita (folha com margem grande), e os
     * valores (cliente, endereço) vêm em fonte de traço única do AutoCAD: não são texto no PDF, só o OCR lê.
     * Por isso o fallback cobre a metade direita da folha, em 3000 px de largura.
     */
    private suspend fun ocrWholeSheetRegion(file: File): String {
        val src = PdfPageSource.open(file, 0)
        try { return ocrRegion(src, NormRect(0.45f, 0.25f, 1f, 1f), 3000) } finally { src.close() }
    }

    /** OCR de uma região qualquer da página (usado também por "Ler carimbo desta tela"). */
    suspend fun ocrRegion(src: PdfPageSource, region: NormRect, widthPx: Int): String {
        val h = (widthPx * (region.height * src.pageHeightPt) / (region.width * src.pageWidthPt)).toInt().coerceIn(200, 4000)
        val bmp: Bitmap = src.renderRegion(region, widthPx, h) ?: return ""
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { rec.process(InputImage.fromBitmap(bmp, 0)).await().text } finally { rec.close(); bmp.recycle() }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
    }
}
