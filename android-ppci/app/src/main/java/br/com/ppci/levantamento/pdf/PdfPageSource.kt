package br.com.ppci.levantamento.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Uma página de PDF aberta para renderização por região.
 *
 * - PdfRenderer NÃO é thread-safe e só permite UMA página aberta por vez: tudo roda em uma única
 *   thread dedicada e a página fica aberta durante toda a sessão de visualização.
 * - Cada tile é desenhado com uma Matrix (pontos da página -> pixels do tile), então o Pdfium
 *   rasteriza o vetor direto na escala final: linhas finas do AutoCAD ficam nítidas em qualquer zoom.
 */
class PdfPageSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val page: PdfRenderer.Page,
    private val dispatcher: CoroutineDispatcher,
    private val executor: java.util.concurrent.ExecutorService
) : Closeable {

    val pageWidthPt: Int = page.width
    val pageHeightPt: Int = page.height

    /** altura / largura */
    val aspect: Float = pageHeightPt.toFloat() / pageWidthPt.toFloat()

    suspend fun renderTile(spec: TileSpec): Bitmap? = renderRegion(spec.region, spec.pxWidth, spec.pxHeight)

    /** Página inteira com a largura pedida (base do visualizador, planta do relatório, OCR do carimbo). */
    suspend fun renderFull(widthPx: Int): Bitmap? =
        renderRegion(NormRect(0f, 0f, 1f, 1f), widthPx, (widthPx * aspect).roundToInt().coerceAtLeast(1))

    suspend fun renderRegion(region: NormRect, pxW: Int, pxH: Int): Bitmap? = withContext(dispatcher) {
        if (!coroutineContext.isActive) return@withContext null // pedido cancelado antes de começar
        val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE) // PDFs do CAD têm fundo transparente
        val m = Matrix().apply {
            postTranslate(-region.left * pageWidthPt, -region.top * pageHeightPt)
            postScale(pxW / (region.width * pageWidthPt), pxH / (region.height * pageHeightPt))
        }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    override fun close() {
        executor.execute {
            runCatching { page.close() }
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
        executor.shutdown()
    }

    companion object {
        /** Bloqueante: chame em Dispatchers.IO. */
        fun open(file: File, pageIndex: Int = 0): PdfPageSource {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
            val dispatcher = executor.asCoroutineDispatcher()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(pageIndex)
            return PdfPageSource(pfd, renderer, page, dispatcher, executor)
        }
    }
}
