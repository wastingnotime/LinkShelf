package org.wastingnotime.linkshelf.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class BackupStatus(
    val enabled: Boolean,
    val lastSuccessMillis: Long?,
    val lastAttemptFailed: Boolean,
)

class ScheduledBackup(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val workManager by lazy { WorkManager.getInstance(context) }

    fun status() = BackupStatus(
        enabled = preferences.contains(KEY_FOLDER),
        lastSuccessMillis = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastAttemptFailed = preferences.getBoolean(KEY_LAST_FAILURE, false),
    )

    fun configure(folder: Uri) = synchronized(operationLock) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = preferences.getString(KEY_FOLDER, null)
        resolver.takePersistableUriPermission(folder, flags)
        val usable = runCatching {
            val selected = DocumentFile.fromTreeUri(context, folder)
            selected != null && selected.isDirectory && selected.canWrite() &&
                validateRenameSupport(selected)
        }.getOrDefault(false)
        if (!usable) {
            if (previous != folder.toString()) {
                runCatching { resolver.releasePersistableUriPermission(folder, flags) }
            }
            error("Selected backup folder is unavailable")
        }
        val editor = preferences.edit().putString(KEY_FOLDER, folder.toString())
            .putString("folder_label", DocumentFile.fromTreeUri(context, folder)?.name ?: folder.toString())
            .putBoolean(KEY_LAST_FAILURE, false)
        if (previous != folder.toString()) editor.remove(KEY_LAST_SUCCESS)
        if (!editor.commit()) {
            if (previous != folder.toString()) {
                runCatching { resolver.releasePersistableUriPermission(folder, flags) }
            }
            error("Could not save backup folder")
        }
        ensureScheduled()
        backupNow()
        if (previous != null && previous != folder.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
        }
    }

    fun backupNow() {
        check(preferences.contains(KEY_FOLDER)) { "Choose a backup folder first" }
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().addTag("manual-feed-backup").build(),
        )
    }

    fun folderLabel(): String? = preferences.getString("folder_label", null)
        ?: preferences.getString(KEY_FOLDER, null)?.let { android.net.Uri.decode(it.substringAfterLast('/')) }
    fun workStatus() = kotlinx.coroutines.flow.combine(
        workManager.getWorkInfosForUniqueWorkFlow(IMMEDIATE_WORK),
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK),
    ) { immediate, periodic -> immediate + periodic }

    fun immediateWorkStatus() = workManager.getWorkInfosForUniqueWorkLiveData(IMMEDIATE_WORK)

    private fun validateRenameSupport(folder: DocumentFile): Boolean {
        val probeId = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
        val probeName = "$RENAME_PROBE_PREFIX$probeId.tmp"
        val renamedName = "$RENAME_PROBE_PREFIX$probeId.json"
        val probe = folder.createFile("application/json", probeName) ?: return false
        var renameAttempted = false
        return try {
            renameAttempted = true
            check(probe.renameTo(renamedName))
            folder.findFile(renamedName) != null
        } finally {
            val cleanupNames = listOf(probeName) + if (renameAttempted) listOf(renamedName) else emptyList()
            cleanupNames.mapNotNull(folder::findFile).forEach {
                check(it.delete()) { "Could not remove backup folder capability check" }
            }
        }
    }

    fun ensureScheduled() {
        if (!preferences.contains(KEY_FOLDER)) return
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS).build(),
        )
    }

    fun disable() = synchronized(operationLock) {
        val previous = preferences.getString(KEY_FOLDER, null)
        check(preferences.edit().remove(KEY_FOLDER).remove("folder_label").remove(KEY_LAST_FAILURE)
            .remove(KEY_LAST_SUCCESS).commit()) {
            "Could not disable scheduled backups"
        }
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
        if (previous != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
        }
    }

    companion object {
        internal val operationLock = Any()
        const val PREFERENCES_NAME = "scheduled_backup"
        internal const val KEY_FOLDER = "folder_uri"
        internal const val KEY_LAST_SUCCESS = "last_success"
        internal const val KEY_LAST_FAILURE = "last_failure"
        private const val PERIODIC_WORK = "daily_feed_backup"
        private const val IMMEDIATE_WORK = "initial_task_backup"
        private const val RENAME_PROBE_PREFIX = "linkshelf-rename-check-"
    }
}

class BackupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = synchronized(ScheduledBackup.operationLock) {
        val preferences = applicationContext.getSharedPreferences(
            ScheduledBackup.PREFERENCES_NAME, Context.MODE_PRIVATE,
        )
        val folderUri = preferences.getString(ScheduledBackup.KEY_FOLDER, null) ?: return Result.success()
        return runCatching {
            writeBackup(Uri.parse(folderUri))
            check(preferences.edit().putLong(ScheduledBackup.KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putBoolean(ScheduledBackup.KEY_LAST_FAILURE, false).commit()) {
                "Could not save backup status"
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                preferences.edit().putBoolean(ScheduledBackup.KEY_LAST_FAILURE, true).commit()
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            },
        )
    }

    private fun writeBackup(folderUri: Uri) {
        val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: error("Backup folder is unavailable")
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        folder.listFiles()
            .filter { isStalePendingBackup(it) }
            .forEach { check(it.delete()) { "Could not remove an incomplete backup" } }

        val pending = folder.createFile("application/json", "$PENDING_PREFIX$timestamp.tmp")
            ?: error("Could not create temporary backup file")
        var published = false
        try {
            val app = applicationContext as org.wastingnotime.linkshelf.FeedApplication
            val json = kotlinx.coroutines.runBlocking { FeedBackup.export(app.database) }
            applicationContext.contentResolver.openOutputStream(pending.uri, "wt")
                ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("Could not write backup file")

            check(pending.renameTo("$BACKUP_PREFIX$timestamp.json")) {
                "Could not publish completed backup"
            }
            published = true
            folder.listFiles()
                .filter { isCompletedBackup(it) }
                .sortedByDescending { it.name }
                .drop(MAX_BACKUPS)
                .forEach { check(it.delete()) { "Could not enforce backup retention" } }

        } catch (error: Exception) {
            if (!published) runCatching { pending.delete() }
            throw error
        }
    }

    private fun isCompletedBackup(file: DocumentFile) =
        file.isFile && file.name?.matches(COMPLETED_BACKUP_NAME) == true

    private fun isStalePendingBackup(file: DocumentFile): Boolean {
        if (!file.isFile) return false
        val name = file.name ?: return false
        val match = PENDING_BACKUP_NAME.matchEntire(name) ?: return false
        val createdAt = runCatching {
            LocalDateTime.parse(match.groupValues[1], PENDING_TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC)
        }.getOrNull() ?: return false
        return createdAt.isBefore(Instant.now().minus(PENDING_STALE_AFTER))
    }

    private companion object {
        const val BACKUP_PREFIX = "linkshelf-auto-"
        const val PENDING_PREFIX = "linkshelf-pending-"
        val COMPLETED_BACKUP_NAME = Regex("^(?:linkshelf|my-feed)-auto-\\d{8}-\\d{6}-\\d{3}\\.json$")
        val PENDING_BACKUP_NAME = Regex("^(?:linkshelf|my-feed)-pending-(\\d{8}-\\d{6}-\\d{3})\\.tmp$")
        val PENDING_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        val PENDING_STALE_AFTER = Duration.ofDays(1)
        const val MAX_BACKUPS = 7
    }
}
