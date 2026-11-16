package br.com.ppci.levantamento.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.data.SurveyEntity
import br.com.ppci.levantamento.domain.ItemStatus
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/**
 * Relatório técnico em PDF (A4) usando só a API nativa (android.graphics.pdf.PdfDocument): sem dependência extra.
 * Seções: cabeçalho + resumo quantitativo, planta anotada, detalhamento item a item, anexo fotográfico.
 */
class ReportPdfWriter {
    private val W = 595; private val H = 842; private val M = 36f
    private val cw get() = W - 2 * M

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.rgb(17, 24, 39) }
    private val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
    private val h1 = TextPaint(bold).apply { textSize = 17f }
    private val h2 = TextPaint(bold).apply { textSize = 10.5f; letterSpacing = 0.05f }
    private val small = TextPaint(body).apply { textSize = 7.5f; color = Color.rgb(91, 103, 118) }
    private val line = Paint().apply { color = Color.rgb(201, 207, 214); strokeWidth = 0.7f }
    private val accent = Paint().apply { color = Color.rgb(217, 80, 11); strokeWidth = 2.5f }

    private lateinit var doc: PdfDocument
    private lateinit var page: PdfDocument.Page
    private lateinit var cv: Canvas
    private var y = M
    private var pageNo = 0
    private lateinit var title: String

    fun write(out: File, survey: SurveyEntity, markers: List<MarkerEntity>, planBitmap: Bitmap) {
        doc = PdfDocument(); title = survey.company
        newPage()
        header(survey)
        kpis(markers)
        section("1. Resumo quantitativo para orçamento")
        val groups = Summary.group(markers)
        val toBudget = groups.filter { it.missing + it.irregular > 0 }
        table(listOf("Categoria", "Especificação", "Não tem", "Irregular", "Total"), floatArrayOf(175f, 165f, 60f, 60f, 63f),
            toBudget.map { listOf(it.category.label, it.spec.ifBlank { "Não especificado" }, it.missing.toString(), it.irregular.toString(), (it.missing + it.irregular).toString()) } +
                listOf(listOf("TOTAL A ORÇAR", "", markers.count { it.status == ItemStatus.MISSING }.toString(), markers.count { it.status == ItemStatus.IRREGULAR }.toString(), markers.count { it.status.budgeted }.toString())),
            boldLast = true, rightFrom = 2)
        section("2. Já existente no local (registro técnico)")
        table(listOf("Categoria", "Especificação", "Qtd."), floatArrayOf(205f, 258f, 60f),
            groups.filter { it.ok > 0 }.map { listOf(it.category.label, it.spec.ifBlank { "Não especificado" }, it.ok.toString()) }, rightFrom = 2)

        // Planta anotada em página própria
        newPage(); section("3. Planta anotada")
        val scale = min(cw / planBitmap.width, (H - y - M - 30f) / planBitmap.height)
        val pw = planBitmap.width * scale; val ph = planBitmap.height * scale; val px = M + (cw - pw) / 2f
        cv.drawBitmap(planBitmap, null, android.graphics.RectF(px, y, px + pw, y + ph), Paint(Paint.FILTER_BITMAP_FLAG))
        cv.drawRect(px, y, px + pw, y + ph, Paint().apply { style = Paint.Style.STROKE; color = line.color; strokeWidth = 0.7f })
        markers.forEachIndexed { i, m -> pin(px + m.x * pw, y + m.y * ph, i + 1, m.status) } // mesmas coordenadas normalizadas da tela
        y += ph + 8f

        // Detalhamento
        newPage(); section("4. Detalhamento item a item")
        table(listOf("Nº", "Categoria", "Especificação", "Status", "Observações de campo"), floatArrayOf(28f, 115f, 105f, 62f, 213f),
            markers.mapIndexed { i, m -> listOf("#${i + 1}", m.category.label, m.spec.ifBlank { "—" }, m.status.label, m.note) })

        // Anexo fotográfico
        val withPhoto = markers.withIndex().filter { it.value.photoPath != null && File(it.value.photoPath!!).exists() }
        if (withPhoto.isNotEmpty()) {
            newPage(); section("5. Anexo fotográfico")
            withPhoto.forEach { (i, m) ->
                val bmp = decodeScaled(m.photoPath!!, 1000) ?: return@forEach
                val ph2 = min(230f, cw * 0.6f * bmp.height / bmp.width); val pw2 = ph2 * bmp.width / bmp.height
                ensure(ph2 + 40f)
                cv.drawText("#${i + 1} · ${m.category.label}${if (m.spec.isNotBlank()) " · " + m.spec else ""} · ${m.status.label}", M, y + 9f, bold); y += 14f
                if (m.note.isNotBlank()) y += text(m.note, M, cw, body) + 4f
                cv.drawBitmap(bmp, null, android.graphics.RectF(M, y, M + pw2, y + ph2), Paint(Paint.FILTER_BITMAP_FLAG)); y += ph2 + 16f
                bmp.recycle()
            }
        }
        finishPage()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    // ---- blocos ----
    private fun header(s: SurveyEntity) {
        cv.drawText("Relatório de Levantamento para Orçamento", M, y + 14f, h1)
        val right = bold.measureText(s.company)
        cv.drawText(s.company, W - M - right, y + 12f, bold)
        y += 22f
        cv.drawText("Plano de Prevenção e Proteção Contra Incêndio — PPCI", M, y + 6f, small)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.createdAt))
        cv.drawText("Visita em $date", W - M - small.measureText("Visita em $date"), y + 6f, small)
        y += 14f; cv.drawLine(M, y, W - M, y, accent); y += 12f
        listOf("Cliente" to s.client, "Obra / Endereço" to s.site, "Responsável técnico (CREA)" to s.technician).forEach { (k, v) ->
            cv.drawText(k.uppercase(), M, y + 6f, small); y += 10f; y += text(v.ifBlank { "—" }, M, cw, body) + 6f
        }
    }
    private fun kpis(markers: List<MarkerEntity>) {
        val items = listOf("Itens a orçar" to markers.count { it.status.budgeted }, "Não tem" to markers.count { it.status == ItemStatus.MISSING },
            "Irregular" to markers.count { it.status == ItemStatus.IRREGULAR }, "Existentes" to markers.count { it.status == ItemStatus.OK })
        val w = (cw - 3 * 6f) / 4f
        items.forEachIndexed { i, (label, n) ->
            val x = M + i * (w + 6f)
            cv.drawRect(x, y, x + w, y + 38f, Paint().apply { style = Paint.Style.STROKE; color = line.color; strokeWidth = 0.7f })
            cv.drawText(n.toString(), x + 6f, y + 22f, TextPaint(bold).apply { textSize = 18f }); cv.drawText(label.uppercase(), x + 6f, y + 33f, small)
        }
        y += 48f
    }
    private fun section(t: String) { ensure(40f); y += 6f; cv.drawText(t.uppercase(), M, y + 10f, h2); y += 14f; cv.drawLine(M, y, W - M, y, Paint().apply { color = Color.rgb(17, 24, 39); strokeWidth = 1.5f }); y += 6f }

    private fun table(headers: List<String>, widths: FloatArray, rows: List<List<String>>, boldLast: Boolean = false, rightFrom: Int = Int.MAX_VALUE) {
        fun row(cells: List<String>, paint: TextPaint, fill: Int?) {
            val layouts = cells.mapIndexed { i, c -> layout(c, widths[i] - 8f, paint, if (i >= rightFrom) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL) }
            val h = (layouts.maxOf { it.height } + 8f)
            ensure(h)
            if (fill != null) cv.drawRect(M, y, W - M, y + h, Paint().apply { color = fill })
            var x = M
            layouts.forEachIndexed { i, l -> cv.save(); cv.translate(x + 4f, y + 4f); l.draw(cv); cv.restore(); x += widths[i] }
            cv.drawLine(M, y + h, W - M, y + h, line); y += h
        }
        row(headers, small.let { TextPaint(it).apply { typeface = Typeface.DEFAULT_BOLD } }, Color.rgb(243, 244, 246))
        if (rows.isEmpty()) { cv.drawText("Nenhum item.", M + 4f, y + 12f, small); y += 18f; return }
        rows.forEachIndexed { i, r -> row(r, if (boldLast && i == rows.lastIndex) bold else body, if (boldLast && i == rows.lastIndex) Color.rgb(243, 244, 246) else null) }
    }

    private fun pin(cx: Float, cy: Float, n: Int, s: ItemStatus) {
        val col = when (s) { ItemStatus.MISSING -> Color.rgb(217, 45, 32); ItemStatus.IRREGULAR -> Color.rgb(245, 164, 0); ItemStatus.OK -> Color.rgb(21, 128, 61) }
        cv.drawCircle(cx, cy, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        cv.drawCircle(cx, cy, 6.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (s == ItemStatus.IRREGULAR) Color.BLACK else Color.WHITE; textSize = 6.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        cv.drawText(n.toString(), cx, cy + 2.3f, p)
    }

    // ---- infraestrutura de paginação ----
    private fun newPage() { if (pageNo > 0) finishPage(); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); cv = page.canvas; y = M }
    private fun finishPage() { cv.drawText("$title · página $pageNo", M, H - 18f, small); doc.finishPage(page) }
    private fun ensure(h: Float) { if (y + h > H - M - 10f) newPage() }
    private fun layout(t: String, w: Float, p: TextPaint, a: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w.toInt().coerceAtLeast(10)).setAlignment(a).build()
    private fun text(t: String, x: Float, w: Float, p: TextPaint): Float {
        val l = layout(t, w, p); ensure(l.height.toFloat()); cv.save(); cv.translate(x, y); l.draw(cv); cv.restore(); return l.height.toFloat()
    }
    private fun decodeScaled(path: String, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, o)
        var s = 1; while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
