package br.com.ppci.levantamento.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {
    @Test fun levelGrowsWithZoom() {
        assertEquals(0, TileMath.levelFor(900f))
        assertEquals(1, TileMath.levelFor(1100f))
        assertEquals(4, TileMath.levelFor(16_500f))
        assertEquals(4, TileMath.levelFor(40_000f)) // limite máximo
    }

    @Test fun fullPageCoversExactlyOnce() {
        val tiles = TileMath.visibleTiles(2, 0.707f, NormRect(0f, 0f, 1f, 1f))
        val area = tiles.sumOf { (it.region.width * it.region.height).toDouble() }
        assertEquals(1.0, area, 1e-4)
        assertTrue(tiles.all { it.pxWidth in 1..TileMath.TILE_PX && it.pxHeight in 1..TileMath.TILE_PX })
    }

    @Test fun smallViewportLoadsFewTiles() {
        val tiles = TileMath.visibleTiles(4, 0.707f, NormRect(0.40f, 0.40f, 0.46f, 0.46f))
        assertTrue("got ${tiles.size}", tiles.size in 1..16)
    }

    @Test fun offPageViewportIsEmpty() {
        assertTrue(TileMath.visibleTiles(1, 1f, NormRect(1.2f, 0f, 1.5f, 1f)).isEmpty())
    }
}
