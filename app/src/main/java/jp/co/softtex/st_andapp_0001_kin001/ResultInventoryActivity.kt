package jp.co.softtex.st_andapp_0001_kin001

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.provider.DocumentFile
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.text.toIntOrNull

// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.softtex.st_andapp_0001_kin001.ProductListActivity.ProductListAdapterV4
import jp.co.toshibatec.model.TagPack
import timber.log.Timber
import java.io.IOException

class ResultInventoryActivity :  BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {

    private lateinit var dbHelper: DatabaseHelper

    private lateinit var buttonConfirmInventory: Button
    private lateinit var buttonCancelInventory: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var checkBoxSaveResult: CheckBox

    private var exportDirUri: Uri? = null
    private var savedBaseFileName: String? = null

    // 読取リスト
    private lateinit var listViewReadInventory: ListView
    private lateinit var inventoryListAdapter: ProductListAdapterV4
    private val currentUiDisplayList: MutableList<ProductListActivity.ProductDisplayItem> = mutableListOf()

    // CSV保存処理の途中でファイルピッカーを待つ状態を示すフラグ
    private var isAwaitingFolderSelectionForCsv: Boolean = false
    // onActivityResult でCSV保存を再開するために一時的にデータを保持
    private var pendingCsvDataForExport: List<Map<String, Any>>? = null

    private lateinit var navigationHelper: NavigationHelper

    companion object {
        private const val TAG = "ResultInventoryActivity"
        private const val REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_CSV = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i( "onCreate.")
        try {
            dbHelper = DatabaseHelper(this)

            // プリファレンスの取得
            loadSavedDirectoryUri()

            // Viewのセット
            setView()

            // 読み取ったリストの表示
            setupInventoryListView()

        } catch (e: Exception) {
            Timber.e( "onCreate: Exception", e)
            e.printStackTrace()
            finish()
            return
        }

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
            // 読み取ったリストの表示
            setupInventoryListView()
        } catch (e: Exception) {
            Timber.e( "onResume: Exception", e)
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        SdkManager.stopMonitoring() // 監視を停止
        SdkManager.removeListener(this)
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
    }

    // --- SDKManager.Listener の実装 ---
    override fun onDriverStatusChanged(isOpened: Boolean, deviceName: String?) {
        runOnUiThread {
            if (isOpened) {
            } else {
                if (::navigationHelper.isInitialized) {
                    navigationHelper.updateConnectionStatus(false)
                    navigationHelper.updateBatteryLevel(-1, 0)
                }
            }
        }
    }

