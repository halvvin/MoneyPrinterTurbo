package com.moneyprinterturbo.android.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.moneyprinterturbo.android.core.model.Stage
import com.moneyprinterturbo.android.core.model.TaskConfig
import com.moneyprinterturbo.android.core.model.TaskStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,                 // UUID
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val configJson: String,                     // TaskConfig serialized
    val lastTaskId: String? = null,
    val lastStatus: Int = 0,                    // TaskStatus.code
    val lastVideoPath: String? = null,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,                 // UUID (upstream task_id parity)
    val projectId: String,
    val subject: String,
    val status: Int,                            // -1 / 0 / 1 / 4 (upstream const.py)
    val progress: Int,                          // 0..100
    val stage: String,                          // Stage name
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
    val videoPath: String? = null,
    val configJson: String,
    val log: String = "",                       // rolling execution log
    val outputPathsJson: String = "[]",          // all generated output files
    val remoteTaskId: String? = null,              // backend task id when REMOTE mode is used
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun forProject(projectId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status = 4 ORDER BY createdAt DESC")
    suspend fun running(): List<TaskEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TaskEntity)

    @Query("UPDATE tasks SET status = :status, progress = :progress, stage = :stage, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, status: Int, progress: Int, stage: String, now: Long)

    @Query("UPDATE tasks SET error = :error, status = -1, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: String, error: String, now: Long)

    @Query("UPDATE tasks SET videoPath = :path, outputPathsJson = :outputPathsJson, status = 1, progress = 100, stage = 'DONE', updatedAt = :now, finishedAt = :now WHERE id = :id")
    suspend fun markComplete(id: String, path: String, outputPathsJson: String, now: Long)

    @Query("UPDATE tasks SET log = substr(COALESCE(log, '') || :line, -16000), updatedAt = :now WHERE id = :id")
    suspend fun appendLog(id: String, line: String, now: Long)

    @Query("UPDATE tasks SET remoteTaskId = :remoteId, updatedAt = :now WHERE id = :id")
    suspend fun setRemoteTaskId(id: String, remoteId: String, now: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Database(entities = [ProjectEntity::class, TaskEntity::class], version = 3, exportSchema = false)
abstract class MptDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN outputPathsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN remoteTaskId TEXT")
            }
        }

        @Volatile private var instance: MptDatabase? = null

        fun get(context: Context): MptDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, MptDatabase::class.java, "mpt.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}

/** JSON codec shared by entities. */
object DbJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun configToString(c: TaskConfig): String = json.encodeToString(c)
    fun configFromString(s: String): TaskConfig =
        try { json.decodeFromString<TaskConfig>(s) } catch (e: Exception) { TaskConfig() }
}

fun TaskStatus.toCode(): Int = this.code
fun Stage.toName(): String = this.name
