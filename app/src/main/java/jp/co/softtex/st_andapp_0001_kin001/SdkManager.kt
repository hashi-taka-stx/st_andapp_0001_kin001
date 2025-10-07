package jp.co.softtex.st_andapp_0001_kin001

// SDKのエイリアス
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.toshibatec.callback.DataEventHandler as SdkDataEventHandler
import jp.co.toshibatec.callback.ErrorEventHandler as SdkErrorEventHandler
import jp.co.toshibatec.callback.BatteryExtendedDataCallback as SdkBatteryLevelCallback
import jp.co.toshibatec.callback.ConnectionEventHandler as SdkConnectionEventHandler
import jp.co.toshibatec.callback.FirmwareVerCallback as SdkFirmwareVerCallback
import jp.co.toshibatec.callback.ResultCallback as SdkResultCallback
import jp.co.toshibatec.callback.*
import jp.co.toshibatec.callback.ResultCallback
import jp.co.toshibatec.model.TagPack
import timber.log.Timber
import java.util.concurrent.TimeUnit

// --- RfidStatusHelperとアダプターが使用するコールバックインターフェース ---
// 名前を AppConnectionEventHandler に変更してSDKのエイリアスとの衝突を回避
interface AppConnectionEventHandler {
    fun onConnectionStateChanged(deviceAddress: String?, sdkState: Int)
}

interface AppBatteryLevelCallback {
    fun onCallback(deviceAddress: String?, level: Int, chagingState: Int, resultCode: Int, resultCodeExtended: Int)
}

interface AppFirmwareVersionCallback {
    fun onCallback(deviceAddress: String?, version: String?, resultCode: Int, resultCodeExtended: Int)
}

interface AppGenericResultCallback {
    fun onCallback(deviceAddress: String?, resultCode: Int, resultCodeExtended: Int)
}

interface AppDataEventHandler {
    fun onDataReceived(tagPacks: Map<String, TagPack>)
}

interface AppErrorEventHandler {
    fun onErrorEventOccurred(operation: String, resultCode: Int, resultCodeExtended: Int, isConnectionLostError: Boolean = false)
}

interface AppResultCallback {
    fun onCallback(resultCode: Int, resultCodeExtended: Int)
}

// --- SDKの機能を抽象化するインターフェース ---
interface TecRfidSuiteAdapter {
    fun open(deviceName: String, context: Context, logLevel: Int, logSize: Int): Int
    fun close(): Int
    fun claimDevice(connectionString: String, eventHandler: AppConnectionEventHandler?): Int
    fun releaseDevice(): Int
    fun setDeviceEnabled(enabled: Boolean): Int
    fun getFirmwareVer(callback: AppFirmwareVersionCallback?): Int
    fun getConnectionState(): Int
    fun getCommunicationMode(): Int
    fun getBatteryLevel(callback: AppBatteryLevelCallback?): Int
    fun getBluetoothList(list: ArrayList<String>): Int
    fun getState(): Int
    fun openResult(): Int
    fun setOptions(options: HashMap<String, Int>): Int
    fun getIsAvailableScanner(): Int
    fun getIsAvailableTagReadMode(): Int
    fun enableModelCheckProperty(callback: AppGenericResultCallback?): Int
    fun startBluetoothDiscovery(context: Context, callback: BluetoothDiscoveryEvent): Int
    fun stopBluetoothDiscovery(): Int
    fun startReadTags(FilterID: String,
                      FilterMask: String,
                      StartReadTagsTimeout: Int,
                      dataEventCallback: SdkDataEventHandler?,
                      errorEventCallback: SdkErrorEventHandler?
    ): Int
    fun stopReadTags(callback: SdkResultCallback?): Int
    fun setDataEventEnabled(enabled: Boolean): Int
}

// --- SDKアダプターの実装 ---
class TecRfidSuiteSdkAdapterImpl(
    private val sdkInstance: ToshibaTecSdk // 本物のSDKインスタンス
) : TecRfidSuiteAdapter {

    private val TAG = "SdkManager"

    private val connectionHandlerMap = mutableMapOf<AppConnectionEventHandler, SdkConnectionEventHandler>()
    private var currentConnectionString: String? = null

    private fun getOrCreateSdkHandler(appHandler: AppConnectionEventHandler, connectionStringForThisHandler: String?): SdkConnectionEventHandler {
        return object : SdkConnectionEventHandler {
            override fun onEvent(sdkState: Int) {
                Timber.d("SDK onEvent (acting as ConnectionStateChanged): state=$sdkState for specific connectionString=$connectionStringForThisHandler (Handler instance: ${this.hashCode()})")
                // SDKからのイベントがアドレス情報を含まない場合、claimDevice時のアドレスを使う
                appHandler.onConnectionStateChanged(connectionStringForThisHandler, sdkState)
            }
        }
    }

    override fun open(deviceName: String, context: Context, logLevel: Int, logSize: Int): Int {
        Timber.d( "open called with deviceName: $deviceName, context type: ${context.javaClass.simpleName}, logLevel: $logLevel, logSize: $logSize")

        // SDKのopenにはActivity Contextが必要と仮定
        val openResultCode = sdkInstance.open(deviceName, context)

        if (openResultCode == ToshibaTecSdk.OPOS_SUCCESS) {
            val logOptions = HashMap<String, Int>()
            logOptions[ToshibaTecSdk.OptionPackKeyLogLevel] = logLevel
            logOptions[ToshibaTecSdk.OptionPackKeyLogFileSize] = logSize

            val setOptionsResultCode = this.setOptions(logOptions)
            if (setOptionsResultCode != ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.w( "Failed to set log options, result: $setOptionsResultCode")
            }
        } else {
            Timber.w( "Skipping setOptions because open failed with result: $openResultCode")
        }
        return openResultCode
    }


    override fun close(): Int {
        Timber.d( "close called")
        return sdkInstance.close()
    }


    override fun claimDevice(connectionString: String, eventHandler: AppConnectionEventHandler?): Int {
        // currentConnectionString = connectionString // ← このフィールドへの依存を減らす
        val sdkHandler = eventHandler?.let {
            // getOrCreateSdkHandler(it) { this.currentConnectionString } // 古い方法
            getOrCreateSdkHandler(it, connectionString)
        }
        Timber.d( "claimDevice called with: $connectionString, sdkHandler: ${sdkHandler != null}")
        val result = sdkInstance.claimDevice(connectionString, sdkHandler)
        if (result == ToshibaTecSdk.OPOS_SUCCESS) { // claimが即時成功した場合などに備え、currentConnectionStringも更新しておくのは悪くない
            this.currentConnectionString = connectionString
        }
        return result
    }

    override fun releaseDevice(): Int {
        Timber.d( "releaseDevice called")
        // connectionHandlerMap.clear() // アダプタが破棄されるまで保持するか、releaseDeviceでクリアするかは設計による
        currentConnectionString = null
        return sdkInstance.releaseDevice()
    }

    override fun setDeviceEnabled(enabled: Boolean): Int {
        Timber.d( "setDeviceEnabled called with enabled: $enabled")
        return sdkInstance.setDeviceEnabled(enabled)
    }

    override fun getFirmwareVer(callback: AppFirmwareVersionCallback?): Int { // ★引数の型を修正
        Timber.d( "getFirmwareVer called")
        val sdkCallback = callback?.let { customFirmwareCallback ->
            object : SdkFirmwareVerCallback {
                override fun onCallback(version: String?, resultCode: Int, resultCodeExtended: Int) {
                    Timber.d( "SDK getFirmwareVer onCallback: version=$version, resultCode=$resultCode, extCode=$resultCodeExtended for $currentConnectionString")
                    customFirmwareCallback.onCallback(currentConnectionString, version, resultCode, resultCodeExtended)
                }
            }
        }
        return sdkInstance.getFirmwareVer(sdkCallback)
    }

    override fun getBatteryLevel(callback: AppBatteryLevelCallback?): Int { // ★引数の型を修正
        Timber.v( "getBatteryLevel called")
        val sdkCallback = callback?.let { customBatteryCallback ->
            object : SdkBatteryLevelCallback {
                override fun onCallback(level: Int, chargingState: Int, resultCode: Int, resultCodeExtended: Int) {
                    Timber.v( "SDK getBatteryLevel onCallback: level=$level, resultCode=$resultCode, extCode=$resultCodeExtended for $currentConnectionString")
                    customBatteryCallback.onCallback(currentConnectionString, level, chargingState, resultCode, resultCodeExtended)
                }
            }
        }
        return sdkInstance.getBatteryExtendedData(sdkCallback)
    }

    override fun getConnectionState(): Int {
        val state = sdkInstance.connectionState // SDKインスタンスのプロパティまたはメソッドから取得
        Timber.v( "getConnectionState called, state: $state")
        return state
    }

    override fun getCommunicationMode(): Int {
        val mode = sdkInstance.communicationMode // SDKインスタンスのプロパティまたはメソッドから取得
        Timber.v( "getCommunicationMode called, mode: $mode")
        return mode
    }

    override fun getBluetoothList(list: ArrayList<String>): Int {
        val result = sdkInstance.getBluetoothList(list) // SDKインスタンスのプロパティまたはメソッドから取得
        Timber.v( "getBluetoothList called, list: $list")
        return result
    }

    override fun getState(): Int {
        val state = sdkInstance.state // SDKインスタンスのプロパティまたはメソッドから取得 (OPOS state)
        Timber.v( "getState called, OPOS state: $state")
        return state
    }

    override fun openResult(): Int {
        val result = sdkInstance.openResult // SDKインスタンスのプロパティまたはメソッドから取得 (OPOS state)
        Timber.v( "openResult called, OPOS result: $result")
        return result
    }

    override fun setOptions(options: HashMap<String, Int>): Int {
        Timber.v( "setOptions called with: $options")
        return sdkInstance.setOptions(options)
    }

    override fun getIsAvailableScanner(): Int {
        val available = sdkInstance.isAvailableScanner // SDKインスタンスのプロパティまたはメソッドから取得
        Timber.v( "getIsAvailableScanner called, available: $available")
        return available
    }

    override fun getIsAvailableTagReadMode(): Int {
        val available = sdkInstance.isAvailableTagReadMode // SDKインスタンスのプロパティまたはメソッドから取得
        Timber.v( "getIsAvailableTagReadMode called, available: $available")
        return available
    }

    override fun enableModelCheckProperty(callback: AppGenericResultCallback?): Int {
        Timber.v( "enableModelCheckProperty called")
        val sdkCallback = callback?.let { customCb ->
            object : SdkResultCallback {
                override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                    Timber.d( "SDK enableModelCheckProperty onCallback: resultCode=$resultCode, extCode=$resultCodeExtended for $currentConnectionString")
                    customCb.onCallback(currentConnectionString, resultCode, resultCodeExtended)
                }
            }
        }
        return sdkInstance.enableModelCheckProperty(sdkCallback)
    }

    override fun startBluetoothDiscovery(context: Context, callback: BluetoothDiscoveryEvent): Int {
        Timber.v( "startBluetoothDiscovery called")
        return try {
            sdkInstance.startBluetoothDiscovery(context, callback) // 仮の呼び出し。SDKのメソッド名に合わせる
        } catch (e: Exception) {
            Timber.e( "Exception in startBluetoothDiscovery", e)
            ToshibaTecSdk.OPOS_E_FAILURE
        }
    }

    override fun stopBluetoothDiscovery(): Int {
        Timber.v( "stopBluetoothDiscovery called")
        return sdkInstance.stopBluetoothDiscovery()
    }

    override fun startReadTags(
        FilterID: String,
        FilterMask: String,
        StartReadTagsTimeout: Int,
        dataEventCallback: SdkDataEventHandler?,
        errorEventCallback: SdkErrorEventHandler?
    ): Int {
        Timber.i( "startReadTags called with FilterID: $FilterID, Timeout: $StartReadTagsTimeout")
        return sdkInstance.startReadTags(
            FilterID,
            FilterMask,
            StartReadTagsTimeout,
            dataEventCallback,
            errorEventCallback
        )
    }

    override fun stopReadTags(callback: ResultCallback?): Int {
        Timber.i( "stopReadTags called")
        return sdkInstance.stopReadTags(callback)
    }

    override fun setDataEventEnabled(enabled: Boolean): Int {
        return sdkInstance.setDataEventEnabled(enabled)
    }
}