    override fun onConnectionStatusChanged(status: SdkManager.ConnectionStatus, deviceAddress: String?) {
        runOnUiThread {
            val isConnected = status == SdkManager.ConnectionStatus.CONNECTED

            if (::navigationHelper.isInitialized) {
                navigationHelper.updateConnectionStatus(isConnected)
            }
            if (!isConnected && ::navigationHelper.isInitialized) {
                navigationHelper.updateBatteryLevel(-1, 0)
            } else if (isConnected) {
                // 接続されたらバッテリーレベルを取得し直す
                SdkManager.fetchBatteryLevel()
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
        powerType: String?, // SdkManagerListenerのシグネチャに合わせる
        isSuccess: Boolean,
        needsInitFileRecreation: Boolean // SdkManagerListenerのシグネチャに合わせる
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
            Timber.e("onErrorOccurred: Op='$operation', Code=$errorCode, ExtCode=$extendedCode, Msg='$message', ConnectionLost=$isConnectionLostError")

            if (isConnectionLostError) {
                if (::navigationHelper.isInitialized) {
                    navigationHelper.updateConnectionStatus(false)
                    navigationHelper.updateBatteryLevel(-1, 0)
                }
            }
        }
    }

    override fun onTagDataReceived(tagPacks: Map<String, TagPack>) {
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
                    // このActivityでタグ読み取りを開始・停止することはない想定
                }
                SdkManager.SdkOperationType.STOP_READ_TAGS -> {
                    // このActivityでタグ読み取りを開始・停止することはない想定
                }
                else -> {
                    Timber.d( "Generic SDK result for $opName: ${if (success) "Success" else "Failed (Code:$resultCode)"}")
                }
            }
        }
    }

    override fun onDeviceDiscoveryUpdate(devices: List<String>, isScanComplete: Boolean) {
    }
    // --- ここまで Listener の実装 ---

    private fun setView() {
        /* メイン画面のレイアウトを読み込む */
        setContentView(R.layout.activity_result_inventory)
        // UI部品の初期化
        listViewReadInventory = findViewById(R.id.listView_inventory_result)
        buttonConfirmInventory = findViewById(R.id.button_confirm_inventory)
        buttonCancelInventory = findViewById(R.id.button_cancel_inventory)
        checkBoxSaveResult = findViewById(R.id.checkBox_save_result)
        progressBar = findViewById(R.id.progressBar)

        setupButtons()

        /* ナビゲーションメニューの初期化 */
        setNavigationMenu()
    }

    private fun setupInventoryListView() {
        syncCurrentUiDisplayListFromInventoryProgressList()
        inventoryListAdapter = ProductListAdapterV4(this, currentUiDisplayList )
        listViewReadInventory.adapter = inventoryListAdapter
    }

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
            val epc_upper = (mapItem[epcKey] as? String)?.uppercase(Locale.getDefault())
            val displayItem = ProductListActivity.ProductDisplayItem(
                barcode_no = mapItem[barcodeNoKey] as? String,
                location = mapItem[locationKey] as? String,
                stock_date = mapItem[stockDateKey] as? String,
                product_name = mapItem[nameKey] as? String,
                book_inventory = mapItem[bookInvKey] as? String, // ProductDisplayItem は String を期待
                physical_inventory = mapItem[physicalInvKey] as? String, // ProductDisplayItem は String を期待
                product_epc = epc_upper,
                scan_result = mapItem[scanResultKey] as? String
            )
            currentUiDisplayList.add(displayItem)
        }
        Timber.d("syncCurrentUiDisplayListFromInventoryProgressList: Synced ${currentUiDisplayList.size} items.")
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

            // ツールバーのタイトルと戻るボタンの表示設定
            navigationHelper.setupToolbarAndDrawer(
                screenTitle = getString(R.string.activity_make_inventory_result_title), // Activityごとのタイトル
                showBackButton = true,
                customBackButtonAction = {
                    finish()
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_make_inventory_result_hint))

            navigationHelper.setNavigationItemSelectedListener(this) // リスナーを自身に設定

        }catch (e: Exception) {
            Timber.e( "Exception Error in setNavigationMenu", e)
            e.printStackTrace()
        }
    }
    /* setNavigationMenu END */

    override fun onNavigationItemSelected(position: Int, title: String) {
        // ここで選択されたアイテムに応じた画面遷移処理を行う
        when (position) {
            0 -> { // MainActivityへ移動
                val intent = Intent(this@ResultInventoryActivity, MainActivity::class.java)
                startActivity(intent)
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@ResultInventoryActivity, FileManageActivity::class.java)
                startActivity(intent)
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@ResultInventoryActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@ResultInventoryActivity, AboutActivity::class.java)
                startActivity(intent)
            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@ResultInventoryActivity, "未対応のメニュー項目: ${title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButtons() {
        buttonConfirmInventory.setOnClickListener {
            if (MainActivity.inventory_progress_list.isEmpty()) {
                Toast.makeText(this, getString(R.string.result_inventory_activity_confirm_empty_list), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 確認ダイアログ
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.result_inventory_activity_confirm_title))
                .setMessage(getString(R.string.result_inventory_activity_confirm_text))
                .setPositiveButton(getString(R.string.btn_txt_yes)) { _, _ ->
                    // UIを処理中状態に
                    progressBar.visibility = View.VISIBLE
                    buttonConfirmInventory.isEnabled = false
                    buttonCancelInventory.isEnabled = false

                    if (checkBoxSaveResult.isChecked) {
                        initiateCsvExportAndDbUpdate(MainActivity.inventory_progress_list)
                    } else {
                        // CSV保存なしでDB更新のみ
                        Timber.d("CSV save NOT checked. Starting DB update task directly.")
                        startDbUpdateTask(true) // csvExportAttemptedOrSkipped = true (スキップされた)
                    }
                }
                .setNegativeButton(getString(R.string.btn_txt_no), null)
                .show()
        }
        buttonCancelInventory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.result_inventory_activity_cancel_title))
                .setMessage(getString(R.string.result_inventory_activity_cancel_text))
                .setPositiveButton(getString(R.string.btn_txt_yes)) { _, _ ->
                    MainActivity.inventory_progress_list.clear() // リストをクリア
                    // 必要であれば他の関連データもクリア
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(getString(R.string.btn_txt_no), null)
                .show()
        }
    }

    private fun initiateCsvExportAndDbUpdate(dataToExport: List<Map<String, Any>>) {
        Timber.d( "Initiating CSV export and DB update process...")
        pendingCsvDataForExport = dataToExport // onActivityResult で使うため保持
        isAwaitingFolderSelectionForCsv = false // 初期化

        if (exportDirUri != null) {
            val directoryDocFile = DocumentFile.fromTreeUri(this, exportDirUri!!)
            if (directoryDocFile != null && directoryDocFile.canWrite()) {
                Timber.i( "Existing directory URI${exportDirUri!!.path.toString()} has write permission. Exporting CSV...")
                val csvSuccess = exportInventoryResultToCsvInternal(dataToExport, exportDirUri!!)
                // CSV保存の成否に関わらず（あるいは成功時のみなど要件による）DB更新へ
                startDbUpdateTask(!csvSuccess) // CSVが成功しなかった場合は skippedCsv = true
                return
            } else {
                Timber.w( "Existing directory URI ($exportDirUri) is invalid or no write permission. Will open file picker.")
            }
        }

        // exportDirUri が null または書き込み権限がない場合、ファイルピッカーを開く
        Timber.i( "Opening document tree for CSV export destination.")
        isAwaitingFolderSelectionForCsv = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_CSV)
        } catch (e: Exception) {
            Timber.e( "Error starting ACTION_OPEN_DOCUMENT_TREE", e)
            Toast.makeText(this, getString(R.string.result_inventory_activity_save_folder_open_failure), Toast.LENGTH_SHORT).show()
            isAwaitingFolderSelectionForCsv = false
            pendingCsvDataForExport = null
            // CSV処理失敗としてDB更新へ
            startDbUpdateTask(true) // skippedCsv = true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_CSV && isAwaitingFolderSelectionForCsv) {
            isAwaitingFolderSelectionForCsv = false // 応答を受け取った
            var csvSaveSuccessAfterPicker = false

            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val treeUri = data.data!!
                Timber.d("Document tree URI selected by user: $treeUri")
                try {
                    // 永続的な権限を取得
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    exportDirUri = treeUri // 選択されたURIを保存
                    saveExportDirUri(treeUri) // SharedPreferencesにも保存

                    // 保留されていたデータでCSVエクスポートを実行
                    pendingCsvDataForExport?.let {
                        Timber.i("Attempting CSV export to newly selected folder.")
                        csvSaveSuccessAfterPicker = exportInventoryResultToCsvInternal(it, treeUri)
                    }
                } catch (e: SecurityException) {
                    Timber.e( "Failed to take persistable URI permission for $treeUri", e)
                    Toast.makeText(this, getString(R.string.result_inventory_activity_save_folder_permmision_failure), Toast.LENGTH_LONG).show()
                    // 権限取得失敗
                }
            } else {
                Timber.w("Folder selection was cancelled or failed. Result code: $resultCode")
                Toast.makeText(this, getString(R.string.result_inventory_activity_save_folder_permmision_canceled), Toast.LENGTH_LONG).show()
                // ユーザーがフォルダ選択をキャンセル
            }
            // CSVエクスポートデータは消費したのでクリア
            pendingCsvDataForExport = null
            // CSV保存処理の後（成功・失敗・キャンセルに関わらず）、DB更新タスクを開始
            startDbUpdateTask(!csvSaveSuccessAfterPicker) // ピッカー後のCSV保存が成功しなかった場合は skippedCsv = true
        }
    }

    private fun exportInventoryResultToCsvInternal(resultInventory: List<Map<String, Any>>, directoryUri: Uri): Boolean {
        if (resultInventory.isEmpty()) {
            Timber.w( "No data to export to CSV.")
            return true // データがない場合は処理不要で成功扱いとするか、falseとするか要件による。ここでは「書き込むべきものがないのでOK」とする。
        }

        val targetDirectory = DocumentFile.fromTreeUri(this, directoryUri)
        if (targetDirectory == null || !targetDirectory.isDirectory || !targetDirectory.canWrite()) {
            Timber.e( "Cannot write to the selected directory URI: $directoryUri. It's null, not a directory, or not writable.")
            Toast.makeText(this, getString(R.string.result_inventory_activity_write_folder_failure), Toast.LENGTH_LONG).show()
            return false
        }

        try {
            val locationKey = DatabaseContract.MasterColumn.LOCATION.getColumnName(this)
            // 最初のアイテムのロケーションを取得（ファイル名に使用する想定）
            var firstItemLocation = resultInventory.firstOrNull()?.get(locationKey) as? String ?: getString(R.string.layout_row_location_default)
            if (firstItemLocation.isNullOrBlank()) {
                firstItemLocation = getString(R.string.layout_row_location_default)
            }

            var baseFileNameToUse = savedBaseFileName
            if (baseFileNameToUse.isNullOrBlank()) { // nullまたは空文字または空白のみの場合
                baseFileNameToUse = getString(R.string.default_csv_filename_base)
            }

            val exportTag = "${firstItemLocation}_${getString(R.string.default_csv_export_tag)}"
            val csvFileName = CsvExporter.createCsvFileName(baseFileNameToUse, exportTag)

            val documentFile = targetDirectory.createFile("text/csv", csvFileName)
            if (documentFile == null) {
                Timber.e( "Failed to create CSV file ('$csvFileName') in directory: ${targetDirectory.uri}")
                Toast.makeText(this, getString(R.string.result_inventory_activity_create_file_failure), Toast.LENGTH_SHORT).show()
                return false
            }

            // CSVヘッダーとデータマッピングの準備 (既存のロジックを流用)
            val inventoryCsvHeaders = DatabaseContract.getMasterCsvExportHeaders(this)
            val csvHeaderToDataKeyMap = DatabaseContract.getMasterCsvHeaderToDbColumnMap(this)
            val itemsToExportForCsv = CsvExporter.convertInventoryResultToExportableList(resultInventory)
            val dataForCsvExporter: List<Map<String, Any?>> = itemsToExportForCsv.map { originalDataMap ->
                inventoryCsvHeaders.associateWith { csvHeader ->
                    val masterColumn: DatabaseContract.MasterColumn? = csvHeaderToDataKeyMap[csvHeader]
                    if (masterColumn != null) {
                        originalDataMap[masterColumn.getColumnName(this)]
                    } else {
                        null
                    }
                }
            }

            contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
                CsvExporter.writeCsvToStream(
                    outputStream,
                    inventoryCsvHeaders,
                    dataForCsvExporter
                )
                Timber.i("Inventory Result CSV file saved successfully: ${documentFile.uri}")
                Toast.makeText(this, getString(R.string.csv_toast_saved_successfully_to, documentFile.name), Toast.LENGTH_LONG).show()
                return true // 成功
            } ?: run {
                Timber.e("Failed to open output stream for CSV: ${documentFile.uri}")
                Toast.makeText(this, getString(R.string.csv_toast_error_opening_output_stream), Toast.LENGTH_LONG).show()
                return false // ストリームが開けなかった
            }
        } catch (e: CsvExporter.CsvWriteException) {
            Timber.e( "CsvWriteException exporting Inventory Result", e)
        } catch (e: IOException) {
            Timber.e( "IOException exporting Inventory Result", e)
        } catch (e: Exception) {
            Timber.e( "Unexpected error exporting Inventory Result", e)
        }
        Toast.makeText(this, "CSVエクスポート中にエラーが発生しました。", Toast.LENGTH_LONG).show()
        return false // 何らかのエラーで失敗
    }

    private fun saveExportDirUri(uri: Uri?) {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsActivity.KEY_EXPORT_DIR_URI, uri?.toString()).apply()
        if (uri != null) {
            Timber.d( "Saved export directory URI to SharedPreferences: $uri")
        } else {
            Timber.d( "Cleared export directory URI from SharedPreferences.")
        }
    }

    private fun loadSavedDirectoryUri() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val prefUriString = prefs.getString(SettingsActivity.KEY_EXPORT_DIR_URI, null)
        if (prefUriString != null) {
            exportDirUri = Uri.parse(prefUriString)
            Timber.v( "Loaded saved export directory URI: $exportDirUri")
        } else {
            Timber.v( "No saved export directory URI found in SharedPreferences.")
        }
        val prefBaseFilename = prefs.getString(SettingsActivity.KEY_FILE_NAME_BASE, null)
        if (prefBaseFilename != null) {
            savedBaseFileName = prefBaseFilename
            Timber.v( "Loaded saved base file name: $savedBaseFileName")
        } else {
            Timber.v( "No saved base file name found in SharedPreferences.")
        }
    }

    private fun startDbUpdateTask(csvExportSkippedOrFailed: Boolean) {
        Timber.d( "Starting DB update task. CSV export skipped/failed: $csvExportSkippedOrFailed")
        ProcessInventoryTask(this, csvExportSkippedOrFailed).execute()
    }

    // 棚卸データの集計とDB更新を行うAsyncTask
    @SuppressLint("StaticFieldLeak")
    private inner class ProcessInventoryTask(
        activity: ResultInventoryActivity,
        private val csvExportSkippedOrFailed: Boolean // CSV処理がスキップされたか失敗したかを受け取る
    ) : AsyncTask<Void, String, Boolean>() {

        private val activityReference: WeakReference<ResultInventoryActivity> = WeakReference(activity)

        override fun onPreExecute() {
            super.onPreExecute()
            val activity = activityReference.get() ?: return
        }

        override fun doInBackground(vararg params: Void?): Boolean {
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) {
                Timber.w( "Activity is null or finishing. Aborting doInBackground.")
                return false
            }
            val contextForDb = activity.applicationContext

            val dbHelper = DatabaseHelper(contextForDb)
            // dbHelper.logAllMasterItems("ProcessInventoryTask_MasterData")

            var updatedCount = 0
            var addedCount = 0
            var skippedCount = 0
            var processedCount = 0

            // MainActivity.inventory_progress_list の各行をそのまま処理
            MainActivity.inventory_progress_list.forEachIndexed { index, progressItem ->
                processedCount++
                // --- 1. progressItemからキー情報と保存する値を取得 ---

                val productEpc = (progressItem[DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(contextForDb)] as? String)
                val location = progressItem[DatabaseContract.MasterColumn.LOCATION.getColumnName(contextForDb)] as? String

                publishProgress("DB処理中 (${processedCount}/${MainActivity.inventory_progress_list.size}) EPC: $productEpc Loc: $location")

                if (productEpc == null) {
                    Timber.w( "PRODUCT_EPCがnullのため、アイテム ${index + 1} の処理をスキップします。Item: $progressItem")
                    skippedCount++
                    return@forEachIndexed // 次のアイテムへ
                }

                val physicalInventoryString = progressItem[DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(contextForDb)] as? String ?: "0"
                val physicalInventoryFromProgress = physicalInventoryString?.toIntOrNull() ?: 0
                val scanResultFromProgress = progressItem[DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(contextForDb)] as? String
                    ?: DatabaseContract.getScanResultString(contextForDb, DatabaseContract.ScanResult.UNCHECKED_NUM)
                Timber.d( "Processing Item: EPC=$productEpc, Loc=$location, PhysInv=$physicalInventoryFromProgress, ScanRes=$scanResultFromProgress")

                // --- 2. Masterテーブルから product_epc と location が一致するデータを検索 ---
                val selection = "${DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(contextForDb)} = ? COLLATE NOCASE AND ${DatabaseContract.MasterColumn.LOCATION.getColumnName(contextForDb)} = ?"
                val selectionArgs = arrayOf(productEpc, location ?: "") // locationがnullの場合は空文字列として検索

                val existingMasterItems = dbHelper.getItems(
                    tableName = DatabaseContract.TableName.TABLE_NAME_MASTER,
                    selection = selection,
                    selectionArgs = selectionArgs
                )

                if (existingMasterItems.isNotEmpty()) {
                    // --- 3. データが見つかった場合：Masterテーブルの該当フィールドを更新 ---
                    val masterItemToUpdate = existingMasterItems[0] // 最初に見つかったアイテムを使用
                    val masterItemId = masterItemToUpdate[DatabaseContract.MasterColumn.ID.getColumnName(contextForDb)] as? Long

                    if (masterItemId != null) {
                        var itemUpdatedSuccessfully = true

                        // physical_inventory を更新
                        if (!dbHelper.updateItemField(
                                DatabaseContract.TableName.TABLE_NAME_MASTER,
                                masterItemId,
                                DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(contextForDb),
                                physicalInventoryFromProgress
                            )
                        ) {
                            itemUpdatedSuccessfully = false
                            Timber.w( "Failed to update PHYSICAL_INVENTORY for Master ID $masterItemId, EPC: $productEpc, Loc: $location")
                        }

                        // scan_result を更新 (前の更新が成功した場合のみ)
                        if (itemUpdatedSuccessfully && !dbHelper.updateItemField(
                                DatabaseContract.TableName.TABLE_NAME_MASTER,
                                masterItemId,
                                DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(contextForDb),
                                scanResultFromProgress
                            )
                        ) {
                            itemUpdatedSuccessfully = false
                            Timber.w( "Failed to update SCAN_RESULT for Master ID $masterItemId, EPC: $productEpc, Loc: $location")
                        }

                        if (itemUpdatedSuccessfully) {
                            updatedCount++
                            Timber.d( "Master item ID $masterItemId potentially updated. EPC: $productEpc, Loc: $location, PhysInv: $physicalInventoryFromProgress, ScanRes: $scanResultFromProgress")
                        }
                    } else {
                        Timber.w( "Found Master item for EPC: $productEpc, Loc: $location but its ID is null. Skipping update.")
                        skippedCount++
                    }
                } else {
                    // --- 4. データが見つからなかった場合：新たなデータとしてMasterテーブルにインサート ---
                    val contentValuesForInsert = ContentValues().apply {
                        put(DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(contextForDb), progressItem[DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(contextForDb)] as? String)
                        put(DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(contextForDb), progressItem[DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(contextForDb)] as? String)
                        put(DatabaseContract.MasterColumn.LOCATION.getColumnName(contextForDb), location ?: "")
                        put(DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(contextForDb), progressItem[DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(contextForDb)] as? String)
                        val bookInventoryFromProgress = progressItem[DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(contextForDb)] as? Int ?: 0
                        put(DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(contextForDb), bookInventoryFromProgress.toLong())
                        put(DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(contextForDb), physicalInventoryFromProgress.toLong())
                        put(DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(contextForDb), productEpc.uppercase(Locale.getDefault()))
                        put(DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(contextForDb), scanResultFromProgress)
                    }

                    val newRowId = dbHelper.insertItem(DatabaseContract.TableName.TABLE_NAME_MASTER, contentValuesForInsert)
                    if (newRowId != -1L) {
                        addedCount++
                        Timber.i( "New item inserted into Master. EPC: $productEpc, Loc: $location, PhysInv: $physicalInventoryFromProgress, New ID: $newRowId")
                    } else {
                        Timber.w( "Failed to insert new item into Master. EPC: $productEpc, Loc: $location, Values: $contentValuesForInsert")
                        // 挿入失敗の場合、エラーハンドリングや再試行ロジックを検討
                    }
                }
            } // end of forEachIndexed for MainActivity.inventory_progress_list

            publishProgress("DB更新完了。更新試行: $updatedCount 件, 追加試行: $addedCount 件, スキップ: $skippedCount 件")
            // 処理の成功/失敗の基準をどうするか。ここでは少なくとも1件の追加か更新があれば成功とする。
            return updatedCount > 0 || addedCount > 0
        }


        override fun onProgressUpdate(vararg values: String?) {
            super.onProgressUpdate(*values)
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return
            values[0]?.let {

                Timber.v( "ProgressUpdate: $it")
            }
        }

        override fun onPostExecute(dbSuccess: Boolean?) {
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return

            activity.progressBar.visibility = View.GONE
            activity.buttonConfirmInventory.isEnabled = true
            activity.buttonCancelInventory.isEnabled = true

            val title: String
            val message: String

            if (dbSuccess == true) {
                title = getString(R.string.result_inventory_activity_save_finished_title)
                message = getString(R.string.result_inventory_activity_save_finished_text)
                MainActivity.inventory_progress_list.clear() // 確定後はリストクリア
            } else {
                title = getString(R.string.result_inventory_activity_save_failure_title)
                message = getString(R.string.result_inventory_activity_save_failure_text)
            }

            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.btn_txt_ok)) { _, _ ->
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    activity.startActivity(intent)
                    activity.finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onBackPressed() {
        // ドロワーが開いていた場合閉じる
        if (navigationHelper.isDrawerOpen()) {
            navigationHelper.closeDrawer()
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.result_inventory_activity_cancel_title))
                .setMessage(getString(R.string.result_inventory_activity_cancel_text))
                .setPositiveButton(getString(R.string.btn_txt_yes)) { _, _ ->
                    super.onBackPressed()
                    MainActivity.inventory_progress_list.clear() // リストをクリア
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("いいえ", null)
                .show()
        }
    }
}