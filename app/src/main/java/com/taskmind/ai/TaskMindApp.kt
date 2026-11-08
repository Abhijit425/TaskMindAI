package com.taskmind.ai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.taskmind.ai.data.repo.CategoryRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.di.ApplicationScope
import com.taskmind.ai.notify.Notifications
import com.taskmind.ai.notify.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TaskMindApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var categories: CategoryRepository
    @Inject lateinit var tasks: TaskRepository

    @Inject
    @field:ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        appScope.launch {
            categories.ensureDefaults()
            workScheduler.scheduleAll()
        }
    }
}
