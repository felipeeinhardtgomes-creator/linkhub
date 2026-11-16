package br.com.ppci.levantamento.pdf

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/** Cache LRU limitado por BYTES (não por contagem): protege contra OutOfMemoryError em A0. */
class TileCache(maxBytes: Int = defaultBudget()) {
    private val lru = object : LruCache<TileKey, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: TileKey, value: ImageBitmap) = value.width * value.height * 4
    }
    operator fun get(key: TileKey): ImageBitmap? = lru.get(key)
    fun put(key: TileKey, bmp: ImageBitmap) { lru.put(key, bmp) }
    fun clear() = lru.evictAll()

    companion object {
        /** 1/6 da heap, no máximo 192 MB, no mínimo 48 MB (≈ 48 a 190 tiles de 1 MB). */
        fun defaultBudget(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceIn(48L * 1024 * 1024, 192L * 1024 * 1024).toInt()
    }
}
