package br.com.ppci.levantamento.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.ppci.levantamento.domain.ItemStatus
import br.com.ppci.levantamento.pdf.NormRect
import br.com.ppci.levantamento.pdf.PdfPageSource
import br.com.ppci.levantamento.pdf.TileCache
import br.com.ppci.levantamento.pdf.TileMath
import br.com.ppci.levantamento.pdf.TileSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

const val MIN_ZOOM = 1f
const val MAX_ZOOM = 15f

/** Marcador pronto para desenhar. x e y são NORMALIZADOS (0..1) na página. */
data class ViewerMarker(val id: Long, val number: Int, val x: Float, val y: Float, val status: ItemStatus)

fun ItemStatus.color(): Color = when (this) {
    ItemStatus.MISSING -> Color(0xFFD92D20)
    ItemStatus.IRREGULAR -> Color(0xFFF5A400)
    ItemStatus.OK -> Color(0xFF15803D)
}
private val DraftColor = Color(0xFFD9500B)

/**
 * Estado de câmera do visualizador. O conteúdo tem largura `fitWidth * zoom` px e é posicionado em `offset`.
 * Toda conversão tela <-> página passa por aqui, então pinos nunca dependem do zoom nem do tamanho da tela.
 */
@Stable
class ViewerState {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)

    fun fitWidth(aspect: Float) = min(viewport.width.toFloat(), viewport.height / aspect)
    fun contentWidth(aspect: Float) = fitWidth(aspect) * zoom
    fun contentHeight(aspect: Float) = contentWidth(aspect) * aspect

    fun toScreen(nx: Float, ny: Float, aspect: Float) =
        Offset(offset.x + nx * contentWidth(aspect), offset.y + ny * contentHeight(aspect))

    fun toNormalized(p: Offset, aspect: Float) =
        Offset((p.x - offset.x) / contentWidth(aspect), (p.y - offset.y) / contentHeight(aspect))

    fun visibleRegion(aspect: Float): NormRect {
        val a = toNormalized(Offset.Zero, aspect)
        val b = toNormalized(Offset(viewport.width.toFloat(), viewport.height.toFloat()), aspect)
        return NormRect(a.x, a.y, b.x, b.y)
    }

    fun reset(aspect: Float) {
        zoom = 1f
        val cw = fitWidth(aspect); val ch = cw * aspect
        offset = Offset((viewport.width - cw) / 2f, (viewport.height - ch) / 2f)
    }

    /** Zoom mantendo o ponto [focus] (em px de tela) fixo sob o dedo; [pan] soma o arrasto do gesto. */
    fun zoomAround(focus: Offset, factor: Float, aspect: Float, pan: Offset = Offset.Zero) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        zoom = newZoom
        offset = clamp(focus - (focus - offset) * f + pan, aspect)
    }

    fun focusOn(nx: Float, ny: Float, targetZoom: Float, aspect: Float) {
        zoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = clamp(Offset(viewport.width / 2f - nx * contentWidth(aspect), viewport.height / 2f - ny * contentHeight(aspect)), aspect)
    }

    /** Impede perder a página de vista: ao menos 25% do menor lado da tela fica sempre coberto. */
    private fun clamp(o: Offset, aspect: Float): Offset {
        val m = min(viewport.width, viewport.height) * 0.25f
        return Offset(
            o.x.coerceIn(m - contentWidth(aspect), viewport.width - m),
            o.y.coerceIn(m - contentHeight(aspect), viewport.height - m)
        )
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun PlanViewer(
    source: PdfPageSource,
    markers: List<ViewerMarker>,
    selectedId: Long?,
    draft: Offset?, // posição normalizada do pino em criação
    onTapEmpty: (nx: Float, ny: Float) -> Unit,
    onTapMarker: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
    state: ViewerState = remember { ViewerState() }
) {
    val aspect = source.aspect
    val cache = remember(source) { TileCache() }
    var revision by remember(source) { mutableIntStateOf(0) } // sobe a cada tile pronto: invalida o desenho
    val textMeasurer = rememberTextMeasurer()
    val currentMarkers by rememberUpdatedState(markers)

    // Base de baixa resolução (aparece instantaneamente; os tiles nítidos entram por cima).
    val base by produceState<ImageBitmap?>(null, source) {
        value = source.renderFull(TileMath.BASE_WIDTH_PX)?.asImageBitmap()
    }

    // Enquadra a página quando a tela é medida ou a planta muda.
    LaunchedEffect(source, state.viewport) { if (state.viewport.width > 0) state.reset(aspect) }

    // Pedidos de tile: reage a zoom/pan, espera o gesto assentar (60 ms) e cancela o que ficou obsoleto.
    LaunchedEffect(source) {
        snapshotFlow { currentTiles(state, aspect) }
            .distinctUntilChanged()
            .debounce(60)
            .collectLatest { wanted ->
                val center = Offset(state.viewport.width / 2f, state.viewport.height / 2f)
                val ordered = wanted.sortedBy { t ->
                    val c = state.toScreen((t.region.left + t.region.right) / 2f, (t.region.top + t.region.bottom) / 2f, aspect)
                    (c - center).getDistance()
                }
                for (t in ordered) {
                    if (cache[t.key] != null) continue
                    ensureActive()
                    val bmp = source.renderTile(t) ?: continue
                    cache.put(t.key, bmp.asImageBitmap())
                    revision++
                }
            }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.viewport = it }
            .pointerInput(source) {
                detectTransformGestures { centroid, pan, zoomChange, _ -> state.zoomAround(centroid, zoomChange, aspect, pan) }
            }
            .pointerInput(source) {
                detectTapGestures(
                    onDoubleTap = { p -> state.zoomAround(p, 2.5f, aspect) },
                    onTap = { p ->
                        val hitRadius = 28.dp.toPx()
                        val hit = currentMarkers
                            .map { it to (state.toScreen(it.x, it.y, aspect) - p).getDistance() }
                            .filter { it.second <= hitRadius }
                            .minByOrNull { it.second }
                        if (hit != null) onTapMarker(hit.first.id)
                        else {
                            val n = state.toNormalized(p, aspect)
                            if (n.x in 0f..1f && n.y in 0f..1f) onTapEmpty(n.x, n.y)
                        }
                    }
                )
            }
    ) {
        @Suppress("UNUSED_VARIABLE") val rev = revision // lê o estado na fase de desenho
        val cw = state.contentWidth(aspect); val ch = state.contentHeight(aspect)
        val o = state.offset

        drawRect(Color.White, topLeft = o, size = Size(cw, ch))
        base?.let { drawImage(it, dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()), dstSize = IntSize(cw.roundToInt(), ch.roundToInt())) }

        // Níveis 1..L em ordem crescente: o mais nítido por cima, os grossos cobrem lacunas enquanto carrega.
        val level = TileMath.levelFor(cw)
        val vis = state.visibleRegion(aspect)
        for (l in 1..level) for (t in TileMath.visibleTiles(l, aspect, vis)) {
            val img = cache[t.key] ?: continue
            val tl = state.toScreen(t.region.left, t.region.top, aspect)
            val br = state.toScreen(t.region.right, t.region.bottom, aspect)
            drawImage(img, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                dstSize = IntSize((br.x - tl.x).roundToInt() + 1, (br.y - tl.y).roundToInt() + 1)) // +1 px evita fresta entre tiles
        }

        // Pinos: posição vem das coordenadas normalizadas; tamanho em dp constante.
        markers.forEach { m -> drawPin(state.toScreen(m.x, m.y, aspect), m.status.color(), m.number.toString(), m.id == selectedId, textMeasurer) }
        draft?.let { drawPin(state.toScreen(it.x, it.y, aspect), DraftColor, null, true, textMeasurer) }
    }
}

