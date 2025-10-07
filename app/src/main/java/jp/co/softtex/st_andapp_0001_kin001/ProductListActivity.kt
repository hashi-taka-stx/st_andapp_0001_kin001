package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import jp.co.softtex.st_andapp_0001_kin001.MainActivity.Companion.showErrorDialog
import java.lang.ref.WeakReference

// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.toshibatec.model.TagPack
import timber.log.Timber


class ProductListActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {

    data class ProductDisplayItem(
        val barcode_no: String?, // BARCODE_NO
        val location: String?, // LOCATION
        val stock_date: String?, // STOCK_DATE
        val product_name: String?, // PRODUCT_NAME
        val book_inventory: String?,   // BOOK_INVENTORY
        val physical_inventory: String?, // PHYSICAL_INVENTORY
        val product_epc: String?, // PRODUCT_EPC
        val scan_result: String? // SCAN_RESULT
    )
    private var currentTask: LoadProductsTask? = null // AsyncTaskの参照を保持

    /* ナビゲーションバー */
    private lateinit var navigationHelper: NavigationHelper // ヘルパーのインスタンス

    private lateinit var productListView: ListView
    private lateinit var inventoryButton: Button
    private lateinit var emptyView: TextView

    private lateinit var dbHelper: DatabaseHelper // DatabaseHelperのインスタンスを保持
    private lateinit var productListAdapter: ProductListAdapterV4

    private var selectedLocationName: String? = null

