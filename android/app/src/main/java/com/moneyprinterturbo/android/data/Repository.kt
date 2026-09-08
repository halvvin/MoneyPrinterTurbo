package com.moneyprinterturbo.android.data

import android.content.Context
import com.moneyprinterturbo.android.core.db.DbJson
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.db.ProjectEntity
import com.moneyprinterturbo.android.core.db.TaskEntity
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.model.TaskConfig
import com.moneyprinterturbo.android.core.model.TaskStatus
import com.moneyprinterturbo.android.core.model.StopAt
import com.moneyprinterturbo.android.core.storage.PrefsStore
import com.moneyprinterturbo.android.pipeline.RenderWorker
import java.util.UUID

/** Project + Task management (create/queue/retry/duplicate/export), backed by Room + WorkManager. */
class Repository(
    private val context: Context,
    private val db: MptDatabase,
    private val prefs: PrefsStore,
) {

    // ---------- Projects ----------

    suspend fun createProject(config: TaskConfig, name: String? = null): ProjectEntity {
        // ISSUE-A: stamp the global execution-mode selection into every NEW project.
        // Per-task mode is the source of truth afterwards (retry/rerun/cancel read it
        // from configJson), while Settings.mode acts as the default for new tasks only.
        val stamped = config.copy(executionMode = prefs.settingsNow().mode)
        AppLogger.log(context, "PROJECT", "create name=${name ?: stamped.videoSubject} mode=${stamped.executionMode}")
        val now = System.currentTimeMillis()
        val p = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name?.ifBlank { null } ?: stamped.videoSubject.take(40).ifBlank { "Untitled" },
            createdAt = now, updatedAt = now,
            configJson = DbJson.configToString(stamped),
        )
        db.projectDao().upsert(p)
        return p
    }

    suspend fun duplicateProject(id: String): ProjectEntity? {
        val src = db.projectDao().get(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = src.copy(id = UUID.randomUUID().toString(), name = src.name + " (copy)", createdAt = now, updatedAt = now, lastTaskId = null, lastStatus = 0, lastVideoPath = null)
        db.projectDao().upsert(copy)
        return copy
    }

    suspend fun updateProject(p: ProjectEntity) = db.projectDao().upsert(p.copy(updatedAt = System.currentTimeMillis()))

    suspend fun updateProjectConfig(id: String, config: TaskConfig): ProjectEntity? {
        val current = db.projectDao().get(id) ?: return null
        val updated = current.copy(
            configJson = DbJson.configToString(config),
            name = current.name.ifBlank { config.videoSubject.take(40).ifBlank { "Untitled" } },
            updatedAt = System.currentTimeMillis(),
        )
        db.projectDao().upsert(updated)
        AppLogger.log(context, "PROJECT", "config updated id=$id")
        return updated
    }
    suspend fun deleteProject(id: String) {
        AppLogger.log(context, "PROJECT", "delete id=$id")
        val tasks = db.taskDao().forProject(id)
        tasks.forEach { task ->
            RenderWorker.cancel(context, task.id)
            if (task.remoteTaskId != null) {
                runCatching { com.moneyprinterturbo.android.core.net.RemoteMptClient(context).deleteTask(task.remoteTaskId) }
            }
            task.videoPath?.let { runCatching { java.io.File(it).parentFile?.deleteRecursively() } }
        }
        db.taskDao().deleteForProject(id)
        db.projectDao().delete(id)
    }
    suspend fun project(id: String): ProjectEntity? = db.projectDao().get(id)
    suspend fun projects(): List<ProjectEntity> = db.projectDao().all()

    suspend fun renameProject(id: String, name: String) {
        db.projectDao().get(id)?.let { db.projectDao().upsert(it.copy(name = name, updatedAt = System.currentTimeMillis())) }
    }

    // ---------- Tasks ----------

    /** Create a task for a project and enqueue it with WorkManager. */
    suspend fun queueTask(project: ProjectEntity, config: TaskConfig = DbJson.configFromString(project.configJson)): TaskEntity {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            subject = config.videoSubject,
            status = TaskStatus.QUEUED.code,
            progress = 0,
            stage = "QUEUED",
            createdAt = now, updatedAt = now,
            configJson = DbJson.configToString(config),
        )
        db.taskDao().upsert(task)
        db.projectDao().upsert(project.copy(lastTaskId = task.id, lastStatus = TaskStatus.QUEUED.code, updatedAt = now))
        AppLogger.log(context, "TASK", "queued id=${task.id} project=${project.id} subject=${config.videoSubject}")
        RenderWorker.enqueue(context, task.id)
        return task
    }

    suspend fun cancelTask(taskId: String) {
        AppLogger.log(context, "TASK", "cancel requested id=$taskId")
        val task = db.taskDao().get(taskId)
        RenderWorker.cancel(context, taskId)
        if (task != null && com.moneyprinterturbo.android.core.db.DbJson.configFromString(task.configJson).executionMode == com.moneyprinterturbo.android.core.model.Mode.REMOTE) {
            task?.remoteTaskId?.let { runCatching { com.moneyprinterturbo.android.core.net.RemoteMptClient(context).cancelTask(it) } }
        }
        db.taskDao().updateProgress(taskId, TaskStatus.CANCELLED.code, 0, "QUEUED", System.currentTimeMillis())
    }

    /** Re-run: clone the task config into a fresh task.
     *  P2.2: a STOPPED_AT task re-runs from the beginning with the SAME task semantics
     *  (upstream has no continue; it re-runs the pipeline, but the saved config already
     *  contains any artifacts the user inspected/edited — script/terms/materials). */
    suspend fun retryTask(taskId: String): TaskEntity? {
        val src = db.taskDao().get(taskId) ?: return null
        val project = db.projectDao().get(src.projectId) ?: return null
        val config = DbJson.configFromString(src.configJson)
        AppLogger.log(context, "TASK", "retry source=$taskId project=${src.projectId}")
        return queueTask(project, config)
    }

    /** P2.2: re-queue a STOPPED_AT task so it runs again with the CURRENT config
     *  (which now includes the user-inspected/edited intermediate artifacts). */
    suspend fun continueStoppedTask(taskId: String): TaskEntity? {
        val src = db.taskDao().get(taskId) ?: return null
        if (src.status != TaskStatus.STOPPED_AT.code) return null
        val config = DbJson.configFromString(src.configJson)
        // Continue means: finish the remaining stages → stopAt back to VIDEO.
        val updated = config.copy(stopAt = StopAt.VIDEO)
        db.taskDao().updateConfigAndState(
            taskId, DbJson.configToString(updated),
            TaskStatus.QUEUED.code, 0, "QUEUED", System.currentTimeMillis(),
        )
        db.projectDao().get(src.projectId)?.let {
            db.projectDao().upsert(it.copy(lastTaskId = taskId, lastStatus = TaskStatus.QUEUED.code, updatedAt = System.currentTimeMillis()))
        }
        AppLogger.log(context, "TASK", "continue stopped task=$taskId stopAt=${config.stopAt.vValue}→video")
        RenderWorker.enqueue(context, taskId)
        return db.taskDao().get(taskId)
    }

    suspend fun task(taskId: String): TaskEntity? = db.taskDao().get(taskId)
    suspend fun tasks(): List<TaskEntity> = db.taskDao().recent()
    suspend fun projectTasks(projectId: String): List<TaskEntity> = db.taskDao().forProject(projectId)
    suspend fun deleteTask(taskId: String) {
        AppLogger.log(context, "TASK", "delete id=$taskId")
        val task = db.taskDao().get(taskId)
        RenderWorker.cancel(context, taskId)
        if (task != null && com.moneyprinterturbo.android.core.db.DbJson.configFromString(task.configJson).executionMode == com.moneyprinterturbo.android.core.model.Mode.REMOTE) {
            task?.remoteTaskId?.let { runCatching { com.moneyprinterturbo.android.core.net.RemoteMptClient(context).deleteTask(it) } }
        }
        task?.videoPath?.let { runCatching { java.io.File(it).parentFile?.deleteRecursively() } }
        db.taskDao().delete(taskId)
    }
}
