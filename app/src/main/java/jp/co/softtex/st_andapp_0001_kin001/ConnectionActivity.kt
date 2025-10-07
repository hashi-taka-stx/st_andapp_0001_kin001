package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import jp.co.softtex.st_andapp_0001_kin001.MainActivity.Companion.showErrorDialog


// RfidStatusHelper とその関連クラス
import jp.co.softtex.st_andapp_0001_kin001.MakeInventoryActivity.Companion.EXTRA_KEY_LOCATION
import jp.co.softtex.st_andapp_0001_kin001.MakeInventoryActivity.Companion.EXTRA_KEY_PRODUCT_EPC
import jp.co.toshibatec.TecRfidSuite
import jp.co.toshibatec.model.TagPack
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {
    private lateinit var mainHandler: Handler
    private var scanRunnable: Runnable? = null
    private val isScanningActive = AtomicBoolean(false) // スキャン実行中フラグ
    private var isConnectButtonClicked = false
    private val SCAN_INTERVAL_MS = 2000L // 2秒間隔

    private lateinit var macAddressEditText: EditText
    private lateinit var macAddressListView: ListView
    private lateinit var connectButton: Button
    private lateinit var buttonReadQR: Button
    private lateinit var progressBar: ProgressBar // レイアウトID: @+id/progressBar

    private lateinit var macAddressAdapter: ArrayAdapter<String>
    private val availableMacAddresses = ArrayList<String>() // MACアドレス文字列のみを保持

    private lateinit var navigationHelper: NavigationHelper

    private var targetProductEpcFilter: String? = null
    private var intentLocation: String? = null

    companion object {
        private const val TAG = "ConnectionActivity"
        private val MAC_ADDRESS_PATTERN = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        private const val QR_CODE_SCANNER_REQUEST_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i( "onCreate.")
        // Intentから渡された値を取得
        intentLocation = intent.getStringExtra(EXTRA_KEY_LOCATION)
        targetProductEpcFilter = intent.getStringExtra(EXTRA_KEY_PRODUCT_EPC)
        Timber.d( "Intent: Location=$intentLocation, ProductEpc=$targetProductEpcFilter")
        mainHandler = Handler(Looper.getMainLooper()) // Handlerを初期化
        try {
            // SdkManager のコア初期化は Application クラスで行われている前提
            if (!MyApplication.isSdkManagerSuccessfullyInitialized) {
                Timber.e( "SdkManager was not initialized successfully in Application class. App functionality might be limited.")
                Toast.makeText(this, getString(R.string.dialog_SDK_initialize_failure), Toast.LENGTH_LONG).show()
                finish()
                return // 初期化失敗時は以降の処理を中断
            }
            Timber.i( "SdkManager core should be initialized.")


            // Viewの初期化とNavigationHelperのセットアップ
            setView()

            // 利用可能なBluetoothデバイスリストの取得 (MACアドレスのみ)
            loadAvailableBluetoothDevices()

        } catch (e: Exception) {
            Timber.e("Exception Error in onCreate", e)
            // クリティカルなエラーの場合、ユーザーに通知して終了するなどの処理
            showErrorDialog(this, getString(R.string.main_activity_dialog_exception_error), ErrNo.ERROR_ON_CRREATE)
            finish()
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
            updateUiBasedOnConnectionStatus(currentStatus, currentDeviceAddress)

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
        SdkManager.removeListener(this) // ★これが呼ばれているか
        SdkManager.stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicScan() //念のためもう一度停止
        mainHandler.removeCallbacksAndMessages(null) // Handlerのキューをクリア
        SdkManager.removeListener(this)
        Timber.d( "ConnectionActivity destroyed.")
    }

    // --- SdkManagerListener の実装 ---
    override fun onDriverStatusChanged(isOpened: Boolean, deviceName: String?) {
        progressBar.visibility = View.GONE // ドライバ操作が終わったらプログレス非表示
        if (isOpened) {
            loadAvailableBluetoothDevices(true) // 初回スキャンとしてフラグを立てる (リストをクリアするため)
            startPeriodicScan() // ドライバが開いたら定期スキャンも開始/再開
        } else {
            availableMacAddresses.clear()
            macAddressAdapter.notifyDataSetChanged()
            stopPeriodicScan() // ドライバが閉じたらスキャンも停止
        }
        // ドライバの状態が変わったので、ボタンの状態も更新
        updateUiBasedOnConnectionStatus(SdkManager.getCurrentConnectionStatus(), SdkManager.getConnectedDeviceAddress())
    }

    override fun onConnectionStatusChanged(status: SdkManager.ConnectionStatus, deviceAddress: String?) {
        updateUiBasedOnConnectionStatus(status, deviceAddress) // UI更新を先に

        if (status == SdkManager.ConnectionStatus.CONNECTED) {
            progressBar.visibility = View.GONE // 接続できたので非表示
            stopPeriodicScan() // 接続したら定期スキャンを停止
            Timber.i( "Device connected: $deviceAddress. Stopping periodic scan.")

            if (isConnectButtonClicked) {
                isConnectButtonClicked = false // フラグをリセット
                saveSelectedString(SettingsActivity.PREFS_NAME, (deviceAddress as String), SettingsActivity.KEY_LAST_CONNECTED_MAC)
                // MakeInventoryActivity に遷移
                Timber.i( "Navigating to MakeInventoryActivity.")
                val keyLocation = MakeInventoryActivity.EXTRA_KEY_LOCATION
                val keyProductEpc = MakeInventoryActivity.EXTRA_KEY_PRODUCT_EPC
                val intent = Intent(this, MakeInventoryActivity::class.java)
                intent.putExtra(keyLocation, intentLocation)
                intent.putExtra(keyProductEpc, targetProductEpcFilter) // product_epc を null で渡す

                startActivity(intent)
            }


        } else if (status == SdkManager.ConnectionStatus.DISCONNECTED || status == SdkManager.ConnectionStatus.ERROR) {
            Timber.i( "Device disconnected or connection error. Address: $deviceAddress")
            progressBar.visibility = View.GONE // 接続試行が終わったので非表示
            if (SdkManager.isDriverOpened()) {
                startPeriodicScan() // 切断されたら、ドライバが開いていれば定期スリーンを再開
                Timber.i( "Attempting to restart periodic scan as device is disconnected and driver is open.")
            }
        } else if (status == SdkManager.ConnectionStatus.CONNECTING) {
            Timber.d( "Device is connecting to $deviceAddress...")
            progressBar.visibility = View.VISIBLE // 接続試行中を表示
        } else if (status == SdkManager.ConnectionStatus.DISCONNECTING) {
            Timber.d( "Device is disconnecting from $deviceAddress...")
            progressBar.visibility = View.VISIBLE // 切断処理中を表示
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

    override fun onFirmwareVersionChanged(fullVersion: String?, parsedVersion: String?, powerType: String?, isSuccess: Boolean, needsInitFileRecreation: Boolean) {
    }

    override fun onErrorOccurred(
        operation: String,
        errorCode: Int,
        extendedCode: Int?,
        message: String,
        isConnectionLostError: Boolean
    ) {
        Timber.e( "onErrorOccurred: Op='$operation', Code=$errorCode, ExtCode=$extendedCode, Msg='$message', ConnectionLost=$isConnectionLostError")
        progressBar.visibility = View.GONE
        // エラー後、UIの状態を更新
        updateUiBasedOnConnectionStatus(SdkManager.getCurrentConnectionStatus(), SdkManager.getConnectedDeviceAddress())
    }

    override fun onTagDataReceived(tagPacks: Map<String, TagPack>) {
    }

    override fun onGenericSdkResult(operationType: SdkManager.SdkOperationType?, resultCode: Int, resultCodeExtended: Int) {
        progressBar.visibility = View.GONE
        runOnUiThread {
            Timber.d( "onGenericSdkResult: OpType=$operationType, Result=$resultCode, ExtResult=$resultCodeExtended")
            val opName = operationType?.name ?: getString(R.string.message_error_unknown_operation)
            val success = (resultCode == TecRfidSuite.OPOS_SUCCESS)

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
        Timber.d( "onDeviceDiscoveryUpdate: Found ${devices.size} devices. Scan complete: $isScanComplete. Devices: $devices")
        runOnUiThread { // UI更新はメインスレッドで
            progressBar.visibility = if (isScanComplete) View.GONE else View.VISIBLE // スキャン中も考慮

            availableMacAddresses.clear()
            if (devices.isNotEmpty()) {
                availableMacAddresses.addAll(devices)
            }
            macAddressAdapter.notifyDataSetChanged()
        }
    }
    // --- ここまで Listener の実装 ---

    private fun setView() {
        setContentView(R.layout.activity_connection)
        var lastConnectedMacAddress: String? = null

        // UI要素の初期化
        macAddressEditText = findViewById(R.id.macAddressEditText)
        macAddressListView = findViewById(R.id.macAddressListView)
        buttonReadQR = findViewById(R.id.button_readQR)
        connectButton = findViewById(R.id.connectButton)
        progressBar = findViewById(R.id.progressBar)

        setNavigationMenu()

        // MACアドレスリスト用のアダプター初期化 (MACアドレス文字列のみ)
        macAddressAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, availableMacAddresses)
        macAddressListView.adapter = macAddressAdapter
        lastConnectedMacAddress = loadSelectedString(SettingsActivity.PREFS_NAME, SettingsActivity.KEY_LAST_CONNECTED_MAC)
        if(lastConnectedMacAddress != null){
            macAddressEditText.setText(lastConnectedMacAddress)
        } else {
            val defaultMacAddress = loadSelectedString(SettingsActivity.PREFS_NAME, SettingsActivity.KEY_DEFAULT_HST)
            if(defaultMacAddress != null){
                macAddressEditText.setText(defaultMacAddress)
            }
        }
        macAddressListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selectedMac = macAddressAdapter.getItem(position)
            macAddressEditText.setText(selectedMac)
        }

        // MACアドレス入力欄のテキスト変更リスナー
        macAddressEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateConnectButtonState()
            }
        })

        setupReadQRButton()

        // 接続/切断ボタンのクリックリスナー
        connectButton.setOnClickListener {
            val macAddress = macAddressEditText.text.toString().trim()
            if (!isValidMacAddressFormat(macAddress)) {
                return@setOnClickListener
            }

            val currentStatus = SdkManager.getCurrentConnectionStatus()
            val connectedDeviceAddress = SdkManager.getConnectedDeviceAddress()

            if (currentStatus == SdkManager.ConnectionStatus.CONNECTED) {
                Timber.d( "Attempting to disconnect from $connectedDeviceAddress")
                SdkManager.disconnect()
            } else if (currentStatus == SdkManager.ConnectionStatus.DISCONNECTED ||
                currentStatus == SdkManager.ConnectionStatus.ERROR) {
                Timber.d( "Attempting to connect to $macAddress")
                val targetDeviceName = SdkManager.getOpenedDeviceName() ?: SdkManager.getDeviceName()
                isConnectButtonClicked = true
                progressBar.visibility = View.VISIBLE // 接続試行中を表示
                SdkManager.connect(macAddress, targetDeviceName) // 接続試行
            } else { // CONNECTING or DISCONNECTING
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
                screenTitle = getString(R.string.activity_connection_title),
                showBackButton = true,
                customBackButtonAction = {
                    finish() // 戻るボタンでActivityを終了
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_connection_hint))

            navigationHelper.setNavigationItemSelectedListener(this) // リスナーを自身に設定

        }catch (e: Exception) {
            Timber.e( "Exception Error in setNavigationMenu", e)
            e.printStackTrace()
        }
    }
    /* setNavigationMenu END */

    private fun loadSelectedString( prefs_name: String, key: String ): String? {
        val prefs = getSharedPreferences(prefs_name, Context.MODE_PRIVATE)
        return prefs.getString(key, null )
    }

    private fun saveSelectedString( prefs_name: String, value: String, key: String ) {
        val prefs = getSharedPreferences(prefs_name, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(key, value)
        editor.apply()
        Timber.d( "Saved preference: $key = $value")
    }

    private fun setupReadQRButton() {
        buttonReadQR.setOnClickListener {
            Timber.d( "Read QR button clicked.")
            try {
                // CameraActivity (QRスキャナー) を起動
                val intent = Intent(this, CameraActivity::class.java)
                startActivityForResult(intent, QR_CODE_SCANNER_REQUEST_CODE)
            } catch (e: Exception) {
                Timber.e( "Error starting CameraActivity for QR scan", e)
                Toast.makeText(this, getString(R.string.camera_activity_error_start_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == QR_CODE_SCANNER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val scanResult = data.getStringExtra(CameraActivity.EXTRA_SCAN_RESULT)
                if (!scanResult.isNullOrEmpty()) {
                    Timber.d( "QR Scan result: $scanResult")

                    // MACアドレスのフォーマットを試みる (":"区切りに統一するなど)
                    val formattedMacAddress = formatMacAddressForDisplay(scanResult)

                    if (formattedMacAddress != null && isValidMacAddressFormat(formattedMacAddress)) {
                        macAddressEditText.setText(formattedMacAddress) // EditTextに設定

                        // スキャンされたMACアドレスが既存のリストにない場合は追加
                        if (!availableMacAddresses.contains(formattedMacAddress)) {
                            availableMacAddresses.add(formattedMacAddress)
                            macAddressAdapter.notifyDataSetChanged() // アダプターに通知してListViewを更新
                            Timber.i( "New MAC address '$formattedMacAddress' added to list from QR scan.")
                        } else {
                            Timber.i( "MAC address '$formattedMacAddress' from QR scan already in list.")
                        }
                        // リスト内で該当のMACアドレスを選択状態にする (任意)
                        val position = availableMacAddresses.indexOf(formattedMacAddress)
                        if (position != -1) {
                            macAddressListView.setSelection(position)
                            macAddressListView.smoothScrollToPosition(position)
                        }

                    } else {
                        Timber.w( "Invalid MAC address format from QR scan: $scanResult (Formatted: $formattedMacAddress)")
                        Toast.makeText(this, getString(R.string.QR_scan_result_invalid,scanResult), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Timber.i( "QR Scan result was empty.")
                    Toast.makeText(this, getString(R.string.QR_scan_result_empty), Toast.LENGTH_SHORT).show()
                }
            } else if (resultCode == RESULT_CANCELED) {
                Timber.i( "QR Scan was cancelled.")
            } else {
                Timber.w( "QR Scan failed with resultCode: $resultCode")
                Toast.makeText(this, getString(R.string.QR_scan_result_failure), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatMacAddressForDisplay(rawMacAddress: String): String? {
        // ハイフンをコロンに置換し、大文字に統一
        val standardized = rawMacAddress.replace('-', ':').toUpperCase()
        return if (MAC_ADDRESS_PATTERN.matches(standardized)) {
            standardized
        } else {

            if (rawMacAddress.length == 12 && rawMacAddress.all { it.isLetterOrDigit() }) {
                // 例: "001122AABBCC" -> "00:11:22:AA:BB:CC"
                return rawMacAddress.chunked(2).joinToString(":")
            }
            standardized // または null を返して厳密にチェック
        }
    }

    private fun loadAvailableBluetoothDevices(isInitialScan: Boolean = false) {
        if (!SdkManager.isDriverOpened()) {
            Timber.w( "Driver is not open. Cannot load Bluetooth device list.")
            return
        }

        // 接続中は新しいデバイスリストの取得をしない
        if (SdkManager.isConnected()) {
            return
        }

        // SdkManager.getBluetoothDeviceList() の引数に渡す一時的なリスト
        val scannedDevices = ArrayList<String>()
        val resultCode = SdkManager.getBluetoothDeviceList(scannedDevices)

        Timber.d( "SdkManager.getBluetoothDeviceList() called, result code (ercd): $resultCode")
        Timber.d( "Scanned devices this time (size: ${scannedDevices.size}):")
        for (deviceAddress in scannedDevices) {
            Timber.d( "  - $deviceAddress")
        }

        runOnUiThread {
            if (resultCode == 0) { // 成功
                var newDevicesAddedCount = 0
                if (isInitialScan) {
                    availableMacAddresses.clear() // 初回スキャン時はリストをクリアして新しい結果で置き換える
                    availableMacAddresses.addAll(scannedDevices)
                    newDevicesAddedCount = scannedDevices.size
                    if (scannedDevices.isNotEmpty()) {
                        Timber.i( "Initial scan: ${scannedDevices.size} devices loaded.")
                    } else {
                        Timber.i( "Initial scan: No devices found.")
                    }
                } else {
                    // 定期スキャンの場合：新しいデバイスのみを追加
                    for (deviceAddress in scannedDevices) {
                        if (!availableMacAddresses.contains(deviceAddress)) {
                            availableMacAddresses.add(deviceAddress)
                            newDevicesAddedCount++
                            Timber.i( "Periodic scan: New device added - $deviceAddress")
                        }
                    }
                }

                if (newDevicesAddedCount > 0 || isInitialScan) { // 新しいデバイスが追加されたか、初回スキャンならアダプターを更新
                    macAddressAdapter.notifyDataSetChanged()
                    Timber.d( "MAC Address list updated. Total devices: ${availableMacAddresses.size}")

                } else {
                    Timber.d( "Periodic scan: No new devices found.")
                }

            } else {
                Timber.e("SdkManager.getBluetoothDeviceList failed with error code: $resultCode")
            }
        }
    }


    private fun startPeriodicScan() {
        if (!SdkManager.isDriverOpened()) {
            Timber.w( "Cannot start periodic scan, driver is not open.")
            return
        }
        if (SdkManager.isConnected()) {
            Timber.i( "Device is connected. Periodic scan will not start.")
            return
        }

        if (isScanningActive.compareAndSet(false, true)) { // スキャンがアクティブでなければ開始
            Timber.i( "Starting periodic Bluetooth device scan every ${SCAN_INTERVAL_MS}ms.")
            scanRunnable = object : Runnable {
                override fun run() {
                    if (!SdkManager.isConnected() && SdkManager.isDriverOpened() && isScanningActive.get()) { // 接続中でなく、ドライバが有効で、スキャンがアクティブな場合のみ実行
                        loadAvailableBluetoothDevices(false) // isInitialScan = false で定期スキャン
                        // 次の実行をスケジュール
                        if (isScanningActive.get()) { // スキャンがまだアクティブなら再度スケジュール
                            mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                        }
                    } else {
                        Timber.i( "Periodic scan condition not met or stopped. DriverOpen: ${SdkManager.isDriverOpened()}, Connected: ${SdkManager.isConnected()}, ScanningActive: ${isScanningActive.get()}")
                        stopPeriodicScan() // 条件を満たさなくなったら明示的に停止
                    }
                }
            }
            mainHandler.post(scanRunnable!!) // 即時実行（初回の定期スキャン）
        } else {
            Timber.d( "Periodic scan is already active or failed to start.")
        }
    }

    private fun stopPeriodicScan() {
        if (isScanningActive.compareAndSet(true, false)) { // スキャンがアクティブなら停止
            Timber.i( "Stopping periodic Bluetooth device scan.")
            scanRunnable?.let { mainHandler.removeCallbacks(it) }
            scanRunnable = null
        } else {
            Timber.d( "Periodic scan is not active or already stopped.")
        }
    }

    private fun isValidMacAddressFormat(macAddress: String): Boolean {
        return MAC_ADDRESS_PATTERN.matches(macAddress)
    }

    private fun updateConnectButtonState() {
        val currentStatus = SdkManager.getCurrentConnectionStatus()
        val macAddress = macAddressEditText.text.toString().trim()
        val isMacValid = isValidMacAddressFormat(macAddress)
        val isDriverOpen = SdkManager.isDriverOpened()

        connectButton.isEnabled = isDriverOpen // ドライバが開いていないと何もできない

        if (!isDriverOpen) {
            connectButton.text = getString(R.string.button_unavailable)
            macAddressEditText.isEnabled = false
            macAddressListView.isEnabled = false
            return
        }

        macAddressEditText.isEnabled = true
        macAddressListView.isEnabled = true

        when (currentStatus) {
            SdkManager.ConnectionStatus.CONNECTED -> {
                connectButton.text = getString(R.string.button_disconnect)
                connectButton.isEnabled = true
            }
            SdkManager.ConnectionStatus.DISCONNECTED,
            SdkManager.ConnectionStatus.ERROR -> {
                connectButton.text = getString(R.string.button_connect)
                connectButton.isEnabled = isMacValid // 有効なMACアドレスがある場合のみ接続可能
            }
            SdkManager.ConnectionStatus.CONNECTING -> {
                connectButton.text = getString(R.string.button_connecting)
                connectButton.isEnabled = false // 処理中は無効
            }
            SdkManager.ConnectionStatus.DISCONNECTING -> {
                connectButton.text = getString(R.string.button_disconnecting)
                connectButton.isEnabled = false // 処理中は無効
            }
        }
    }

    private fun updateUiBasedOnConnectionStatus(status: SdkManager.ConnectionStatus, connectedDeviceAddr: String?) {
        if (::navigationHelper.isInitialized) {
            navigationHelper.updateConnectionStatus(status == SdkManager.ConnectionStatus.CONNECTED)
        }
        updateConnectButtonState()

        val enableInputFields = (status == SdkManager.ConnectionStatus.DISCONNECTED ||
                status == SdkManager.ConnectionStatus.ERROR) && SdkManager.isDriverOpened()
        macAddressEditText.isEnabled = enableInputFields
        // MACアドレスリストはドライバが開いていれば常に選択可能とするか、未接続時のみとするか
        macAddressListView.isEnabled = SdkManager.isDriverOpened()


        if (status == SdkManager.ConnectionStatus.CONNECTING || status == SdkManager.ConnectionStatus.DISCONNECTING) {
            progressBar.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.GONE
        }

        if (status == SdkManager.ConnectionStatus.CONNECTED && connectedDeviceAddr != null) {
            macAddressEditText.setText(connectedDeviceAddr) // 接続成功時にMACアドレスをEditTextに反映
        }
    }

    // --- NavigationHelper.NavigationItemSelectedListener の実装 ---
    override fun onNavigationItemSelected(position: Int, title: String) {
        // ここで選択されたアイテムに応じた画面遷移処理を行う
        when (position) {
            0 -> { // MainActivityへ移動
                val intent = Intent(this@ConnectionActivity, MainActivity::class.java)
                startActivity(intent)
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@ConnectionActivity, FileManageActivity::class.java)
                startActivity(intent)
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@ConnectionActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@ConnectionActivity, AboutActivity::class.java)
                startActivity(intent)
            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@ConnectionActivity, "未対応のメニュー項目: ${title}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}