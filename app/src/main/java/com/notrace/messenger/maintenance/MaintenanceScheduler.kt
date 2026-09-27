package com.notrace.messenger.maintenance

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules MaintenanceWorker to run roughly once a day. Enqueuing is
 * itself cheap and non-blocking (just registers the request with the
 * system; WorkManager decides the actual run time based on constraints
 * and Doze/battery state) - safe to call eagerly at app launch.
 *
 * Constraints are deliberately loose (no network required - this is
 * pure local DB cleanup) but battery-considerate (won't run when the
 * battery is low), matching plan Section 12's general "don't waste
 * battery/resources" spirit for background work.
 */
object MaintenanceScheduler {
    private const val UNIQUE_WORK_NAME = "notrace_maintenance"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // don't reset the schedule on every app launch
            request
        )
    }
}