    companion object {
        const val EXTRA_LOCATION_VALUE = "jp.co.softtex.st_andapp_0001.EXTRA_LOCATION_VALUE"
        const val TAG = "ProductListActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i( "onCreate.")
        try {
            // SdkManager のコア初期化は Application クラスで行われている前提
            if (!MyApplication.isSdkManagerSuccessfullyInitialized) {
                Timber.e( "SdkManager was not initialized successfully in Application class. App functionality might be limited.")
                showErrorDialog(this, getString(R.string.dialog_SDK_initialize_failure), ErrNo.ERROR_ON_INITIALIZE)
                finish()
                return
            }

            dbHelper = DatabaseHelper(this) // DatabaseHelperを初期化

            // ビューの生成
            setView()

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
        } catch (e: Exception) {
            Timber.e("onResume: Exception", e)
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
    }

    override fun onBackPressed() {
        if (::navigationHelper.isInitialized && navigationHelper.isDrawerOpen()) {
            navigationHelper.closeDrawer()
        } else {
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
        currentTask?.cancel(true)
    }

    /**
     * @brief private fun setView
     *
     * @details ビューの初期設定を行う
     * @param[in]
     * @return
     * @author 橋本隆宏(HASHIMOTO Takahiro)
     * @date 2025-07-15 : 作成
     */
    private fun setView() {
        /* メイン画面のレイアウトを読み込む */
        setContentView(R.layout.activity_product_list)

        /* ナビゲーションメニューの初期化 */
        setNavigationMenu()

        productListView = findViewById(R.id.list_view_products)
        emptyView = findViewById(R.id.empty_view_product_list)
        inventoryButton = findViewById(R.id.inventory_button)
        productListView.emptyView = emptyView

        selectedLocationName = intent.getStringExtra(EXTRA_LOCATION_VALUE)
        Timber.d( "intent:Selected Location: $selectedLocationName")

        loadProductList(selectedLocationName)

        productListView.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
            val selectedProduct = parent.getItemAtPosition(position) as? ProductDisplayItem
            if (selectedProduct != null) {
                Timber.v( "Item tapped: Product EPC: ${selectedProduct.product_epc}, Location: $selectedLocationName")
                // locationとproduct_epcからMasterTableのidを取得する
                val context = this@ProductListActivity
                val selection = "${DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)} = ? AND ${DatabaseContract.MasterColumn.LOCATION.getColumnName(context)} = ?"
                val selectionArgs: Array<String>? // 初期化は後で行う

                val productEpc = selectedProduct.product_epc as? String
                val location = selectedProduct.location as? String
                if (productEpc != null && location != null) {
                    selectionArgs = arrayOf(productEpc, location)
                } else if (productEpc != null) {
                    selectionArgs = arrayOf(productEpc)
                } else {
                    selectionArgs = null
                }
                val existingItemsList = dbHelper.getItems(
                    tableName = DatabaseContract.TableName.TABLE_NAME_MASTER,
                    selection = selection,
                    selectionArgs = selectionArgs
                )
                val existingMasterItem: Map<String, Any?>? = if (existingItemsList.isNotEmpty()) {
                    existingItemsList[0] // EPCはユニークであるはずなので、最初のアイテムを取得
                } else {
                    null
                }

                if (existingMasterItem != null) {
                    // 2. Masterテーブルにstock_epcが一致するアイテムがあれば更新
                    //    updateItemField を使用して各フィールドを更新
                    val masterItemId =
                        existingMasterItem[DatabaseContract.MasterColumn.ID.getColumnName(context)].toString()
                    if (masterItemId != null) {
                        // ItemDetailAcitvityへ画面遷移
                        val intent = Intent(this@ProductListActivity, ItemDetailActivity::class.java)
                        val extra_id = ItemDetailActivity.EXTRA_ID
                        Timber.d( "Starting ItemDetailActivity with ID: $masterItemId. Intent: $intent, Extras: ${intent.extras}")
                        intent.putExtra(extra_id, masterItemId)
                        startActivity(intent)
                    } else {
                        Timber.e( "Error: Could not retrieve ID from existing master item.")
                    }
                } else {
                    Timber.e( "Error: Could not find matching master item in database.")
                }

            } else {
                Timber.e( "Error: Could not cast selected item to ProductDisplayItem at position $position")
                Toast.makeText(this, getString(R.string.product_list_activity_error_get_item_data), Toast.LENGTH_SHORT).show()
            }
        }
        // inventory_button (fabInventory) のクリックリスナー
        inventoryButton.setOnClickListener {
            // navigationHelper が初期化されていることを確認 (setNavigationMenu() 後なので通常はOK)
            if (::navigationHelper.isInitialized) {
                navigationHelper.closeDrawer()
            } else {
                Timber.w( "inventoryButton: NavigationHelper not initialized when trying to close drawer.")
            }
            val keyLocation = MakeInventoryActivity.EXTRA_KEY_LOCATION
            val keyProductEpc = MakeInventoryActivity.EXTRA_KEY_PRODUCT_EPC

            if (SdkManager.isConnected()) {
                // 既に接続中なので棚卸画面に遷移
                // MakeInventoryActivityに遷移
                Timber.d( "Device online. Navigating to MakeInventoryActivity.")
                val intent = Intent(this@ProductListActivity, MakeInventoryActivity::class.java)
                intent.putExtra(keyLocation, selectedLocationName)
                intent.putExtra(keyProductEpc, null as String?) // product_epc を null で渡す
                startActivity(intent)
            } else {
                // 接続されていない場合は、接続を促す
                Timber.d( "Device offline. Navigating to ConnectionActivity.")
                val intent = Intent(this@ProductListActivity, ConnectionActivity::class.java)
                intent.putExtra(keyLocation, selectedLocationName)
                intent.putExtra(keyProductEpc, null as String?) // product_epc を null で渡す

                startActivity(intent)
            }
        }
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
                screenTitle = getString(R.string.activity_product_list_title), // Activityごとのタイトル
                showBackButton = true,
                customBackButtonAction = {
                    finish()
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_product_list_hint))

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
                val intent = Intent(this@ProductListActivity, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@ProductListActivity, FileManageActivity::class.java)
                startActivity(intent)
                finish()
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@ProductListActivity, SettingsActivity::class.java)
                startActivity(intent)
                finish()
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@ProductListActivity, AboutActivity::class.java)
                startActivity(intent)

            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@ProductListActivity, "未対応のメニュー項目: ${title}", Toast.LENGTH_SHORT).show()
            }
        }
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


    /**
     * 指定された置き場名に基づいてProductテーブルを更新し、商品リストをListViewに表示する。(非同期処理)
     * @param locationName 商品をフィルタリングする置き場名。nullの場合は Product テーブルの全商品が表示される想定。
     */
    private fun loadProductList(locationName: String?) {
        Timber.d( "loadProductList called with locationName: $locationName")

        // 既存のタスクがあればキャンセル
        currentTask?.cancel(true)
        // 新しいタスクを開始
        currentTask = LoadProductsTask(this, locationName)
        currentTask?.execute()
    }

    // staticなインナークラスとしてAsyncTaskを定義 (ActivityへのWeakReferenceを持つ)
    private class LoadProductsTask(
        activity: ProductListActivity,
        private val locationName: String?
    ) : AsyncTask<Void, Void, Pair<Int, List<ProductDisplayItem>>>() {
        // AsyncTaskがActivityへの強い参照を持つとメモリリークの原因になるためWeakReferenceを使用
        private val activityReference: WeakReference<ProductListActivity> = WeakReference(activity)

        private var createTableException: Exception? = null
        private var fetchItemsException: Exception? = null

        override fun onPreExecute() {
            super.onPreExecute()
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return

            // 必要であればプログレスバーなどを表示
            // activity.progressBar.visibility = View.VISIBLE
            // activity.productListView.visibility = View.GONE
            // activity.emptyView.visibility = View.GONE
        }
        override fun onPostExecute(result: Pair<Int, List<ProductDisplayItem>>) { // result.first は使わない
            super.onPostExecute(result)
            val activity = activityReference.get()
            if (activity == null || activity.isFinishing) return

            val productDisplayItems = result.second

            if (fetchItemsException != null) {
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.product_list_activity_error_get_item_data,
                        fetchItemsException?.localizedMessage
                    ),
                    Toast.LENGTH_LONG
                ).show()

                if (activity.productListView.adapter == null && !activity::productListAdapter.isInitialized) {
                    activity.productListAdapter = ProductListAdapterV4(activity, mutableListOf())
                    activity.productListView.adapter = activity.productListAdapter
                }
                activity.productListAdapter.updateData(emptyList())
                activity.emptyView.visibility = View.VISIBLE
                activity.emptyView.text = activity.getString(R.string.error_loading_products)
                return
            }

            if (productDisplayItems.isNotEmpty()) {
                if (!activity::productListAdapter.isInitialized) {
                    activity.productListAdapter = ProductListAdapterV4(activity, productDisplayItems.toMutableList())
                    activity.productListView.adapter = activity.productListAdapter
                } else {
                    activity.productListAdapter.updateData(productDisplayItems)
                }
                activity.emptyView.visibility = View.GONE
                activity.productListView.visibility = View.VISIBLE
            } else {
                if (activity::productListAdapter.isInitialized) {
                    activity.productListAdapter.clearData()
                } else {
                    // アダプターが未初期化の場合、空のアダプターを設定
                    activity.productListAdapter = ProductListAdapterV4(activity, mutableListOf())
                    activity.productListView.adapter = activity.productListAdapter
                }
                activity.emptyView.visibility = View.VISIBLE
                activity.productListView.visibility = View.GONE // リストが空なら非表示

                // locationName を考慮したメッセージに変更 (MasterTable から直接読むため)
                val currentFilterLocation = activity.selectedLocationName // Activityのメンバ変数などから取得
                if (currentFilterLocation.isNullOrEmpty() || currentFilterLocation == activity.getString(R.string.location_name_all)) {
                    activity.emptyView.text = activity.getString(R.string.empty_product_list_message, activity.getString(R.string.location_name_all_for_message))
                } else {
                    activity.emptyView.text = activity.getString(R.string.empty_product_list_message, currentFilterLocation)
                }
                // または、より単純に「表示する商品がありません。」だけでも良いかもしれません。
                // activity.emptyView.text = activity.getString(R.string.empty_product_list_generic)
            }
        }

        override fun doInBackground(vararg params: Void?): Pair<Int, List<ProductDisplayItem>> {
            val activity = activityReference.get()
            // Activityが既に破棄されているか、タスクがキャンセルされた場合は処理中断
            if (activity == null || activity.isFinishing || isCancelled) {
                return Pair(-1, emptyList())
            }

            val productDisplayItems = mutableListOf<ProductDisplayItem>()
            fetchItemsException = null // 初期化

            try {
                val masterTableName = DatabaseContract.TableName.TABLE_NAME_MASTER
                // MasterTableから取得するカラムを定義
                val barcodeNoCol =
                    DatabaseContract.MasterColumn.BARCODE_NO.getColumnName(activity)
                val locationCol =
                    DatabaseContract.MasterColumn.LOCATION.getColumnName(activity)
                val stockDateCol =
                    DatabaseContract.MasterColumn.STOCK_DATE.getColumnName(activity)
                val productNameCol =
                    DatabaseContract.MasterColumn.PRODUCT_NAME.getColumnName(activity)
                val bookInventoryCol =
                    DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(activity)
                val physicalInventoryCol =
                    DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(activity)
                val productEPCCol =
                    DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(activity)
                val scanResultCol =
                    DatabaseContract.MasterColumn.SCAN_RESULT.getColumnName(activity)

                val columnsToFetch = mutableListOf(
                    barcodeNoCol,
                    locationCol,
                    stockDateCol,
                    productNameCol,
                    bookInventoryCol,
                    physicalInventoryCol,
                    productEPCCol,
                    scanResultCol
                    // 必要に応じて MasterTable の他のカラムも追加
                ).toTypedArray()

                var selection: String? = null
                var selectionArgs: Array<String>? = null

                // locationName で絞り込み
                if (locationName == null) {
                    Timber.v( "locationName is null, fetching all items (or define specific logic).")
                } else if (locationName.isEmpty()) {
                    selection = "$locationCol = ?"
                    selectionArgs = arrayOf("")
                    Timber.v( "locationName is empty string, fetching items where location is an empty string.")
                } else {
                    // locationName が指定されている場合 (空文字ではない)
                    selection = "$locationCol = ?"
                    selectionArgs = arrayOf(locationName)
                    Timber.v( "locationName is '$locationName', fetching items for this location.")
                }

                // MasterTableからデータを取得 (ソート順は productNameCol ASC を維持すると仮定)
                val masterDataList = activity.dbHelper.getItems(
                    tableName = masterTableName,
                    columns = columnsToFetch,
                    selection = selection,
                    selectionArgs = selectionArgs,
                    orderBy = "$barcodeNoCol ASC" // 必要に応じてソート順を調整
                )

                for (itemMap in masterDataList) {
                    if (isCancelled) break

                    val pCode = itemMap[barcodeNoCol]?.toString() ?: ""
                    val loc = itemMap[locationCol]?.toString() ?: ""
                    val stockDateValue = itemMap[stockDateCol]
                    var stockDate:String
                    if (stockDateValue is Long) { // 値がLong型 (タイムスタンプ) の場合
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                            stockDate = sdf.format(java.util.Date(stockDateValue))
                        } catch (e: Exception) {
                            Timber.w( "Error formatting timestamp: $stockDateValue", e)
                            stockDate = stockDateValue.toString() // フォーマット失敗時は元のLong値を文字列化 (または空文字など)
                        }
                    } else if (stockDateValue is String) { // 既に文字列の場合
                        stockDate = stockDateValue
                    } else { // その他の型、またはnullの場合
                        stockDate = stockDateValue?.toString() ?: "" // nullなら空文字
                    }

                    val pName = itemMap[productNameCol]?.toString() ?: ""
                    val bInv = itemMap[bookInventoryCol]?.toString() ?: ""
                    val pInv = itemMap[physicalInventoryCol]?.toString() ?: ""
                    val pEpc = itemMap[productEPCCol]?.toString() ?: ""
                    val scanRes = itemMap[scanResultCol]?.toString() ?: ""

                    // ProductDisplayItem の bookInventory, physicalInventory は MasterTable に
                    // 直接対応するカラムがなければ null またはデフォルト値になります。
                    // もし MasterTable にこれらの情報がある、または別の計算が必要な場合はここで処理します。
                    productDisplayItems.add(
                        ProductDisplayItem(
                            barcode_no = pCode,
                            location = loc,
                            stock_date = stockDate,
                            product_name = pName,
                            book_inventory = bInv,
                            physical_inventory = pInv,
                            product_epc = pEpc,
                            scan_result = scanRes,
                        )
                    )
                }
                Timber.v("AsyncTask: Fetched ${productDisplayItems.size} items directly from Master table for location: $locationName.")
            } catch (e: Exception) {
                Timber.w("AsyncTask: Error fetching data from Master table for location: $locationName")
                fetchItemsException = e
                productDisplayItems.clear()
            }
            return Pair(0, productDisplayItems)
        }
    }

    class ProductListAdapterV4(
        context: Context,
        private var dataSource: MutableList<ProductDisplayItem> // 更新可能にするため MutableList に
    ) : ArrayAdapter<ProductDisplayItem>(context, 0, dataSource) { // 第2引数 resource は0でOK (getViewでカスタムレイアウトを指定するため)

        // ViewHolderパターンを使用してパフォーマンスを向上させます
        private data class ViewHolder(
            val barcodeNoTextView: TextView,
            val locationTextView: TextView,
            val productNameTextView: TextView,
            val productEpcTextView: TextView,
            val bookInventoryTextView: TextView,
            val physicalInventoryTextView: TextView,
            val scanResultTextView: TextView
        )

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val viewHolder: ViewHolder

            if (convertView == null) {
                // row_product.xml をインフレート
                view = LayoutInflater.from(context).inflate(R.layout.row_product, parent, false)
                viewHolder = ViewHolder(
                    barcodeNoTextView = view.findViewById(R.id.layout_row_product_textview_barcode_no),
                    locationTextView = view.findViewById(R.id.layout_row_product_textview_location),
                    productNameTextView = view.findViewById(R.id.layout_row_product_textview_product_name),
                    productEpcTextView = view.findViewById(R.id.layout_row_product_textview_product_epc),
                    bookInventoryTextView = view.findViewById(R.id.layout_row_product_textview_book_inventory_num),
                    physicalInventoryTextView = view.findViewById(R.id.layout_row_product_textview_physical_inventory_num),
                    scanResultTextView = view.findViewById(R.id.layout_row_product_textview_scan_result)
                )
                view.tag = viewHolder
            } else {
                view = convertView
                viewHolder = view.tag as ViewHolder
            }

            val item = getItem(position) // dataSource[position] と同じ
            if (item != null) {
                viewHolder.barcodeNoTextView.text = item.barcode_no ?: ""
                viewHolder.locationTextView.text = item.location ?: context.getString(R.string.layout_row_location_default)
                viewHolder.productNameTextView.text = item.product_name ?: context.getString(R.string.layout_row_product_name_default)
                viewHolder.productEpcTextView.text = item.product_epc
                viewHolder.bookInventoryTextView.text = item.book_inventory ?: ""
                viewHolder.physicalInventoryTextView.text = item.physical_inventory ?: ""
                viewHolder.scanResultTextView.text = item.scan_result ?: ""
                view.setBackgroundColor(ContextCompat.getColor(context, R.color.white))
                if (item.scan_result != null) {
                    val resultstrings = context.resources.getStringArray(R.array.csv_scan_result_strings)
                    when (item.scan_result) {
                        resultstrings[DatabaseContract.ScanResult.UNCHECKED_NUM]
                        -> {
                            view.setBackgroundColor(ContextCompat.getColor(context, R.color.white))
                        }
                        resultstrings[DatabaseContract.ScanResult.MATCH_NUM]
                        -> {
                            view.setBackgroundColor(ContextCompat.getColor(context, R.color.row_background_match))
                        }
                        resultstrings[DatabaseContract.ScanResult.UNDER_NUM]
                        -> {
                            view.setBackgroundColor(ContextCompat.getColor(context, R.color.row_background_under))
                        }
                        resultstrings[DatabaseContract.ScanResult.OVER_NUM]
                        -> {
                            view.setBackgroundColor(ContextCompat.getColor(context, R.color.row_background_over))
                        }
                        else -> {
                            view.setBackgroundColor(ContextCompat.getColor(context, R.color.white)) // 不明な場合は白
                        }
                    }
                }
            }

            return view
        }

        /**
         * リストデータを更新し、ListViewに再描画を促すメソッド。
         * @param newData 新しい ProductDisplayItem のリスト。
         */
        fun updateData(newData: List<ProductDisplayItem>) {
            dataSource.clear()
            dataSource.addAll(newData)
            notifyDataSetChanged()
        }

        /**
         * アダプターのデータをクリアするメソッド
         */
        fun clearData() {
            dataSource.clear()
            notifyDataSetChanged()
        }
    }
}