// --- SdkManagerのリスナーインターフェース ---
interface SdkManagerListener {
    fun onConnectionStatusChanged(status: SdkManager.ConnectionStatus, deviceAddress: String?)
    fun onBatteryLevelChanged(level: Int, state: Int, isSuccess: Boolean)
    fun onFirmwareVersionChanged(
        fullVersion: String?,
        parsedVersion: String?,
        powerType: String?,
        isSuccess: Boolean,
        needsInitFileRecreation: Boolean = false
    )
    fun onErrorOccurred(
        operation: String,
        errorCode: Int,
        extendedCode: Int?,
        message: String,
        isConnectionLostError: Boolean = false
    )
    fun onTagDataReceived(tagPacks: Map<String, TagPack>) // Map<String, TagPack> がSDKのHashMap<String, TagPack>と互換
    fun onGenericSdkResult(operationType: SdkManager.SdkOperationType?, resultCode: Int, resultCodeExtended: Int)
    fun onDeviceDiscoveryUpdate(devices: List<String>, isScanComplete: Boolean)
    fun onDriverStatusChanged(isOpened: Boolean, deviceName: String?)

}

object SdkManager {

    private const val TAG = "SdkManager"

    private val deviceName_UF_3000 = "UF-3000" // デバイス名

    private lateinit var appContext: Context // ApplicationContextを保持
    private lateinit var tecRfidAdapter: TecRfidSuiteAdapter
    private lateinit var sdkInstance: ToshibaTecSdk
    private var isCoreSdkInitialized = false // SDKコア(getInstance)の初期化状態
    @Volatile
    private var isDriverOpened = false // SDKのopen()が成功したかどうかの状態
    @Volatile
    private var openedDeviceName: String? = null // open()に成功したデバイス名

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SdkManagerBackgroundThread") }

    private val listeners = mutableSetOf<SdkManagerListener>()

    @Volatile
    private var currentConnectionStatus = ConnectionStatus.DISCONNECTED
    @Volatile
    private var connectedDeviceAddressInternal: String? = null // 接続プロセス中に仮設定、成功時に確定
    @Volatile
    private var lastBatteryLevel: Int = -1
    @Volatile
    private var lastchargingState: Int = 0
    @Volatile
    private var lastRawFirmwareVersion: String? = null
    @Volatile
    private var lastParsedFirmwareVersion: String? = null

    var shouldRecreateInitFileAfterFirmwareGet: Boolean = false

    private val isScanningDevice = AtomicBoolean(false)

    private val discoveredDevicesForScan = mutableSetOf<String>()

    // デフォルトのドライバログレベル
    private const val DEFAULT_DRIVER_LOG_LEVEL = 0
    private const val DEFAULT_DRIVER_LOG_SIZE_KB = 10 *1024

    private var currentDriverLogLevel: Int = DEFAULT_DRIVER_LOG_LEVEL

    private val batteryCheckIntervalMillis = 2 * 1000L
    private val periodicBatteryCheckRunnable = object : Runnable {
        override fun run() {
            if (isConnected()) {
                fetchBatteryLevel()
            }
            if (currentConnectionStatus == ConnectionStatus.CONNECTED) {
                mainHandler.postDelayed(this, batteryCheckIntervalMillis)
            }
        }
    }

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    enum class SdkOperationType {
        STOP_READ_TAGS,
        START_READ_TAGS,
        SET_SDK_LOGGING,
        OPEN_DRIVER
    }

    fun initialize(context: Context): Boolean {
        if (isCoreSdkInitialized) {
            Timber.i( "SdkManager core is already initialized.")
            return true
        }
        appContext = context.applicationContext // applicationContext を使用

        try {
            // プリファレンス取得
            loadDriverLogLevelPreference(appContext)
            // SDKインスタンスの取得
            sdkInstance = ToshibaTecSdk.getInstance() ?: run {
                Timber.e( "ToshibaTecSdk.getInstance() returned null. SDK not available.")
                isCoreSdkInitialized = false
                return false // 初期化失敗
            }

            // アダプターの初期化
            tecRfidAdapter = TecRfidSuiteSdkAdapterImpl(sdkInstance)

            isCoreSdkInitialized = true
            Timber.i( "SdkManager core initialized successfully.")
            return true // 初期化成功
        } catch (e: Exception) {
            Timber.e( "Exception during SdkManager core initialization", e)
            isCoreSdkInitialized = false
            return false // 初期化失敗
        }
    }

    fun loadDriverLogLevelPreference(context: Context) {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        currentDriverLogLevel = prefs.getInt(SettingsActivity.KEY_DRIVER_LOG_LEVEL, DEFAULT_DRIVER_LOG_LEVEL)
        Timber.v( "Loaded driver log level from prefs: $currentDriverLogLevel")
    }

    fun isSdkCoreInitialized(): Boolean {
        return isCoreSdkInitialized
    }

    fun isDriverOpened(): Boolean {
        return isDriverOpened
    }

    fun isScanningDevice(): Boolean {
        return isScanningDevice.get()
    }

    fun addListener(listener: SdkManagerListener) {
        synchronized(listeners) {
            if (listeners.add(listener)) {
                Timber.d( "Listener added: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}") // ★追加
                mainHandler.post {
                    listener.onDriverStatusChanged(isDriverOpened, openedDeviceName)
                    listener.onConnectionStatusChanged(currentConnectionStatus, connectedDeviceAddressInternal)
                    if (lastBatteryLevel != -1) {
                        listener.onBatteryLevelChanged(lastBatteryLevel, 0, true)
                    }
                    if (lastParsedFirmwareVersion != null || lastRawFirmwareVersion != null) {
                        listener.onFirmwareVersionChanged(
                            lastRawFirmwareVersion,
                            lastParsedFirmwareVersion,
                            parsePowerTypeFromFirmware(lastRawFirmwareVersion),
                            true,
                            shouldRecreateInitFileAfterFirmwareGet
                        )
                    }
                }
            } else {
                Timber.d( "Listener already registered: $listener")
            }
        }
    }

