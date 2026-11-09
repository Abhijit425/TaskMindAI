package com.abhijit.airealityscanner.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhijit.airealityscanner.data.export.ReportExporter
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily background housekeeping (battery-friendly, only when battery is not low):
 * removes unsaved scans older than 24 h, applies the user's auto-delete period,
 * deletes orphaned image files and clears old exported reports.
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanRepository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val reportExporter: ReportExporter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val settings = settingsRepository.settings.first()
        scanRepository.cleanup(
            unsavedOlderThanMs = TimeUnit.HOURS.toMillis(24),
            autoDeleteDays = settings.autoDeleteDays,
        )
        reportExporter.clearExports()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val NAME = "daily-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
