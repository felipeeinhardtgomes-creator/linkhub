package br.com.ppci.levantamento.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import br.com.ppci.levantamento.util.PhotoUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Levantamento + contagens para a lista da tela inicial. */
data class SurveyWithCount(@Embedded val survey: SurveyEntity, val markerCount: Int, val budgetCount: Int)

@Dao
interface SurveyDao {
    @Insert suspend fun insert(survey: SurveyEntity): Long
    @Update suspend fun update(survey: SurveyEntity)
    @Delete suspend fun delete(survey: SurveyEntity)
    @Query("SELECT * FROM survey WHERE id = :id") fun observe(id: Long): Flow<SurveyEntity?>
    @Query("SELECT * FROM survey WHERE id = :id") suspend fun get(id: Long): SurveyEntity?
    @Query(
        "SELECT s.*, " +
            "(SELECT COUNT(*) FROM marker m WHERE m.surveyId = s.id) AS markerCount, " +
            "(SELECT COUNT(*) FROM marker m WHERE m.surveyId = s.id AND m.status != 'OK') AS budgetCount " +
            "FROM survey s ORDER BY s.createdAt DESC"
    )
    fun observeAllWithCount(): Flow<List<SurveyWithCount>>
}

@Dao
interface MarkerDao {
    /** A numeração (#1, #2...) é a posição nesta lista, ordenada por id, em todas as pranchas. */
    @Query("SELECT * FROM marker WHERE surveyId = :surveyId ORDER BY id") fun observe(surveyId: Long): Flow<List<MarkerEntity>>
    @Query("SELECT * FROM marker WHERE surveyId = :surveyId ORDER BY id") suspend fun list(surveyId: Long): List<MarkerEntity>
    @Upsert suspend fun upsert(marker: MarkerEntity): Long
    @Insert suspend fun insertAll(markers: List<MarkerEntity>)
    @Delete suspend fun delete(marker: MarkerEntity)
}

// Versão 1 ainda não foi publicada: mudanças de esquema entram aqui sem migração.
// A partir da primeira versão distribuída, toda alteração exige Migration + teste (schemas/ é versionado).
@Database(entities = [SurveyEntity::class, MarkerEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun markerDao(): MarkerDao
}

data class SurveyForm(val company: String, val client: String, val site: String, val technician: String)

@Singleton
class SurveyRepository @Inject constructor(
    private val surveys: SurveyDao,
    private val markers: MarkerDao,
    @ApplicationContext private val ctx: Context
) {
    fun observeSurvey(id: Long) = surveys.observe(id)
    fun observeAll() = surveys.observeAllWithCount()
    fun observeMarkers(surveyId: Long) = markers.observe(surveyId)
    suspend fun markerList(surveyId: Long) = markers.list(surveyId)
    suspend fun upsert(m: MarkerEntity) = markers.upsert(m)
    suspend fun delete(m: MarkerEntity) {
        markers.delete(m)
        m.photoPath?.let { File(it).delete() }
    }

    /**
     * Importa PDF (copia em streaming) ou imagem PNG/JPG (convertida em PDF de 1 página, lado maior ≤ 4096 px):
     * assim todo o resto do app trabalha com um único formato.
     */
    suspend fun importPlan(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "plans").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.pdf")
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("image/")) {
            val bmp = PhotoUtils.decodeUpright(ctx, uri, 4096) ?: error("Imagem inválida")
            val doc = PdfDocument()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create())
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
            doc.close(); bmp.recycle()
        } else {
            ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
        }
        // Valida que abre como PDF; se não, não deixa lixo no armazenamento.
        runCatching { pageCount(out) }.onFailure { out.delete(); throw it }
        out
    }

    fun pageCount(pdf: File): Int =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }

    suspend fun createSurvey(form: SurveyForm, pdf: File): Long = withContext(Dispatchers.IO) {
        surveys.insert(
            SurveyEntity(company = form.company, client = form.client, site = form.site, technician = form.technician,
                createdAt = System.currentTimeMillis(), pdfPath = pdf.absolutePath, pageCount = pageCount(pdf))
        )
    }

    /** Remove o levantamento e todos os arquivos dele (PDF e fotos). */
    suspend fun deleteSurvey(s: SurveyEntity) = withContext(Dispatchers.IO) {
        markers.list(s.id).forEach { m -> m.photoPath?.let { File(it).delete() } }
        File(s.pdfPath).delete()
        surveys.delete(s)
    }
}
