package com.tonybenoy.smbdrop.transfer

import android.content.Context
import androidx.work.*
import com.tonybenoy.smbdrop.backup.BackupWorker
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val TRANSFER = "transfer"
    private const val BACKUP_PERIODIC = "backup-periodic"
    private const val BACKUP_NOW = "backup-now"

    fun kick(c: Context) {
        val req = OneTimeWorkRequestBuilder<TransferWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(TRANSFER, ExistingWorkPolicy.KEEP, req)
    }

    fun schedulePeriodicBackup(c: Context) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(BACKUP_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun backupNow(c: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(BACKUP_NOW, ExistingWorkPolicy.REPLACE, req)
    }
}
