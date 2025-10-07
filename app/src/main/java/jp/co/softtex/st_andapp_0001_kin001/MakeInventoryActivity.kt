package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Locale

// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.softtex.st_andapp_0001_kin001.ProductListActivity.ProductListAdapterV4
import jp.co.toshibatec.model.TagPack
import timber.log.Timber
import kotlin.text.lowercase
import kotlin.text.toIntOrNull


class MakeInventoryActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {

    private lateinit var dbHelper: DatabaseHelper

    // UI要素
    private lateinit var textViewBookInventoryCount: TextView
    private lateinit var textViewPhysicalInventoryCount: TextView
    private lateinit var buttonStartRead: Button
    private lateinit var buttonStopRead: Button
    private lateinit var buttonFinishInventory: Button
    private lateinit var progressBar: ProgressBar
//    private lateinit var emptyView: TextView
    // 読取リスト
    private lateinit var listViewReadInventory: ListView
    private lateinit var inventoryListAdapter: ProductListAdapterV4

    private val currentUiDisplayList: MutableList<ProductListActivity.ProductDisplayItem> = mutableListOf()

    private lateinit var navigationHelper: NavigationHelper

    private val mFilterID: String = "00000000" // EPCフィルターID (現状使用しない場合は空やnullも検討)
    private val mFiltermask:String = "00000000" // EPCフィルターマスク
    private val mStartReadTagsTimeout:Int = ToshibaTecSdk.OPOS_FOREVER // 連続読み取り

    // 棚卸処理関連
    private var targetLocation: String? = null
    private var targetList: MutableList<MutableMap<String, Any>> = mutableListOf()
    private var masterEpcList: MutableList<MutableMap<String, Any>> = mutableListOf()
    private var isReading: Boolean = false
    private var isAllLocationMode: Boolean = false

    // 在庫カウント用
    private var bookInventoryTotalCount: Int = 0
    private var physicalInventoryScannedCount: Int = 0 // セッション内で実在庫として新規にカウントされたユニークEPC数0


    private val processedEpcsForThisSession: MutableSet<String> = mutableSetOf()
    private var targetProductEpcFilter: String? = null
    private var intentLocation: String? = null

    companion object {
        private const val TAG = "MakeInventoryActivity"
        const val EXTRA_KEY_LOCATION = "jp.co.softtex.st_andapp_0001.EXTRA_LOCATION_VALUE"
        const val EXTRA_KEY_PRODUCT_EPC = "jp.co.softtex.st_andapp_0001.EXTRA_PRODUCT_EPC_VALUE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i( "onCreate.")
        // Intentから渡された値を取得
        intentLocation = intent.getStringExtra(EXTRA_KEY_LOCATION)
        targetProductEpcFilter = intent.getStringExtra(EXTRA_KEY_PRODUCT_EPC)
        Timber.d( "Intent: Location=$intentLocation, ProductEpc=$targetProductEpcFilter")

        dbHelper = DatabaseHelper(this)

        // isAllLocationMode は DatabaseContract の文字列と比較
        isAllLocationMode = intentLocation == null || intentLocation == DatabaseContract.get_location_all(applicationContext)
        targetLocation = if (isAllLocationMode) null else intentLocation

        // Viewのセット
        setView()

        // 棚卸対象リストのロード
        loadInitialInventoryData()
    }