    fun removeListener(listener: SdkManagerListener) {
        synchronized(listeners) {
            Timber.d( "Listener removed: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}")
            listeners.remove(listener)
        }
    }

    private fun executeOnBackground(block: () -> Unit) {
        if (!backgroundExecutor.isShutdown) {
            try {
                backgroundExecutor.execute(block)
            } catch (e: RejectedExecutionException) {
                Timber.e( "Failed to execute task on background executor: ${e.message}", e)
                mainHandler.post {
                    notifyErrorOccurredToListeners("background_execution_failure", -100, null, "Could not schedule background task.", false)
                }
            }
        } else {
            Timber.w( "Background executor is shutdown. Cannot execute task.")
        }
    }

    fun shutdown() {
        Timber.i("Shutting down SdkManager...")
        stopMonitoring() // 同期的

        if (currentConnectionStatus != ConnectionStatus.DISCONNECTED && currentConnectionStatus != ConnectionStatus.DISCONNECTING) {
            // disconnect() // これは非同期。同期的なdisconnectがあればそちらを検討
            // もし同期的なdisconnectがなければ、この場でできる限りの処理をする
            try {
                tecRfidAdapter.releaseDevice() // 同期的に試みる
                updateConnectionStatusInternal(ConnectionStatus.DISCONNECTED, connectedDeviceAddressInternal)
            } catch (e: Exception) { Timber.e(e, "Error during sync releaseDevice in shutdown") }
        }

        if (isDriverOpened) {
            try {
                tecRfidAdapter.close() // ★ 同期的にクローズを試みる
                isDriverOpened = false
                openedDeviceName = null
                Timber.i("Driver closed synchronously during shutdown.")
            } catch (e: Exception) {
                Timber.e(e, "Exception during synchronous driver close in shutdown.")
            }
        }

        if (isScanningDevice.getAndSet(false)) {
            try {
                tecRfidAdapter.stopBluetoothDiscovery()
            } catch (e: Exception) { Timber.e(e, "Error stopping BT discovery in shutdown") }
        }

        mainHandler.removeCallbacksAndMessages(null) // メインスレッドのキューをクリア

        backgroundExecutor.shutdown()
        try {
            if (!backgroundExecutor.awaitTermination(1, TimeUnit.SECONDS)) { // 1秒待つ
                backgroundExecutor.shutdownNow() // 強制停止
                if (!backgroundExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    Timber.e("Background executor did not terminate after shutdownNow.")
                }
            }
        } catch (ie: InterruptedException) {
            backgroundExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        synchronized(listeners) {
            listeners.clear()
        }
        // 状態をリセット
        currentConnectionStatus = ConnectionStatus.DISCONNECTED
        connectedDeviceAddressInternal = null
        isCoreSdkInitialized = false // SdkManager自体が再初期化されることを想定
        Timber.i("SdkManager shutdown sequence complete.")
    }

    fun getDeviceName(): String = deviceName_UF_3000
    // --- 状態取得 ---
    fun isConnected(): Boolean = currentConnectionStatus == ConnectionStatus.CONNECTED
    fun getCurrentConnectionStatus(): ConnectionStatus = currentConnectionStatus
    fun getConnectedDeviceAddress(): String? = connectedDeviceAddressInternal
    fun getLastBatteryLevel(): Int = lastBatteryLevel
    fun getLastChargingState(): Int = lastchargingState
    fun getLastRawFirmwareVersion(): String? = lastRawFirmwareVersion
    fun getLastParsedFirmwareVersion(): String? = lastParsedFirmwareVersion
    fun getOpenedDeviceName(): String? = openedDeviceName
    fun getDefaultDriverLogLevel(): Int = DEFAULT_DRIVER_LOG_LEVEL
    fun getDefaultDriverLogSize(): Int = DEFAULT_DRIVER_LOG_SIZE_KB


    // --- SDK操作メソッド ---
    fun openDriver(deviceNameToOpen: String) {
        if (!isCoreSdkInitialized) {
            Timber.e( "Cannot open driver, SdkManager core not initialized.")
            mainHandler.post {
                notifyErrorOccurredToListeners("openDriver", ToshibaTecSdk.OPOS_E_CLOSED, null, "SDK Core not initialized", false)
                notifyDriverStatusChangedToListeners(false, deviceNameToOpen)
            }
            return
        }
        if (isDriverOpened) {
            if (openedDeviceName == deviceNameToOpen) {
                Timber.d( "Driver for '$deviceNameToOpen' is already open.")
                mainHandler.post { notifyDriverStatusChangedToListeners(true, deviceNameToOpen) } // すでに開いていることを通知
                return
            } else {
                Timber.w( "Driver is open for '$openedDeviceName', but requested for '$deviceNameToOpen'. Closing existing first.")
                // 既存を閉じてから新しいものを開く必要がある場合、closeDriver を呼び出すか、より複雑なロジックが必要
                // ここでは単純化のため、エラーとするか、何もしない（あるいは既存を優先）
                // 最小限の変更としては、現在のものを維持し、新しいopen要求は無視またはエラー
                mainHandler.post {
                    notifyErrorOccurredToListeners("openDriver", ToshibaTecSdk.OPOS_E_ILLEGAL, null, "Driver already open for another device: $openedDeviceName", false)
                    notifyDriverStatusChangedToListeners(true, openedDeviceName) // 現在の状態を通知
                }
                return
            }
        }
        Timber.i( "Attempting to open driver for '$deviceNameToOpen' with ActivityContext.")

        executeOnBackground {
            try {
                // TecRfidSuiteAdapterのopenメソッドを使用
                val result = tecRfidAdapter.open(
                    deviceNameToOpen,
                    appContext,
                    currentDriverLogLevel,
                    DEFAULT_DRIVER_LOG_SIZE_KB
                )

                mainHandler.post {
                    if (result == ToshibaTecSdk.OPOS_SUCCESS) {
                        isDriverOpened = true
                        openedDeviceName = deviceNameToOpen
                        Timber.i( "Driver opened successfully for: $deviceNameToOpen")
                        notifyDriverStatusChangedToListeners(true, deviceNameToOpen)
                    } else {
                        isDriverOpened = false
                        openedDeviceName = null
                        Timber.e( "Failed to open driver for '$deviceNameToOpen'. Code: $result")
                        notifyErrorOccurredToListeners("openDriver", result, null, appContext.getString(R.string.message_error_device_open, deviceNameToOpen), false)
                        notifyDriverStatusChangedToListeners(false, deviceNameToOpen)
                    }
                }
            } catch (e: Exception) {
                Timber.e( "Exception during driver open for $deviceNameToOpen", e)
                mainHandler.post {
                    isDriverOpened = false
                    openedDeviceName = null
                    notifyErrorOccurredToListeners("openDriver_exception", -1, null, "Exception: ${e.localizedMessage}", false)
                    notifyDriverStatusChangedToListeners(false,deviceNameToOpen)
                }
            }
        }
    }

    private fun closeDriverInternal(isShuttingDown: Boolean = false) {
        if (!isDriverOpened) {
            Timber.d( "Driver not open, no need to close.")
            return
        }
        val deviceNameToClose = openedDeviceName
        Timber.i( "Attempting to close driver for '$deviceNameToClose'")
        // ドライバクローズ処理は接続状態とは独立して行う

        executeOnBackground {
            try {
                val result = tecRfidAdapter.close()
                mainHandler.post {
                    if (result == ToshibaTecSdk.OPOS_SUCCESS) {
                        Timber.i( "Driver closed successfully for: $deviceNameToClose")
                    } else {
                        Timber.e( "Failed to close driver for '$deviceNameToClose'. Code: $result")
                        if (!isShuttingDown) { // シャットダウン時以外はエラー通知
                            notifyErrorOccurredToListeners("closeDriver", result, null, appContext.getString(R.string.message_error_device_close, deviceNameToClose), false)
                        }
                    }
                    // 成功失敗に関わらず、クローズ試行後は状態を更新し通知
                    isDriverOpened = false
                    openedDeviceName = null
                    if (!isShuttingDown) {
                        notifyDriverStatusChangedToListeners(false, deviceNameToClose)
                    }
                }
            } catch (e: Exception) {
                Timber.e( "Exception during driver close for $deviceNameToClose", e)
                mainHandler.post {
                    if (!isShuttingDown) {
                        notifyErrorOccurredToListeners("closeDriver_exception", -1, null, "Exception: ${e.localizedMessage}", false)
                    }
                    isDriverOpened = false
                    openedDeviceName = null
                    if (!isShuttingDown) {
                        notifyDriverStatusChangedToListeners(false, deviceNameToClose)
                    }
                }
            }
        }
    }

    fun connect(connectionString: String, deviceName: String) { // deviceName は openDriver で使用されたものと一致想定
        if (!isCoreSdkInitialized) {
            Timber.e( "Cannot connect, SdkManager core not initialized.")
            handleConnectionErrorInternal("connect_core_init", ToshibaTecSdk.OPOS_E_CLOSED, null, deviceName, "SDK Core not initialized for connect")
            return
        }
        if (!isDriverOpened) {
            Timber.e( "Cannot connect, driver not opened for '$deviceName'. Call openDriver() first.")
            // ここでエラーを通知し、接続状態をERRORにする
            handleConnectionErrorInternal("connect_driver_closed", ToshibaTecSdk.OPOS_E_CLOSED, null, deviceName, appContext.getString(R.string.message_error_driver_not_opened, deviceName))
            return
        }
        if (openedDeviceName != deviceName) {
            Timber.e( "Cannot connect, driver opened for '$openedDeviceName' but trying to connect to '$deviceName'.")
            handleConnectionErrorInternal("connect_device_mismatch", ToshibaTecSdk.OPOS_E_ILLEGAL, null, deviceName, "Device name mismatch for connect")
            return
        }

        if (currentConnectionStatus == ConnectionStatus.CONNECTING || currentConnectionStatus == ConnectionStatus.CONNECTED) {
            Timber.w( "Connect called while already $currentConnectionStatus. Ignoring.")
            // 既存の接続状態を再通知するかどうかは要件による
            mainHandler.post { notifyConnectionStatusChangedToListeners(currentConnectionStatus, connectedDeviceAddressInternal) }
            return
        }
        Timber.i( "Attempting to connect to '$deviceName' (using address '$connectionString')")
        this.connectedDeviceAddressInternal = connectionString // 接続試行中のアドレスを仮設定
        updateConnectionStatusInternal(ConnectionStatus.CONNECTING, connectionString)

        executeOnBackground {
            try {
                // 1. claimDevice
                var result = tecRfidAdapter.claimDevice(connectionString, internalConnectionEventHandler)
                if (result != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to claim device '$connectionString'. Code: $result")
                    handleConnectionErrorInternal("claimDevice", result, null, connectionString, appContext.getString(R.string.message_error_device_claim, connectionString))
                    return@executeOnBackground
                }
                Timber.i( "Device claimed successfully: $connectionString")
                // claimDevice に成功したら、実際に接続されたアドレスを確定させる
                // (internalConnectionEventHandler の onConnectionStateChanged(CONNECT_STATE_ONLINE) でも良いが、ここで設定しても良い)
                this.connectedDeviceAddressInternal = connectionString // 確定

                // 2. setDeviceEnabled(true) - 接続タイプに関わらず実行
                result = tecRfidAdapter.setDeviceEnabled(true)
                if (result != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to enable device '$deviceName' (address: '$connectionString'). Code: $result")
                    tecRfidAdapter.releaseDevice() // enable失敗時はreleaseする
                    handleConnectionErrorInternal("setDeviceEnabled", result, null, deviceName, appContext.getString(R.string.message_error_device_enable))
                    return@executeOnBackground
                }
                Timber.i( "Device enabled successfully: '$deviceName' (address: '$connectionString')")

                // 3. enableModelCheckProperty
                //    このコールバック内で onConnectionStatusChanged(ConnectionStatus.CONNECTED, ...) を呼び出し、
                //    さらに fetchInitialDeviceData() を呼び出すのがより堅牢。
                //    ここでは、まず呼び出しまでを実装する。
                val modelCheckResult = tecRfidAdapter.enableModelCheckProperty(object : AppGenericResultCallback {
                    override fun onCallback(addr: String?, resCode: Int, resCodeExt: Int) {
                        mainHandler.post { // UIスレッド/メインスレッドで後続処理
                            if (resCode == ToshibaTecSdk.OPOS_SUCCESS) {
                                Timber.d( "enableModelCheckProperty successful for $addr.")
                                fetchInitialDeviceData() // バッテリー、ファームウェアなどを取得
                            } else {
                                val errorMsg = "Failed to enable model check property for $addr. Code: $resCode, Ext: $resCodeExt"
                                Timber.e( errorMsg)
                                notifyErrorOccurredToListeners("enableModelCheckProperty_callback", resCode, resCodeExt, errorMsg, false)
                            }
                        }
                    }
                })

                if (modelCheckResult != ToshibaTecSdk.OPOS_SUCCESS) {
                    // enableModelCheckProperty の呼び出し自体が即時エラーを返した場合
                    val errorMsg = "Failed to initiate enableModelCheckProperty for '$connectionString'. Code: $modelCheckResult"
                    Timber.e( errorMsg)

                    Timber.w( "enableModelCheckProperty send failed. Attempting to fetch data anyway.")
                    mainHandler.post { fetchInitialDeviceData() }
                }

                Timber.i( "Connection process after claim/enable initiated for $deviceName ($connectionString). Waiting for SDK connection events (or model check callback).")

            } catch (e: Exception) {
                Timber.e( "Exception during connection to $deviceName ($connectionString)", e)
                handleConnectionErrorInternal("connect_exception", -1, null, deviceName, appContext.getString(R.string.message_error_connect_exception, e.localizedMessage ?: "Unknown error"))
            }
        }
    }

    private fun fetchInitialDeviceData() {
        if (currentConnectionStatus == ConnectionStatus.CONNECTED) { // 念のため接続状態を確認
            Timber.d( "Fetching initial data (battery, firmware) for $connectedDeviceAddressInternal")
            fetchBatteryLevel()
            fetchFirmwareVersion()
            // 他に必要な初期情報があればここで取得
        } else {
            Timber.w( "Cannot fetch initial device data, current status is $currentConnectionStatus")
        }
    }

    fun disconnect() {
        if (currentConnectionStatus == ConnectionStatus.DISCONNECTED || currentConnectionStatus == ConnectionStatus.DISCONNECTING) {
            Timber.w( "Disconnect called while already $currentConnectionStatus.")
            // 状態がDISCONNECTEDでない場合は、現在の状態を再通知することが考えられる
            if (currentConnectionStatus != ConnectionStatus.DISCONNECTED) {
                mainHandler.post { notifyConnectionStatusChangedToListeners(currentConnectionStatus, connectedDeviceAddressInternal) }
            }
            return
        }
        val addrAtDisconnect = connectedDeviceAddressInternal
        val deviceNameAtDisconnect = openedDeviceName // ドライバが開かれたデバイス名
        Timber.i( "Attempting to disconnect from ${addrAtDisconnect ?: "unknown device"} (driver: ${deviceNameAtDisconnect ?: "N/A"})")
        updateConnectionStatusInternal(ConnectionStatus.DISCONNECTING, addrAtDisconnect)

        executeOnBackground {
            var result: Int = ToshibaTecSdk.OPOS_SUCCESS
            try {
                result = tecRfidAdapter.releaseDevice()
                if (result != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to release device ($addrAtDisconnect). Code: $result")
                    // Notify error, but still attempt to close
                    mainHandler.post {
                        notifyErrorOccurredToListeners("releaseDevice", result, null, appContext.getString(R.string.message_error_device_release, addrAtDisconnect), false)
                    }
                } else {
                    Timber.i( "Device released: $addrAtDisconnect")
                }
            } catch (e: Exception) {
                Timber.e( "Exception during disconnection from $addrAtDisconnect", e)
                mainHandler.post {
                    notifyErrorOccurredToListeners("closeDriver_on_disconnect", result, null, appContext.getString(R.string.message_error_device_close, deviceNameAtDisconnect), false)
                }
            } finally {
                // Ensure state is updated even if SDK calls fail, internalConnectionEventHandler might also call this for ConnectStateOffline
                if (currentConnectionStatus != ConnectionStatus.DISCONNECTED) {
                    updateConnectionStatusInternal(ConnectionStatus.DISCONNECTED, addrAtDisconnect)
                }
                mainHandler.removeCallbacks(periodicBatteryCheckRunnable) // Stop battery checks
            }
        }
    }

    fun fetchBatteryLevel() {
        if (!isConnected()) {
            Timber.v( "Cannot fetch battery level, not connected ($currentConnectionStatus).")
            mainHandler.post { listeners.forEach { it.onBatteryLevelChanged(-1,0, false) } }
            return
        }
        Timber.v( "Requesting battery level for $connectedDeviceAddressInternal")
        val result = tecRfidAdapter.getBatteryLevel(internalBatteryCallback) // Adapter uses AppBatteryLevelCallback
        if (result != ToshibaTecSdk.OPOS_SUCCESS) {
            val errorMsg = appContext.getString(R.string.message_error_send_get_battery)
            Timber.w( "$errorMsg Code: $result")
            mainHandler.post {
                listeners.forEach { it.onBatteryLevelChanged(-1,0, false) }
                notifyErrorOccurredToListeners("getBatteryLevel_send", result, null, errorMsg, false)
            }
        }
    }

    fun fetchFirmwareVersion() {
        if (!isConnected()) {
            Timber.d( "Cannot fetch firmware version, not connected ($currentConnectionStatus).")
            mainHandler.post { listeners.forEach { it.onFirmwareVersionChanged(null, null, null, false, false) } }
            return
        }
        Timber.v( "Requesting firmware version for $connectedDeviceAddressInternal")
        val result = tecRfidAdapter.getFirmwareVer(internalFirmwareCallback) // Adapter uses AppFirmwareVersionCallback
        if (result != ToshibaTecSdk.OPOS_SUCCESS) {
            val errorMsg = appContext.getString(R.string.message_error_send_get_firmware)
            Timber.w( "$errorMsg Code: $result")
            mainHandler.post {
                listeners.forEach { it.onFirmwareVersionChanged(null, null, null, false, false) }
                notifyErrorOccurredToListeners("getFirmwareVer_send", result, null, errorMsg, false)
            }
        }
    }

    fun performStartReadTags(filterID: String, filterMask: String, timeout: Int) {
        if (!isConnected()) {
            Timber.w( "Cannot start read tags, not connected.")
            mainHandler.post {
                notifyGenericResultToListeners(SdkOperationType.START_READ_TAGS, ToshibaTecSdk.OPOS_E_OFFLINE, 0)
                notifyErrorOccurredToListeners("startReadTags", ToshibaTecSdk.OPOS_E_OFFLINE, null, "Not connected", true)
            }
            return
        }
        Timber.i( "performStartReadTags called with filterID: $filterID, filterMask: $filterMask, timeout: $timeout")

        // SDKのDataEventHandlerとErrorEventHandlerを直接実装する
        val sdkDataHandler = SdkDataEventHandler { tagList ->
            Timber.d( "performStartReadTags - SdkDataEventHandler.onEvent received: ${tagList?.size ?: 0} tags")
            mainHandler.post {
                if (tagList != null) {
                    notifyTagDataToListeners(tagList)
                }
                executeOnBackground {
                    Timber.d( "Re-enabling data event after receiving one...")
                    // TecRfidSuiteAdapter の setDataEventEnabled を呼び出す
                    val reEnableResult = tecRfidAdapter.setDataEventEnabled(true)
                    if (reEnableResult != ToshibaTecSdk.OPOS_SUCCESS) {
                        Timber.e( "Failed to re-enable dataEventEnabled. Code: $reEnableResult")
                        mainHandler.post{ // エラー通知はメインスレッドで
                            notifyErrorOccurredToListeners(
                                "setDataEventEnabled_reenable",
                                reEnableResult,
                                null,
                                appContext.getString(R.string.message_error_set_data_event_enabled_reenable),
                                false
                            )
                        }
                    } else {
                        Timber.d( "dataEventEnabled re-enabled successfully.")
                    }
                }
            }
        }

        val sdkErrorHandler = SdkErrorEventHandler { resultCode, resultCodeExtended ->
            Timber.d("performStartReadTags - SdkErrorEventHandler.onEvent: resultCode=$resultCode, extCode=$resultCodeExtended")
            val isTriggerWaitCode = (resultCode == 301 && resultCodeExtended == 21)
            if (isTriggerWaitCode) {
                Timber.v( "Trigger wait event received (Code: $resultCode, Ext: $resultCodeExtended). Not treating as an error. Waiting for trigger.")
            } else {
                Timber.e( "Unhandled async error during startReadTags. Code: $resultCode, Ext: $resultCodeExtended. Attempting to stop read.")
                mainHandler.post {
                    notifyErrorOccurredToListeners(
                        "startReadTags_async_error",
                        resultCode,
                        resultCodeExtended,
                        appContext.getString(R.string.message_error_start_read_tags_async) + " (Code:$resultCode, Ext:$resultCodeExtended)",
                        !isConnected() // Check if this error implies connection loss
                    )
                    // Also notify as a generic result if appropriate, for example, if the operation itself failed
                    notifyGenericResultToListeners(
                        SdkOperationType.START_READ_TAGS,
                        resultCode,
                        resultCodeExtended
                    )
                    Timber.i( "Calling performStopReadTags as a safety measure due to unhandled async error.")
                    performStopReadTags()
                }
            }
        }

        executeOnBackground {
            // 1. startReadTags を呼び出す
            val resultCodeOfStart = tecRfidAdapter.startReadTags(
                filterID,
                filterMask,
                timeout,
                sdkDataHandler,
                sdkErrorHandler
            )

            mainHandler.post { // UIスレッド/メインスレッドで結果を通知
                notifyGenericResultToListeners(SdkOperationType.START_READ_TAGS, resultCodeOfStart, 0)
            }

            if (resultCodeOfStart == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.d( "performStartReadTags successfully initiated.")
                Timber.d( "Attempting to set dataEventEnabled to TRUE after successful startReadTags.")
                val enableEventResult = tecRfidAdapter.setDataEventEnabled(true) // TecRfidSuiteAdapter のメソッドを呼び出す
                if (enableEventResult != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to set dataEventEnabled to TRUE after startReadTags. Code: $enableEventResult")
                    mainHandler.post { // UIスレッド/メインスレッドでエラーを通知
                        notifyErrorOccurredToListeners(
                            "setDataEventEnabled_after_start",
                            enableEventResult,
                            null,
                            appContext.getString(R.string.message_error_set_data_event_enabled_after_start),
                            false
                        )
                        Timber.i( "Calling performStopReadTags as a safety measure due to unhandled async error.")
                        performStopReadTags()
                    }
                } else {
                    Timber.i( "dataEventEnabled set to TRUE successfully after startReadTags. Waiting for data/error events.")
                }
            } else {
                Timber.w( "performStartReadTags initiation failed with result code: $resultCodeOfStart. setDataEventEnabled will not be called.")
            }
        }
    }

    fun performStopReadTags() {
        if (!isConnected()) {
            Timber.w( "Cannot stop read tags, not connected ($currentConnectionStatus).")
            mainHandler.post {
                notifyGenericResultToListeners(SdkOperationType.STOP_READ_TAGS, ToshibaTecSdk.OPOS_E_OFFLINE, 0)
                notifyErrorOccurredToListeners("stopReadTags", ToshibaTecSdk.OPOS_E_OFFLINE, null, "Not connected", true)
            }
            return
        }
        Timber.d( "performStopReadTags called")

        // SDKのResultCallbackを直接実装する
        val sdkStopCallback = SdkResultCallback { resultCode, resultCodeExtended ->
            Timber.d( "performStopReadTags - SdkResultCallback.onCallback: resultCode=$resultCode, extCode=$resultCodeExtended")
            mainHandler.post {
                notifyGenericResultToListeners(SdkOperationType.STOP_READ_TAGS, resultCode, resultCodeExtended)
                if (resultCode != ToshibaTecSdk.OPOS_SUCCESS) {
                    notifyErrorOccurredToListeners(
                        "stopReadTags_async_result",
                        resultCode,
                        resultCodeExtended,
                        appContext.getString(R.string.message_error_stop_read_tags_async),
                        !isConnected() // Check if this error implies connection loss
                    )
                }
            }
        }
        executeOnBackground {
            try {
                // TecRfidSuiteAdapterのstopReadTagsがSdkResultCallbackを引数に取る
                val resultCodeOfStopCall = tecRfidAdapter.stopReadTags(sdkStopCallback)

                // stopReadTags の "呼び出し自体" の同期的結果
                if (resultCodeOfStopCall != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.w( "performStopReadTags call itself failed with sync result code: $resultCodeOfStopCall. Async callback might not be invoked.")
                    mainHandler.post {
                        // 同期的な呼び出しエラーも通知
                        notifyGenericResultToListeners(SdkOperationType.STOP_READ_TAGS, resultCodeOfStopCall, 0)
                        notifyErrorOccurredToListeners(
                            "stopReadTags_sync_call_error",
                            resultCodeOfStopCall,
                            0,
                            appContext.getString(R.string.message_error_stop_read_tags_sync),
                            !isConnected()
                        )
                    }
                } else {
                    Timber.d( "performStopReadTags call successfully initiated. Waiting for async SdkResultCallback.")
                }
            } catch (e: Exception) {
                Timber.e( "Exception during performStopReadTags call: ${e.message}", e)
                mainHandler.post {
                    notifyGenericResultToListeners(SdkOperationType.STOP_READ_TAGS, ToshibaTecSdk.OPOS_E_FAILURE, 0)
                    notifyErrorOccurredToListeners(
                        "performStopReadTags_exception",
                        ToshibaTecSdk.OPOS_E_FAILURE,
                        0,
                        appContext.getString(R.string.message_error_stop_read_tags_exception, e.localizedMessage),
                        true
                    )
                }
            }
        }
    }
    fun setSdkLoggingOptions(logLevel: Int, logSize: Int) {
        if (!isCoreSdkInitialized) { // SDKコア未初期化時は何もしない
            Timber.w( "Cannot set SDK logging options, core not initialized.")
            mainHandler.post {
                notifyGenericResultToListeners(SdkOperationType.SET_SDK_LOGGING, ToshibaTecSdk.OPOS_E_CLOSED, 0)
                notifyErrorOccurredToListeners("setSdkLoggingOptions", ToshibaTecSdk.OPOS_E_CLOSED, null, "SDK Core not initialized", false)
            }
            return
        }
        if (!isDriverOpened()) {
            Timber.w( "Cannot set SDK logging options, driver not opened.")
            return
        }
        Timber.i( "Setting SDK logging options: Level=$logLevel, Size=$logSize")
        executeOnBackground {
            val options = HashMap<String, Int>()
            options[ToshibaTecSdk.OptionPackKeyLogLevel] = logLevel
            options[ToshibaTecSdk.OptionPackKeyLogFileSize] = logSize
            val result = tecRfidAdapter.setOptions(options) // adapter経由

            mainHandler.post {
                notifyGenericResultToListeners(SdkOperationType.SET_SDK_LOGGING, result, 0)
                if (result != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to set SDK logging options. Code: $result")
                    notifyErrorOccurredToListeners(
                        "setSdkLoggingOptions",
                        result,
                        null,
                        appContext.getString(R.string.message_error_set_sdk_options),
                        false
                    )
                } else {
                    Timber.i( "SDK logging options set successfully.")
                }
            }
        }
    }

    fun startMonitoring() {
        Timber.i( "startMonitoring called.")
        mainHandler.post { // Notify current status
            notifyConnectionStatusChangedToListeners(currentConnectionStatus, connectedDeviceAddressInternal)
        }

        if (isConnected()) {
            fetchBatteryLevel()
            fetchFirmwareVersion()
            mainHandler.removeCallbacks(periodicBatteryCheckRunnable) // Remove existing
            mainHandler.postDelayed(periodicBatteryCheckRunnable, batteryCheckIntervalMillis)
            Timber.i( "Battery level periodic check scheduled every $batteryCheckIntervalMillis ms.")
        } else {
            Timber.i( "Not connected, periodic battery check not started.")
            mainHandler.post { // Notify with last known or default values if not connected
                listeners.forEach {
                    it.onBatteryLevelChanged(lastBatteryLevel.takeIf { l -> l != -1 } ?: -1,lastchargingState, lastBatteryLevel != -1 && currentConnectionStatus == ConnectionStatus.CONNECTED)
                    it.onFirmwareVersionChanged(
                        lastRawFirmwareVersion,
                        lastParsedFirmwareVersion,
                        parsePowerTypeFromFirmware(lastRawFirmwareVersion),
                        lastParsedFirmwareVersion != null && currentConnectionStatus == ConnectionStatus.CONNECTED
                    )
                }
            }
        }
    }

    fun stopMonitoring() {
        Timber.i( "stopMonitoring called.")
        mainHandler.removeCallbacks(periodicBatteryCheckRunnable)
        Timber.i( "Battery level periodic check stopped.")
    }

    // SettingsActivityで使用するメソッド
    fun getTriggerSwMode(callback: TriggerSwModeCallback): Int {
        return sdkInstance.getTriggerSwMode(callback)
    }
    fun setTriggerSwMode(trigMode: Int,callback: ResultCallback): Int {
        return sdkInstance.setTriggerSwMode(trigMode,callback)
    }
    fun getPower(callback: PowerCallback): Int {
        return sdkInstance.getPower(callback)
    }
    fun setPower(powerLevel: Int,callback: ResultCallback): Int {
        return sdkInstance.setPower(powerLevel,callback)
    }
    fun getQValue(callback: QValueCallback): Int {
        return sdkInstance.getQValue(callback)
    }
    fun setQValue(valueQ: Int,callback: ResultCallback): Int {
        return sdkInstance.setQValue(valueQ,callback)
    }
    fun getFrequency(callback: FrequencyCallback): Int {
        return sdkInstance.getFrequency(callback)
    }
    fun setFrequency(frequencyChannel: Int,autoFrequencyList: ArrayList<Int>,callback: ResultCallback): Int {
        return sdkInstance.setFrequency(frequencyChannel,autoFrequencyList,callback)
    }
    fun getSavingEnergy(callback: SavingEnergyCallback): Int {
        return sdkInstance.getSavingEnergy(callback)
    }
    fun setSavingEnergy(energy: Int, callback: ResultCallback): Int {
        return sdkInstance.setSavingEnergy(energy,callback)
    }
    fun getSessionID(callback: SessionIDCallback ): Int {
        return sdkInstance.getSessionID(callback)
    }
    fun setSessionID(sessionID: Int, callback: ResultCallback): Int {
        return sdkInstance.setSessionID(sessionID,callback)
    }
    fun getTagReadMode(callback: TagReadModeCallback ): Int {
        return sdkInstance.getTagReadMode(callback)
    }
    fun setTagReadMode(tagSpeed: Int,millerSubCarrier: Int,callback: ResultCallback): Int {
        return sdkInstance.setTagReadMode(tagSpeed,millerSubCarrier,callback)
    }
    fun getFlagAB(callback: FlagABCallback): Int {
        return sdkInstance.getFlagAB(callback)
    }
    fun setFlagAB(flag: Int, callback: ResultCallback): Int {
        return sdkInstance.setFlagAB(flag,callback)
    }
    fun getMisreadingPreventionSettings(callback: MisreadingPreventionSettingsCallback): Int {
        return sdkInstance.getMisreadingPreventionSettings(callback)
    }
    fun setMisreadingPreventionSettings(id: Int, callback: ResultCallback): Int {
        return sdkInstance.setMisreadingPreventionSettings(id,callback)
    }
    fun getUSBCharging(callback: USBChargingCallback): Int {
        return sdkInstance.getUSBCharging(callback)
    }
    fun setUSBCharging(charging: Int, callback: ResultCallback): Int {
        return sdkInstance.setUSBCharging(charging, callback)
    }
    fun getAntennaPolarization(callback: AntennaPolarizationCallback): Int {
        return sdkInstance.getAntennaPolarization(callback)
    }
    fun setAntennaPolarization(polarization: Int, callback: ResultCallback): Int {
        return sdkInstance.setAntennaPolarization(polarization, callback)
    }
    fun saveMemory(callback: ResultCallback): Int {
        return sdkInstance.saveMemory(callback)
    }
    fun stopReadTags(callback: ResultCallback): Int {
        return sdkInstance.stopReadTags(callback)
    }

    // --- Internal Callbacks (Adapter to SdkManager) ---

    private val internalConnectionEventHandler = object : AppConnectionEventHandler {
        override fun onConnectionStateChanged(deviceAddress: String?, sdkConnectionStatus: Int) {
            val statusStr = sdkStatusToString(sdkConnectionStatus)
            // Log the raw event received from the adapter
            Timber.d(
                "internalConnectionEventHandler - onConnectionStateChanged RECEIVED: " +
                        "sdkDeviceAddress='${deviceAddress ?: "null"}', " +
                        "sdkConnectionStatus=$statusStr ($sdkConnectionStatus), " +
                        "currentMgrStatus=$currentConnectionStatus, " +
                        "currentMgrDeviceAddr='${this@SdkManager.connectedDeviceAddressInternal ?: "null"}'"
            )

            mainHandler.post { // Ensure all subsequent logic runs on the main thread
                val previousMgrStatus = currentConnectionStatus // SdkManager's status before this event
                val previousMgrDeviceAddress = this@SdkManager.connectedDeviceAddressInternal // SdkManager's connected address before this event

                if (sdkConnectionStatus == ToshibaTecSdk.ConnectStateOnline) {
                    // Determine the device address to use. Prioritize SDK's address, fallback to SdkManager's current.
                    val addrToUseForConnection = deviceAddress ?: previousMgrDeviceAddress

                    if (addrToUseForConnection == null && previousMgrStatus == ConnectionStatus.CONNECTING) {
                        // This indicates a problem: we were trying to connect, SDK says online, but no address is available.
                        Timber.e(
                            "internalConnectionEventHandler: ConnectStateOnline received but effective device address is NULL. " +
                                    "Cannot transition to CONNECTED. Previous SdkManager status: $previousMgrStatus."
                        )
                        // Update status to ERROR and notify listeners
                        currentConnectionStatus = ConnectionStatus.ERROR
                        this@SdkManager.connectedDeviceAddressInternal = null // Clear any stale address
                        notifyConnectionStatusChangedToListeners(ConnectionStatus.ERROR, null)
                        notifyErrorOccurredToListeners(
                            "onConnectionStateChanged_OnlineNoAddr",
                            sdkConnectionStatus,
                            null,
                            appContext.getString(R.string.message_error_connected_no_address), // You might need a new string resource
                            true
                        )
                        return@post
                    }

                    if (addrToUseForConnection == null && previousMgrStatus == ConnectionStatus.CONNECTED) {
                        Timber.w(
                            "internalConnectionEventHandler: ConnectStateOnline received but effective device address is NULL. " +
                                    "However, SdkManager was already CONNECTED to '$previousMgrDeviceAddress'. Maintaining previous state for now."
                        )
                        return@post
                    }


                    if (previousMgrStatus != ConnectionStatus.CONNECTED) {
                        Timber.i(
                            "internalConnectionEventHandler: Transitioning SdkManager status from $previousMgrStatus to CONNECTED. " +
                                    "Device: '$addrToUseForConnection'"
                        )
                        currentConnectionStatus = ConnectionStatus.CONNECTED
                        this@SdkManager.connectedDeviceAddressInternal = addrToUseForConnection // Update SdkManager's known address

                        notifyConnectionStatusChangedToListeners(ConnectionStatus.CONNECTED, this@SdkManager.connectedDeviceAddressInternal)

                        // モニター開始
                        fetchBatteryLevel()
                        fetchFirmwareVersion()
                        mainHandler.removeCallbacks(periodicBatteryCheckRunnable) // Remove existing if any
                        mainHandler.postDelayed(periodicBatteryCheckRunnable, batteryCheckIntervalMillis)
                        Timber.i( "Battery level periodic check scheduled every $batteryCheckIntervalMillis ms for $addrToUseForConnection.")

                    } else { // 既に接続されている場合
                        if (addrToUseForConnection != null && addrToUseForConnection != previousMgrDeviceAddress) {
                            Timber.w(
                                "internalConnectionEventHandler: ConnectStateOnline received for a DIFFERENT address ('$addrToUseForConnection') " +
                                        "while SdkManager already CONNECTED to ('$previousMgrDeviceAddress'). Updating address and notifying."
                            )
                            this@SdkManager.connectedDeviceAddressInternal = addrToUseForConnection // Update to the new address

                            // 別のデバイスへの接続を通知
                            notifyConnectionStatusChangedToListeners(ConnectionStatus.CONNECTED, this@SdkManager.connectedDeviceAddressInternal)

                            // モニター開始
                            fetchBatteryLevel()
                            fetchFirmwareVersion()
                            mainHandler.removeCallbacks(periodicBatteryCheckRunnable)
                            mainHandler.postDelayed(periodicBatteryCheckRunnable, batteryCheckIntervalMillis)

                        } else {
                            Timber.d(
                                "internalConnectionEventHandler: ConnectStateOnline received while SdkManager already CONNECTED " +
                                        "to the same address ('$addrToUseForConnection'). No status change needed."
                            )
                        }
                    }

                } else { // 接続されていない場合
                    val newHelperStatus = when (sdkConnectionStatus) {
                        ToshibaTecSdk.ConnectStateOffline -> ConnectionStatus.DISCONNECTED
                        else -> ConnectionStatus.ERROR
                    }
                    Timber.i(
                        "internalConnectionEventHandler: SDK reported non-Online state ($statusStr). " +
                                "Mapping to SdkManager status: $newHelperStatus. " +
                                "Previous SdkManager status: $previousMgrStatus. " +
                                "SDK Address: '${deviceAddress ?: "null"}'"
                    )

                    val wasConnectedOrConnecting = (previousMgrStatus == ConnectionStatus.CONNECTED || previousMgrStatus == ConnectionStatus.CONNECTING)

                    if (currentConnectionStatus != newHelperStatus || wasConnectedOrConnecting) {
                        currentConnectionStatus = newHelperStatus
                        val addrForNotification = deviceAddress ?: previousMgrDeviceAddress

                        if (newHelperStatus == ConnectionStatus.DISCONNECTED || newHelperStatus == ConnectionStatus.ERROR) {
                            Timber.i(
                                "SdkManager is now $newHelperStatus for device: '${addrForNotification ?: "unknown"}'. " +
                                        "Stopping periodic checks and clearing stale data."
                            )
                            this@SdkManager.connectedDeviceAddressInternal = null
                            lastBatteryLevel = -1
                            mainHandler.removeCallbacks(periodicBatteryCheckRunnable)
                            Timber.i( "Battery level periodic check stopped due to $newHelperStatus.")
                        }

                        notifyConnectionStatusChangedToListeners(newHelperStatus, addrForNotification)

                        if (newHelperStatus == ConnectionStatus.ERROR) {
                            val errorMessage = appContext.getString(R.string.message_unexpected_connection_state, statusStr, deviceAddress ?: "N/A")
                            notifyErrorOccurredToListeners(
                                "onConnectionStateChanged_NonOnline",
                                sdkConnectionStatus,
                                null,
                                errorMessage,
                                true
                            )
                        }
                    } else {
                        Timber.d( "internalConnectionEventHandler: SDK reported non-Online state ($statusStr), " +
                                "but SdkManager status ($currentConnectionStatus) already reflects a non-connected state. No change."
                        )
                    }
                }
            }
        }
    }

    private val internalBatteryCallback = object : AppBatteryLevelCallback {
        override fun onCallback(deviceAddress: String?, level: Int, chargingState: Int, resultCode: Int, resultCodeExtended: Int) {
            Timber.d( "internalBatteryCallback: addr=$deviceAddress, level=$level, chargingState=$chargingState, code=$resultCode, ext=$resultCodeExtended")
            mainHandler.post {
                if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                    lastBatteryLevel = level
                    listeners.forEach { it.onBatteryLevelChanged(level, chargingState,true) }
                } else {
                    Timber.w( "Failed to get battery level. Code: $resultCode, Ext: $resultCodeExtended")
                    lastBatteryLevel = -1
                    listeners.forEach { it.onBatteryLevelChanged(lastBatteryLevel, 0,false) }
                    notifyErrorOccurredToListeners(
                        "getBatteryLevel_callback",
                        resultCode,
                        resultCodeExtended,
                        appContext.getString(R.string.message_error_get_battery_callback),
                        false
                    )
                }
            }
        }
    }

