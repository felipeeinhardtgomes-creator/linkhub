package br.com.ppci.levantamento.pdf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Roda as plantas REAIS (A0 do AutoCAD) no aparelho. Os PDFs de clientes não entram no repositório; envie-os assim:
 *
 *   adb push planta.pdf /sdcard/Android/data/br.com.ppci.levantamento.debug/files/plans/
 *   ./gradlew connectedDebugAndroidTest --tests '*RealPlansTest'
 *   adb logcat -s RealPlans
 *
 * Verifica o que a análise das 3 plantas de exemplo apontou como risco:
 *  - as folhas vêm com /Rotate 270 (2384x3370 pt retrato, exibidas em paisagem): o app precisa enxergá-las em paisagem;
 *  - tempo de tile no zoom máximo em CAD denso (no poppler de um servidor ficou entre 44 e 160 ms por tile).
 */
@RunWith(AndroidJUnit4::class)
class RealPlansTest {
    @Test fun realSheetsOpenInLandscapeAndRenderTiles() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "plans")
        val files = dir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.orEmpty()
        assumeTrue("Sem PDFs em ${dir.path} (veja o comentário da classe)", files.isNotEmpty())
        for (f in files) {
            val src = PdfPageSource.open(f, 0)
            try {
                Log.i("RealPlans", "${f.name}: ${src.pageWidthPt}x${src.pageHeightPt} pt, aspect=${src.aspect}")
                assertTrue("${f.name}: página rotacionada em retrato; /Rotate não foi aplicado?", src.pageWidthPt > src.pageHeightPt)
                assertNotNull(src.renderFull(TileMath.BASE_WIDTH_PX))
                for (level in listOf(2, TileMath.MAX_LEVEL)) {
                    val tiles = TileMath.visibleTiles(level, src.aspect, NormRect(0.30f, 0.30f, 0.38f, 0.38f)).take(6)
                    val ms = tiles.map { t -> val t0 = System.nanoTime(); assertNotNull(src.renderTile(t)); (System.nanoTime() - t0) / 1_000_000 }
                    Log.i("RealPlans", "${f.name} nível $level: tiles(ms)=$ms mediana=${ms.sorted()[ms.size / 2]}")
                }
            } finally { src.close() }
        }
    }
}
