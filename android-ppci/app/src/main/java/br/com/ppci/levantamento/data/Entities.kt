package br.com.ppci.levantamento.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus

@Entity(tableName = "survey")
data class SurveyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val company: String,
    val client: String,
    val site: String,
    val technician: String,
    val createdAt: Long,
    /** PDF copiado para filesDir/plans (imagens são convertidas em PDF de 1 página na importação). */
    val pdfPath: String,
    val pageCount: Int = 1
)

@Entity(
    tableName = "marker",
    foreignKeys = [ForeignKey(SurveyEntity::class, parentColumns = ["id"], childColumns = ["surveyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("surveyId")]
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surveyId: Long,
    /** Índice da prancha (página do PDF), base 0. */
    val page: Int = 0,
    /** Coordenadas NORMALIZADAS da página: 0f..1f, origem no canto superior esquerdo. */
    val x: Float,
    val y: Float,
    val category: Category,
    val spec: String = "",
    val status: ItemStatus,
    val note: String = "",
    val photoPath: String? = null,
    val createdAt: Long
)
