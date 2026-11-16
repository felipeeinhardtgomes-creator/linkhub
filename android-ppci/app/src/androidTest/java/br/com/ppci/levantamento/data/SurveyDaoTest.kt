package br.com.ppci.levantamento.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.ppci.levantamento.domain.Category
import br.com.ppci.levantamento.domain.ItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveyDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun tearDown() = db.close()

    private fun survey() = SurveyEntity(company = "ACME", client = "Cliente", site = "Obra", technician = "RT", createdAt = 1, pdfPath = "/x.pdf", pageCount = 2)
    private fun marker(sid: Long, status: ItemStatus, page: Int = 0) =
        MarkerEntity(surveyId = sid, page = page, x = 0.25f, y = 0.75f, category = Category.EXTINGUISHER, status = status, createdAt = 1)

    @Test fun markersKeepNormalizedCoordinatesPageAndOrder() = runBlocking {
        val sid = db.surveyDao().insert(survey())
        db.markerDao().insertAll(listOf(marker(sid, ItemStatus.MISSING), marker(sid, ItemStatus.OK, page = 1)))
        val list = db.markerDao().observe(sid).first()
        assertEquals(listOf(ItemStatus.MISSING, ItemStatus.OK), list.map { it.status })
        assertEquals(listOf(0, 1), list.map { it.page })
        assertEquals(0.25f, list[0].x); assertEquals(0.75f, list[0].y)
    }

    @Test fun deletingSurveyCascadesToMarkers() = runBlocking {
        val sid = db.surveyDao().insert(survey())
        db.markerDao().insertAll(listOf(marker(sid, ItemStatus.MISSING)))
        db.surveyDao().delete(db.surveyDao().get(sid)!!)
        assertEquals(0, db.markerDao().list(sid).size)
    }

    @Test fun listCountsBudgetItemsOnly() = runBlocking {
        val sid = db.surveyDao().insert(survey())
        db.markerDao().insertAll(listOf(marker(sid, ItemStatus.MISSING), marker(sid, ItemStatus.IRREGULAR), marker(sid, ItemStatus.OK)))
        val row = db.surveyDao().observeAllWithCount().first().single()
        assertEquals(3, row.markerCount); assertEquals(2, row.budgetCount)
    }

    @Test fun recentSpecsRankByUseAndIgnoreBlankAndOtherCategories() = runBlocking {
        val sid = db.surveyDao().insert(survey())
        fun m(cat: Category, spec: String) = marker(sid, ItemStatus.MISSING).copy(category = cat, spec = spec)
        db.markerDao().insertAll(listOf(
            m(Category.EXTINGUISHER, "PQS ABC 6kg"), m(Category.EXTINGUISHER, "CO2 4kg"), m(Category.EXTINGUISHER, "PQS ABC 6kg"),
            m(Category.EXTINGUISHER, ""), m(Category.SIGN, "Saída S1")
        ))
        assertEquals(listOf("PQS ABC 6kg", "CO2 4kg"), db.markerDao().recentSpecs(Category.EXTINGUISHER.name).first())
    }
}
