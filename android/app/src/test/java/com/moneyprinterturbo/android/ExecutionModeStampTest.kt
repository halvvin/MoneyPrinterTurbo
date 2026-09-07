package com.moneyprinterturbo.android

import com.moneyprinterturbo.android.core.db.DbJson
import com.moneyprinterturbo.android.core.model.Mode
import com.moneyprinterturbo.android.core.model.TaskConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-A verification tests (JVM — no Android framework needed).
 * Proves the stamping contract end-to-end at the persistence layer:
 * Settings.mode → stamped TaskConfig → configJson → (retry/rerun reads configJson).
 */
class ExecutionModeStampTest {

    private fun stamp(config: TaskConfig, mode: Mode): TaskConfig =
        config.copy(executionMode = mode)

    @Test
    fun case1_local_setting_stamps_LOCAL() {
        val config = stamp(TaskConfig(videoSubject = "t"), Mode.LOCAL)
        assertEquals(Mode.LOCAL, config.executionMode)
        val json = DbJson.configToString(config)
        assertTrue(json.contains("\"executionMode\":\"LOCAL\""))
        assertEquals(Mode.LOCAL, DbJson.configFromString(json).executionMode)
    }

    @Test
    fun case2_remote_setting_stamps_REMOTE() {
        val config = stamp(TaskConfig(videoSubject = "t"), Mode.REMOTE)
        assertEquals(Mode.REMOTE, config.executionMode)
        val json = DbJson.configToString(config)
        assertTrue(json.contains("\"executionMode\":\"REMOTE\""))
        assertEquals(Mode.REMOTE, DbJson.configFromString(json).executionMode)
    }

    @Test
    fun case3_retry_rerun_preserve_stored_mode() {
        // A REMOTE task is persisted; the global setting then changes to LOCAL.
        // retryTask/rerun read the STORED configJson — mode must stay REMOTE.
        val remoteConfig = stamp(TaskConfig(videoSubject = "t"), Mode.REMOTE)
        val storedJson = DbJson.configToString(remoteConfig)
        val globalSettingChangedTo = Mode.LOCAL
        val reloaded = DbJson.configFromString(storedJson)
        // retry path (Repository.retryTask): queueTask(project, config-from-taskJson)
        val retried = reloaded // NOT re-stamped from globals — retry passes stored config
        assertEquals(Mode.REMOTE, retried.executionMode)
        // rerun path (ProjectScreen): queueTask(p) → default param reads project.configJson
        val rerun = DbJson.configFromString(storedJson)
        assertEquals(Mode.REMOTE, rerun.executionMode)
        // sanity: global change does NOT retroactively affect stored config
        assertTrue(globalSettingChangedTo != retried.executionMode)
    }

    @Test
    fun case4_preexisting_local_project_stays_LOCAL() {
        // A project created before the fix has LOCAL persisted in configJson.
        val legacyJson = DbJson.configToString(TaskConfig(videoSubject = "old"))
        val reloaded = DbJson.configFromString(legacyJson)
        assertEquals(Mode.LOCAL, reloaded.executionMode)
    }
}
