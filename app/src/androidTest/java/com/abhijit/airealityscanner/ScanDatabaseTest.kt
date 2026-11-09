package com.abhijit.airealityscanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abhijit.airealityscanner.data.local.ImageStorage
import com.abhijit.airealityscanner.data.local.db.AppDatabase
import com.abhijit.airealityscanner.data.repository.ScanRepositoryImpl
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.Confidence
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.StoredImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Database persistence tests (run on a device/emulator: ./gradlew connectedDebugAndroidTest). */
@RunWith(AndroidJUnit4::class)
class ScanDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScanRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SeedCallback)
            .build()
        repo = ScanRepositoryImpl(db, ImageStorage(context))
    }

    @After
    fun tearDown() = db.close()

    private fun analysis() = ScanAnalysis(
        subject = "Tulsi (holy basil)",
        confidence = Confidence.MEDIUM,
        confidenceNote = "Leaf shape matches",
        summary = "A common aromatic plant.",
        sections = emptyList(),
        keyFacts = listOf("Fact"),
        applications = emptyList(),
        safety = emptyList(),
        verificationNote = "",
        extractedText = null,
        rawText = "{}",
        providerName = "Test",
    )

    @Test
    fun sessionResultAndConversationPersist() = runTest {
        val id = repo.createSession(ScanMode.PLANT, "Plant scan", listOf(StoredImage("/tmp/none.jpg", 10, 10, "camera")), saved = true)
        repo.saveResult(id, analysis())
        repo.addMessage(id, ChatRole.USER, "Is it safe for cats?")
        repo.addMessage(id, ChatRole.ASSISTANT, "Generally considered non-toxic, but check with a vet.")

        val session = repo.getSession(id)!!
        assertEquals("Tulsi (holy basil)", session.displayTitle)
        assertEquals(1, session.images.size)
        assertEquals(2, repo.getMessages(id).size)

        val history = repo.observeHistory("basil", favouritesOnly = false, categoryId = null).first()
        assertEquals(1, history.size)
    }

    @Test
    fun categoriesAreSeeded() = runTest {
        val categories = repo.observeCategories().first()
        assertEquals(ScanMode.entries.size, categories.size)
    }

    @Test
    fun favouritesFilterAndDeletion() = runTest {
        val id = repo.createSession(ScanMode.GENERAL, "Object", emptyList(), saved = true)
        assertTrue(repo.observeHistory("", favouritesOnly = true, categoryId = null).first().isEmpty())
        repo.setFavourite(id, true)
        assertEquals(1, repo.observeHistory("", favouritesOnly = true, categoryId = null).first().size)
        repo.deleteSession(id)
        assertNull(repo.getSession(id))
    }
}
