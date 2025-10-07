package jp.co.softtex.st_andapp_0001_kin001 // あなたのアプリケーションのパッケージ名

import android.app.Application
import timber.log.Timber

class MyApplication : Application() {

    companion object {
        // SdkManager の初期化が成功したかどうかをどこからでも参照できるようにフラグを持たせる（任意）
        var isSdkManagerSuccessfullyInitialized = false
            private set // このクラスの外部からは読み取り専用にする
    }

    override fun onCreate() {
        super.onCreate()

        // TimberLogの初期化
        initTimberLog()
        val testlognum = 101


        // SdkManager の初期化処理
        if (SdkManager.initialize(applicationContext)) {
            isSdkManagerSuccessfullyInitialized = true
            Timber.i( "SdkManager initialized successfully in MyApplication.")
        } else {
            isSdkManagerSuccessfullyInitialized = false
            Timber.e( "Failed to initialize SdkManager in MyApplication.")
        }

    }

    override fun onTerminate() {
        super.onTerminate()
        Timber.i("MyApplication.onTerminate: Shutting down SdkManager.")
        if (isSdkManagerSuccessfullyInitialized) { // または SdkManager.isSdkCoreInitialized()
            SdkManager.shutdown()
        }
    }

    private fun initTimberLog() {
        LogHelper.appLogList.clear()

        LogHelper.initializeFileLogging(this)

        val treeToPlant = DynamicLogLevelTree(applicationContext)

        Timber.plant(treeToPlant)

        LogHelper.initializeFileLogging(this)

        Timber.i("Timber and FileLogging initialized. Current log level: ${LogLevel.toString(DynamicLogLevelTree.currentMinLogLevel)}")
    }
}