    private val internalFirmwareCallback = object : AppFirmwareVersionCallback {
        override fun onCallback(deviceAddress: String?, fullFirmwareVersion: String?, resultCode: Int, resultCodeExtended: Int) {
            Timber.d( "internalFirmwareCallback: addr=$deviceAddress, version='$fullFirmwareVersion', code=$resultCode, ext=$resultCodeExtended")
            mainHandler.post {
                if (resultCode == ToshibaTecSdk.OPOS_SUCCESS && fullFirmwareVersion != null) {
                    lastRawFirmwareVersion = fullFirmwareVersion
                    var parsedVersion: String? = null
                    var powerType: String? = null
                    try {
                        // --- Firmware文字列パース ---
                        if (fullFirmwareVersion.length >= 10) {
                            val mainFwWithRevision = fullFirmwareVersion.substring(0, 7)
                            val subCPUFw = fullFirmwareVersion.substring(7, 10)
                            powerType = if (fullFirmwareVersion.length >= 11) fullFirmwareVersion.substring(10, 11) else null

                            val parts = fullFirmwareVersion.split("_", " ")
                            if (parts.isNotEmpty()) {
                                parsedVersion = parts[0]
                                if (parts.size > 2) powerType = parts.getOrNull(2)
                            } else {
                                parsedVersion = fullFirmwareVersion
                            }
                        } else {
                            Timber.w( "Firmware version string too short for parsing: '$fullFirmwareVersion'")
                            parsedVersion = fullFirmwareVersion
                        }
                    } catch (e: StringIndexOutOfBoundsException) {
                        Timber.e( "Error parsing firmware version string (StringIndexOutOfBounds): '$fullFirmwareVersion' for $deviceAddress", e)
                        parsedVersion = fullFirmwareVersion
                        notifyErrorOccurredToListeners("parseFirmwareVersion", -1, null, appContext.getString(R.string.message_error_parse_firmware), false)
                    } catch (e: Exception) {
                        Timber.e( "Generic error parsing firmware version string: '$fullFirmwareVersion' for $deviceAddress", e)
                        parsedVersion = fullFirmwareVersion
                        notifyErrorOccurredToListeners("parseFirmwareVersion_generic", -2, null, appContext.getString(R.string.message_error_parse_firmware_generic), false)
                    }

                    lastParsedFirmwareVersion = parsedVersion
                    val finalPowerType = powerType ?: parsePowerTypeFromFirmware(fullFirmwareVersion)

                    listeners.forEach {
                        it.onFirmwareVersionChanged(
                            fullFirmwareVersion,
                            lastParsedFirmwareVersion,
                            finalPowerType,
                            true,
                            shouldRecreateInitFileAfterFirmwareGet
                        )
                    }
                    shouldRecreateInitFileAfterFirmwareGet = false

                } else {
                    Timber.w( "Failed to get firmware version for $deviceAddress. Code: $resultCode, Ext: $resultCodeExtended")
                    listeners.forEach { it.onFirmwareVersionChanged(null, null, null, false, false) }
                    notifyErrorOccurredToListeners(
                        "getFirmwareVer_callback",
                        resultCode,
                        resultCodeExtended,
                        appContext.getString(R.string.message_processfailed_getFirmwareVer),
                        false
                    )
                }
            }
        }
    }

