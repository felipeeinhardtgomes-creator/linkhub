package br.com.ppci.levantamento.backup

import android.content.Context
import br.com.ppci.levantamento.data.MarkerDao
import br.com.ppci.levantamento.data.MarkerEntity
import br.com.ppci.levantamento.data.SurveyDao
import br.com.ppci.levantamento.data.SurveyEntity
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupMarker(
    val page: Int, val x: Float, val y: Float, val category: String, val spec: String,
    val status: String, val note: String, val photo: String?, val createdAt: Long
)

@Serializable
data class BackupSurvey(
    val format: Int = 1, val company: String, val client: String, val site: String, val technician: String,
    val createdAt: Long, val markers: List<BackupMarker>
)

/**
 * Backup/restauração de UM levantamento em um .zip autocontido:
 *   survey.json  (dados + marcadores, com coordenadas normalizadas)
 *   plan.pdf     (a prancha)
 *   photos/*.jpg (fotos dos itens)
 * Serve para trocar de aparelho ou enviar a visita ao escritório. Nomes de entradas do zip nunca viram caminhos
 * livres (só `photos/<nome simples>` e `plan.pdf`), o que evita zip-slip.
 */
@Singleton
class BackupManager @Inject constructor(
    private val surveys: SurveyDao,
    private val markers: MarkerDao,
    @ApplicationContext private val ctx: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(surveyId: Long): File? = withContext(Dispatchers.IO) {
        val s = surveys.get(surveyId) ?: return@withContext null
        val list = markers.list(surveyId)
        val photos = list.mapNotNull { m -> m.photoPath?.let(::File)?.takeIf { it.exists() } }.distinctBy { it.name }
        val meta = BackupSurvey(
            company = s.company, client = s.client, site = s.site, technician = s.technician, createdAt = s.createdAt,
            markers = list.map { m ->
                BackupMarker(m.page, m.x, m.y, m.category.name, m.spec, m.status.name, m.note,
                    m.photoPath?.let { File(it).name }?.takeIf { n -> photos.any { it.name == n } }, m.createdAt)
            }
        )
        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
        val slug = s.client.ifBlank { "obra" }.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)
        val out = File(dir, "backup_${slug}.ppci.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("survey.json")); z.write(json.encodeToString(meta).toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("plan.pdf")); File(s.pdfPath).inputStream().use { it.copyTo(z) }; z.closeEntry()
            photos.forEach { f -> z.putNextEntry(ZipEntry("photos/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        out
    }

    /** Cria um NOVO levantamento a partir do backup (nunca sobrescreve um existente). */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val plansDir = File(ctx.filesDir, "plans").apply { mkdirs() }
        val photosDir = File(ctx.filesDir, "photos").apply { mkdirs() }
        val token = UUID.randomUUID().toString()
        var metaText: String? = null
        var plan: File? = null
        val photoMap = mutableMapOf<String, File>() // nome original -> arquivo restaurado
        try {
            ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name == "survey.json" -> metaText = z.readBytes().toString(Charsets.UTF_8)
                        e.name == "plan.pdf" -> plan = File(plansDir, "$token.pdf").also { f -> f.outputStream().use { z.copyTo(it) } }
                        e.name.startsWith("photos/") && !e.isDirectory -> {
                            val name = File(e.name).name
                            photoMap[name] = File(photosDir, "${token}_$name").also { f -> f.outputStream().use { z.copyTo(it) } }
                        }
                    }
                    z.closeEntry()
                }
            }
            val meta = json.decodeFromString<BackupSurvey>(metaText ?: error("Backup sem survey.json"))
            val pdf = plan ?: error("Backup sem plan.pdf")
            val pages = ctx.let { c ->
                android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                }
            }
            val id = surveys.insert(SurveyEntity(company = meta.company, client = meta.client, site = meta.site, technician = meta.technician,
                createdAt = meta.createdAt, pdfPath = pdf.absolutePath, pageCount = pages))
            markers.insertAll(meta.markers.map { m ->
                MarkerEntity(surveyId = id, page = m.page.coerceIn(0, pages - 1), x = m.x.coerceIn(0f, 1f), y = m.y.coerceIn(0f, 1f),
                    category = runCatching { Category.valueOf(m.category) }.getOrDefault(Category.OTHER), spec = m.spec,
                    status = runCatching { ItemStatus.valueOf(m.status) }.getOrDefault(ItemStatus.MISSING), note = m.note,
                    photoPath = m.photo?.let { photoMap[it]?.absolutePath }, createdAt = m.createdAt)
            })
            id
        } catch (t: Throwable) {
            plan?.delete(); photoMap.values.forEach { it.delete() }
            throw t
        }
    }
}
