package br.com.ppci.levantamento.data

import androidx.room.ColumnInfo
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
    /** Caminho do PDF copiado para filesDir/plans (o original nunca é tocado). */
    val pdfPath: String,
    val pageIndex: Int = 0
)

@Entity(
    tableName = "marker",
    foreignKeys = [ForeignKey(SurveyEntity::class, parentColumns = ["id"], childColumns = ["surveyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("surveyId")]
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surveyId: Long,
    /** Coordenadas NORMALIZADAS da página: 0f..1f, origem no canto superior esquerdo. */
    val x: Float,
    val y: Float,
    val category: Category,
    val spec: String = "",
    val status: ItemStatus,
    val note: String = "",
    @ColumnInfo(name = "photoPath") val photoPath: String? = null,
    val createdAt: Long
)
