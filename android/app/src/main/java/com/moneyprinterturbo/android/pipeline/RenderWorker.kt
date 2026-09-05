package com.moneyprinterturbo.android.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.NotificationManagerCompat
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.model.TaskStatus
import com.moneyprinterturbo.android.core.storage.PrefsStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * WorkManager wrapper around TaskPipeline — keeps rendering alive in background and
 * survives app switch. One unique worker per task id; cancellation via WorkManager.
 */
class RenderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val db = MptDatabase.get(applicationContext)
        val prefs = PrefsStore(applicationContext, com.moneyprinterturbo.android.core.storage.SecureStore(applicationContext))
        val task = db.taskDao().get(taskId) ?: return Result.failure()
        try {
            setForeground(foregroundInfo())
        } catch (_: Exception) {
            // foreground not granted — continue without notification
        }
        val pipeline = TaskPipeline(
            applicationContext, db, prefs,
            com.moneyprinterturbo.android.core.net.Http.client(),
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
        )
        return try {
            pipeline.run(task)
            Result.success()
        } catch (e: TaskPipeline.CancelledException) {
            Result.success(Data.Builder().putBoolean(KEY_CANCELLED, true).build())
        } catch (e: Exception) {
            if (runAttemptCount < 1 && task.status != TaskStatus.FAILED.code) {
                Result.retry()
            } else Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = NotificationManagerCompat.from(applicationContext)
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, RenderNotifications.CHANNEL_RENDER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MoneyPrinterTurbo")
            .setContentText("Rendering video…")
            .setOngoing(true)
            .build()
        return ForegroundInfo(RenderNotifications.NOTIF_RENDER, notification)
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_CANCELLED = "cancelled"

        fun enqueue(context: Context, taskId: String) {
            val request = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).build())
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .addTag("render_$taskId")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("render_$taskId", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("render_$taskId")
        }
    }
}

/** Notification channel setup + progress updates. */
object RenderNotifications {
    const val CHANNEL_RENDER = "render"
    const val NOTIF_RENDER = 42

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel(
                CHANNEL_RENDER, "Video rendering", android.app.NotificationManager.IMPORTANCE_LOW,
            )
            androidx.core.app.NotificationManagerCompat.from(context).createNotificationChannel(ch)
        }
    }
}
