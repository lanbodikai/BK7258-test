package com.airecorder.mvp

import android.app.Application
import com.airecorder.mvp.core.ble.AndroidRecorderBleClient
import com.airecorder.mvp.core.database.RecorderDatabase
import com.airecorder.mvp.core.database.RecordingRepository
import com.airecorder.mvp.processing.ProcessingScheduler
import com.airecorder.mvp.sync.SyncCoordinator
import com.airecorder.mvp.sync.SyncSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RecorderApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = RecorderDatabase.create(application)
    val recordings = RecordingRepository(database, application.filesDir)
    val processingScheduler = ProcessingScheduler(application)
    val ble = AndroidRecorderBleClient(application)
    val syncCoordinator = SyncCoordinator(application, ble, recordings, processingScheduler)
    val syncSession = SyncSessionController(application, syncCoordinator, ble, applicationScope)
}
