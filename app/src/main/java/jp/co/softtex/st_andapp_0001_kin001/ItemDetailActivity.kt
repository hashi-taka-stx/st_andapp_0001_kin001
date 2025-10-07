package jp.co.softtex.st_andapp_0001_kin001

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.toshibatec.model.TagPack
import timber.log.Timber

class ItemDetailActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {

    /* ナビゲーションバー */
    private lateinit var navigationHelper: NavigationHelper // ヘルパーのインスタンス

    private lateinit var tvBarcodeNo: TextView
    private lateinit var tvStockDate: TextView
    private lateinit var tvOrderLocation: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvProductEpc: TextView
    private lateinit var tvBookInventory: TextView
    private lateinit var tvPhysicalInventory: TextView
    private lateinit var inventoryButton: Button

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var weakReference: WeakReference<ItemDetailActivity>

    companion object {
        const val EXTRA_ID = "extra_id"
        const val TAG = "ItemDetailActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i( "onCreate.")
        try {
            // SdkManager のコア初期化は Application クラスで行われている前提
            if (!MyApplication.isSdkManagerSuccessfullyInitialized) {
                Timber.e( "SdkManager was not initialized successfully in Application class. App functionality might be limited.")
                MainActivity.showErrorDialog(this, getString(R.string.dialog_SDK_initialize_failure), ErrNo.ERROR_ON_INITIALIZE)
                finish()
                return
            }

            dbHelper = DatabaseHelper(this) // DatabaseHelperを初期化

            // 3. ビューの生成
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
            Timber.e( "onResume: Exception", e)
            e.printStackTrace()
        }
    }
    override fun onPause() {
        super.onPause()
        if (SdkManager.isScanningDevice()) {
            SdkManager.stopDeviceScan()
        }
        SdkManager.removeListener(this)
    }

