package br.com.ppci.levantamento.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Estresse sintético: página A1 (2384 x 1684 pt) com 40 000 linhas finas, imitando uma prancha de CAD densa.
 * Mede o tempo de tile no nível máximo (zoom 15x). O número real aparece no Logcat (tag TileBench):
 *   adb logcat -s TileBench
 * O limite do assert é só uma rede de segurança contra regressões grosseiras: o critério de aceite de verdade
 * é rodar o app com uma prancha A0 real em um tablet.
 */
@RunWith(AndroidJUnit4::class)
class TileRenderBenchmarkTest {
    @Test fun denseA1TileRendersInReasonableTime() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.cacheDir, "dense_a1.pdf")
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(2384, 1684, 1).create())
        val paint = Paint().apply { strokeWidth = 0.2f; isAntiAlias = true }
        val rnd = Random(42)
        repeat(40_000) {
            val x = rnd.nextFloat() * 2384; val y = rnd.nextFloat() * 1684
            page.canvas.drawLine(x, y, x + rnd.nextFloat() * 60 - 30, y + rnd.nextFloat() * 60 - 30, paint)
        }
        doc.finishPage(page); file.outputStream().use { doc.writeTo(it) }; doc.close()

        val src = PdfPageSource.open(file, 0)
        try {
            val tiles = TileMath.visibleTiles(TileMath.MAX_LEVEL, src.aspect, NormRect(0.45f, 0.45f, 0.55f, 0.55f))
            assertTrue(tiles.isNotEmpty())
            val times = tiles.take(6).map { t ->
                val t0 = System.nanoTime()
                assertNotNull(src.renderTile(t))
                (System.nanoTime() - t0) / 1_000_000
            }
            Log.i("TileBench", "tile ms (nível ${TileMath.MAX_LEVEL}): $times  mediana=${times.sorted()[times.size / 2]}")
            assertTrue("tile muito lento: $times", times.sorted()[times.size / 2] < 5_000)
        } finally { src.close() }
    }
}
