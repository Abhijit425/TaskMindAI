package com.taskmind.ai

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.taskmind.ai.ai.ParsedTask
import com.taskmind.ai.ai.TranscriptExtractor
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.db.UserSettingsEntity
import com.taskmind.ai.data.prefs.ThemeMode
import com.taskmind.ai.notify.appEntryPoint
import com.taskmind.ai.util.TimeUtil
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the real app on an emulator: onboarding → every tab → task editor → quick add →
 * assistant → call insights → settings, saving a full-screen screenshot of each step.
 */
@RunWith(AndroidJUnit4::class)
class AppTourTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(900)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun seed() = runBlocking {
        val ep = app.appEntryPoint()
        val tasks = ep.taskRepository()
        val now = TimeUtil.now()
        ep.database().userSettingsDao().upsert(UserSettingsEntity(displayName = "Abhijit", managerName = "Rahul"))
        tasks.createFromParsed(ParsedTask("Submit weekly timesheet", dueAt = now.minusDays(1).withHour(17).withMinute(0), hasTime = true, priority = Priority.HIGH, category = "Work"), TaskSource.MANUAL)
        tasks.createFromParsed(ParsedTask("Pay electricity bill", dueAt = now.toLocalDate().atTime(23, 30), hasTime = true, priority = Priority.HIGH, category = "Finance"), TaskSource.VOICE)
        tasks.createFromParsed(ParsedTask("Book dentist appointment", dueAt = now.toLocalDate().atTime(23, 45), hasTime = true, category = "Health"), TaskSource.MANUAL)
        tasks.createFromParsed(ParsedTask("Plan Durga Puja family trip", dueAt = now.plusDays(4), hasTime = false, priority = Priority.LOW, category = "Family", tags = listOf("family")), TaskSource.MANUAL)
        val done = tasks.createFromParsed(ParsedTask("Morning standup notes", category = "Work"), TaskSource.MANUAL)
        tasks.setCompleted(done, true)

        val calls = ep.callRepository()
        val transcript = "Rahul: Abhijit, please complete the QAD customization by Monday. Also send the updated document to the client tomorrow.\n" +
            "Me: Sure, I'll share the test results with the team by Friday."
        val sid = calls.createTextSession("Call with Rahul", transcript, CallDirection.INCOMING, TranscriptSource.MANUAL, "en-IN", "Rahul")
        calls.setContact(sid, "Rahul", "Manager", null)
        calls.applyExtraction(sid, TranscriptExtractor.extract(transcript, now, "Rahul"), "On-device", null)
    }

    @Test
    fun tourEveryScreen() {
        // Onboarding (fresh install)
        if (compose.onAllNodesWithTag("onboarding_start").fetchSemanticsNodes().isNotEmpty()) {
            shot("01_onboarding")
            compose.onNodeWithTag("onboarding_name").performTextInput("Abhijit")
            compose.onNodeWithTag("onboarding_start").performScrollTo().performClick()
        }
        waitForTag("tab_home")
        seed()
        waitForText("Submit weekly timesheet")
        shot("02_home")

        // Tasks list + editor
        compose.onNodeWithTag("tab_tasks").performClick()
        waitForText("Pay electricity bill")
        shot("03_tasks")
        compose.onNodeWithText("Pay electricity bill").performClick()
        waitForText("Priority")
        shot("04_task_editor")
        back()

        // Quick add with natural language
        shot("04b_after_back")
        waitForTag("tab_home")
        compose.onNodeWithTag("tab_home").performClick()
        shot("04c_home_again")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Add task", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Add task", useUnmergedTree = true).onFirst().performClick()
        waitForText("New task")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Remind me to call the doctor next Wednesday at 11 AM")
        waitForText("Call the doctor")
        shot("05_quick_add")
        compose.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        compose.onNodeWithTag("tab_tasks").performClick()
        waitForText("Call the doctor")

        // Assistant
        compose.onNodeWithTag("tab_assistant").performClick()
        compose.onNodeWithText("What should I complete today?").performClick()
        waitForText("Start with")
        shot("06_assistant")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("What tasks did my manager assign?")
        compose.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        waitForText("Still to review")
        shot("07_assistant_manager")

        // Call insights
        compose.onNodeWithTag("tab_calls").performClick()
        waitForText("Call Insights")
        shot("08_calls")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Rahul"))
        compose.onNodeWithText("Rahul").performClick()
        waitForText("Tasks & commitments")
        shot("09_call_detail")
        compose.onNodeWithText("Approve all").performClick()
        waitForText("Added")
        shot("10_call_approved")
        back()

        // Settings
        compose.onNodeWithTag("tab_settings").performClick()
        waitForText("Your name")
        shot("11_settings")
        compose.onNodeWithText("Ask to capture tasks on calls").performScrollTo()
        shot("12_settings_calls")

        // Dark theme
        runBlocking { app.appEntryPoint().settings().setTheme(ThemeMode.DARK) }
        compose.onNodeWithTag("tab_home").performClick()
        waitForText("Daily briefing")
        shot("13_home_dark")
        compose.onNodeWithTag("tab_calls").performClick()
        shot("14_calls_dark")
        runBlocking { app.appEntryPoint().settings().setTheme(ThemeMode.SYSTEM) }
    }
}
