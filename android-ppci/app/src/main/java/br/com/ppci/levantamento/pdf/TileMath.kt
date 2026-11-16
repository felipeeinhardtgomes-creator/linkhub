package br.com.ppci.levantamento.pdf

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt

/** Retângulo em coordenadas normalizadas da página (0..1, origem no topo-esquerdo). */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class TileKey(val level: Int, val col: Int, val row: Int)

/** Um tile a renderizar: que região da página e em quantos pixels. */
data class TileSpec(val key: TileKey, val region: NormRect, val pxWidth: Int, val pxHeight: Int)

/**
 * Pirâmide de níveis de detalhe. O nível L renderiza a página inteira com largura BASE_WIDTH_PX * 2^L,
 * fatiada em tiles de TILE_PX. Nenhum bitmap da página inteira em alta resolução existe na memória:
 * só os tiles visíveis (≈ 12 a 20 tiles de 1 MB) e um base de 1024 px.
 * Matemática pura (sem Android) para ser testável em JVM.
 */
object TileMath {
    const val TILE_PX = 512
    const val BASE_WIDTH_PX = 1024
    const val MAX_LEVEL = 4 // 1024 * 16 = 16384 px de largura: cobre zoom 15x em telas de ~1100 px

    /** Menor nível cuja resolução é >= à largura de tela do conteúdo (sem perder nitidez). */
    fun levelFor(contentWidthPx: Float): Int {
        if (contentWidthPx <= BASE_WIDTH_PX) return 0
        return ceil(log2(contentWidthPx / BASE_WIDTH_PX)).toInt().coerceIn(0, MAX_LEVEL)
    }

    fun levelWidth(level: Int) = BASE_WIDTH_PX shl level
    fun levelHeight(level: Int, aspect: Float) = (levelWidth(level) * aspect).roundToInt().coerceAtLeast(1)

    /** Tiles do [level] que cobrem a região [visible]; [aspect] = altura/largura da página. */
    fun visibleTiles(level: Int, aspect: Float, visible: NormRect): List<TileSpec> {
        if (visible.right <= 0f || visible.left >= 1f || visible.bottom <= 0f || visible.top >= 1f) return emptyList()
        val w = levelWidth(level)
        val h = levelHeight(level, aspect)
        val cols = ceil(w / TILE_PX.toFloat()).toInt()
        val rows = ceil(h / TILE_PX.toFloat()).toInt()
        val c0 = floor(visible.left.coerceIn(0f, 1f) * w / TILE_PX).toInt().coerceIn(0, cols - 1)
        val c1 = (ceil(visible.right.coerceIn(0f, 1f) * w / TILE_PX).toInt() - 1).coerceIn(c0, cols - 1)
        val r0 = floor(visible.top.coerceIn(0f, 1f) * h / TILE_PX).toInt().coerceIn(0, rows - 1)
        val r1 = (ceil(visible.bottom.coerceIn(0f, 1f) * h / TILE_PX).toInt() - 1).coerceIn(r0, rows - 1)
        val out = ArrayList<TileSpec>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (r in r0..r1) for (c in c0..c1) {
            val x0 = c * TILE_PX
            val y0 = r * TILE_PX
            val pw = min(TILE_PX, w - x0)
            val ph = min(TILE_PX, h - y0)
            out += TileSpec(
                TileKey(level, c, r),
                NormRect(x0 / w.toFloat(), y0 / h.toFloat(), (x0 + pw) / w.toFloat(), (y0 + ph) / h.toFloat()),
                pw, ph
            )
        }
        return out
    }
}