    // --- Bluetooth Device Discovery ---
    private val sdkBluetoothDiscoveryEventCallback = object : BluetoothDiscoveryEvent {
        // Bluetoothデバイスを発見した際にコールされる
        override fun onFindDevice(device: BluetoothDevice) {
            val deviceName = try { device.name } catch (e: SecurityException) { "N/A (No permission)" }
            val deviceAddress = device.address
            Timber.d( "sdkBluetoothDiscoveryEventCallback.onFindDevice: name=${deviceName}, addr=${deviceAddress}")

            mainHandler.post {
                if (!deviceAddress.isNullOrEmpty()) {
                    var shouldNotify = false
                    synchronized(discoveredDevicesForScan) {
                        if (discoveredDevicesForScan.add(deviceAddress)) {
                            shouldNotify = true
                        }
                    }
                    if (shouldNotify) {
                        synchronized(discoveredDevicesForScan) {
                            notifyDeviceDiscoveryUpdateToListeners(ArrayList(discoveredDevicesForScan), false)
                        }
                    }
                }
            }
        }

        // 検索が終わった時にコールされる
        override fun onDiscoveryFinished() {
            Timber.d( "sdkBluetoothDiscoveryEventCallback.onDiscoveryFinished")
            mainHandler.post {
                isScanningDevice.set(false)
                Timber.i( "Bluetooth discovery finished. Found ${discoveredDevicesForScan.size} devices.")
                synchronized(discoveredDevicesForScan) {
                    notifyDeviceDiscoveryUpdateToListeners(ArrayList(discoveredDevicesForScan), true) // スキャン完了として通知
                }
            }
        }
    }