    override fun onResume() {
        super.onResume()
        try {
            // SdkManager のコアが初期化されているか再確認
            if (!SdkManager.isSdkCoreInitialized()) {
                Timber.e( "SdkManager core is not initialized in onResume. This should not happen if Application class setup is correct.")
                Toast.makeText(this, getString(R.string.message_error_sdk_open_error), Toast.LENGTH_SHORT).show()
                return
            }

            // ドライバがまだ開かれていなければ開く試み
            if (!SdkManager.isDriverOpened()) {
                val deviceName = SdkManager.getDeviceName()
                Timber.d( "Driver is not open. Attempting to open driver for ${deviceName}")
                SdkManager.openDriver(deviceName)
            } else {
                Timber.d( "Driver is already open for ${SdkManager.getOpenedDeviceName()}. Not callingopenDriver again.")
                // ドライバが既に開いている場合、現在の状態をUIに反映させるために通知を模倣
                onDriverStatusChanged(true, SdkManager.getOpenedDeviceName())
            }

            SdkManager.addListener(this) // リスナー追加
            SdkManager.startMonitoring() // モニタリング開始 (接続状態やバッテリーレベルの取得など)

            // UIの初期状態を SdkManager から取得して設定
            val currentStatus = SdkManager.getCurrentConnectionStatus()
            val currentDeviceAddress = SdkManager.getConnectedDeviceAddress()
            onConnectionStatusChanged(currentStatus, currentDeviceAddress) // UI更新

            if (SdkManager.isConnected()) {
                SdkManager.fetchBatteryLevel()
            } else {
                if (::navigationHelper.isInitialized) { // navigationHelperが初期化済みか確認
                    navigationHelper.updateBatteryLevel(-1,0 ) // 未接続時はバッテリー不明
                }
            }
        } catch (e: Exception) {
            Timber.e( "onResume: Exception", e)
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        SdkManager.stopMonitoring() // 監視を停止
        if (SdkManager.isScanningDevice()) {
            SdkManager.stopDeviceScan()
        }
        SdkManager.removeListener(this)

        // 読み取り中であれば停止する
        if (isReading) {
            stopRfidReading(isInternalStop = true)
        }
    }

    // バックキーが押されたときの処理 (ドロワーが開いていれば閉じる)
    override fun onBackPressed() {
        if (::navigationHelper.isInitialized && navigationHelper.isDrawerOpen()) {
            navigationHelper.closeDrawer()
        } else {
            if (isReading) {
                stopRfidReading()
                Toast.makeText(this, getString(R.string.make_inventory_activity_on_back_pressed), Toast.LENGTH_SHORT).show()
            }
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::navigationHelper.isInitialized) {
            navigationHelper.onDestroy()
        }
        if (::dbHelper.isInitialized) { // _dbHelperが初期化済みか確認
            dbHelper.close()
        }
        Timber.i( "onDestroy: Finished")
        // アプリ終了時に SdkManager のシャットダウン処理を行う場合はここで
        // SdkManager.shutdown()
    }

    private fun setView() {
        setContentView(R.layout.activity_make_inventory)

        // Viewの取得
        textViewBookInventoryCount = findViewById(R.id.layout_activity_make_inventory_book_inventory_num)
        textViewPhysicalInventoryCount = findViewById(R.id.layout_activity_make_inventory_physical_inventory_num)
        buttonStartRead = findViewById(R.id.button_activity_make_inventory_start_read)
        buttonStopRead = findViewById(R.id.button_activity_make_inventory_stop_read)
        buttonFinishInventory = findViewById(R.id.button_activity_make_inventory_finish)
        progressBar = findViewById(R.id.progressBarInventory)

        buttonStopRead.isEnabled = false
        buttonFinishInventory.isEnabled = false

        buttonStartRead.setOnClickListener { startRfidReading() }
        buttonStopRead.setOnClickListener { stopRfidReading() }
        buttonFinishInventory.setOnClickListener { finishInventoryAndProceed() }

        // NavigationHelperのセットアップ
        setNavigationMenu()

        listViewReadInventory = findViewById(R.id.list_view_read_inventory)

        setupInventoryListView()
        updateInventoryCountsUI()
    }
    /* setView END */

    private fun setupInventoryListView() {
        // ProductListAdapterV4 を使用する
        // 初期データは空リストでアダプターを作成し、データロード後に更新する
        inventoryListAdapter = ProductListAdapterV4(
            this,
            currentUiDisplayList // 初期は空の currentUiDisplayList を渡す
        )
        listViewReadInventory.adapter = inventoryListAdapter
    }

    // --- SDKManager.Listener の実装 ---
    override fun onDriverStatusChanged(isOpened: Boolean, deviceName: String?) {
         if (isOpened) {
             updateConnectionStatusUI()
        } else {
             if (::navigationHelper.isInitialized) {
                 navigationHelper.updateConnectionStatus(false)
                 navigationHelper.updateBatteryLevel(-1, 0)
             }
             // ドライバが閉じたら読み取り関連のボタンも無効化
             buttonStartRead.isEnabled = false
             buttonStopRead.isEnabled = false
             if (isReading) { // もし読み取り中だったら状態をリセット
                 isReading = false
                 updateUiForStopReading() // UIも読み取り停止状態に
             }
        }
    }

    override fun onConnectionStatusChanged(status: SdkManager.ConnectionStatus, deviceAddress: String?) {
        runOnUiThread {
            updateConnectionStatusUI() // 接続状態が変わったのでUIを更新

            if (status == SdkManager.ConnectionStatus.CONNECTED) {
                SdkManager.fetchBatteryLevel() // 接続されたらバッテリーレベルを取得
            }

            // 読み取り中に接続が切れた、またはエラーが発生した場合
            if (isReading && (status == SdkManager.ConnectionStatus.DISCONNECTED || status == SdkManager.ConnectionStatus.ERROR)) {
                Timber.w( "Connection lost or error ($status) while reading. Stopping RFID reading.")
                Toast.makeText(this, getString(R.string.rfid_reader_disconnected), Toast.LENGTH_LONG).show()
                // SdkManager.performStopReadTags() // performStopReadTags はエラーや切断時にSDK内部で処理されるか、別途呼び出しが必要か確認
                // SdkManager.disconnect() // 同上

                isReading = false
                updateUiForStopReading() // UIを読み取り停止状態にする
                // finish() // 即座に画面を終了させるか、ユーザーに通知して操作を促すか検討
            }
        }
    }

    override fun onBatteryLevelChanged(level: Int, state: Int, isSuccess: Boolean) {
        runOnUiThread {
            if (::navigationHelper.isInitialized) {
                if (isSuccess) {
                    navigationHelper.updateBatteryLevel(level, state)
                } else {
                    navigationHelper.updateBatteryLevel(-1, 0)
                }
            }
        }
    }

    override fun onFirmwareVersionChanged(
        fullVersion: String?,
        parsedVersion: String?,
        powerType: String?,
        isSuccess: Boolean,
        needsInitFileRecreation: Boolean
    ) {
    }

    override fun onErrorOccurred(
        operation: String,
        errorCode: Int,
        extendedCode: Int?,
        message: String,
        isConnectionLostError: Boolean
    ) {
        runOnUiThread {
            Timber.e( "onErrorOccurred: Op='$operation', Code=$errorCode, ExtCode=$extendedCode, Msg='$message', ConnectionLost=$isConnectionLostError")
            if (isConnectionLostError) {
                if (::navigationHelper.isInitialized) {
                    navigationHelper.updateConnectionStatus(false)
                    navigationHelper.updateBatteryLevel(-1,0 )
                }
                if (isReading) {
                    isReading = false
                    updateUiForStopReading()
                }
                // 接続関連エラーであれば、開始ボタンも無効化を検討
                buttonStartRead.isEnabled = false
                buttonStopRead.isEnabled = false
            }
            // 読み取り開始/停止操作でのエラーの場合、UIを適切にリセット
            if (operation == SdkManager.SdkOperationType.START_READ_TAGS.name ||
                operation == SdkManager.SdkOperationType.STOP_READ_TAGS.name) {
                if (isReading && operation == SdkManager.SdkOperationType.START_READ_TAGS.name)
                { // 開始試行中のエラーなど
                    isReading = false // 読み取り状態をリセット
                }
                updateUiForStopReading() // UIを読み取り停止状態に戻す
            }
        }
    }

    override fun onTagDataReceived(tagPacks: Map<String, TagPack>) {
        runOnUiThread {
            if (!isReading) {
                Timber.i( "onDataReceived called but not in reading state. Ignoring.")
            } else {
                val newEpcsFound = mutableListOf<String>()
                tagPacks.forEach { (epc, tagPack) ->
                    Timber.v( "Tag received: ID=${tagPack.tagID}, EPC=$epc")
                    epc?.let {
                        val lowerEpc = it.lowercase(Locale.getDefault())
                        if (!processedEpcsForThisSession.contains(lowerEpc)) {
                            newEpcsFound.add(lowerEpc)
                        }
                    }
                }
                if (newEpcsFound.isNotEmpty()) {
                    // UIが固まらないようにAsyncTaskや別スレッドでの処理が望ましい
                    ProcessFoundEpcsTask(this).execute(newEpcsFound)
                }
            }
        }
    }

    override fun onGenericSdkResult(operationType: SdkManager.SdkOperationType?, resultCode: Int, resultCodeExtended: Int) {
        runOnUiThread {
            Timber.d( "onGenericSdkResult: OpType=$operationType, Result=$resultCode, ExtResult=$resultCodeExtended")
            val opName = operationType?.name ?: getString(R.string.message_error_unknown_operation)
            val success = (resultCode == ToshibaTecSdk.OPOS_SUCCESS)

            when(operationType) {
                SdkManager.SdkOperationType.OPEN_DRIVER -> {
                    if (success) {
                        Timber.i( "Driver opened successfully via onGenericSdkResult.")
                        // openDriver成功時の追加処理 (onDriverStatusChangedでも通知されるはず)
                    } else {
                        Timber.e( "Failed to open driver via onGenericSdkResult. Code: $resultCode")
                        MainActivity.showErrorDialog(this, getString(R.string.message_error_sdk_open_error), resultCode)
                    }
                }
                SdkManager.SdkOperationType.START_READ_TAGS -> {
                    if (success) {
                        Timber.i( "START_READ_TAGS command successful. Waiting for data/error events.")
                    } else {
                        Timber.e( "START_READ_TAGS command failed: $resultCode, Ext: $resultCodeExtended")
                        Toast.makeText(this, getString(R.string.message_error_start_read_tags_async,resultCode,resultCodeExtended), Toast.LENGTH_SHORT).show()
                        isReading = false
                        updateUiForStopReading()
                    }
                }
                SdkManager.SdkOperationType.STOP_READ_TAGS -> {
                    if (success) {
                        Timber.i("STOP_READ_TAGS command successful (callback).")
                    } else {
                        Timber.e("STOP_READ_TAGS command failed (callback): $resultCode, Ext: $resultCodeExtended")
                        Toast.makeText(this, getString(R.string.message_error_stop_read_tags_async, resultCode, resultCodeExtended), Toast.LENGTH_SHORT).show()
                    }
                    if (!isReading) {
                        Timber.d("STOP_READ_TAGS callback: isReading is already false. UI should be updated.")
                    } else {
                        Timber.w("STOP_READ_TAGS callback: isReading was still true. Forcing UI update.")
                        isReading = false
                        updateUiForStopReading()
                    }
                }
                else -> {
                    Timber.d( "Generic SDK result for $opName: ${if (success) "Success" else "Failed (Code:$resultCode)"}")
                }
            }
        }
    }

    override fun onDeviceDiscoveryUpdate(devices: List<String>, isScanComplete: Boolean) {
        // このActivityでは使用しない
    }
    // --- ここまで SDKManager.Listener の実装 ---

    private fun loadInitialInventoryData() {
        MainActivity.inventory_progress_list.clear()
        currentUiDisplayList.clear()
        targetList.clear()
        masterEpcList.clear()
        processedEpcsForThisSession.clear()
        bookInventoryTotalCount = 0
        physicalInventoryScannedCount = 0

        updateInventoryCountsUI()

        LoadInitialDataTask(this).execute()
        LoadMasterEpcListTask(this).execute()
    }

    private class LoadMasterEpcListTask(activity: MakeInventoryActivity) : AsyncTask<Void, Void, MutableList<MutableMap<String, Any>>>() {
        private val activityReference: WeakReference<MakeInventoryActivity> = WeakReference(activity)
        override fun doInBackground(vararg params: Void?): MutableList<MutableMap<String, Any>> {
            val activity = activityReference.get()
            return if (activity == null || activity.isFinishing) {
                mutableListOf()
            } else {
                activity.dbHelper.getMasterEpcList()
            }
        }
        override fun onPostExecute(result: MutableList<MutableMap<String, Any>>) {
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) {
                return
            }
            activity.masterEpcList.clear()
            activity.masterEpcList.addAll(result)
        }
    }

