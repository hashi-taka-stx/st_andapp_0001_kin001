package jp.co.softtex.st_andapp_0001_kin001

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.widget.AdapterView
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import android.provider.Settings
import android.view.View
import android.widget.TextView

// ログ(Timber)
import timber.log.Timber

// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.toshibatec.model.TagPack

class MainActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {

    private lateinit var itemMenu: ListView
    private lateinit var itemMenuAdapter: SimpleAdapter
    private lateinit var emptyMenu: TextView
    var displayLocationList: MutableList<Map<String, Any>> = mutableListOf()

    /* ナビゲーションバー */
    private lateinit var navigationHelper: NavigationHelper // ヘルパーのインスタンス

    /* データベースヘルパー */
    private lateinit var dbHelper: DatabaseHelper

    /* MainActivityのコンテキスト */
    private lateinit var context: Context

    /** リストデータ */
    private var _from : Array<String> = arrayOf()
    private var _to : IntArray = intArrayOf()

    private var sdkFeaturesInitialized = false // SDK関連機能が初期化されたかを示すフラグ
    private var fileManageResult = false // データ更新フラグ

    companion object {
        /* MainActivityのリストデータ */
        @JvmStatic
        var inventory_progress_list: MutableList<MutableMap<String, Any>> = mutableListOf()

        /* BLEデバイスリスト */

        val TAG = "MainActivity"
        const val PERMISSION_REQUEST_CODE = 600
        const val REQUEST_CODE_FILE_MANAGE = 1001

        /**
         * @brief private fun showErrorDialog
         *
         * @details ダイアログを表示する
         * @param[in] mycontext コンテキスト
         * @param[in] message エラーメッセージ
         * @param[in] ercd エラーコード
         *
         * @return
         * @author 橋本隆宏(HASHIMOTO Takahiro)
         * @date 2025-04-07 : 作成
         */
        @JvmStatic
        fun showErrorDialog(context:Context, message: String, ercd: Int) {
            var message = message+ "エラーコード:" + ercd.toString()
            AlertDialog.Builder(context)
                .setTitle("エラー")
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
        /* showErrorDialog END */
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this@MainActivity
        Timber.i( "onCreate.")
        try {
            // SdkManager のコア初期化は Application クラスで行われている前提
            if (!MyApplication.isSdkManagerSuccessfullyInitialized) {
                Timber.e("SdkManager was not initialized successfully in Application class. App functionality might be limited.")
                showErrorDialog(this, getString(R.string.dialog_SDK_initialize_failure), ErrNo.ERROR_ON_INITIALIZE)
                finish()
                return // 初期化失敗時は以降の処理を中断
            } else {
                Timber.i("SdkManager core should be initialized. Proceeding with MainActivity setup.")
            }

            initPermissions() // パーミッション要求を先に行う

            setView()
            initDatabase()
            initListView()

        } catch (e: Exception) {
            Timber.e("Exception Error in onCreate", e)
            // クリティカルなエラーの場合、ユーザーに通知して終了するなどの処理
            showErrorDialog(this, getString(R.string.main_activity_dialog_exception_error), ErrNo.ERROR_ON_CRREATE)
            // finish() // 必要に応じて終了させる
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_FILE_MANAGE) {
            if (resultCode == FileManageActivity.DATA_UPDATED_RESULT_CODE) {
                Timber.i("onActivityResult: Data was updated in FileManageActivity. Marking list for refresh.")
                fileManageResult = true
            }
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

            // データベースヘルパーが初期化されているか確認
            if (!::dbHelper.isInitialized) {
                initDatabase() // もし初期化されていなければ初期化
            }
            if(fileManageResult) {
                Timber.i("onResume: Refreshing list due to update from FileManageActivity.")
                initListView() // ListViewのデータを再読み込みしてアダプターを更新
                fileManageResult = false
            }

            if (!sdkFeaturesInitialized && hasAllRequiredPermissions()) {
                Timber.d("onResume: Permissions already granted and SDK not yet initialized. Initializing SDK features.")
                initializeSdkRelatedFeatures()
            } else if (sdkFeaturesInitialized) {
                // SDK機能が初期化済みなら、モニタリング再開やUI更新など
                SdkManager.addListener(this) // 再度フォアグラウンドになるのでリスナー再登録
                SdkManager.startMonitoring()
                // UI更新
                if (::navigationHelper.isInitialized) {
                    navigationHelper.updateConnectionStatus(SdkManager.isConnected())
                    if(SdkManager.isConnected()) SdkManager.fetchBatteryLevel()
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
        Timber.i("onDestroy: Finished")
        // アプリ終了時に SdkManager のシャットダウン処理を行う場合はここで
        // SdkManager.shutdown()
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
            Timber.d("onGenericSdkResult: OpType=$operationType, Result=$resultCode, ExtResult=$resultCodeExtended")
            val opName = operationType?.name ?: getString(R.string.message_error_unknown_operation)
            val success = (resultCode == ToshibaTecSdk.OPOS_SUCCESS)

            when(operationType) {
                SdkManager.SdkOperationType.OPEN_DRIVER -> {
                    if (success) {
                        Timber.i("Driver opened successfully via onGenericSdkResult.")
                        // openDriver成功時の追加処理 (onDriverStatusChangedでも通知されるはず)
                    } else {
                        Timber.e("Failed to open driver via onGenericSdkResult. Code: $resultCode")
                        showErrorDialog(this, getString(R.string.message_error_sdk_open_error), resultCode)
                    }
                }
                SdkManager.SdkOperationType.START_READ_TAGS -> {
                    // このActivityでタグ読み取りを開始・停止することはない想定
                }
                SdkManager.SdkOperationType.STOP_READ_TAGS -> {
                    // このActivityでタグ読み取りを開始・停止することはない想定
                }
                else -> {
                    Timber.d("Generic SDK result for $opName: ${if (success) "Success" else "Failed (Code:$resultCode)"}")
                }
            }
        }
    }

    override fun onDeviceDiscoveryUpdate(devices: List<String>, isScanComplete: Boolean) {
    }
    // --- ここまで Listener の実装 ---

    private fun initListView() {

        emptyMenu = findViewById(R.id.layout_activity_main_textfview_empty_list)
        emptyMenu.visibility = View.GONE
        emptyMenu.text = getString(R.string.layout_activity_main_textfview_empty_list_text,getString(R.string.activity_file_manage_title))

        /* DBからデータを取得する */
        val key_location_name = DatabaseContract.LocationColumn.LOCATION.getColumnName(this)

        // _from と _to が setView で初期化されていることを確認
        if (_from.isEmpty() || _to.isEmpty()) {
            Timber.e("_from or _to is not initialized. Make sure setView() is called before initListView() if they depend on it.")
            _from = DatabaseContract.LocationColumn.from(this)
            val toArray = DatabaseContract.LocationColumn.to()
            if (toArray == null || toArray.isEmpty()) {
                _to = intArrayOf(android.R.id.text1)
            } else {
                _to = toArray
            }
        }

        displayLocationList = createLocationList(arrayOf(key_location_name))
        if (!::itemMenu.isInitialized) { // itemMenuが初期化されていなければfindViewById
            itemMenu = findViewById<ListView>(R.id.layout_activity_main_listview_itemMenu)
        }

        // アダプターが既に存在すれば新しいデータで更新、なければ新規作成
        if (::itemMenuAdapter.isInitialized) {
            itemMenuAdapter.notifyDataSetChanged()
            itemMenuAdapter = SimpleAdapter(this@MainActivity, displayLocationList, R.layout.row_location, _from, _to)
            itemMenu.adapter = itemMenuAdapter
        } else {
            itemMenuAdapter = SimpleAdapter(this@MainActivity, displayLocationList, R.layout.row_location, _from, _to)
            itemMenu.adapter = itemMenuAdapter
        }

        // 表示するアイテムが無い場合、空であることを表示
        if(displayLocationList.size == 0) {
            emptyMenu.visibility = View.VISIBLE
        }

        // ListViewのアイテムクリックリスナーを設定
        itemMenu.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
            // タップされたアイテムのデータを取得
            val selectedItem = displayLocationList[position] // Companion object の _itemList を参照

            // "location"キーで値を取得 (createLocationListで設定したキー名と合わせる)
            var locationValue = selectedItem[key_location_name]?.toString()

            if (locationValue == null || locationValue == getString(R.string.layout_row_location_default)) {
                locationValue = ""
            }
            Timber.i("Selected location: $locationValue")

            // ProductListActivityに遷移するためのIntentを作成
            val intent = Intent(this@MainActivity, ProductListActivity::class.java)

            // Intentにlocationの値をセット
            intent.putExtra(ProductListActivity.EXTRA_LOCATION_VALUE, locationValue)

            startActivity(intent)
        }
    }

    private fun initDatabase() {
        //DatabaseHelperクラスをインスタンス化
        dbHelper = DatabaseHelper(this)
    }
    /* initDatabase END */

    private fun initPermissions() {
        /* 権限取得 */
        val requiredPermissions: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            requiredPermissions = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        val distinctPermissions = requiredPermissions.distinct().toTypedArray()

        if (!hasPermissions(distinctPermissions)) {
            ActivityCompat.requestPermissions(this, distinctPermissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    private fun setView() {
        setContentView(R.layout.activity_main)

        // ListView関連のデータ設定
        _from = DatabaseContract.LocationColumn.from(this)
        val toArray = DatabaseContract.LocationColumn.to()
        if (toArray == null || toArray.isEmpty()) {
            Timber.e("DatabaseContract.LocationColumn.LOCATION.to() returned null or empty. Check implementation.")
            // エラー処理またはデフォルト値を設定
            _to = intArrayOf(R.id.layout_row_location)
        } else {
            _to = toArray
        }

        // NavigationHelperのセットアップ
        setNavigationMenu()
    }
    /* setView END */

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
                screenTitle = getString(R.string.activity_main_title), // Activityごとのタイトル
                showBackButton = false,
                customBackButtonAction = {
                    finish()
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_main_hint))

            // ナビゲーションアイテム選択時のリスナーを設定
            navigationHelper.setNavigationItemSelectedListener(this)
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
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                startActivity(intent)
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@MainActivity, FileManageActivity::class.java)
                startActivityForResult(intent, REQUEST_CODE_FILE_MANAGE)
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@MainActivity, AboutActivity::class.java)
                startActivity(intent)

            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@MainActivity, getString(R.string.navigation_drawer_menulist_not_assigned,title), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createLocationList(columns: Array<String>): MutableList<Map<String, Any>> {
        val default_location = DatabaseContract.get_location_default(this)
        Timber.d("createLocationList: default_location value is: '$default_location'")
        val key_location = DatabaseContract.LocationColumn.LOCATION.getColumnName(this)
        val key_location_array = arrayOf(key_location)
        var locationList: MutableList<MutableMap<String, Any?>> = mutableListOf()
        val viewList: MutableList<Map<String, Any>> = mutableListOf()
        try {
            /* データベースから置き場リストを取得 */
            locationList = dbHelper.getItems(
                DatabaseContract.TableName.TABLE_NAME_LOCATION,
                key_location_array,
                null,
                null,
                "$key_location ASC")
            Timber.d("createLocationList: Retrieved locationList from DB. Size: ${locationList.size}")
            if( locationList.isEmpty() ) {
                Timber.d("database is Empty")
            }

            // locationList からviewListへ値をセット
            // 置き場のリストをセットする
            for (index in locationList.indices){
                val viewItem = mutableMapOf<String, Any>()
                val item = locationList[index]
                val locationValueFromDb = item[key_location]?.toString()
                if (locationValueFromDb == "") {
                    viewItem[key_location] = default_location
                } else if (locationValueFromDb != null) {
                    viewItem[key_location] = locationValueFromDb
                } else { // locationValueFromDb is null
                    viewItem[key_location] = default_location
               }
                viewList.add(viewItem)
            }
        } catch (e: Exception) {
            // getItemsでエラーが発生した場合
            Timber.e("Error in createItemList", e)
            e.printStackTrace()
            showErrorDialog(this,getString(R.string.main_activity_database_error),ErrNo.DB_ERROR)
            finish()
        }
        for (index in viewList.indices){
            Timber.v("MainActivity createItemList: viewList[$index] = ${viewList[index]}")
        }
        return viewList
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allPermissionsGranted = true
            if (grantResults.isEmpty()) {
                allPermissionsGranted = false
            } else {
                for (grantResult in grantResults) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        allPermissionsGranted = false
                        break
                    }
                }
            }

            if (allPermissionsGranted) {
                if (!sdkFeaturesInitialized) { // まだ初期化されていなければ初期化
                    initializeSdkRelatedFeatures()
                } else {
                    // 既に初期化済みだが、何らかの理由で再度パーミッション要求があった場合
                    Timber.d("onRequestPermissionsResult: Permissions granted, SDK features were already initialized.")
                }
            } else {
                sdkFeaturesInitialized = false // 権限がなければ初期化できない
                Timber.w("onRequestPermissionsResult: Not all requested permissions were granted.")
                // ユーザーに権限が必要である旨を再度通知するか、アプリの機能を制限する
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.main_activity_dialog_permission_denied_title))
                    .setMessage(getString(R.string.main_activity_dialog_permission_denied))
                    .setPositiveButton(getString(R.string.main_activity_dialog_permission_denied_button_goto)) { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.main_activity_dialog_permission_denied_button_close)) { _, _ ->

                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun initializeSdkRelatedFeatures() {
        if (sdkFeaturesInitialized) {
            Timber.d("initializeSdkRelatedFeatures: Already initialized.")
            return
        }
        Timber.i("Initializing SDK related features...")
        val deviceName = SdkManager.getDeviceName()

        // ドライバがまだ開かれていなければ開く
        if (!SdkManager.isDriverOpened()) {
            if( deviceName.isEmpty() ) {
                Timber.e("deviceName is empty")
                showErrorDialog(this, getString(R.string.message_error_no_device_name), ErrNo.ERROR_DEVICE_NAME)
                finish()
            }
            Timber.d("Attempting to open driver for ${deviceName}")
            SdkManager.openDriver(deviceName)
        } else {
            // 既にドライバが開いている場合
            Timber.d("Driver is already open for ${SdkManager.getOpenedDeviceName()}.")
            onDriverStatusChanged(true, SdkManager.getOpenedDeviceName())
        }
        SdkManager.addListener(this) // リスナー登録
        SdkManager.startMonitoring() // バッテリーレベルなどの監視開始

        // NavigationHelperの更新もここで行う
        if (::navigationHelper.isInitialized) {
            val connected = SdkManager.isConnected()
            navigationHelper.updateConnectionStatus(connected)
            if (connected) SdkManager.fetchBatteryLevel()
        }
        sdkFeaturesInitialized = true // 初期化完了フラグを立てる
        Timber.i("SDK related features initialization complete.")
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val permissionsToCheck: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (S) 以降
            permissionsToCheck = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            // Android 11 (R) 以前
            permissionsToCheck = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        for (permission in permissionsToCheck.distinct()) { // 重複を避ける
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Timber.w("Permission not granted: $permission")
                return false
            }
        }
        Timber.d("All required permissions are granted.")
        return true
    }
}