    // バックキーが押されたときの処理 (ドロワーが開いていれば閉じる)
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
        // アプリ終了時に SdkManager のシャットダウン処理を行う場合はここで
        // SdkManager.shutdown()
    }

    private fun setView() {
        setContentView(R.layout.activity_item_detail)

        tvBarcodeNo = findViewById(R.id.layout_item_detail_textview_barcode_no)
        tvStockDate = findViewById(R.id.layout_item_detail_textview_stock_date)
        tvOrderLocation = findViewById(R.id.layout_item_detail_textview_order_location)
        tvProductName = findViewById(R.id.layout_item_detail_textview_product_name)
        tvProductEpc = findViewById(R.id.layout_item_detail_textview_product_epc)
        tvBookInventory = findViewById(R.id.layout_item_detail_textview_book_exist)
        tvPhysicalInventory = findViewById(R.id.layout_item_detail_textview_physical_exist)

        loadAndDisplayItemDetails()

        /* ナビゲーションメニューの初期化 */
        setNavigationMenu()
        // inventory_button (fabInventory) のクリックリスナー
        inventoryButton = findViewById(R.id.inventory_button)
        inventoryButton.setOnClickListener {
            // navigationHelper が初期化されていることを確認 (setNavigationMenu() 後なので通常はOK)
            if (::navigationHelper.isInitialized) {
                navigationHelper.closeDrawer()
            } else {
                Timber.w( "inventoryButton: NavigationHelper not initialized when trying to close drawer.")
            }
            val keyLocation = MakeInventoryActivity.EXTRA_KEY_LOCATION
            val keyProductEpc = MakeInventoryActivity.EXTRA_KEY_PRODUCT_EPC
            val locationValue = tvOrderLocation.text.toString()
            val productEPCValue = tvProductEpc.text.toString()
            Timber.d("ItemDetailActivity: Preparing to putExtra for EPC. keyProductEpc='$keyProductEpc', productEPCValue='$productEPCValue', is productEPCValue null? ${productEPCValue == null}")
            // RfidStatusHelper が初期化されていることを確認 (onCreateで初期化されていればOK)
            // かつ isConnected() メソッドが RfidStatusHelper に実装されていること
            if (SdkManager.isConnected()) {
                // 既に接続中なので棚卸画面に遷移
                // MakeInventoryActivityに遷移
                Timber.d( "Device online. Navigating to MakeInventoryActivity.")
                val intent = Intent(this@ItemDetailActivity, MakeInventoryActivity::class.java)
                intent.putExtra(keyLocation, locationValue)
                intent.putExtra(keyProductEpc, productEPCValue)
                Timber.d("ItemDetailActivity: Starting ConnectionActivity with Intent Extras:")
                for (keyBundle in intent.extras?.keySet() ?: emptySet()) {
                    Timber.d("ItemDetailActivity: Extra from Bundle: $keyBundle = ${intent.extras?.get(keyBundle)} (Type: ${intent.extras?.get(keyBundle)?.javaClass?.name})")
                }
                startActivity(intent)
            } else {
                // 接続されていない場合は、接続を促す
                Timber.d( "Device offline. Navigating to ConnectionActivity.")
                val intent = Intent(this@ItemDetailActivity, ConnectionActivity::class.java)
                intent.putExtra(keyLocation, locationValue)
                intent.putExtra(keyProductEpc, productEPCValue)
                Timber.d("ItemDetailActivity: Starting ConnectionActivity with Intent Extras:")
                for (keyBundle in intent.extras?.keySet() ?: emptySet()) {
                    Timber.d("ItemDetailActivity: Extra from Bundle: $keyBundle = ${intent.extras?.get(keyBundle)} (Type: ${intent.extras?.get(keyBundle)?.javaClass?.name})")
                }
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
                screenTitle = getString(R.string.activity_item_detail_title), // Activityごとのタイトル
                showBackButton = true,
                customBackButtonAction = {
                    finish()
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_item_detail_hint))

            navigationHelper.setNavigationItemSelectedListener(this) // リスナーを自身に設定

        }catch (e: Exception) {
            Timber.e("Exception Error in setNavigationMenu", e)
            e.printStackTrace()
        }
    }
    /* setNavigationMenu END */

    override fun onNavigationItemSelected(position: Int, title: String) {
        // ここで選択されたアイテムに応じた画面遷移処理を行う
        when (position) {
            0 -> { // MainActivityへ移動
                val intent = Intent(this@ItemDetailActivity, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@ItemDetailActivity, FileManageActivity::class.java)
                startActivity(intent)
                finish()
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@ItemDetailActivity, SettingsActivity::class.java)
                startActivity(intent)
                finish()
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@ItemDetailActivity, AboutActivity::class.java)
                startActivity(intent)

            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@ItemDetailActivity, "未対応のメニュー項目: ${title}", Toast.LENGTH_SHORT).show()
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
            val opName = operationType?.name ?: "不明な操作"
            val success = (resultCode == ToshibaTecSdk.OPOS_SUCCESS)

            when(operationType) {
                SdkManager.SdkOperationType.OPEN_DRIVER -> {
                    if (success) {
                        Timber.i( "Driver opened successfully via onGenericSdkResult.")
                        // openDriver成功時の追加処理 (onDriverStatusChangedでも通知されるはず)
                    } else {
                        Timber.e( "Failed to open driver via onGenericSdkResult. Code: $resultCode")
                        MainActivity.showErrorDialog(this, "リーダーの準備に失敗しました。", resultCode)
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

    private fun loadAndDisplayItemDetails() {
        val idFromIntent = intent.getStringExtra(EXTRA_ID)

        if (idFromIntent.isNullOrEmpty()) {
            Timber.e( "ID not provided in Intent.")
            MainActivity.showErrorDialog(this, getString(R.string.error_message_id_missing),ErrNo.ERROR_NO_INTENT_EXTRA)
            finish()
            return
        }

        val items: MutableList<MutableMap<String, Any?>> = try {
            val tableName = DatabaseContract.TableName.TABLE_NAME_MASTER
            val selection = "${DatabaseContract.MasterColumn.ID.getColumnName(this)} = ?"
            val selectionArgs = arrayOf(idFromIntent)
            dbHelper.getItems(
                tableName = tableName,
                columns = null,
                selection = selection,
                selectionArgs = selectionArgs,
                orderBy = null
            )
        } catch (e: Exception) {
            Timber.e( "Database query failed for EPC: $idFromIntent", e)
            mutableListOf() // エラー時は空のリストを返す
        }

        val itemDetails: Map<String, Any?>? // 取得したアイテムを格納する変数

        if (items.isNotEmpty()) {
            if (items.size > 1) {
                // idはユニークなはずなので、複数返ってくる場合はデータ不整合の可能性
                Timber.w("Multiple items found for unique Product_EPC: $idFromIntent. Using the first one.")
            }
            itemDetails = items[0] // 最初のアイテムを使用
        } else {
            itemDetails = null // アイテムが見つからなかった場合
        }

        if (itemDetails == null) {
            Timber.w( "No item details found in DB for Stock EPC: $idFromIntent")
            MainActivity.showErrorDialog(this, getString(R.string.error_message_item_not_found, idFromIntent), ErrNo.ERROR_ITEM_NOT_FOUND)
            finish()
            return
        }

        // データをTextViewにセット (以降のロジックは変更なし)
        val defaultNa = getString(R.string.default_not_available)

        tvBarcodeNo.text = itemDetails[getString(DatabaseContract.MasterColumn.BARCODE_NO.columnNameResId)]?.toString() ?: defaultNa
        tvStockDate.text = itemDetails[getString(DatabaseContract.MasterColumn.STOCK_DATE.columnNameResId)]?.toString() ?: defaultNa
        tvOrderLocation.text = itemDetails[getString(DatabaseContract.MasterColumn.LOCATION.columnNameResId)]?.toString() ?: defaultNa
        tvProductName.text = itemDetails[getString(DatabaseContract.MasterColumn.PRODUCT_NAME.columnNameResId)]?.toString() ?: defaultNa
        tvBookInventory.text = itemDetails[getString(DatabaseContract.MasterColumn.BOOK_INVENTORY.columnNameResId)]?.toString() ?: defaultNa
        tvPhysicalInventory.text = itemDetails[getString(DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.columnNameResId)]?.toString() ?: defaultNa
        tvProductEpc.text = itemDetails[getString(DatabaseContract.MasterColumn.PRODUCT_EPC.columnNameResId)]?.toString() ?: defaultNa
    }
}