    fun startDeviceScan() {
        if (!isCoreSdkInitialized) {
            Timber.w( "Cannot start device scan, SdkManager core not initialized.")
            mainHandler.post {
                notifyErrorOccurredToListeners("startDeviceScan_core_init", ToshibaTecSdk.OPOS_E_CLOSED, null, "SDK Core not initialized", false)
                notifyDeviceDiscoveryUpdateToListeners(emptyList(), true)
            }
            return
        }
        if (isScanningDevice.getAndSet(true)) {
            Timber.d( "Device scan already in progress.")
            return
        }
        Timber.i( "Starting Bluetooth device scan...")
        synchronized(discoveredDevicesForScan) {
            discoveredDevicesForScan.clear()
        }
        notifyDeviceDiscoveryUpdateToListeners(emptyList(), false)

        executeOnBackground {
            try {
                val discoveryInitResult = tecRfidAdapter.startBluetoothDiscovery(
                    appContext,
                    sdkBluetoothDiscoveryEventCallback
                )

                if (discoveryInitResult != ToshibaTecSdk.OPOS_SUCCESS) {
                    Timber.e( "Failed to initiate Bluetooth discovery. SDK init code: $discoveryInitResult")
                    isScanningDevice.set(false)
                    mainHandler.post {
                        notifyDeviceDiscoveryUpdateToListeners(emptyList(), true)
                        notifyErrorOccurredToListeners(
                            "startBluetoothDiscovery_init",
                            discoveryInitResult,
                            null,
                            appContext.getString(R.string.message_error_start_bluetooth_discovery_init),
                            false
                        )
                    }
                } else {
                    Timber.d( "startBluetoothDiscovery initiated successfully.")
                }
            } catch (e: Exception) {
                Timber.e( "Exception when calling startBluetoothDiscovery", e)
                isScanningDevice.set(false)
                mainHandler.post {
                    notifyDeviceDiscoveryUpdateToListeners(emptyList(), true)
                    notifyErrorOccurredToListeners(
                        "startBluetoothDiscovery_exception",
                        -1,
                        null,
                        "Exception: ${e.localizedMessage}",
                        false)
                }
            }
        }
    }

