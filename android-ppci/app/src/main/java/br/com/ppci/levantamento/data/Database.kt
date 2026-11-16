package br.com.ppci.levantamento.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Dao
interface SurveyDao {
    @Insert suspend fun insert(survey: SurveyEntity): Long
    @Update suspend fun update(survey: SurveyEntity)
    @Query("SELECT * FROM survey WHERE id = :id") fun observe(id: Long): Flow<SurveyEntity?>
    @Query("SELECT * FROM survey ORDER BY createdAt DESC") fun observeAll(): Flow<List<SurveyEntity>>
}

@Dao
interface MarkerDao {
    /** A numeração (#1, #2...) é a posição nesta lista, ordenada por id. */
    @Query("SELECT * FROM marker WHERE surveyId = :surveyId ORDER BY id") fun observe(surveyId: Long): Flow<List<MarkerEntity>>
    @Query("SELECT * FROM marker WHERE surveyId = :surveyId ORDER BY id") suspend fun list(surveyId: Long): List<MarkerEntity>
    @Upsert suspend fun upsert(marker: MarkerEntity): Long
    @Delete suspend fun delete(marker: MarkerEntity)
}

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
    fun observeMarkers(surveyId: Long) = markers.observe(surveyId)
    suspend fun markerList(surveyId: Long) = markers.list(surveyId)
    suspend fun upsert(m: MarkerEntity) = markers.upsert(m)
    suspend fun delete(m: MarkerEntity) = markers.delete(m)

    /** Copia o PDF (pode ter dezenas de MB) para o armazenamento privado, em streaming. */
    suspend fun copyPlan(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "plans").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
        out
    }

    suspend fun createSurvey(form: SurveyForm, pdf: File): Long = surveys.insert(
        SurveyEntity(company = form.company, client = form.client, site = form.site, technician = form.technician,
            createdAt = System.currentTimeMillis(), pdfPath = pdf.absolutePath)
    )
}
