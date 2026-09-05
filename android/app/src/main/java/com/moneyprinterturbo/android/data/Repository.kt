package com.moneyprinterturbo.android.data

import android.content.Context
import com.moneyprinterturbo.android.core.db.DbJson
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.db.ProjectEntity
import com.moneyprinterturbo.android.core.db.TaskEntity
import com.moneyprinterturbo.android.core.model.TaskConfig
import com.moneyprinterturbo.android.core.model.TaskStatus
import com.moneyprinterturbo.android.pipeline.RenderWorker
import java.util.UUID

/** Project + Task management (create/queue/retry/duplicate/export), backed by Room + WorkManager. */
class Repository(private val context: Context, private val db: MptDatabase) {

    // ---------- Projects ----------

    suspend fun createProject(config: TaskConfig, name: String? = null): ProjectEntity {
        val now = System.currentTimeMillis()
        val p = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name?.ifBlank { null } ?: config.videoSubject.take(40).ifBlank { "Untitled" },
            createdAt = now, updatedAt = now,
            configJson = DbJson.configToString(config),
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
    suspend fun deleteProject(id: String) { db.projectDao().delete(id) }
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
        RenderWorker.enqueue(context, task.id)
        return task
    }

    suspend fun cancelTask(taskId: String) {
        RenderWorker.cancel(context, taskId)
        db.taskDao().updateProgress(taskId, TaskStatus.CANCELLED.code, 0, "QUEUED", System.currentTimeMillis())
    }

    /** Re-run: clone the task config into a fresh task. */
    suspend fun retryTask(taskId: String): TaskEntity? {
        val src = db.taskDao().get(taskId) ?: return null
        val project = db.projectDao().get(src.projectId) ?: return null
        val config = DbJson.configFromString(src.configJson).copy(videoScript = src.configJson.let { DbJson.configFromString(it).videoScript })
        return queueTask(project, config)
    }

    suspend fun task(taskId: String): TaskEntity? = db.taskDao().get(taskId)
    suspend fun tasks(): List<TaskEntity> = db.taskDao().recent()
    suspend fun projectTasks(projectId: String): List<TaskEntity> = db.taskDao().forProject(projectId)
    suspend fun deleteTask(taskId: String) = db.taskDao().delete(taskId)
}
