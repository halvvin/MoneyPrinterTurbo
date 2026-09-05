package com.moneyprinterturbo.android

import android.app.Application
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.media.FontManager
import com.moneyprinterturbo.android.core.storage.SecureStore
import com.moneyprinterturbo.android.core.storage.PrefsStore
import com.moneyprinterturbo.android.data.Repository
import com.moneyprinterturbo.android.pipeline.RenderNotifications

class MptApplication : Application() {

    lateinit var db: MptDatabase
        private set
    lateinit var prefs: PrefsStore
        private set
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        db = MptDatabase.get(this)
        prefs = PrefsStore(this, SecureStore(this))
        repository = Repository(this, db)
        FontManager.ensureBundled(this)
        RenderNotifications.ensureChannel(this)
    }
}
