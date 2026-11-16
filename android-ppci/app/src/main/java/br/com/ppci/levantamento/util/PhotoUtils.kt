package br.com.ppci.levantamento.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

object PhotoUtils {
    /**
     * Reduz a foto da câmera para no máximo [maxSide] px (padrão 1600) e aplica a rotação do EXIF.
     * Uma foto de 12 MP (~4 MB) vira ~300 KB: dezenas de fotos por visita sem encher o armazenamento.
     */
    fun shrinkInPlace(file: File, maxSide: Int = 1600, quality: Int = 85) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val rotation = rotationDegrees(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
            val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nb !== bmp) { bmp.recycle(); bmp = nb }
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
    }

    /** Decodifica uma imagem do seletor de arquivos já na orientação correta e com lado maior ≤ [maxSide]. */
    fun decodeUpright(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = cr.openInputStream(uri)?.use { rotationDegrees(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) } ?: 0f
        val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
            val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nb !== bmp) { bmp.recycle(); bmp = nb }
        }
        return bmp
    }

    private fun rotationDegrees(exif: Int) = when (exif) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
}