    private class LoadInitialDataTask(activity: MakeInventoryActivity) :
        AsyncTask<Void, Void, MutableList<Map<String, Any?>>>() { // 戻り値の型を変更
        private val activityReference: WeakReference<MakeInventoryActivity> = WeakReference(activity)

        override fun onPreExecute() {
            super.onPreExecute()
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return
            activity.progressBar.visibility = View.VISIBLE
        }

        override fun doInBackground(vararg params: Void?): MutableList<Map<String, Any?>>? {
            val activity = activityReference.get()
            return if (activity == null || activity.isFinishing) {
                null
            } else {
                // getTargetList を呼び出す
                // 今回の棚卸初期データロードでは、重複排除は不要と想定 (distinctColumnName = null)
                // もし特定のカラムで初期データをユニークにしたい場合は、適切なMasterColumnを指定する。
                activity.dbHelper.getTargetList(
                    activity.targetLocation,
                    activity.targetProductEpcFilter,
                    null // distinctColumnName: MasterColumn? - 初期ロードでは通常null
                )
            }
        }

        override fun onPostExecute(result: MutableList<Map<String, Any?>>?) { // 引数の型を変更
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return

            activity.progressBar.visibility = View.GONE

            if (result != null) {
                result.forEach { dbRowMap ->
                    val mapItem = mutableMapOf<String, Any>()
                    val context = activity.applicationContext

                    val barcodeNoKey = DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(context)
                    val locationKey = DatabaseContract.MasterColumn.LOCATION.getColumnName(context)
                    val stockDateKey = DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(context)
                    val nameKey = DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(context)
                    val bookInvKey = DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(context)
                    val physicalInvKey = DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(context)
                    val epcKey = DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)
                    val scanResultKey = DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(context)

                    val pCode = dbRowMap[barcodeNoKey]?.toString() ?: ""
                    val loc = dbRowMap[locationKey] as? String ?: context.getString(R.string.layout_row_location_default)
                    val pDate = dbRowMap[stockDateKey] as? String ?: ""
                    val pName = dbRowMap[nameKey] as? String ?: ""
                    val bookInv = (dbRowMap[bookInvKey] as? Long)?.toInt() ?: 0
                    val productEpc = (dbRowMap[epcKey] as? String)?.lowercase(Locale.getDefault()) ?: ""

                    mapItem[barcodeNoKey] = pCode
                    mapItem[locationKey] = loc
                    mapItem[stockDateKey] = pDate
                    mapItem[nameKey] = pName
                    mapItem[bookInvKey] = bookInv.toString()
                    mapItem[physicalInvKey] = "0" // 初期実棚は0
                    mapItem[epcKey] = productEpc
                    // SCAN_RESULTは初期は未スキャン
                    val scanResultInitial = activity.getScanResultStringFromCount(context, mapItem[bookInvKey].toString(), mapItem[physicalInvKey].toString())
                    mapItem[scanResultKey] = scanResultInitial

                    // MainActivity.inventory_progress_list に Map を追加
                    MainActivity.inventory_progress_list.add(mapItem)

                    // targetListに追加
                    activity.targetList.add(mapItem) // targetListがMap<String, Any>を期待する場合

                    activity.bookInventoryTotalCount += bookInv
                }
                activity.syncCurrentUiDisplayListFromInventoryProgressList()
                activity.updateInventoryCountsUI()

                activity.buttonFinishInventory.isEnabled = true
            } else {
                Toast.makeText(activity, activity.getString(R.string.make_inventory_activity_initialize_inventory_failure), Toast.LENGTH_LONG).show()
                activity.buttonStartRead.isEnabled = false // 初期化失敗時は読み取り開始不可など
                activity.syncCurrentUiDisplayListFromInventoryProgressList() // ★空でもUIリストを同期
                activity.updateInventoryCountsUI() // UI更新
            }
        }
    }

    // AsyncTask for processing found EPCs
    private class ProcessFoundEpcsTask(activity: MakeInventoryActivity) : AsyncTask<List<String>, Void, Boolean>() {
        private val activityReference: WeakReference<MakeInventoryActivity> = WeakReference(activity)

        override fun doInBackground(vararg params: List<String>?): Boolean {
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing || params.isEmpty() || params[0] == null) {
                return false
            }
            val scannedEpcsBatch = params[0]!! // RFIDリーダーから受け取ったEPC文字列リスト
            var anyUpdateToCurrentInventoryListOccurred = false
            val context = activity.applicationContext

            // --- キー名の事前取得 ---
            // targetList および currentInventoryList で使用する MasterColumn のキー
            val productKeyBarcode =
                DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(context)
            val productKeyLocation = DatabaseContract.MasterColumn.LOCATION.getColumnName(context)
            val productKeyStockDate = DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(context)
            val productKeyName =
                DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(context)
            val productKeyBookInv =
                DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(context)
            val productKeyPhysicalInv =
                DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(context)
            val productKeyEpc = DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)
            val productKeyScanResult = DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(context)

            Timber.d("[ProcessEpcsTask] Starting processing for ${scannedEpcsBatch.size} EPCs.")

            for (scannedEpcOriginal in scannedEpcsBatch) {
                val scannedEpcLower = scannedEpcOriginal.lowercase(Locale.getDefault())

                // 1. epcがprocessedEpcsForThisSessionと重複したら無視
                if (activity.processedEpcsForThisSession.contains(scannedEpcLower)) {
                    Timber.v( "EPC $scannedEpcLower already processed. Skipping.")
                    continue // 次のEPCへ
                }
                // このEPCを処理対象とするので、セッションに追加
                activity.processedEpcsForThisSession.add(scannedEpcLower)
                Timber.v( "EPC $scannedEpcLower added to processedEpcsForThisSession.")

                var matchedEpcForCurrentInventory: String? = null // currentInventoryListで照合・使用するEPC
                var baseItemInfoForCurrentInventory: Map<String, Any?>? = null // currentInventoryList追加時の元情報

                // 2. epcがtargetListのproduct_epcと前方一致するか
                val targetListItemMatched = activity.targetList.find { targetItem ->
                    val targetEpc = targetItem[productKeyEpc] as? String
                    targetEpc != null && targetEpc.isNotEmpty() &&
                            scannedEpcLower.startsWith(targetEpc.lowercase(Locale.getDefault()))
                }

                if (targetListItemMatched != null) {
                    // --- 2a. targetListのアイテムに前方一致した場合 ---
                    matchedEpcForCurrentInventory =
                        targetListItemMatched[productKeyEpc] as String // targetListのEPCを正とする
                    baseItemInfoForCurrentInventory =
                        targetListItemMatched // targetListの情報をベースにする
                    baseItemInfoForCurrentInventory.forEach { (key, value) -> // 分割代入
                        Timber.v( "  $key = $value (Type: ${value?.javaClass?.simpleName ?: "null"})")
                    }
                    activity.physicalInventoryScannedCount++ // 棚卸対象が見つかったのでカウント (UI表示用)
                } else {
                    // --- 2b. targetListのアイテムに前方一致しなかった場合 ---
                    Timber.v( "EPC $scannedEpcLower NO forward match in targetList.")
                } // end of targetList or masterEpcList matching logic

                // 3. currentInventoryList (MainActivity.inventory_progress_list) の更新処理
                if (matchedEpcForCurrentInventory != null && baseItemInfoForCurrentInventory != null) {
                    val existingCurrentInventoryItemMap =
                        MainActivity.inventory_progress_list.find { currentItemMap ->
                            (currentItemMap[productKeyEpc] as? String)?.lowercase(Locale.getDefault()) == matchedEpcForCurrentInventory.lowercase(
                                Locale.getDefault()
                            )
                        }

                    if (existingCurrentInventoryItemMap != null) {
                        // --- 3a. currentInventoryList に matched_epc と一致するアイテムが存在する場合 ---
                        var physicalInventory = (existingCurrentInventoryItemMap[productKeyPhysicalInv] as? String)?.toIntOrNull() ?: 0
                        physicalInventory++
                        existingCurrentInventoryItemMap[productKeyPhysicalInv] = physicalInventory.toString()

                        val bookInvStr = existingCurrentInventoryItemMap[productKeyBookInv] as? String
                        existingCurrentInventoryItemMap[productKeyScanResult] = activity.getScanResultStringFromCount(context, bookInvStr, physicalInventory.toString())

                        Timber.v( "Updated PHYSICAL_INVENTORY for EPC $matchedEpcForCurrentInventory in MainActivity.inventory_progress_list to $physicalInventory.")

                        anyUpdateToCurrentInventoryListOccurred = true
                    } else {
                        // --- 3b. currentInventoryList に matched_epc と一致するアイテムが存在しない場合 ---
                        // 新規アイテムとして MainActivity.inventory_progress_list に追加
                        val newItemMap = mutableMapOf<String, Any>()
                        newItemMap[productKeyBarcode] = baseItemInfoForCurrentInventory[productKeyBarcode] ?: ""
                        newItemMap[productKeyLocation] = baseItemInfoForCurrentInventory[productKeyLocation] as? String ?: ""
                        newItemMap[productKeyStockDate] = baseItemInfoForCurrentInventory[productKeyStockDate] as? String ?: ""
                        newItemMap[productKeyName] = baseItemInfoForCurrentInventory[productKeyName] as? String ?: ""
                        newItemMap[productKeyBookInv] = baseItemInfoForCurrentInventory[productKeyBookInv] as? String ?: "0" // targetListから取得
                        newItemMap[productKeyPhysicalInv] = "1" // 実在庫を1
                        newItemMap[productKeyEpc] = matchedEpcForCurrentInventory.uppercase(Locale.getDefault()) // baseItemInfoから取得したEPC

                        val scanResultStr = activity.getScanResultStringFromCount(context, newItemMap[productKeyBookInv] as? String, "1")
                        newItemMap[productKeyScanResult] = scanResultStr

                        MainActivity.inventory_progress_list.add(newItemMap)
                        Timber.d( "Successfully ADDED new item to MainActivity.inventory_progress_list (EPC: $matchedEpcForCurrentInventory, Physical: 1).")
                        anyUpdateToCurrentInventoryListOccurred = true
                    }
                } else if (targetListItemMatched == null) {
                    // targetListにも前方一致しない場合、このEPCは処理されない（リストに追加もされない）。
                    Timber.d("EPC $scannedEpcLower is unregistered tag.")
                }

            } // end of for loop over scannedEpcsBatch

            return anyUpdateToCurrentInventoryListOccurred
        }

        override fun onPostExecute(needsUiUpdate: Boolean?) {
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return

            if (needsUiUpdate == true) {
                activity.syncCurrentUiDisplayListFromInventoryProgressList()
                activity.updateInventoryCountsUI()
            }
        }
    }

    /**
     * MainActivity.inventory_progress_list (Mapのリスト) から
     * currentUiDisplayList (ProductDisplayItemのリスト) を同期（再作成）する。
     */
    private fun syncCurrentUiDisplayListFromInventoryProgressList() {
        currentUiDisplayList.clear()
        val context = applicationContext
        val barcodeNoKey = DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(context)
        val locationKey = DatabaseContract.MasterColumn.LOCATION.getColumnName(context)
        val stockDateKey = DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(context)
        val nameKey = DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(context)
        val bookInvKey = DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(context)
        val physicalInvKey = DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(context)
        val epcKey = DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)
        val scanResultKey = DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(context)

        MainActivity.inventory_progress_list.forEach { mapItem ->
            val epc_upper = (mapItem[epcKey] as? String ?: "").uppercase(Locale.getDefault())
            val displayItem = ProductListActivity.ProductDisplayItem(
                barcode_no = mapItem[barcodeNoKey] as? String,
                location = mapItem[locationKey] as? String,
                stock_date = mapItem[stockDateKey] as? String,
                product_name = mapItem[nameKey] as? String,
                book_inventory = mapItem[bookInvKey] as? String,
                physical_inventory = mapItem[physicalInvKey] as? String,
                product_epc = epc_upper,
                scan_result = mapItem[scanResultKey] as? String
            )
            currentUiDisplayList.add(displayItem)
        }
    }

    private fun getScanResultStringFromCount(context: Context, bookInventoryString: String?, physicalInventoryString: String?): String {
        val scanResult: Int

        if (bookInventoryString.isNullOrBlank() || bookInventoryString == "0") {
            scanResult = DatabaseContract.ScanResult.UNCHECKED_NUM
        } else {
            val bookInventory = bookInventoryString.toIntOrNull()
            val physicalInventory = physicalInventoryString?.toIntOrNull() ?: 0

            if (bookInventory == null) {
                scanResult = DatabaseContract.ScanResult.UNCHECKED_NUM
            } else if (bookInventory == physicalInventory) {
                scanResult = DatabaseContract.ScanResult.MATCH_NUM
            } else if (bookInventory > physicalInventory) {
                scanResult = DatabaseContract.ScanResult.UNDER_NUM
            } else {
                scanResult = DatabaseContract.ScanResult.OVER_NUM
            }
        }
        return DatabaseContract.getScanResultString(context, scanResult)
    }

    private fun updateInventoryCountsUI() {
        textViewBookInventoryCount.text = bookInventoryTotalCount.toString()
        textViewPhysicalInventoryCount.text = physicalInventoryScannedCount.toString()


        if (::inventoryListAdapter.isInitialized) { // adapterが初期化済みか確認
            // currentUiDisplayList をアダプターに渡す
            val listToPass = ArrayList(currentUiDisplayList)
            inventoryListAdapter.updateData(listToPass)
            Timber.v( "updateInventoryCountsUI: Called updateData() on inventoryListAdapter with listToPass.size = ${listToPass.size}.")
        } else {
            Timber.w( "updateInventoryCountsUI: inventoryListAdapter is not initialized yet.")
        }
    }

    private fun startRfidReading() {
        if (!SdkManager.isConnected()) {
            Toast.makeText(this, getString(R.string.rfid_reader_noto_connected), Toast.LENGTH_SHORT).show()
            Timber.w( "startRfidReading attempted but reader is not connected.")
            return
        }

        if (isReading) {
            Timber.d( "Already in reading state. Ignoring startRfidReading request.")
            return
        }
        navigationHelper.setupToolbarHint( hint = getString(R.string.activity_make_inventory_hint_reading))

        Timber.i( "Attempting to start RFID reading...")
        isReading = true
        updateUiForStartReading()

        SdkManager.performStartReadTags(
            filterID = mFilterID,
            filterMask = mFiltermask,
            timeout = mStartReadTagsTimeout
        )
    }

    private fun updateUiForStartReading() {
        buttonStartRead.isEnabled = false
        buttonStopRead.isEnabled = true
        buttonFinishInventory.isEnabled = false // 読み取り中は完了不可
    }

    private fun stopRfidReading(isInternalStop: Boolean = false) {
        if (!isReading && !isInternalStop) { // isInternalStop が true の場合は isReading が false でも処理を進めるように変更
            Timber.d("Not in reading state and not an internal stop. Ignoring stopRfidReading request.")
            if (!SdkManager.isConnected() && buttonStopRead.isEnabled) {
                updateUiForStopReading()
            }
            return
        }

        if (isReading || isInternalStop) {
            navigationHelper.setupToolbarHint(hint = getString(R.string.activity_make_inventory_hint_finish))
            Timber.i("Attempting to stop RFID reading (isInternalStop: $isInternalStop)...")

            isReading = false
            updateUiForStopReading()

            SdkManager.performStopReadTags()
        }
    }

    private fun updateUiForStopReading() {
        runOnUiThread {
            isReading = false // 確実に読み取り状態をfalseにする
            buttonStartRead.isEnabled = SdkManager.isConnected() // 接続中のみ開始ボタン有効
            buttonStopRead.isEnabled = false
            buttonFinishInventory.isEnabled = true // 読み取り停止後は完了可能

        }
    }

    private fun updateConnectionStatusUI() {
        runOnUiThread {
            val connected = SdkManager.isConnected()
            if (::navigationHelper.isInitialized) {
                navigationHelper.updateConnectionStatus(connected)
            }

            if (isReading && !connected) {

            } else if (!isReading) { // 読み取り中でない場合
                buttonStartRead.isEnabled = connected
                buttonStopRead.isEnabled = false // 読み取り中でなければ停止ボタンは常に無効
            }
        }
    }

    private fun finishInventoryAndProceed() {
        if (isReading) {
            stopRfidReading()
            // UI/UXによっては、読み取り停止後に再度ボタンを押してもらうなどの工夫も可能
        }
        val intent = Intent(this, ResultInventoryActivity::class.java) // InventoryResultActivity は結果表示画面の仮名
        startActivity(intent)
    }


    private fun setNavigationMenu() {
        try {
            navigationHelper = NavigationHelper(
                activity = this,
                drawerContainerId = R.id.drawer_layout,
                navDrawerLayoutId = R.id.nav_drawer_root,
                navListViewId = R.id.nav_listview,
                navHeaderLayoutId = R.layout.nav_header,
                navHeaderCloseButtonId = R.id.header_close_button,
                toolbarTitleViewId = R.id.toolbar_title,
                toolbarMenuButtonId = R.id.toolbar_menu_button,
                toolbarBackButtonId = R.id.toolbar_back_button,
                toolbarBatteryIconId = R.id.header_battery_icon,
                toolbarBatteryTextId = R.id.header_battery_text,
                toolbarHintViewId = R.id.toolbar_hint,
                toolbarConnectionIconId = R.id.header_app_icon
            )

            navigationHelper.setupToolbarAndDrawer(
                screenTitle = getString(R.string.activity_make_inventory_title),
                showBackButton = true,
                customBackButtonAction = {
                    if (isReading) {
                        stopRfidReading() // 読み取りを停止
                        Toast.makeText(this, getString(R.string.make_inventory_activity_on_back_pressed), Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            )
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_make_inventory_hint_first))
            navigationHelper.setNavigationItemSelectedListener(this)

        }catch (e: Exception) {
            Timber.e(e, "Exception Error in setNavigationMenu")
        }
    }

    override fun onNavigationItemSelected(position: Int, title: String) {
        if (isReading) {
            stopRfidReading() // 画面遷移前に読み取りを停止
            Toast.makeText(this, getString(R.string.make_inventory_activity_navigation_menu_selected), Toast.LENGTH_SHORT).show()
        }
        proceedWithNavigation(position, title)
    }

    private fun proceedWithNavigation(position: Int, title: String) {
        when (position) {
            0 -> {
                val intent = Intent(this@MakeInventoryActivity, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
            1 -> {
                val intent = Intent(this@MakeInventoryActivity, FileManageActivity::class.java)
                startActivity(intent)
                finish()
            }
            2 -> {
                val intent = Intent(this@MakeInventoryActivity, SettingsActivity::class.java)
                startActivity(intent)
                finish()
            }
            3 -> {
                val intent = Intent(this@MakeInventoryActivity, AboutActivity::class.java)
                startActivity(intent)
                // finish() // About画面は戻れるようにfinishしないことが多い
            }
            else -> {
                Toast.makeText(this@MakeInventoryActivity, "未対応のメニュー項目: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }
}