private fun currentTiles(state: ViewerState, aspect: Float): List<TileSpec> {
    if (state.viewport.width == 0) return emptyList()
    val level = TileMath.levelFor(state.contentWidth(aspect))
    return if (level == 0) emptyList() else TileMath.visibleTiles(level, aspect, state.visibleRegion(aspect))
}

private fun DrawScope.drawPin(c: Offset, color: Color, label: String?, selected: Boolean, tm: androidx.compose.ui.text.TextMeasurer) {
    val r = 15.dp.toPx()
    if (selected) drawCircle(color.copy(alpha = 0.30f), r + 7.dp.toPx(), c)
    drawCircle(Color.White, r + 2.dp.toPx(), c, style = Stroke(2.dp.toPx())) // contorno branco: legível sobre linhas escuras
    drawCircle(color, r, c, style = Stroke(3.5.dp.toPx())) // anel vazado: o símbolo do CAD continua visível
    if (label != null) {
        val bc = Offset(c.x + r * 0.9f, c.y - r * 1.1f)
        drawCircle(Color.White, 10.dp.toPx(), bc)
        drawCircle(color, 9.dp.toPx(), bc)
        val ink = if (color == ItemStatus.IRREGULAR.color()) Color(0xFF1A1200) else Color.White
        val layout = tm.measure(label, TextStyle(color = ink, fontSize = 10.sp, textAlign = TextAlign.Center))
        drawText(layout, topLeft = Offset(bc.x - layout.size.width / 2f, bc.y - layout.size.height / 2f))
    }
}