    fun stopDeviceScan() {
        if (!isCoreSdkInitialized) {
            Timber.w( "Cannot stop device scan, SdkManager core not initialized.")
            // エラー通知は任意
            return
        }
        if (!isScanningDevice.getAndSet(false)) {
            Timber.d( "Device scan is not running or already stopping.")
            return
        }
        Timber.i( "Stopping Bluetooth device scan...")
        executeOnBackground {
            try {
                val result = tecRfidAdapter.stopBluetoothDiscovery()
                if (result != ToshibaTecSdk.OPOS_SUCCESS ) { // SDKの成功コードに合わせる
                    Timber.w( "Error when calling SDK's stopBluetoothDiscovery. Code: $result")
                } else {
                    Timber.d( "SDK's stopBluetoothDiscovery called successfully.")
                }
            } catch (e: Exception) {
                Timber.e( "Exception when calling stopBluetoothDiscovery", e)
            } finally {
                mainHandler.post {
                    synchronized(discoveredDevicesForScan) {
                        notifyDeviceDiscoveryUpdateToListeners(ArrayList(discoveredDevicesForScan), true)
                    }
                }
            }
        }
    }

    fun getBluetoothDeviceList(outDeviceList: ArrayList<String>): Int {
        if (tecRfidAdapter == null) {
            Timber.e( "getBluetoothDeviceList: tecRfidAdapter is not initialized!")
            outDeviceList.clear()
            return ToshibaTecSdk.OPOS_E_CLOSED
        }
        return tecRfidAdapter!!.getBluetoothList(outDeviceList)
    }

