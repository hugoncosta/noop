package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Checks whether Health Connect already carries step data for the recent days. Used only to decide
 * whether to warn the user before turning on phone step counting: if HC already has steps, enabling the
 * counter would double-count those days (max-across-sources in [com.noop.ui.stepsForDay] protects the
 * Today tile, but a watch's own HC writes and the phone's count are both legitimate producers, so the
 * user should decide which one owns the days).
 *
 * Read-only. Never throws.
 */
object PhoneStepHcOverlap {
    suspend fun hasRecentSteps(context: Context): Boolean {
        return runCatching {
            if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return@runCatching false
            val client = HealthConnectImporter.client(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) return@runCatching false
            val now = Instant.now()
            val from = now.minus(7, ChronoUnit.DAYS)
            val resp = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(from, now)))
            resp.records.isNotEmpty()
        }.getOrDefault(false)
    }
}