    // --- Notification Helpers ---
    private fun notifyConnectionStatusChangedToListeners(status: ConnectionStatus, deviceAddress: String?) {
        Timber.d("[NotifyLOG] Attempting to notify listeners. Status: $status, Address: $deviceAddress. Listener count: ${listeners.size}")
        synchronized(listeners) {
            val listenersCopy = ArrayList(listeners)
            for (listener in listenersCopy) {
                try {
                    Timber.d( "[NotifyLOG] Notifying listener: ${listener.javaClass.simpleName}")
                    listener.onConnectionStatusChanged(status, deviceAddress)
                } catch (e: Exception) {
                    Timber.e( "Error notifying listener ${listener.javaClass.simpleName}", e)
                }
            }
        }
    }

    private fun notifyErrorOccurredToListeners(operation: String, errorCode: Int, extendedCode: Int?, message: String, isConnectionLost: Boolean) {
        synchronized(listeners) {
            listeners.forEach { it.onErrorOccurred(operation, errorCode, extendedCode, message, isConnectionLost) }
        }
    }

    private fun notifyTagDataToListeners(tagPacks: HashMap<String, TagPack>) {
        val dataCopy = HashMap(tagPacks)
        synchronized(listeners) {
            listeners.forEach { it.onTagDataReceived(dataCopy) }
        }
    }

    private fun notifyGenericResultToListeners(operationType: SdkOperationType?, resultCode: Int, resultCodeExtended: Int) {
        synchronized(listeners) {
            listeners.forEach { it.onGenericSdkResult(operationType, resultCode, resultCodeExtended) }
        }
    }

    private fun notifyDeviceDiscoveryUpdateToListeners(devices: List<String>, isComplete: Boolean) {
        val deviceListCopy = ArrayList(devices) // Defensive copy
        synchronized(listeners) {
            listeners.forEach { it.onDeviceDiscoveryUpdate(deviceListCopy, isComplete) }
        }
    }

    private fun notifyDriverStatusChangedToListeners(isOpened: Boolean, deviceName: String?) {
        synchronized(listeners) {
            listeners.forEach { it.onDriverStatusChanged(isOpened, deviceName) }
        }
    }

    // --- Internal State Updaters ---
    private fun updateConnectionStatusInternal(newStatus: ConnectionStatus, deviceAddress: String?) {
        val oldStatus = currentConnectionStatus
        Timber.d( "updateConnectionStatusInternal: oldStatus=$oldStatus, newStatus=$newStatus, newAddr=$deviceAddress, currentConnectedAddr=${this.connectedDeviceAddressInternal}")
        currentConnectionStatus = newStatus

        if (oldStatus != newStatus || (newStatus == ConnectionStatus.CONNECTING && this.connectedDeviceAddressInternal != deviceAddress) ) {
            if (newStatus == ConnectionStatus.CONNECTING) {
                this.connectedDeviceAddressInternal = deviceAddress
            }
            mainHandler.post {
                notifyConnectionStatusChangedToListeners(newStatus, this.connectedDeviceAddressInternal ?: deviceAddress)
            }
        }
        if (newStatus == ConnectionStatus.DISCONNECTED ||
            newStatus == ConnectionStatus.ERROR ||
            (newStatus == ConnectionStatus.DISCONNECTING && oldStatus == ConnectionStatus.CONNECTED)
        ) {
            mainHandler.removeCallbacks(periodicBatteryCheckRunnable)
            if (newStatus == ConnectionStatus.DISCONNECTED || newStatus == ConnectionStatus.ERROR){
                this.connectedDeviceAddressInternal = null
                this.lastBatteryLevel = -1
            }
        }
    }

    private fun handleConnectionErrorInternal(operation: String, errorCode: Int, extendedCode: Int?, deviceNameOrAddress: String?, defaultMessage: String) {
        val fullMessage = "$defaultMessage (Device: ${deviceNameOrAddress ?: "N/A"}, Op: $operation, Code: $errorCode${extendedCode?.let { ", Ext: $it" } ?: ""})"
        Timber.e( fullMessage)

        val addrToReport = if (operation == "open" || operation == "claimDevice" || operation == "setDeviceEnabled") {
            this.connectedDeviceAddressInternal
        } else {
            deviceNameOrAddress
        }

        updateConnectionStatusInternal(ConnectionStatus.ERROR, addrToReport)
        mainHandler.post {
            notifyErrorOccurredToListeners(operation, errorCode, extendedCode, defaultMessage, true)
        }
    }


    // --- Utility Functions ---
    private fun sdkStatusToString(sdkStatus: Int): String {
        return when (sdkStatus) {
            ToshibaTecSdk.ConnectStateOnline -> "Online"
            ToshibaTecSdk.ConnectStateOffline -> "Offline"
            else -> "Unknown ($sdkStatus)"
        }
    }

    private fun parsePowerTypeFromFirmware(fullFirmwareVersion: String?): String? {
        if (fullFirmwareVersion == null) return null
        val parts = fullFirmwareVersion.split('_', ' ')
        if (parts.size > 1) {
            val lastPart = parts.last()
            if (lastPart.length == 1 && lastPart.first().isLetterOrDigit()) {
                return lastPart
            }
        }
        if (fullFirmwareVersion.length == 11) {
            return fullFirmwareVersion.substring(10, 11)
        }
        if (fullFirmwareVersion.length >= 10 && fullFirmwareVersion.contains("Ver.")) {
            val pIndex = fullFirmwareVersion.lastIndexOf('_')
            if (pIndex != -1 && pIndex < fullFirmwareVersion.length - 1) {
                return fullFirmwareVersion.substring(pIndex + 1)
            }
        }
        return null
    }
}