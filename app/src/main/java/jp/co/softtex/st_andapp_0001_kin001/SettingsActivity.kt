package jp.co.softtex.st_andapp_0001_kin001

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.support.v4.provider.DocumentFile


// RfidStatusHelper とその関連クラス
import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk
import jp.co.toshibatec.callback.*
import jp.co.toshibatec.TecRfidSuite
import jp.co.toshibatec.model.TagPack
import timber.log.Timber

data class DeviceSettings(
    var defaultHST: String? = null,
    var scanPower: Int? = null,
    var qValue: Int? = null,
    var frequency: Int? = null,
    var sessionId: Int? = null,
    var triggerSwitch: Int? = null,
    var sleepMode: Int? = null,
    var modulationFormat: Int? = null,
    var miller: Int? = null,
    var readIdentifier: Int? = null,
    var flagAB: Int? = null,
    var usbCharge: Int? = null,
    var polarizedWave: Int? = null,
    var appLogLevel: Int? = null,
    var appLogRotateDays: Int? = null,
    var driverLogLevel: Int? = null,
    var readFolderUri: Uri? = null,
    var writeFolderUri: Uri? = null
)

class SettingsActivity : BasePaddedActivity(), SdkManagerListener, NavigationHelper.NavigationItemSelectedListener {
    data class SpinnerList( val displayNames: Array<String>, val displayValues: IntArray )
    class SpinnerList_defaultHST {
        val displayNames: MutableList<String> = mutableListOf()
        val displayValues: MutableList<String> = mutableListOf()
    }

    private lateinit var navigationHelper: NavigationHelper

    private val availableMacAddresses = ArrayList<String>() // MACアドレス文字列のみを保持

    // SharedPreferencesから読み込んだ初期値を保持する (変更比較用)
    private var initialDeviceSettings: DeviceSettings = DeviceSettings()
    private var grantedReadFolderUri: Uri? = null
    private var grantedWriteFolderUri: Uri? = null

    // リーダーへの設定変更に失敗した項目を記録するリスト
    private val failedSettingsTracker = mutableMapOf<String, String>() // Key: 設定項目名, Value: エラー理由など

    // 設定処理の進行状況を管理するためのキューやインデックス (逐次処理用)
    private var settingStep = 0
    private var settingsToApply = mutableListOf<Pair<String, () -> Unit>>() // 設定処理のキュー (名前, 実行関数)

    private var progressDialog: ProgressDialog? = null // 処理中ダイアログ
    private var shouldFinishAfterApply = false

    // Spinner
    private lateinit var spinnerdefaultHST: Spinner
    private lateinit var spinnerScanPower: Spinner
    private lateinit var spinnerQValue: Spinner
    private lateinit var spinnerFrequency: Spinner
    private lateinit var spinnerSessionid: Spinner
    private lateinit var spinnerTriggersw: Spinner
    private lateinit var spinnerSleepmode: Spinner
    private lateinit var spinnerModulationformat: Spinner
    private lateinit var spinnerMiller: Spinner
    private lateinit var spinnerflagAB: Spinner
    private lateinit var spinnerusbcharge: Spinner
    private lateinit var spinnerpolarizedwave: Spinner
    private lateinit var spinnerAppLogLevel: Spinner
    private lateinit var spinnerAppLogRotate: Spinner
    private lateinit var spinnerDriverLogLevel: Spinner

    // TextView
    private lateinit var textviewreadFolder: TextView
    private lateinit var textviewwriteFolder: TextView

    // Button
    private lateinit var buttonReadQR: Button
    private lateinit var buttonConnectHST: Button
    private lateinit var buttonReadfolderBrowse: Button
    private lateinit var buttonWritefolderBrowse: Button
    private lateinit var buttonShowLog: Button
    private lateinit var buttonApply: Button
    private lateinit var buttonReset: Button
    private lateinit var buttonBack: Button

    // EditText
    private lateinit var editTextReadIdentifier: EditText
    // 警告表示
    private lateinit var warningTextReadIdentifier: TextView

    // Spinnerのデータソース
    private val defaultHSTSpinnerList = SpinnerList_defaultHST()
    private lateinit var scanPowerSpinnerList: SpinnerList
    private lateinit var qValueSpinnerList: SpinnerList
    private lateinit var frequencySpinnerList: SpinnerList
    private lateinit var sessionidSpinnerList: SpinnerList
    private lateinit var triggerswSpinnerList: SpinnerList
    private lateinit var sleepmodeSpinnerList: SpinnerList
    private lateinit var modulationformatSpinnerList: SpinnerList
    private lateinit var millerSpinnerList: SpinnerList
    private lateinit var flagABSpinnerList: SpinnerList
    private lateinit var usbchargeSpinnerList: SpinnerList
    private lateinit var polarizedwaveSpinnerList: SpinnerList
    private lateinit var appLogLevelSpinnerList: SpinnerList
    private lateinit var appLogRotateSpinnerList: SpinnerList
    private lateinit var driverLogLevelSpinnerList: SpinnerList

    //詳細表示設定
    private lateinit var advancedSettingsToggleContainer: LinearLayout
    private lateinit var advancedSettingsItemsContainer: LinearLayout
    private lateinit var iconAdvancedSettingsToggle: ImageView
    private var isAdvancedSettingsVisible = false // 詳細設定の表示状態を管理

    //adapter
    private lateinit var defaultHSTSpinnerAdapter: ArrayAdapter<String>
    private var isFetchingBleList = AtomicBoolean(false)

    // SharedPreferences 用のキー
    companion object {
        private const val READ_FOLDER_REQUEST_CODE = 101
        private const val WRITE_FOLDER_REQUEST_CODE = 102
        private const val QR_CODE_SCANNER_REQUEST_CODE = 200

        private const val TAG = "SettingsActivity"

        const val PREFS_NAME = "SettingsPrefs"
        const val KEY_SCAN_POWER = "scanPowerValue"
        const val KEY_Q_VALUE = "qValue"
        const val KEY_FREQUENCY = "frequencyChannel"
        const val KEY_SESSION_ID = "sessionID"
        const val KEY_TRIGGER_SW = "triggerSWMode"
        const val KEY_SLEEP_MODE = "sleepModeValue"
        const val KEY_MODULATION_FORMAT = "modulationFormatValue"
        const val KEY_MILLER = "millerValue"
        const val KEY_READ_IDENTIFIER = "readIdentifierValue"
        const val KEY_FLAG_AB = "flagABValue"
        const val KEY_USB_CHARGE = "usbChargeValue"
        const val KEY_POLARIZED_WAVE = "polarizedWaveValue"

        // リーダー以外の設定項目キー
        const val KEY_DEFAULT_HST = "defaultHSTMacAddress"
        const val KEY_LAST_CONNECTED_MAC = "lastConnectedMacAddress"
        const val KEY_EXPORT_DIR_URI = "exportDirUri"
        const val KEY_IMPORT_DIR_URI = "importDirUri"
        const val KEY_FILE_NAME_BASE = "fileName"
        const val KEY_APP_LOG_LEVEL = "appLogLevel"
        const val KEY_APP_LOG_ROTATE_DAYS = "appLogRotateDays"
        const val KEY_DRIVER_LOG_LEVEL = "driverLogLevel"

        // 自動周波数リスト
        val autoFrequencyList:ArrayList<Int> = arrayListOf(
            ToshibaTecSdk.FrequencyLowChannelTypeCh05,
            ToshibaTecSdk.FrequencyLowChannelTypeCh11,
            ToshibaTecSdk.FrequencyLowChannelTypeCh17,
            ToshibaTecSdk.FrequencyLowChannelTypeCh23,
            ToshibaTecSdk.FrequencyLowChannelTypeCh24,
            ToshibaTecSdk.FrequencyLowChannelTypeCh25,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d( "onCreate.")
        try {
            // SdkManager のコア初期化は Application クラスで行われている前提
            if (!MyApplication.isSdkManagerSuccessfullyInitialized) {
                Timber.e("SdkManager was not initialized successfully in Application class. App functionality might be limited.")
                Toast.makeText(this, getString(R.string.dialog_SDK_initialize_failure), Toast.LENGTH_LONG).show()
                finish()
                return // 初期化失敗時は以降の処理を中断
            }

            // 1. Viewの初期化とNavigationHelperのセットアップ
            setView()

            // 2. Spinner/EditTextのデータソース読み込み (arrays_settings.xml から)
            loadAllSpinnerOptions()
            // loadAllEditTextOptions() // EditText は直接設定するため、このメソッドは実質不要

            // 3. SharedPreferencesから設定値を読み込み、initialDeviceSettings を初期化し、UIに反映
            loadAndApplyInitialSettings()

            // 4. UI要素にリスナーを設定
            setupAllListeners()
        } catch (e: Exception) {
            Timber.e( "onCreate: Exception", e)
            e.printStackTrace()
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
                if (!SdkManager.isScanningDevice()) {
                    SdkManager.startDeviceScan()
                } else {
                    Timber.d( "Device scan already in progress or not needed.")
                }
            }

            SdkManager.addListener(this) // リスナー追加
            SdkManager.startMonitoring() // モニタリング開始 (接続状態やバッテリーレベルの取得など)

            // UIの初期状態を SdkManager から取得して設定
            updateConnectButtonState(SdkManager.getCurrentConnectionStatus(), SdkManager.getDeviceName())

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
        SdkManager.removeListener(this)
        SdkManager.stopMonitoring()
    }

    // バックキーが押されたときの処理 (ドロワーが開いていれば閉じる)
    override fun onBackPressed() {
        if (::navigationHelper.isInitialized && navigationHelper.isDrawerOpen()) {
            navigationHelper.closeDrawer()
        } else {
            val currentSettings = getCurrentUiSettings()
            val settingsChanged = haveSettingsChanged(initialDeviceSettings,currentSettings)
            if (settingsChanged) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_confirm))
                    .setMessage(getString(R.string.settings_activity_dialog_cancel))
                    .setPositiveButton(getString(R.string.btn_txt_yes)) { _, _ ->
                        // 「はい」の場合: 設定を適用してから終了
                        dismissProgressDialog()
                        applySettings()
                        super.onBackPressed()
                    }
                    .setNegativeButton(getString(R.string.btn_txt_no)) { _, _ ->
                        // 「いいえ」の場合: 設定を保存せずに終了
                        super.onBackPressed()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // 設定変更がない場合は、通常の戻る処理
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
        if (::navigationHelper.isInitialized) {
            navigationHelper.onDestroy()
        }
        SdkManager.removeListener(this)
        Timber.i( "onDestroy: Finished")
    }

    private fun dismissProgressDialog() {
        progressDialog?.let {
            if (it.isShowing) {
                try {
                    it.dismiss()
                } catch (e: Exception) {
                    Timber.e(e, "Error dismissing progress dialog in dismissProgressDialog")
                }
            }
        }
        progressDialog = null // 参照を確実にクリア
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setView() {
        /* メイン画面のレイアウトを読み込む */
        setContentView(R.layout.activity_settings)

        // レイアウトファイルに追加したSpinnerのIDを指定
        spinnerdefaultHST = findViewById(R.id.spinner_defaulthst)
        spinnerScanPower = findViewById(R.id.spinner_scan_power)
        spinnerQValue = findViewById(R.id.spinner_q_value)
        spinnerFrequency = findViewById(R.id.spinner_frequency)
        spinnerSessionid = findViewById(R.id.spinner_sessionid)
        spinnerTriggersw = findViewById(R.id.spinner_triggersw)
        spinnerSleepmode = findViewById(R.id.spinner_sleepmode)
        spinnerModulationformat = findViewById(R.id.spinner_modulationFormat)
        spinnerMiller = findViewById(R.id.spinner_miller)
        spinnerflagAB = findViewById(R.id.spinner_flagAB)
        spinnerusbcharge = findViewById(R.id.spinner_usbcharge)
        spinnerpolarizedwave = findViewById(R.id.spinner_polarizedwave)
        spinnerAppLogLevel = findViewById(R.id.spinner_app_log_level)
        spinnerAppLogRotate = findViewById(R.id.spinner_app_log_rotate)
        spinnerDriverLogLevel = findViewById(R.id.spinner_driver_log_level)

        // レイアウトファイルに追加したTextViewのIDを指定
        textviewreadFolder = findViewById(R.id.textview_readfolder)
        textviewwriteFolder = findViewById(R.id.textview_witefolder)

        // レイアウトファイルに追加したButtonのIDを指定
        buttonReadQR = findViewById(R.id.button_readQR)
        buttonConnectHST = findViewById(R.id.button_connectHST)
        buttonReadfolderBrowse = findViewById(R.id.button_readfolder_browse)
        buttonWritefolderBrowse = findViewById(R.id.button_writefolder_browse)
        buttonShowLog = findViewById(R.id.button_showlog)
        buttonApply = findViewById(R.id.button_apply)
        buttonReset = findViewById(R.id.button_reset)
        buttonBack = findViewById(R.id.button_back)

        // レイアウトファイルに追加したEditTextのIDを指定
        editTextReadIdentifier = findViewById(R.id.edittext_read_identifier)
        // 警告表示テキストのIDを指定
        warningTextReadIdentifier = findViewById(R.id.read_identifier_warning_text)

        /* 詳細設定のトグルボタンの要素を取得 */
        advancedSettingsToggleContainer = findViewById(R.id.advanced_settings_toggle_container)
        advancedSettingsItemsContainer = findViewById(R.id.advanced_settings_items_container)
        iconAdvancedSettingsToggle = findViewById(R.id.icon_advanced_settings_toggle)

        // 初期状態を設定 (SharedPreferencesなどから読み込んでも良い)
        updateAdvancedSettingsVisibility()

        advancedSettingsToggleContainer.setOnClickListener {
            isAdvancedSettingsVisible = !isAdvancedSettingsVisible
            updateAdvancedSettingsVisibility()
        }
        /* ナビゲーションメニューの初期化 */
        setNavigationMenu()
    }

    private fun loadAllSpinnerOptions() {
        scanPowerSpinnerList = loadSpinnerOptionsFromResource(R.array.array_scanpower_display_names, R.array.array_scanpower_display_values)
        qValueSpinnerList = loadSpinnerOptionsFromResource(R.array.array_qvalue_display_names, R.array.array_qvalue_display_values)
        frequencySpinnerList = loadSpinnerOptionsFromResource(R.array.array_frequency_display_names, R.array.array_frequency_display_values)
        sessionidSpinnerList = loadSpinnerOptionsFromResource(R.array.array_sessionid_display_names, R.array.array_sessionid_display_values)
        triggerswSpinnerList = loadSpinnerOptionsFromResource(R.array.array_triggersw_display_names, R.array.array_triggersw_display_values)
        sleepmodeSpinnerList = loadSpinnerOptionsFromResource(R.array.array_sleepmode_display_names, R.array.array_sleepmode_display_values)
        modulationformatSpinnerList = loadSpinnerOptionsFromResource(R.array.array_modulationformat_display_names, R.array.array_modulationformat_display_values)
        millerSpinnerList = loadSpinnerOptionsFromResource(R.array.array_miller_display_names, R.array.array_miller_display_values)
        flagABSpinnerList = loadSpinnerOptionsFromResource(R.array.array_flagAB_display_names, R.array.array_flagAB_display_values)
        usbchargeSpinnerList = loadSpinnerOptionsFromResource(R.array.array_usbcharge_display_names, R.array.array_usbcharge_display_values)
        polarizedwaveSpinnerList = loadSpinnerOptionsFromResource(R.array.array_polarizedwave_display_names, R.array.array_polarizedwave_display_values)
        appLogLevelSpinnerList = loadSpinnerOptionsFromResource(R.array.array_app_log_level_display_names, R.array.array_app_log_level_display_values)
        appLogRotateSpinnerList = loadSpinnerOptionsFromResource(R.array.array_app_log_rotate_display_names, R.array.array_app_log_rotate_display_values)
        driverLogLevelSpinnerList = loadSpinnerOptionsFromResource(R.array.array_driver_log_level_display_names, R.array.array_driver_log_level_display_values)
    }

    private fun loadSpinnerOptionsFromResource(stringArrayId: Int, intArrayId: Int): SpinnerList {
        return try {
            val displayNames = resources.getStringArray(stringArrayId)
            val displayValues = resources.getIntArray(intArrayId)
            if (displayNames.size != displayValues.size) {
                Timber.e( "Spinner options array lengths do not match! stringArrayId: $stringArrayId, intArrayId: $intArrayId. XMLを確認してください。")
                SpinnerList(arrayOf("エラー: 不整合"), intArrayOf(-1)) // エラーを示すデータ
            } else {
                SpinnerList(displayNames, displayValues)
            }
        } catch (e: Exception) {
            Timber.e( "Error loading spinner options from XML (stringArrayId: $stringArrayId, intArrayId: $intArrayId)", e)
            Toast.makeText(this, "設定オプションの読み込みに失敗しました。", Toast.LENGTH_LONG).show()
            SpinnerList(arrayOf("エラー: 読込失敗"), intArrayOf(-1))
        }
    }

    private fun loadAndApplyInitialSettings() {
        Timber.d("loadAndApplyInitialSettings: --- Start Loading All Preferences ---")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scanPowerInitial = getResources().getInteger(R.integer.default_scanpower)
        val qValueInitial = getResources().getInteger(R.integer.default_qvalue)
        val frequencyInitial = getResources().getInteger(R.integer.default_frequency)
        val sessionidInitial = getResources().getInteger(R.integer.default_sessionid)
        val triggerswInitial = getResources().getInteger(R.integer.default_triggersw)
        val sleepmodeInitial = getResources().getInteger(R.integer.default_sleepmode)
        val modulationformatInitial = getResources().getInteger(R.integer.default_modulationformat)
        val millerInitial = getResources().getInteger(R.integer.default_miller)
        val readIdentifierInitial = getResources().getInteger(R.integer.read_identifier_disable)
        val flagABInitial = getResources().getInteger(R.integer.default_flagAB)
        val usbChargeInitial = getResources().getInteger(R.integer.default_usbcharge)
        val polarizedWaveInitial = getResources().getInteger(R.integer.default_polarizedwave)
        val appLogLevelInitial = getResources().getInteger(R.integer.default_app_log_level)
        val appLogRotateInitial = getResources().getInteger(R.integer.default_app_log_rotate)
        val driverLogLevelInitial = getResources().getInteger(R.integer.default_driver_log_level)


        // 1. initialDeviceSettings を SharedPreferences からロード
        initialDeviceSettings = DeviceSettings(
            defaultHST = prefs.getString(KEY_DEFAULT_HST, null), // null許容に変更、デフォルトはsetupDefaultHSTSpinnerで処理
            scanPower = prefs.getInt(KEY_SCAN_POWER, scanPowerSpinnerList.displayValues.getOrElse(0){scanPowerInitial}),
            qValue = prefs.getInt(KEY_Q_VALUE, qValueSpinnerList.displayValues.getOrElse(0){qValueInitial}),
            frequency = prefs.getInt(KEY_FREQUENCY, frequencySpinnerList.displayValues.getOrElse(0){frequencyInitial}),
            sessionId = prefs.getInt(KEY_SESSION_ID, sessionidSpinnerList.displayValues.getOrElse(0){sessionidInitial}),
            triggerSwitch = prefs.getInt(KEY_TRIGGER_SW, triggerswSpinnerList.displayValues.getOrElse(0){triggerswInitial}),
            sleepMode = prefs.getInt(KEY_SLEEP_MODE, sleepmodeSpinnerList.displayValues.getOrElse(0){sleepmodeInitial}),
            modulationFormat = prefs.getInt(KEY_MODULATION_FORMAT, modulationformatSpinnerList.displayValues.getOrElse(0){modulationformatInitial}),
            miller = prefs.getInt(KEY_MILLER, millerSpinnerList.displayValues.getOrElse(0){millerInitial}),
            readIdentifier = prefs.getString(KEY_READ_IDENTIFIER, "0")?.toIntOrNull() ?: readIdentifierInitial,
            flagAB = prefs.getInt(KEY_FLAG_AB, flagABSpinnerList.displayValues.getOrElse(0){flagABInitial}),
            usbCharge = prefs.getInt(KEY_USB_CHARGE, usbchargeSpinnerList.displayValues.getOrElse(0){usbChargeInitial}),
            polarizedWave = prefs.getInt(KEY_POLARIZED_WAVE, polarizedwaveSpinnerList.displayValues.getOrElse(0){polarizedWaveInitial}),
            appLogLevel = prefs.getInt(KEY_APP_LOG_LEVEL, appLogLevelSpinnerList.displayValues.getOrElse(0){appLogLevelInitial}),
            appLogRotateDays = prefs.getInt(KEY_APP_LOG_ROTATE_DAYS, appLogRotateSpinnerList.displayValues.getOrElse(0){appLogRotateInitial}),
            driverLogLevel = prefs.getInt(KEY_DRIVER_LOG_LEVEL, driverLogLevelSpinnerList.displayValues.getOrElse(0){driverLogLevelInitial}),
            readFolderUri = prefs.getString(KEY_IMPORT_DIR_URI, null)?.let { Uri.parse(it) },
            writeFolderUri = prefs.getString(KEY_EXPORT_DIR_URI, null)?.let { Uri.parse(it) }
        )
        Timber.v("loadAndApplyInitialSettings: Loaded initialDeviceSettings = $initialDeviceSettings")

        // 2. UI に initialDeviceSettings の値を設定
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupDefaultHSTSpinner with defaultHST='${initialDeviceSettings.defaultHST}', lastConnectedMac='${prefs.getString(KEY_LAST_CONNECTED_MAC, null)}'")
        setupDefaultHSTSpinner(initialDeviceSettings.defaultHST, prefs.getString(KEY_LAST_CONNECTED_MAC, null))

        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for ScanPower with initialValue=${initialDeviceSettings.scanPower}")
        setupSpinner(spinnerScanPower, scanPowerSpinnerList, KEY_SCAN_POWER, initialDeviceSettings.scanPower)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for QValue with initialValue=${initialDeviceSettings.qValue}")
        setupSpinner(spinnerQValue, qValueSpinnerList, KEY_Q_VALUE, initialDeviceSettings.qValue)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for Frequency with initialValue=${initialDeviceSettings.frequency}")
        setupSpinner(spinnerFrequency, frequencySpinnerList, KEY_FREQUENCY, initialDeviceSettings.frequency)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for SessionId with initialValue=${initialDeviceSettings.sessionId}")
        setupSpinner(spinnerSessionid, sessionidSpinnerList, KEY_SESSION_ID, initialDeviceSettings.sessionId)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for TriggerSwitch with initialValue=${initialDeviceSettings.triggerSwitch}")
        setupSpinner(spinnerTriggersw, triggerswSpinnerList, KEY_TRIGGER_SW, initialDeviceSettings.triggerSwitch)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for SleepMode with initialValue=${initialDeviceSettings.sleepMode}")
        setupSpinner(spinnerSleepmode, sleepmodeSpinnerList, KEY_SLEEP_MODE, initialDeviceSettings.sleepMode)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for ModulationFormat with initialValue=${initialDeviceSettings.modulationFormat}")
        setupSpinner(spinnerModulationformat, modulationformatSpinnerList, KEY_MODULATION_FORMAT, initialDeviceSettings.modulationFormat)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for Miller with initialValue=${initialDeviceSettings.miller}")
        setupSpinner(spinnerMiller, millerSpinnerList, KEY_MILLER, initialDeviceSettings.miller)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for FlagAB with initialValue=${initialDeviceSettings.flagAB}")
        setupSpinner(spinnerflagAB, flagABSpinnerList, KEY_FLAG_AB, initialDeviceSettings.flagAB)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for USBCharge with initialValue=${initialDeviceSettings.usbCharge}")
        setupSpinner(spinnerusbcharge, usbchargeSpinnerList, KEY_USB_CHARGE, initialDeviceSettings.usbCharge)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for PolarizedWave with initialValue=${initialDeviceSettings.polarizedWave}")
        setupSpinner(spinnerpolarizedwave, polarizedwaveSpinnerList, KEY_POLARIZED_WAVE, initialDeviceSettings.polarizedWave)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for AppLogLevel with initialValue=${initialDeviceSettings.appLogLevel}")
        setupSpinner(spinnerAppLogLevel, appLogLevelSpinnerList, KEY_APP_LOG_LEVEL, initialDeviceSettings.appLogLevel)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for AppLogRotate with initialValue=${initialDeviceSettings.appLogRotateDays}")
        setupSpinner(spinnerAppLogRotate, appLogRotateSpinnerList, KEY_APP_LOG_ROTATE_DAYS, initialDeviceSettings.appLogRotateDays)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupSpinner for DriverLogLevel with initialValue=${initialDeviceSettings.driverLogLevel}")
        setupSpinner(spinnerDriverLogLevel, driverLogLevelSpinnerList, KEY_DRIVER_LOG_LEVEL, initialDeviceSettings.driverLogLevel)

        Timber.v("loadAndApplyInitialSettings: Preparing to call setupEditText for ReadIdentifier with initialValue=${initialDeviceSettings.readIdentifier}")
        setupEditText(
            editTextReadIdentifier,
            warningTextReadIdentifier,
            KEY_READ_IDENTIFIER, // Companion object のキーを使用
            initialDeviceSettings.readIdentifier ?: 0, // 初期値
            resources.getInteger(R.integer.read_identifier_disable),
            resources.getInteger(R.integer.read_identifier_min),
            resources.getInteger(R.integer.read_identifier_max)
        )

        Timber.v("loadAndApplyInitialSettings: Preparing to call setupEditText for readFolderUri with initialValue=${initialDeviceSettings.readFolderUri}")
        textviewreadFolder.text = initialDeviceSettings.readFolderUri?.path ?: getString(R.string.hint_readfolder)
        Timber.v("loadAndApplyInitialSettings: Preparing to call setupEditText for writeFolderUri with initialValue=${initialDeviceSettings.writeFolderUri}")
        textviewwriteFolder.text = initialDeviceSettings.writeFolderUri?.path ?: getString(R.string.hint_writefolder)

        updateAdvancedSettingsVisibility() // 詳細設定の表示状態を復元
        Timber.d("loadAndApplyInitialSettings: --- Finished Applying to UI ---")
    }

    private fun setupDefaultHSTSpinner(initialDefaultMacFromPrefs: String?, lastConnectedMacFromPrefs: String?) {
        val initialDisplayNamesFromResource = getString(R.string.array_defaulthst_display_names)
        val initialDisplayValuesFromResource = getString(R.string.array_defaulthst_display_values)
        defaultHSTSpinnerList.displayNames.clear()
        defaultHSTSpinnerList.displayValues.clear()

        val hasVaildInitialMac_def = initialDefaultMacFromPrefs?.let { isValidMacAddress(it) && it != initialDisplayValuesFromResource } ?: false
        val hasValidInitialMac_last = lastConnectedMacFromPrefs?.let{ isValidMacAddress(it) && it != initialDisplayValuesFromResource } ?: false
        val hasValidInitialMac = (hasVaildInitialMac_def || hasValidInitialMac_last)
        if(!hasValidInitialMac) {
            defaultHSTSpinnerList.displayNames.add(initialDisplayNamesFromResource)
            defaultHSTSpinnerList.displayValues.add(initialDisplayValuesFromResource)
        }

        // 最後に接続したMACアドレスをリストに追加（もし存在しなければ）
        lastConnectedMacFromPrefs?.let { mac ->
            if (isValidMacAddress(mac) && !defaultHSTSpinnerList.displayValues.contains(mac)) {
                defaultHSTSpinnerList.displayNames.add(mac) // 表示名もMACアドレスそのもの
                defaultHSTSpinnerList.displayValues.add(mac)
            }
        }
        // SharedPreferencesから保存されたデフォルトHSTをリストに追加（もし存在しなければ）
        initialDefaultMacFromPrefs?.let { mac ->
            if (isValidMacAddress(mac) && !defaultHSTSpinnerList.displayValues.contains(mac)) {
                defaultHSTSpinnerList.displayNames.add(mac)
                defaultHSTSpinnerList.displayValues.add(mac)
            }
        }

        defaultHSTSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            defaultHSTSpinnerList.displayNames.toMutableList() // 複製を渡す
        )
        defaultHSTSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerdefaultHST.adapter = defaultHSTSpinnerAdapter

        // 初期選択: 1. 保存されたデフォルトHST, 2. 最後に接続したMAC, 3. リストの先頭
        var selectionPosition = -1
        initialDefaultMacFromPrefs?.let { mac ->
            selectionPosition = defaultHSTSpinnerList.displayValues.indexOf(mac)
        }
        if (selectionPosition == -1) {
            lastConnectedMacFromPrefs?.let { mac ->
                selectionPosition = defaultHSTSpinnerList.displayValues.indexOf(mac)
            }
        }
        if (selectionPosition == -1 && defaultHSTSpinnerList.displayValues.isNotEmpty()) {
            selectionPosition = 0
        }

        if (selectionPosition != -1 && selectionPosition < defaultHSTSpinnerAdapter.count) {
            spinnerdefaultHST.setSelection(selectionPosition)
        } else if (defaultHSTSpinnerAdapter.count > 0) {
            spinnerdefaultHST.setSelection(0)
            Timber.d( "Default HST Spinner set to position 0 as fallback.")
        } else {
            Timber.w( "Default HST Spinner has no items to select.")
        }
        // Listener は setupAllListeners で設定
    }

    private fun setupSpinner(spinner: Spinner, spinnerList: SpinnerList, preferenceKey: String, initialValue: Int?) {
        if (spinnerList.displayNames.isEmpty() || spinnerList.displayValues.isEmpty()) {
            Timber.w( "Spinner data for key '$preferenceKey' is empty. Cannot setup spinner ${spinner.id}.")
            spinner.visibility = View.GONE // データがない場合は非表示にするなど
            return
        }
        spinner.visibility = View.VISIBLE

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerList.displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val positionToSelect = initialValue?.let { value ->
            spinnerList.displayValues.indexOf(value)
        } ?: -1

        if (positionToSelect != -1 && positionToSelect < adapter.count) {
            spinner.setSelection(positionToSelect)
        } else if (adapter.count > 0) {
            spinner.setSelection(0) // デフォルトで最初のアイテム
            Timber.w( "Initial value for spinner key '$preferenceKey' ($initialValue) not found or invalid. Defaulting to first item.")
        } else {
            Timber.w( "Spinner for key '$preferenceKey' has no items after adapter setup.")
        }
        // Listener は setupAllListeners で設定
    }

    private fun setupEditText(
        editText: EditText,
        errorTextView: TextView,
        preferenceKey: String,
        initialValue: Int,
        disableValue: Int,
        minValue: Int,
        maxValue: Int
    ) {
        editText.setText(initialValue.toString())

        // 初期ロード時の検証とエラー表示
        val initialValidationResult = checkEditTextValue(initialValue.toString(), disableValue, minValue, maxValue)
        when (initialValidationResult) {
            ErrNo.EDITTEXT_RESULT_OK -> errorTextView.visibility = View.GONE
            ErrNo.EDITTEXT_RESULT_EMPTY -> { // disableValue=0 で空の場合などはOK扱い
                if (disableValue == 0 && initialValue == 0) errorTextView.visibility = View.GONE
                else {
                    errorTextView.text = getString(R.string.read_identifier_warning_text_empty)
                    errorTextView.visibility = View.VISIBLE
                }
            }
            ErrNo.EDITTEXT_RESULT_NON_NUMERIC -> {
                errorTextView.text = getString(R.string.read_identifier_warning_non_numeric)
                errorTextView.visibility = View.VISIBLE
            }
            ErrNo.EDITTEXT_RESULT_ERROR -> {
                errorTextView.text = getString(R.string.read_identifier_warning_out_of_range)
                errorTextView.visibility = View.VISIBLE
            }
        }
        // Listener は setupAllListeners で設定
    }

    private fun setupAllListeners() {
        Timber.d( "Setting up all listeners.")

        // --- Button Listeners ---
        buttonApply.setOnClickListener {
            Timber.v( "Apply button clicked.")
            applySettings()
        }

        buttonReset.setOnClickListener {
            Timber.v( "Reset button clicked.")
            restoreSettingsFromPreferences()
            // finish()
        }

        buttonBack.setOnClickListener {
            Timber.v("Back button clicked.")
            val currentSettings = getCurrentUiSettings()
            val settingsChanged = haveSettingsChanged(initialDeviceSettings, currentSettings)

            if (progressDialog?.isShowing == true) {
                Timber.w("Back button pressed while settings were being applied. Dismissing progress dialog.")
                dismissProgressDialog()
            }

            if (settingsChanged) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_confirm))
                    .setMessage(getString(R.string.settings_activity_dialog_cancel))
                    .setPositiveButton(getString(R.string.btn_txt_yes)) { dialog, _ ->
                        shouldFinishAfterApply = true
                        applySettings()
                        dialog.dismiss() // 確認ダイアログを閉じる
                    }
                    .setNegativeButton(getString(R.string.btn_txt_no)) { dialog, _ ->
                        dialog.dismiss() // 確認ダイアログを閉じる
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                finish()
            }
        }

        buttonReadQR.setOnClickListener {
            Timber.v( "Read QR button clicked.")
            val intent = Intent(this, CameraActivity::class.java)
            startActivityForResult(intent, QR_CODE_SCANNER_REQUEST_CODE)
        }

        buttonConnectHST.setOnClickListener {
            Timber.v( "Connect HST button clicked.")
            handleConnectDisconnect()
        }

        buttonReadfolderBrowse.setOnClickListener {
            Timber.v( "Read folder browse button clicked.")
            openDirectoryPicker(READ_FOLDER_REQUEST_CODE)
        }

        buttonWritefolderBrowse.setOnClickListener {
            Timber.v( "Write folder browse button clicked.")
            openDirectoryPicker(WRITE_FOLDER_REQUEST_CODE)
        }

        buttonShowLog.setOnClickListener {
            Timber.v( "Show log button clicked.")
            val intent = Intent(this, DisplayLogActivity::class.java)
            startActivity(intent)
        }

        // --- Spinner ItemSelected Listeners (SharedPreferencesへの即時保存用) ---
        spinnerdefaultHST.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!::defaultHSTSpinnerAdapter.isInitialized) {
                    return
                }
                val emptyDefaultMac = getString(R.string.array_defaulthst_display_values)
                val currentAdapterItemCount = defaultHSTSpinnerAdapter.count
                if (position >= 0 && position < currentAdapterItemCount && position < defaultHSTSpinnerList.displayValues.size) {
                    val originallySelectedMac = defaultHSTSpinnerList.displayValues[position]
                    val originallySelectedDisplayName = defaultHSTSpinnerList.displayNames[position]

                    var macToSelectAfterUpdate = originallySelectedMac // 更新後に選択すべきMAC (初期値は現在の選択)
                    var selectionNeedsUpdate = false

                    if (isValidMacAddress(originallySelectedMac)) {
                        Timber.d( "Default HST selected: '$originallySelectedDisplayName' ($originallySelectedMac).")

                        if (originallySelectedMac != emptyDefaultMac) {
                            // 有効なMACが選択され、かつそれが emptyDefaultMac ではない場合
                            macToSelectAfterUpdate = originallySelectedMac // これが最終的に選択されてほしいMAC

                            // emptyDefaultMac がリストに存在すれば削除する
                            val noneIndexInDisplayValues = defaultHSTSpinnerList.displayValues.indexOf(emptyDefaultMac)
                            if (noneIndexInDisplayValues != -1) {
                                val noneDisplayName = defaultHSTSpinnerList.displayNames.getOrNull(noneIndexInDisplayValues) ?: emptyDefaultMac

                                defaultHSTSpinnerList.displayNames.removeAt(noneIndexInDisplayValues)
                                defaultHSTSpinnerList.displayValues.removeAt(noneIndexInDisplayValues)

                                // アダプターを更新
                                defaultHSTSpinnerAdapter.clear()
                                defaultHSTSpinnerAdapter.addAll(defaultHSTSpinnerList.displayNames.toMutableList())
                                defaultHSTSpinnerAdapter.notifyDataSetChanged() // 必須ではないが、明示的に呼ぶ

                                Timber.v( "Removed '$noneDisplayName' ($emptyDefaultMac) from Default HST spinner list and updated adapter.")
                                selectionNeedsUpdate = true // アダプターが変更されたので選択位置の更新が必要
                            }
                        }

                        if (selectionNeedsUpdate) {
                            // macToSelectAfterUpdate (元々ユーザーが選んだ有効なMAC) の新しい位置を探す
                            val newPosition = defaultHSTSpinnerList.displayValues.indexOf(macToSelectAfterUpdate)
                            if (newPosition != -1 && newPosition < defaultHSTSpinnerAdapter.count) {
                                if (spinnerdefaultHST.selectedItemPosition != newPosition) {
                                    spinnerdefaultHST.setSelection(newPosition)
                                    Timber.d( "Spinner selection reset to position $newPosition for MAC $macToSelectAfterUpdate after list update.")
                                }
                            } else {
                                Timber.w( "Could not find $macToSelectAfterUpdate in updated list or newPosition invalid.")
                                // フォールバック: 何かを選択させる (例: 先頭)
                                if (defaultHSTSpinnerAdapter.count > 0) {
                                    if (spinnerdefaultHST.selectedItemPosition != 0) {
                                        spinnerdefaultHST.setSelection(0)
                                    }
                                }
                            }
                        }
                        // 接続ボタンの状態は、最終的なSpinnerの選択状態に基づいて更新
                        updateConnectButtonState(SdkManager.getCurrentConnectionStatus(), macToSelectAfterUpdate)

                    } else { // isValidMacAddress(originallySelectedMac) が false
                        Timber.w( "Invalid MAC address format selected from list: $originallySelectedMac")
                        updateConnectButtonState(SdkManager.getCurrentConnectionStatus(), originallySelectedMac)
                    }
                } else { // position が範囲外
                    Timber.w( "onItemSelected: Invalid position ($position) or adapter/list size mismatch. Adapter count: $currentAdapterItemCount, List size: ${defaultHSTSpinnerList.displayValues.size}")
                    updateConnectButtonState(SdkManager.getCurrentConnectionStatus(), emptyDefaultMac)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
        spinnerdefaultHST.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (isFetchingBleList.compareAndSet(false, true)) {
                    Timber.d( "Spinner default HST touched. Updating BLE list...")
                    updateBleListInSpinner()
                } else {
                    Timber.d( "Already fetching BLE list for default HST. Ignoring tap.")
                    if (!isSpinnerPopupShowing(spinnerdefaultHST)) {
                        // spinnerdefaultHST.performClick() // 更新処理後に開くのでここでは不要かも
                    }
                }
            }
            false
        }

        // 汎用Spinnerのリスナー設定 (例: ScanPower)
        setupGenericSpinnerListener(spinnerScanPower, scanPowerSpinnerList) { initialDeviceSettings.scanPower }
        setupGenericSpinnerListener(spinnerQValue, qValueSpinnerList) { initialDeviceSettings.qValue }
        setupGenericSpinnerListener(spinnerFrequency, frequencySpinnerList) { initialDeviceSettings.frequency }
        setupGenericSpinnerListener(spinnerSessionid, sessionidSpinnerList) { initialDeviceSettings.sessionId }
        setupGenericSpinnerListener(spinnerTriggersw, triggerswSpinnerList) { initialDeviceSettings.triggerSwitch }
        setupGenericSpinnerListener(spinnerSleepmode, sleepmodeSpinnerList) { initialDeviceSettings.sleepMode }
        setupGenericSpinnerListener(spinnerModulationformat, modulationformatSpinnerList) { initialDeviceSettings.modulationFormat }
        setupGenericSpinnerListener(spinnerMiller, millerSpinnerList) { initialDeviceSettings.miller }
        setupGenericSpinnerListener(spinnerflagAB, flagABSpinnerList) { initialDeviceSettings.flagAB }
        setupGenericSpinnerListener(spinnerusbcharge, usbchargeSpinnerList) { initialDeviceSettings.usbCharge }
        setupGenericSpinnerListener(spinnerpolarizedwave, polarizedwaveSpinnerList) { initialDeviceSettings.polarizedWave }
        setupGenericSpinnerListener(spinnerAppLogLevel, appLogLevelSpinnerList) { initialDeviceSettings.appLogLevel }
        setupGenericSpinnerListener(spinnerAppLogRotate, appLogRotateSpinnerList) { initialDeviceSettings.appLogRotateDays }
        setupGenericSpinnerListener(spinnerDriverLogLevel, driverLogLevelSpinnerList) { initialDeviceSettings.driverLogLevel }

        // --- EditText TextChangedListener ---
        editTextReadIdentifier.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val valueStr = s.toString()
                val validationResult = checkEditTextValue(
                    valueStr,
                    resources.getInteger(R.integer.read_identifier_disable),
                    resources.getInteger(R.integer.read_identifier_min),
                    resources.getInteger(R.integer.read_identifier_max)
                )
                when (validationResult) {
                    ErrNo.EDITTEXT_RESULT_OK -> {
                        warningTextReadIdentifier.visibility = View.GONE
                        val intValue = valueStr.toIntOrNull() ?: if (valueStr.isEmpty() && resources.getInteger(R.integer.read_identifier_disable) == 0) 0 else null
                        intValue?.let {
                            // saveEditTextValue(KEY_READ_IDENTIFIER, it.toString()) // 適用ボタンを押すまでプリファレンスには保存しない
                            Timber.d( "Read Identifier changed: $it. Saved to Prefs with $KEY_READ_IDENTIFIER.")
                        }
                    }
                    ErrNo.EDITTEXT_RESULT_EMPTY -> {
                        if (resources.getInteger(R.integer.read_identifier_disable) == 0 && valueStr.isEmpty()) {
                            warningTextReadIdentifier.visibility = View.GONE // 0がdisableValueなら空はOK
                            // saveEditTextValue(KEY_READ_IDENTIFIER, "0") // 適用ボタンを押すまでプリファレンスには保存しない
                            initialDeviceSettings.readIdentifier = 0
                        } else {
                            warningTextReadIdentifier.text = getString(R.string.read_identifier_warning_text_empty)
                            warningTextReadIdentifier.visibility = View.VISIBLE
                        }
                    }
                    ErrNo.EDITTEXT_RESULT_NON_NUMERIC -> {
                        warningTextReadIdentifier.text = getString(R.string.read_identifier_warning_non_numeric)
                        warningTextReadIdentifier.visibility = View.VISIBLE
                    }
                    ErrNo.EDITTEXT_RESULT_ERROR -> {
                        warningTextReadIdentifier.text = getString(R.string.read_identifier_warning_out_of_range)
                        warningTextReadIdentifier.visibility = View.VISIBLE
                    }
                }
            }
        })

        // --- 詳細設定トグル ---
        advancedSettingsToggleContainer.setOnClickListener {
            isAdvancedSettingsVisible = !isAdvancedSettingsVisible
            updateAdvancedSettingsVisibility()
        }
        Timber.d( "All listeners set up.")
    }

    private fun restoreSettingsFromPreferences(){
        Timber.d( "Restoring settings from SharedPreferences.")
        loadAndApplyInitialSettings()
    }

    private fun setupGenericSpinnerListener(
        spinner: Spinner,
        spinnerList: SpinnerList,
        getInitialValue: () -> Int?
    ) {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < spinnerList.displayValues.size) {
                    val selectedValue = spinnerList.displayValues[position]
                    val spinnerIdentifier = spinner.tag as? String ?: try {
                        resources.getResourceEntryName(spinner.id)
                    } catch (e: Exception) { "SpinnerId-${spinner.id}" }
                    Timber.d( "Spinner '$spinnerIdentifier' selected value: $selectedValue. (initialDeviceSettings not updated here)")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun handleConnectDisconnect() {
        val selectedMacAddress = getSelectedMacAddressFromSpinner()
        val emptyDefaultMac = getString(R.string.array_defaulthst_display_values)

        if (selectedMacAddress == null || !isValidMacAddress(selectedMacAddress) || selectedMacAddress == emptyDefaultMac) {
            Toast.makeText(this, getString(R.string.settings_activity_toast_invalid_MACAdress), Toast.LENGTH_SHORT).show()
            return
        }

        val currentSdkStatus = SdkManager.getCurrentConnectionStatus()
        val connectedDeviceAddress = SdkManager.getConnectedDeviceAddress()

        Timber.d( "Connect button: Selected MAC='$selectedMacAddress', Current SDK Status='$currentSdkStatus', Connected Device='$connectedDeviceAddress'")

        when (currentSdkStatus) {
            SdkManager.ConnectionStatus.CONNECTED -> {
                if (connectedDeviceAddress == selectedMacAddress) {
                    Timber.d( "Attempting to disconnect from $selectedMacAddress")
                    SdkManager.disconnect()
                } else {
                    Toast.makeText(this, "現在 ${connectedDeviceAddress ?: "別のデバイス"} に接続中です。まず切断してください。", Toast.LENGTH_LONG).show()
                }
            }
            SdkManager.ConnectionStatus.DISCONNECTED, SdkManager.ConnectionStatus.ERROR -> {
                Timber.d( "Attempting to connect to $selectedMacAddress")
                val deviceNameToConnect = SdkManager.getOpenedDeviceName() ?: selectedMacAddress // フォールバック
                SdkManager.connect(selectedMacAddress, deviceNameToConnect)
            }
            SdkManager.ConnectionStatus.CONNECTING, SdkManager.ConnectionStatus.DISCONNECTING -> {
                Timber.d( "Operation (connecting/disconnecting) already in progress.")
                Toast.makeText(this, getString(R.string.operation_in_progress), Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun applySettings() {
        Timber.d( "applySettings started.")

        val currentUiSettings = getCurrentUiSettings()

        // 端末の設定適用
        applySettingsTerminal()

        // SDKドライバの設定適用
        if (SdkManager.isDriverOpened()) {
            applySettingsDriver()
        }
        // RFIDリーダーの設定適用
        if (SdkManager.isConnected()) {
            applySettingsHST(currentUiSettings)
        }
    }

    private fun applySettingsTerminal() {
        try {
            val currentUiSettings = getCurrentUiSettings()
            // 初期HSTのMACアドレスをSpinnerの選択ポジションからプリファレンスに保存する
            val selectedMacAddress = currentUiSettings.defaultHST
            Timber.d( "Selected MAC address from spinner: $selectedMacAddress")
            if (selectedMacAddress != null) {
                initialDeviceSettings.defaultHST = selectedMacAddress
                saveSelectedString(selectedMacAddress, KEY_DEFAULT_HST)
                Timber.d( "Saved selected MAC address to KEY_DEFAULT_HST: $selectedMacAddress")
            }
            // 読込フォルダのuriをプリファレンスに保存する
            val readFolderUri = currentUiSettings.readFolderUri
            Timber.d( "Read folder URI: $readFolderUri")
            if (readFolderUri != null) {
                initialDeviceSettings.readFolderUri = readFolderUri
                saveSelectedString(readFolderUri.toString(), KEY_IMPORT_DIR_URI)
                Timber.d( "Saved read folder URI to KEY_IMPORT_DIR_URI: $readFolderUri")
            }
            // 書出フォルダのuriをプリファレンスに保存する
            val writeFolderUri = currentUiSettings.writeFolderUri
            Timber.d( "Write folder URI: $writeFolderUri")
            if (writeFolderUri != null) {
                initialDeviceSettings.writeFolderUri = writeFolderUri
                saveSelectedString(writeFolderUri.toString(), KEY_EXPORT_DIR_URI)
                Timber.d( "Saved write folder URI to KEY_EXPORT_DIR_URI: $writeFolderUri")
            }
            // アプリログレベルをプリファレンスに保存する
            val appLogLevel = currentUiSettings.appLogLevel
            Timber.d( "App log level: $appLogLevel")
            if (appLogLevel != null) {
                initialDeviceSettings.appLogLevel = appLogLevel
                saveSelectedItem(appLogLevel, KEY_APP_LOG_LEVEL)
                Timber.d( "Saved app log level to KEY_APP_LOG_LEVEL: $appLogLevel")
            }
            // アプリログローテート期間をプリファレンスに保存する
            val appLogRotateDays = currentUiSettings.appLogRotateDays
            Timber.d( "App log rotate days: $appLogRotateDays")
            if (appLogRotateDays != null) {
                initialDeviceSettings.appLogRotateDays = appLogRotateDays
                saveSelectedItem(appLogRotateDays, KEY_APP_LOG_ROTATE_DAYS)
            }
        } catch (e: Exception) {
            Timber.e( "Exception in applySettingsTerminal", e)
        }
    }

    private fun applySettingsDriver() {
        Timber.d( "Applying driver settings...")
        try {
            val currentSettings = getCurrentUiSettings() // UIから最新の値を取得
            val defaultDriverLogSize = SdkManager.getDefaultDriverLogSize()
            val defaultDriverLogLevel = SdkManager.getDefaultDriverLogLevel()

            val uiDriverLogLevel = currentSettings.driverLogLevel // UIから取得したログレベル (null許容)

            if (uiDriverLogLevel != null) {
                // --- UIで有効なログレベルが選択されている場合 ---
                if (uiDriverLogLevel != initialDeviceSettings.driverLogLevel) {
                    Timber.i( "Driver log level changed in UI. Applying new level: $uiDriverLogLevel, size: $defaultDriverLogSize KB")

                    val editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    editor.putInt(KEY_DRIVER_LOG_LEVEL, uiDriverLogLevel)
                    editor.apply()

                    SdkManager.setSdkLoggingOptions(uiDriverLogLevel, defaultDriverLogSize)
                    initialDeviceSettings.driverLogLevel = uiDriverLogLevel // 適用後の値を initial にも反映
                } else {
                    Timber.i( "Driver log level ($uiDriverLogLevel) unchanged from initial settings. No SDK options update needed.")
                }
            } else {
                // --- UIで有効なログレベルが選択されていない (uiDriverLogLevel is null) ---
                Timber.w( "Driver log level was null from UI settings.")
            }
        } catch (e: Exception) {
            Timber.e( "Exception in applySettingsDriver", e)
        }
    }

    private fun applySettingsHST(currentSettings: DeviceSettings) {
        try {
            val title = getString(R.string.settings_activity_dialog_applying_HST_settings_title)
            val text = getString(R.string.settings_activity_dialog_applying_HST_settings_text)
            progressDialog = ProgressDialog.show(
                this,
                title,
                text,
                true,
                false
            )
            failedSettingsTracker.clear()
            settingsToApply.clear()
            settingStep = 0

            Timber.d( "Current UI settings for apply: $currentSettings")
            Timber.d( "Initial (loaded and potentially updated by listeners) settings for apply: $initialDeviceSettings")

            // --- Scan Power ---
            val settingNameSP = getString(R.string.label_text_scanpower)?:"スキャン出力"
            if (currentSettings.scanPower != initialDeviceSettings.scanPower && currentSettings.scanPower != null) {
                Timber.i( "Applying ${settingNameSP}...")
                settingsToApply.add(settingNameSP!! to {
                    SdkManager.setPower(currentSettings.scanPower!!, object : ResultCallback {
                        override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                            handleSetResult(
                                settingNameSP!!, resultCode,
                                onSuccess = {
                                    initialDeviceSettings.scanPower = currentSettings.scanPower
                                },
                                onFailure = { SdkManager.getPower(powerCallbackForFailedSet) }
                            )
                        }
                    })
                })
            }
            // --- Q Value ---
            val settingNameQV = getString(R.string.label_text_qvalue)?:"Q値"
            if (currentSettings.qValue != initialDeviceSettings.qValue && currentSettings.qValue != null) {
                Timber.i( "Applying ${settingNameQV}...")
                settingsToApply.add(settingNameQV!! to {
                    SdkManager.setQValue(currentSettings.qValue!!, object : ResultCallback {
                        override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                            handleSetResult(
                                settingNameQV!!, resultCode,
                                onSuccess = {
                                    initialDeviceSettings.qValue = currentSettings.qValue
                                },
                                onFailure = { SdkManager.getQValue(qValueCallbackForFailedSet) }
                            )
                        }
                    })
                })
            }
            // --- Frequency ---
            val settingNameF = getString(R.string.label_text_frequency)?:"周波数"
            if (currentSettings.frequency != initialDeviceSettings.frequency && currentSettings.frequency != null) {
                Timber.i( "Applying ${settingNameF}...")
                val freqChannelType = currentSettings.frequency!!
                if (freqChannelType != null) {
                    settingsToApply.add(settingNameF!! to {
                        SdkManager.setFrequency(
                            freqChannelType,
                            autoFrequencyList,
                            object : ResultCallback {
                                override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                    handleSetResult(
                                        settingNameF!!, resultCode,
                                        onSuccess = {
                                            initialDeviceSettings.frequency =
                                                currentSettings.frequency
                                        },
                                        onFailure = {
                                            SdkManager.getFrequency(
                                                frequencyCallbackForFailedSet
                                            )
                                        }
                                    )
                                }
                            })
                    })
                } else {
                    Timber.w( "Invalid frequency value for enum: ${currentSettings.frequency}")
                }
            }
            // --- Session ID ---
            val settingNameSID = getString(R.string.label_text_sessionid)?:"セッションID"
            if (currentSettings.sessionId != initialDeviceSettings.sessionId && currentSettings.sessionId != null) {
                Timber.i( "Applying ${settingNameSID}...")
                settingsToApply.add(settingNameSID!! to {
                    SdkManager.setSessionID(currentSettings.sessionId!!, object : ResultCallback {
                        override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                            handleSetResult(
                                settingNameSID!!, resultCode,
                                onSuccess = {
                                    initialDeviceSettings.sessionId = currentSettings.sessionId
                                },
                                onFailure = { SdkManager.getSessionID(sessionIDCallbackForFailedSet) }
                            )
                        }
                    })
                })
            }
            // --- Trigger Switch ---
            val settingNameTRG = getString(R.string.label_text_triggersw)?:"トリガースイッチ"
            if (currentSettings.triggerSwitch != initialDeviceSettings.triggerSwitch && currentSettings.triggerSwitch != null) {
                Timber.i( "Applying ${settingNameTRG}...")
                val trigModeType = currentSettings.triggerSwitch!!
                if (trigModeType != null) {
                    settingsToApply.add(settingNameTRG!! to {
                        SdkManager.setTriggerSwMode(trigModeType, object : ResultCallback {
                            override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                handleSetResult(
                                    settingNameTRG!!, resultCode,
                                    onSuccess = {
                                        initialDeviceSettings.triggerSwitch =
                                            currentSettings.triggerSwitch
                                    },
                                    onFailure = {
                                        SdkManager.getTriggerSwMode(
                                            triggerSwModeCallbackForFailedSet
                                        )
                                    }
                                )
                            }
                        })
                    })
                } else {
                    Timber.w("Invalid trigger switch value for enum: ${currentSettings.triggerSwitch}")
                }
            }
            // --- Sleep Mode (Saving Energy) ---
            val settingNameSLP = getString(R.string.label_text_sleepmode)?:"省電力設定"
            if (currentSettings.sleepMode != initialDeviceSettings.sleepMode && currentSettings.sleepMode != null) {
                Timber.i( "Applying ${settingNameSLP}...")
                settingsToApply.add(settingNameSLP!! to {
                    SdkManager.setSavingEnergy(
                        currentSettings.sleepMode!!,
                        object : ResultCallback {
                            override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                handleSetResult(
                                    settingNameSLP!!, resultCode,
                                    onSuccess = {
                                        initialDeviceSettings.sleepMode = currentSettings.sleepMode
                                    },
                                    onFailure = {
                                        SdkManager.getSavingEnergy(
                                            savingEnergyCallbackForFailedSet
                                        )
                                    }
                                )
                            }
                        })
                })
            }
            // --- Modulation Format & Miller (Tag Read Mode) ---
            val settingNameML = getString(R.string.label_text_modulationFormat)?:"変調方式"
            if ((currentSettings.modulationFormat != initialDeviceSettings.modulationFormat || currentSettings.miller != initialDeviceSettings.miller) &&
                currentSettings.modulationFormat != null && currentSettings.miller != null
            ) {
                Timber.i( "Applying ${settingNameML}...")
                val sdkTagSpeed = currentSettings.modulationFormat!!
                val sdkMiller = currentSettings.miller!!
                if (sdkTagSpeed != null && sdkMiller != null) {
                    settingsToApply.add(settingNameML!! to {
                        SdkManager.setTagReadMode(sdkTagSpeed, sdkMiller, object : ResultCallback {
                            override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                handleSetResult(
                                    settingNameML!!, resultCode,
                                    onSuccess = {
                                        initialDeviceSettings.modulationFormat =
                                            currentSettings.modulationFormat
                                        initialDeviceSettings.miller = currentSettings.miller
                                    },
                                    onFailure = {
                                        SdkManager.getTagReadMode(
                                            tagReadModeCallbackForFailedSet
                                        )
                                    }
                                )
                            }
                        })
                    })
                } else {
                    Timber.w( "Invalid modulation format or miller value for SDK: Mod=${currentSettings.modulationFormat}, Miller=${currentSettings.miller}")
                }
            }
            // --- Read Identifier (Misreading Prevention) ---
            val settingNameRID = getString(R.string.label_text_read_identifier)?:"読取識別番号"
            if (currentSettings.readIdentifier != initialDeviceSettings.readIdentifier && currentSettings.readIdentifier != null) {
                Timber.i( "Applying ${settingNameRID}...")
                settingsToApply.add(settingNameRID!! to {
                    SdkManager.setMisreadingPreventionSettings(
                        currentSettings.readIdentifier!!,
                        object : ResultCallback {
                            override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                handleSetResult(
                                    settingNameRID!!, resultCode,
                                    onSuccess = {
                                        initialDeviceSettings.readIdentifier =
                                            currentSettings.readIdentifier
                                    },
                                    onFailure = {
                                        SdkManager.getMisreadingPreventionSettings(
                                            misreadingPreventionSettingsCallbackForFailedSet
                                        )
                                    }
                                )
                            }
                        })
                })
            }
            // --- Flag AB ---
            val settingNameAB = getString(R.string.label_text_flagAB)?:"フラグA/B"
            if (currentSettings.flagAB != initialDeviceSettings.flagAB && currentSettings.flagAB != null) {
                Timber.i( "Applying ${settingNameAB}...")
                settingsToApply.add(settingNameAB!! to {
                    SdkManager.setFlagAB(currentSettings.flagAB!!, object : ResultCallback {
                        override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                            handleSetResult(
                                settingNameAB!!, resultCode,
                                onSuccess = {
                                    initialDeviceSettings.flagAB = currentSettings.flagAB
                                },
                                onFailure = { SdkManager.getFlagAB(flagABCallbackForFailedSet) }
                            )
                        }
                    })
                })
            }
            // --- USB Charge ---
            val settingNameUSBCHG = getString(R.string.label_text_usbcharge)?:"USB充電"
            if (currentSettings.usbCharge != initialDeviceSettings.usbCharge && currentSettings.usbCharge != null) {
                Timber.i( "Applying ${settingNameUSBCHG}...")
                settingsToApply.add(settingNameUSBCHG!! to {
                    SdkManager.setUSBCharging(currentSettings.usbCharge!!, object : ResultCallback {
                        override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                            handleSetResult(
                                settingNameUSBCHG!!, resultCode,
                                onSuccess = {
                                    initialDeviceSettings.usbCharge = currentSettings.usbCharge
                                },
                                onFailure = {
                                    SdkManager.getUSBCharging(
                                        usbChargingCallbackForFailedSet
                                    )
                                }
                            )
                        }
                    })
                })
            }
            // --- Polarized Wave (Antenna Polarization) ---
            val settingNamePW = getString(R.string.label_text_polarizedwave)?:"偏波"
            if (currentSettings.polarizedWave != initialDeviceSettings.polarizedWave && currentSettings.polarizedWave != null) {
                Timber.i( "Applying ${settingNamePW}...")
                settingsToApply.add(settingNamePW!! to {
                    SdkManager.setAntennaPolarization(
                        currentSettings.polarizedWave!!,
                        object : ResultCallback {
                            override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                                handleSetResult(
                                    settingNamePW!!, resultCode,
                                    onSuccess = {
                                        initialDeviceSettings.polarizedWave =
                                            currentSettings.polarizedWave
                                    },
                                    onFailure = {
                                        SdkManager.getAntennaPolarization(
                                            antennaPolarizationCallbackForFailedSet
                                        )
                                    }
                                )
                            }
                        })
                })
            }
            if (settingsToApply.isNotEmpty()) {
                executeNextSetting()
            } else {
                Timber.d("applySettingsHST: No settings changed for the RFID reader. Calling saveAllSettingsToPrefs() directly.")
                saveAllSettingsToPrefs()
                runOnUiThread {
                    finalizeSettingsApplication()
                }
            }
        } catch (e: Exception) {
            Timber.e( "Error in applySettings: ${e.message}", e)
            runOnUiThread {
                finalizeSettingsApplication()
            }
        }
    }

    private fun getCurrentUiSettings(): DeviceSettings {
        // 各Spinnerから現在選択されている "value" を取得
        val defaultHST = getSelectedMacAddressFromSpinner()
        val readFolderUri = grantedReadFolderUri ?: initialDeviceSettings.readFolderUri
        val writeFolderUri = grantedWriteFolderUri ?: initialDeviceSettings.writeFolderUri
        val scanPower = scanPowerSpinnerList.displayValues.getOrNull(spinnerScanPower.selectedItemPosition)
        val qValue = qValueSpinnerList.displayValues.getOrNull(spinnerQValue.selectedItemPosition)
        val frequency = frequencySpinnerList.displayValues.getOrNull(spinnerFrequency.selectedItemPosition)
        val sessionId = sessionidSpinnerList.displayValues.getOrNull(spinnerSessionid.selectedItemPosition)
        val triggerSwitch = triggerswSpinnerList.displayValues.getOrNull(spinnerTriggersw.selectedItemPosition)
        val sleepMode = sleepmodeSpinnerList.displayValues.getOrNull(spinnerSleepmode.selectedItemPosition)
        val modulationFormat = modulationformatSpinnerList.displayValues.getOrNull(spinnerModulationformat.selectedItemPosition)
        val miller = millerSpinnerList.displayValues.getOrNull(spinnerMiller.selectedItemPosition)
        val flagAB = flagABSpinnerList.displayValues.getOrNull(spinnerflagAB.selectedItemPosition)
        val usbCharge = usbchargeSpinnerList.displayValues.getOrNull(spinnerusbcharge.selectedItemPosition)
        val polarizedWave = polarizedwaveSpinnerList.displayValues.getOrNull(spinnerpolarizedwave.selectedItemPosition)
        val appLogLevel = appLogLevelSpinnerList.displayValues.getOrNull(spinnerAppLogLevel.selectedItemPosition)
        val appLogRotateDays = appLogRotateSpinnerList.displayValues.getOrNull(spinnerAppLogRotate.selectedItemPosition)
        val driverLogLevel = driverLogLevelSpinnerList.displayValues.getOrNull(spinnerDriverLogLevel.selectedItemPosition)

        val readIdentifierValue = editTextReadIdentifier.text.toString().toIntOrNull() ?:
        (if (editTextReadIdentifier.text.toString().isEmpty() &&
            resources.getInteger(R.integer.read_identifier_disable) == 0) 0 else null)

        return DeviceSettings(
            defaultHST = defaultHST,
            scanPower = scanPower,
            qValue = qValue,
            frequency = frequency,
            sessionId = sessionId,
            triggerSwitch = triggerSwitch,
            sleepMode = sleepMode,
            modulationFormat = modulationFormat,
            miller = miller,
            readIdentifier = readIdentifierValue,
            flagAB = flagAB,
            usbCharge = usbCharge,
            polarizedWave = polarizedWave,
            appLogLevel = appLogLevel,
            appLogRotateDays = appLogRotateDays,
            driverLogLevel = driverLogLevel,
            readFolderUri = readFolderUri,
            writeFolderUri = writeFolderUri,
        )
    }

    private fun executeNextSetting() {
        if (settingStep < settingsToApply.size) {
            val (settingName, action) = settingsToApply[settingStep]
            Timber.d( "Executing setting: $settingName (Step ${settingStep + 1}/${settingsToApply.size})")
            action.invoke()
        } else {
            Timber.d("executeNextSetting: All steps processed. Attempting to call SdkManager.saveMemory().")
            SdkManager.saveMemory(object: ResultCallback {
                override fun onCallback(resultCode: Int, resultCodeExtended: Int) {
                    Timber.v("executeNextSetting: SdkManager.saveMemory() onCallback received. resultCode=$resultCode")
                    if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                        Timber.i( "saveMemory successful.")
                    } else {
                        Timber.e( "saveMemory failed. Code: $resultCode, Ext: $resultCodeExtended")
                        failedSettingsTracker["リーダーメモリ保存"] = "失敗 (コード: $resultCode)"
                    }
                    // SharedPreferencesに全設定を保存 (initialDeviceSettings は成功/失敗に応じて更新済み)
                    saveAllSettingsToPrefs()
                    runOnUiThread {
                        finalizeSettingsApplication() // UI処理 (ダイアログなど)
                    }
                }
            })
        }
    }

    private fun handleSetResult(settingName: String, resultCode: Int, onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
            Timber.i( "Setting '$settingName' successful.")
            onSuccess() // initialDeviceSettings の該当フィールドをUIの値で更新
        } else {
            Timber.e( "Setting '$settingName' failed. Code: $resultCode. Attempting to get current value from reader.")
            failedSettingsTracker[settingName] = "設定失敗 (コード: $resultCode)"
            onFailure() // リーダーから現在の値を取得する処理を呼び出す (内部で executeNextSetting を呼ぶ)
            return // getXXX のコールバックが来るまで待つ
        }
        settingStep++
        if (settingStep < settingsToApply.size) {
            executeNextSetting() // 次の設定項目へ
        } else {
            // すべてのリーダーへの設定項目適用が完了
            Timber.i("handleSetResult: All individual reader settings processed. Now calling SdkManager.saveMemory().")
            SdkManager.saveMemory(object : ResultCallback {
                override fun onCallback(saveMemoryResultCode: Int, saveMemoryResultCodeExtended: Int) {
                    Timber.d("handleSetResult: SdkManager.saveMemory() onCallback received. resultCode=$saveMemoryResultCode")
                    if (saveMemoryResultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                        Timber.i("SdkManager.saveMemory() successful.")
                    } else {
                        Timber.e("SdkManager.saveMemory() failed. Code: $saveMemoryResultCode, Ext: $saveMemoryResultCodeExtended")
                        failedSettingsTracker["リーダーメモリ保存"] = "失敗 (コード: $saveMemoryResultCode)"
                    }
                    // SharedPreferencesに全設定を保存
                    saveAllSettingsToPrefs()
                    runOnUiThread {
                        finalizeSettingsApplication() // UI処理 (ダイアログなど)
                    }
                }
            })
        }
    }

    private fun finalizeSettingsApplication() {
        if (isFinishing || isDestroyed) {
            Timber.w( "finalizeSettingsApplication called but Activity is finishing or destroyed. Skipping dialog dismiss.")
            dismissProgressDialog()
            return
        }
        dismissProgressDialog()
        progressDialog?.let {
            if (it.isShowing) { // ダイアログが表示されている場合のみ dismiss を試みる
                try {
                    it.dismiss()
                } catch (e: IllegalArgumentException) {
                    // まれに isShowing が true でも dismiss で例外が発生することがあるため、念のためキャッチ
                    Timber.e( "Error dismissing progress dialog in finalizeSettingsApplication", e)
                }
            }
        }
        progressDialog = null

        if (failedSettingsTracker.isNotEmpty()) {
            if (!isFinishing && !isDestroyed) {
                val errorMessages =
                    failedSettingsTracker.map { "${it.key}: ${it.value}" }.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_activity_dialog_result_title))
                    .setMessage(getString(R.string.settings_activity_dialog_result_text_failure,errorMessages))
                    .setPositiveButton(getString(R.string.btn_txt_ok), null)
                    .show()
            } else {
                Timber.w( "Activity not valid for showing AlertDialog with error messages.")
            }
        } else {
            Toast.makeText(this, getString(R.string.settings_activity_dialog_result_text_success), Toast.LENGTH_LONG).show()
        }
        if (shouldFinishAfterApply) {
            shouldFinishAfterApply = false
            Timber.d("Finalizing and finishing activity as requested.")
            finish()
        }
        Timber.d( "applySettings finished. Final initialDeviceSettings: $initialDeviceSettings")
    }

    private fun haveSettingsChanged(initial: DeviceSettings, current: DeviceSettings): Boolean {
        if (initial.defaultHST != current.defaultHST) {
            Timber.d( "Settings difference found: defaultHST ('${initial.defaultHST}' vs '${current.defaultHST}')")
            return true
        }
        if (initial.scanPower != current.scanPower) {
            Timber.d( "Settings difference found: scanPower (${initial.scanPower} vs ${current.scanPower})")
            return true
        }
        if (initial.qValue != current.qValue) {
            Timber.d( "Settings difference found: qValue (${initial.qValue} vs ${current.qValue})")
            return true
        }
        if (initial.frequency != current.frequency) {
            Timber.d( "Settings difference found: frequency (${initial.frequency} vs ${current.frequency})")
            return true
        }
        if (initial.sessionId != current.sessionId) {
            Timber.d( "Settings difference found: sessionId (${initial.sessionId} vs ${current.sessionId})")
            return true
        }
        if (initial.triggerSwitch != current.triggerSwitch) {
            Timber.d( "Settings difference found: triggerSwitch (${initial.triggerSwitch} vs ${current.triggerSwitch})")
            return true
        }
        if (initial.sleepMode != current.sleepMode) {
            Timber.d( "Settings difference found: sleepMode (${initial.sleepMode} vs ${current.sleepMode})")
            return true
        }
        if (initial.modulationFormat != current.modulationFormat) {
            Timber.d( "Settings difference found: modulationFormat (${initial.modulationFormat} vs ${current.modulationFormat})")
            return true
        }
        if (initial.miller != current.miller) {
            Timber.d( "Settings difference found: miller (${initial.miller} vs ${current.miller})")
            return true
        }
        if (initial.readIdentifier != current.readIdentifier) {
            Timber.d( "Settings difference found: readIdentifier (${initial.readIdentifier} vs ${current.readIdentifier})")
            return true
        }
        if (initial.flagAB != current.flagAB) {
            Timber.d( "Settings difference found: flagAB (${initial.flagAB} vs ${current.flagAB})")
            return true
        }
        if (initial.usbCharge != current.usbCharge) {
            Timber.d( "Settings difference found: usbCharge (${initial.usbCharge} vs ${current.usbCharge})")
            return true
        }
        if (initial.polarizedWave != current.polarizedWave) {
            Timber.d( "Settings difference found: polarizedWave (${initial.polarizedWave} vs ${current.polarizedWave})")
            return true
        }
        if (initial.appLogLevel != current.appLogLevel) {
            Timber.d( "Settings difference found: appLogLevel (${initial.appLogLevel} vs ${current.appLogLevel})")
            return true
        }
        if (initial.appLogRotateDays != current.appLogRotateDays) {
            Timber.d( "Settings difference found: appLogRotateDays (${initial.appLogRotateDays} vs ${current.appLogRotateDays})")
            return true
        }
        if (initial.driverLogLevel != current.driverLogLevel) {
            Timber.d( "Settings difference found: driverLogLevel (${initial.driverLogLevel} vs ${current.driverLogLevel})")
            return true
        }
        // Uriの比較は toString() で行うか、より厳密な比較が必要なら path などを比較
        if (initial.readFolderUri?.toString() != current.readFolderUri?.toString()) {
            Timber.d( "Settings difference found: readFolderUri ('${initial.readFolderUri}' vs '${current.readFolderUri}')")
            return true
        }
        if (initial.writeFolderUri?.toString() != current.writeFolderUri?.toString()) {
            Timber.d( "Settings difference found: writeFolderUri ('${initial.writeFolderUri}' vs '${current.writeFolderUri}')")
            return true
        }

        Timber.d( "No settings difference found between initial and current.")
        return false
    }

    private fun saveAllSettingsToPrefs() {
        Timber.d("saveAllSettingsToPrefs: --- Start Saving All Preferences ---")
        val settingsToSave = initialDeviceSettings
        Timber.v("saveAllSettingsToPrefs: Current initialDeviceSettings to be saved = $initialDeviceSettings")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // initialDeviceSettings にはリーダーへの適用試行後の最新の状態が反映されている
        // (成功時はUIの値、失敗時はリーダーから読み戻した値、リスナーで即時更新された値)
        editor.putString(KEY_DEFAULT_HST, initialDeviceSettings.defaultHST)
        Timber.v("saveAllSettingsToPrefs: Putting KEY_DEFAULT_HST = ${initialDeviceSettings.defaultHST}")
        initialDeviceSettings.scanPower?.let {
            editor.putInt(KEY_SCAN_POWER, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_SCAN_POWER = $it")
        } ?: editor.remove(KEY_SCAN_POWER)
        initialDeviceSettings.qValue?.let {
            editor.putInt(KEY_Q_VALUE, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_Q_VALUE = $it")
        } ?: editor.remove(KEY_Q_VALUE)
        initialDeviceSettings.frequency?.let {
            editor.putInt(KEY_FREQUENCY, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_FREQUENCY = $it")
        } ?: editor.remove(KEY_FREQUENCY)
        initialDeviceSettings.sessionId?.let {
            editor.putInt(KEY_SESSION_ID, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_SESSION_ID = $it")
        } ?: editor.remove(KEY_SESSION_ID)
        initialDeviceSettings.triggerSwitch?.let {
            editor.putInt(KEY_TRIGGER_SW, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_TRIGGER_SW = $it")
        } ?: editor.remove(KEY_TRIGGER_SW)
        initialDeviceSettings.sleepMode?.let {
            editor.putInt(KEY_SLEEP_MODE, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_SLEEP_MODE = $it")
        } ?: editor.remove(KEY_SLEEP_MODE)
        initialDeviceSettings.modulationFormat?.let {
            editor.putInt(KEY_MODULATION_FORMAT, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_MODULATION_FORMAT = $it")
        } ?: editor.remove(KEY_MODULATION_FORMAT)
        initialDeviceSettings.miller?.let {
            editor.putInt(KEY_MILLER, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_MILLER = $it")
        } ?: editor.remove(KEY_MILLER)
        initialDeviceSettings.readIdentifier?.let {
            editor.putString(KEY_READ_IDENTIFIER, it.toString())
            Timber.v("saveAllSettingsToPrefs: Putting KEY_READ_IDENTIFIER = $it")
        } ?: editor.remove(KEY_READ_IDENTIFIER)
        initialDeviceSettings.flagAB?.let {
            editor.putInt(KEY_FLAG_AB, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_FLAG_AB = $it")
        } ?: editor.remove(KEY_FLAG_AB)
        initialDeviceSettings.usbCharge?.let {
            editor.putInt(KEY_USB_CHARGE, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_USB_CHARGE = $it")
        } ?: editor.remove(KEY_USB_CHARGE)
        initialDeviceSettings.polarizedWave?.let {
            editor.putInt(KEY_POLARIZED_WAVE, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_POLARIZED_WAVE = $it")
        } ?: editor.remove(KEY_POLARIZED_WAVE)
        initialDeviceSettings.appLogLevel?.let {
            editor.putInt(KEY_APP_LOG_LEVEL, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_APP_LOG_LEVEL = $it")
        } ?: editor.remove(KEY_APP_LOG_LEVEL)
        initialDeviceSettings.appLogRotateDays?.let {
            editor.putInt(KEY_APP_LOG_ROTATE_DAYS, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_APP_LOG_ROTATE_DAYS = $it")
        } ?: editor.remove(KEY_APP_LOG_ROTATE_DAYS)
        initialDeviceSettings.driverLogLevel?.let {
            editor.putInt(KEY_DRIVER_LOG_LEVEL, it)
            Timber.v("saveAllSettingsToPrefs: Putting KEY_DRIVER_LOG_LEVEL = $it")
        } ?: editor.remove(KEY_DRIVER_LOG_LEVEL)
        initialDeviceSettings.readFolderUri?.let {
            editor.putString(KEY_IMPORT_DIR_URI, it.toString())
            Timber.v("saveAllSettingsToPrefs: Putting KEY_IMPORT_DIR_URI = $it")
        } ?: editor.remove(KEY_IMPORT_DIR_URI)
        initialDeviceSettings.writeFolderUri?.let {
            editor.putString(KEY_EXPORT_DIR_URI, it.toString())
            Timber.v("saveAllSettingsToPrefs: Putting KEY_EXPORT_DIR_URI = $it")
        } ?: editor.remove(KEY_EXPORT_DIR_URI)

        editor.apply() // apply() に戻す
        Timber.i("saveAllSettingsToPrefs: All settings apply() called. SharedPreferences will be updated asynchronously.")

    }

    // --- 各設定項目のset失敗時にリーダーから値を取得するためのコールバック実装 ---
    private val powerCallbackForFailedSet = object : PowerCallback {
        override fun onCallback(powerLevel: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Power after set failure: $powerLevel")
                initialDeviceSettings.scanPower = powerLevel
                setSpinnerSelectionFromValue(spinnerScanPower, scanPowerSpinnerList.displayValues, powerLevel)
            } else { Timber.e( "Failed to fetch Power after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val qValueCallbackForFailedSet = object : QValueCallback {
        override fun onCallback(valueQ: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Q Value after set failure: $valueQ")
                initialDeviceSettings.qValue = valueQ
                setSpinnerSelectionFromValue(spinnerQValue, qValueSpinnerList.displayValues, valueQ)
            } else { Timber.e( "Failed to fetch Q Value after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val frequencyCallbackForFailedSet = object : FrequencyCallback {
        override fun onCallback(frequencyChannel: Int, autoFrequencyList: ArrayList<Int>?, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
//                val freqValue = sdkFrequencyChannelTypeToAppInt(frequencyChannel) // 変換関数使用
                val freqValue = frequencyChannel
                Timber.i( "Successfully fetched Frequency after set failure: $freqValue ($frequencyChannel)")
                initialDeviceSettings.frequency = freqValue
                freqValue?.let { setSpinnerSelectionFromValue(spinnerFrequency, frequencySpinnerList.displayValues, it) }
            } else { Timber.e( "Failed to fetch Frequency after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val sessionIDCallbackForFailedSet = object : SessionIDCallback {
        override fun onCallback(valueSessionID: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Session ID after set failure: $valueSessionID")
                initialDeviceSettings.sessionId = valueSessionID
                setSpinnerSelectionFromValue(spinnerSessionid, sessionidSpinnerList.displayValues, valueSessionID)
            } else { Timber.e( "Failed to fetch Session ID after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val triggerSwModeCallbackForFailedSet = object : TriggerSwModeCallback {
        override fun onCallback(trigMode: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                val trigValue = trigMode
                Timber.i( "Successfully fetched Trigger Switch Mode after set failure: $trigValue ($trigMode)")
                initialDeviceSettings.triggerSwitch = trigValue
                trigValue?.let { setSpinnerSelectionFromValue(spinnerTriggersw, triggerswSpinnerList.displayValues, it) }
            } else { Timber.e( "Failed to fetch Trigger Switch Mode after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val savingEnergyCallbackForFailedSet = object : SavingEnergyCallback {
        override fun onCallback(energy: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Saving Energy (Sleep Mode) after set failure: $energy")
                initialDeviceSettings.sleepMode = energy
                setSpinnerSelectionFromValue(spinnerSleepmode, sleepmodeSpinnerList.displayValues, energy)
            } else { Timber.e( "Failed to fetch Saving Energy after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val tagReadModeCallbackForFailedSet = object : TagReadModeCallback {
        override fun onCallback(tagSpeed: Int, millerSubCarrier: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                val modFormatValue = tagSpeed
                val millerValue = millerSubCarrier
                Timber.i( "Successfully fetched Tag Read Mode after set failure: ModFormat(SDKval)=$tagSpeed -> $modFormatValue, Miller(SDKval)=$millerSubCarrier -> $millerValue")
                initialDeviceSettings.modulationFormat = modFormatValue
                initialDeviceSettings.miller = millerValue
                modFormatValue?.let { setSpinnerSelectionFromValue(spinnerModulationformat, modulationformatSpinnerList.displayValues, it) }
                millerValue?.let { setSpinnerSelectionFromValue(spinnerMiller, millerSpinnerList.displayValues, it) }
            } else { Timber.e( "Failed to fetch Tag Read Mode after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val misreadingPreventionSettingsCallbackForFailedSet = object : MisreadingPreventionSettingsCallback {
        override fun onCallback(id: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Misreading Prevention (Read Identifier) after set failure: $id")
                initialDeviceSettings.readIdentifier = id
                editTextReadIdentifier.setText(id.toString())
            } else { Timber.e( "Failed to fetch Misreading Prevention after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val flagABCallbackForFailedSet = object : FlagABCallback {
        override fun onCallback(valueFlagAB: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Flag A/B after set failure: $valueFlagAB")
                initialDeviceSettings.flagAB = valueFlagAB
                setSpinnerSelectionFromValue(spinnerflagAB, flagABSpinnerList.displayValues, valueFlagAB)
            } else { Timber.e( "Failed to fetch Flag A/B after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val usbChargingCallbackForFailedSet = object : USBChargingCallback {
        override fun onCallback(charging: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched USB Charging after set failure: $charging")
                initialDeviceSettings.usbCharge = charging
                setSpinnerSelectionFromValue(spinnerusbcharge, usbchargeSpinnerList.displayValues, charging)
            } else { Timber.e( "Failed to fetch USB Charging after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }
    private val antennaPolarizationCallbackForFailedSet = object : AntennaPolarizationCallback {
        override fun onCallback(polarization: Int, resultCode: Int, resultCodeExtended: Int) {
            if (resultCode == ToshibaTecSdk.OPOS_SUCCESS) {
                Timber.i( "Successfully fetched Antenna Polarization after set failure: $polarization")
                initialDeviceSettings.polarizedWave = polarization
                setSpinnerSelectionFromValue(spinnerpolarizedwave, polarizedwaveSpinnerList.displayValues, polarization)
            } else { Timber.e( "Failed to fetch Antenna Polarization after set failure. Code: $resultCode") }
            settingStep++
            executeNextSetting()
        }
    }

    private fun setSpinnerSelectionFromValue(spinner: Spinner, values: IntArray, valueToSelect: Int) {
        val position = values.indexOf(valueToSelect)
        if (position != -1 && position < spinner.adapter.count) {
            spinner.setSelection(position)
        } else {
            Timber.w( "Value $valueToSelect not found in spinner ${spinner.id} or position out of bounds for callback update.")
            if (spinner.adapter.count > 0) spinner.setSelection(0) // Fallback to first item
        }
    }

    // --- SDKManager.Listener の実装 ---
    override fun onDriverStatusChanged(isOpened: Boolean, deviceName: String?) {
        Timber.d( "onDriverStatusChanged: isOpened=$isOpened, deviceName=$deviceName")
        val isConnected = (SdkManager.isConnected())
        updateSpinnerSelectable(isConnected, isOpened)
        if (isOpened) {
            if (!SdkManager.isScanningDevice()) {
                SdkManager.startDeviceScan()
            }
        } else {
            updateConnectButtonState(SdkManager.ConnectionStatus.DISCONNECTED, null)
        }
        if (::navigationHelper.isInitialized) {
            navigationHelper.updateConnectionStatus(isOpened && SdkManager.isConnected()) // isOpened だけでは不十分
        }
    }

    override fun onConnectionStatusChanged(status: SdkManager.ConnectionStatus, deviceAddress: String?) {
        Timber.d( "onConnectionStatusChanged: $status, Device: $deviceAddress")
        runOnUiThread { // UI更新はメインスレッドで
            updateConnectButtonState(status, deviceAddress)
            val isConnected = (SdkManager.isConnected())
            val isOpened = (SdkManager.isDriverOpened())
            updateSpinnerSelectable(isConnected, isOpened)
            if (isConnected) {
                deviceAddress?.let { macAddress ->
                    if (isValidMacAddress(macAddress)) {
                        saveSelectedString(macAddress, KEY_LAST_CONNECTED_MAC)
                        val currentSelectedDefaultHST = initialDeviceSettings.defaultHST
                        if (currentSelectedDefaultHST != macAddress) {
                            var positionToSelect = defaultHSTSpinnerList.displayValues.indexOf(macAddress)
                            if (positionToSelect == -1) {
                                var positionToSelect = defaultHSTSpinnerList.displayValues.indexOf(macAddress)
                                if (positionToSelect == -1) { // スピナーリストに接続MACが存在しない場合
                                    if (!defaultHSTSpinnerList.displayValues.contains(macAddress)) {
                                        defaultHSTSpinnerList.displayNames.add(macAddress)
                                        defaultHSTSpinnerList.displayValues.add(macAddress)
                                        defaultHSTSpinnerAdapter.notifyDataSetChanged() // アダプターにデータセットの変更を通知
                                        Timber.d( "Added $macAddress to defaultHSTSpinnerList and notified adapter.")
                                    }
                                    positionToSelect = defaultHSTSpinnerList.displayValues.indexOf(macAddress) // 再度位置を取得
                                }

                                if (positionToSelect != -1 && positionToSelect < defaultHSTSpinnerAdapter.count) {
                                    spinnerdefaultHST.post { // UIスレッドのキューにpostする
                                        try {
                                            if (positionToSelect < defaultHSTSpinnerAdapter.count) { // 再度チェック
                                                spinnerdefaultHST.setSelection(positionToSelect)
                                                Timber.d( "Default HST Spinner selection posted for position: $positionToSelect ($macAddress)")
                                            } else {
                                                Timber.w( "Position $positionToSelect out of bounds for adapter count ${defaultHSTSpinnerAdapter.count} even after post. MAC: $macAddress")
                                                if (defaultHSTSpinnerAdapter.count > 0) spinnerdefaultHST.setSelection(0)
                                            }
                                        } catch (e: Exception) {
                                            Timber.e( "Error during spinner.setSelection in post", e)
                                        }
                                    }
                                } else {
                                    Timber.w( "Could not select $macAddress in spinner. Position $positionToSelect, Adapter count ${defaultHSTSpinnerAdapter.count}")
                                    if (defaultHSTSpinnerAdapter.count > 0 && spinnerdefaultHST.selectedItemPosition >= defaultHSTSpinnerAdapter.count) {
                                        spinnerdefaultHST.post { spinnerdefaultHST.setSelection(0) } // 安全策
                                    }
                                }
                            }
                        }
                    }
                }
                SdkManager.fetchBatteryLevel()
                SdkManager.fetchFirmwareVersion()
            } else {
                if (::navigationHelper.isInitialized) {
                    navigationHelper.updateBatteryLevel(-1, 0)
                }
            }
            if (::navigationHelper.isInitialized) {
                navigationHelper.updateConnectionStatus(status == SdkManager.ConnectionStatus.CONNECTED)
            }
        }
    }

    private fun updateConnectButtonState(status: SdkManager.ConnectionStatus, connectedDeviceAddress: String?) {
        val selectedMacInSpinner = getSelectedMacAddressFromSpinner() // 現在スピナーで選択されている有効なMAC
        runOnUiThread {
            when (status) {
                SdkManager.ConnectionStatus.CONNECTED -> {
                    buttonConnectHST.text = getString(R.string.label_text_disconnectHST)
                    buttonConnectHST.isEnabled = true // 接続中は常に切断可能
                }
                SdkManager.ConnectionStatus.DISCONNECTED, SdkManager.ConnectionStatus.ERROR -> {
                    buttonConnectHST.text = getString(R.string.label_text_connectHST)
                    buttonConnectHST.isEnabled = selectedMacInSpinner != null && isValidMacAddress(selectedMacInSpinner)
                }
                SdkManager.ConnectionStatus.CONNECTING, SdkManager.ConnectionStatus.DISCONNECTING -> {
                    buttonConnectHST.text = getString(R.string.operation_in_progress)
                    buttonConnectHST.isEnabled = false
                }
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
        Timber.e( "onErrorOccurred: Op='$operation', Code=$errorCode, ExtCode=$extendedCode, Msg='$message', ConnectionLost=$isConnectionLostError")
        if (isConnectionLostError) {
            if (::navigationHelper.isInitialized) {
                navigationHelper.updateConnectionStatus(false)
                navigationHelper.updateBatteryLevel(-1, 0)
            }
        }
    }

    override fun onDeviceDiscoveryUpdate(devices: List<String>, isScanComplete: Boolean) {
    }

    override fun onTagDataReceived(tagPacks: Map<String, TagPack>) {
    }

    override fun onGenericSdkResult(operationType: SdkManager.SdkOperationType?, resultCode: Int, resultCodeExtended: Int) {
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
    // --- SDKManager.Listener の実装終了 ---

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
                screenTitle = getString(R.string.activity_settings_title), // Activityごとのタイトル
                showBackButton = true,
                customBackButtonAction = {
                    finish()
                }
            )
            // ヒントの表示設定
            navigationHelper.setupToolbarHint( hint = getString(R.string.activity_settings_hint))

            // ナビゲーションアイテム選択時のリスナーを設定
            navigationHelper.setNavigationItemSelectedListener(this)
        }catch (e: Exception) {
            Timber.e( "Exception Error in setNavigationMenu", e)
            e.printStackTrace()
        }
    }
    /* setNavigationMenu END */

    // --- NavigationHelper.NavigationItemSelectedListener の実装 ---
    override fun onNavigationItemSelected(position: Int, title: String) {
        // ここで選択されたアイテムに応じた画面遷移処理を行う
        when (position) {
            0 -> { // MainActivityへ移動
                val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
            1 -> { // FileManageActivityへ移動
                val intent = Intent(this@SettingsActivity, FileManageActivity::class.java)
                startActivity(intent)
                finish()
            }
            2 -> {
                // SettingsActivityへ遷移
                val intent = Intent(this@SettingsActivity, SettingsActivity::class.java)
                startActivity(intent)
                finish()
            }
            3 -> {
                // AboutActivityへ遷移
                val intent = Intent(this@SettingsActivity, AboutActivity::class.java)
                startActivity(intent)
            }
            // 必要に応じて他のケースを追加
            else -> {
                Toast.makeText(this@SettingsActivity, "未対応のメニュー項目: ${title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBleListInSpinner() {
        if (!SdkManager.isDriverOpened()) {
            Timber.w( "updateBleListInSpinner: Driver not open.")
            isFetchingBleList.set(false)
            return
        }
        //getBluetoothListを実行前にリストをクリア
        availableMacAddresses.clear()
        val ercd = SdkManager.getBluetoothDeviceList(availableMacAddresses)
        Timber.d( "SdkManager.getBluetoothDeviceList: ercd = $ercd")
        Timber.d( "SdkManager.getBluetoothDeviceList: availableMacAddresses contents after getBluetoothDeviceList (size: ${availableMacAddresses.size}):")
        //macAddressAdapterにデータがある場合に、updateMacAddressListViewを実行
        if (availableMacAddresses.isNotEmpty()) {
            updateMacAddressListView()
        } else {
            for (deviceAddress in availableMacAddresses) {
                Timber.d( "  - $deviceAddress")
            }
        }

        // 取得したリストでSpinnerのUIを更新
        // macAddressAdapterにデータがある場合（または空でもUIを更新する場合）にupdateMacAddressListViewを実行
        updateMacAddressListView() // このメソッド内で availableMacAddresses を使用してUIを更新

        isFetchingBleList.set(false) // 処理が完了したらフラグをリセット

        // 更新後、ドロップダウンを確実に開く（ユーザーがタップした結果なので）
        if (!isSpinnerPopupShowing(spinnerdefaultHST)) {
            spinnerdefaultHST.performClick()
        }
    }

    /* デバイスのMACアドレスのリストビューを更新 */
    private fun updateMacAddressListView() {
        try {
            val currentSelectedValue = getSelectedMacAddressFromSpinner() // 更新前の選択値を保持

            for (deviceAddress in availableMacAddresses) {
                if (!defaultHSTSpinnerList.displayValues.contains(deviceAddress)) {
                    val displayName = deviceAddress
                    defaultHSTSpinnerList.displayNames.add(displayName)
                    defaultHSTSpinnerList.displayValues.add(deviceAddress)
                }
            }

            // SharedPreferencesから最後に接続したMACアドレスをリストに追加（もし存在しなければ）
            val lastConnectedMac = loadSelectedString(KEY_LAST_CONNECTED_MAC)
            if (lastConnectedMac != null && !defaultHSTSpinnerList.displayValues.contains(lastConnectedMac)) {
                val displayNameForSavedMac = lastConnectedMac
                defaultHSTSpinnerList.displayNames.add(displayNameForSavedMac)
                defaultHSTSpinnerList.displayValues.add(lastConnectedMac)
            }

            defaultHSTSpinnerAdapter.clear()
            defaultHSTSpinnerAdapter.addAll(defaultHSTSpinnerList.displayNames.toMutableList())
            defaultHSTSpinnerAdapter.notifyDataSetChanged()

            // 更新後のリストで、以前選択されていた値、または lastConnectedMac を再選択する
            var newSelectionPosition = -1 // updateMacAddressListView 内のローカル変数
            if (currentSelectedValue != null) {
                newSelectionPosition = defaultHSTSpinnerList.displayValues.indexOf(currentSelectedValue)
            }

            // もし以前の値が見つからなければ、lastConnectedMac を試す
            if (newSelectionPosition == -1 && lastConnectedMac != null) {
                newSelectionPosition = defaultHSTSpinnerList.displayValues.indexOf(lastConnectedMac)
            }

            if (newSelectionPosition != -1) {
                spinnerdefaultHST.setSelection(newSelectionPosition, false)
            } else if (defaultHSTSpinnerAdapter.count > 0) {
                spinnerdefaultHST.setSelection(0) // どれも見つからなければ先頭
            }

        } catch (e: Exception) {
            Timber.e( "updateMacAddressListView: Exception", e)
            e.printStackTrace()
        }
    }

    private fun getSelectedMacAddressFromSpinner(): String? {
        val selectedPosition = spinnerdefaultHST.selectedItemPosition
        val emptyDefaultMac = getString(R.string.array_defaulthst_display_values)
        if (selectedPosition >= 0 && selectedPosition < defaultHSTSpinnerList.displayValues.size) {
            val selectedValue = defaultHSTSpinnerList.displayValues[selectedPosition]
            return if (isValidMacAddress(selectedValue) && selectedValue != emptyDefaultMac) {
                selectedValue
            } else {
                null
            }
        }
        return null
    }

    private fun isSpinnerPopupShowing(spinner: Spinner?): Boolean {
        return spinner?.isFocused == true && spinner.windowToken != null
    }

    private fun isValidMacAddress(macAddress: String?): Boolean {
        return macAddress != null && Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$").matches(macAddress)
    }

    private fun formatMacAddress(mac: String?): String? {
        if (mac == null) return null
        val cleanMac = mac.replace("[:-]".toRegex(), "")
        if (cleanMac.length != 12 || !cleanMac.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
            return null
        }
        return cleanMac.chunked(2).joinToString(":")
    }

    private fun saveSelectedString(value: String, key: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        Timber.d( "Saved preference: $key = $value")
    }

    private fun saveSelectedItem(value: Int, key: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).apply()
        Timber.d( "Saved preference (Int): $key = $value")
    }

    private fun saveEditTextValue(key: String, value: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        Timber.d( "Saved EditText preference: $key = $value")
    }

    private fun loadSelectedString(key: String): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(key, null)
    }

    private fun checkEditTextValue(value: String, disableValue: Int, minValue: Int, maxValue: Int): Int {
        if (value.isEmpty()) {
            return if (disableValue == 0) ErrNo.EDITTEXT_RESULT_OK else ErrNo.EDITTEXT_RESULT_EMPTY
        }
        val intValue = value.toIntOrNull() ?: return ErrNo.EDITTEXT_RESULT_NON_NUMERIC
        if (intValue == disableValue) return ErrNo.EDITTEXT_RESULT_OK
        if (intValue < minValue || intValue > maxValue) return ErrNo.EDITTEXT_RESULT_ERROR
        return ErrNo.EDITTEXT_RESULT_OK
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                READ_FOLDER_REQUEST_CODE -> {
                    data?.data?.also { uri ->
                        Timber.d( "Read folder selected: $uri")
                        try {
                            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            contentResolver.takePersistableUriPermission(uri, takeFlags)
                            grantedReadFolderUri = uri
                            // UI表示用のパス取得 (元の 'uri' を直接使う)
                            textviewreadFolder.text = uri.path ?: uri.toString()

                            // saveSelectedString(uri.toString(), KEY_IMPORT_DIR_URI) // 適用ボタンを押すまでプリファレンスには保存しない
                        } catch (e: SecurityException) {
                            Timber.e( "Failed to take persistable URI permission for read folder", e)
                            Toast.makeText(this, "フォルダの権限取得に失敗しました。", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                QR_CODE_SCANNER_REQUEST_CODE -> {
                    val scanResult = data?.getStringExtra(CameraActivity.EXTRA_SCAN_RESULT)
                    if (!scanResult.isNullOrEmpty()) {
                        Timber.d( "Scan result: $scanResult")
                        val formattedMacAddress = formatMacAddress(scanResult)
                        if (formattedMacAddress == null) {
                            Timber.w( "Invalid MAC address format from QR scan: $scanResult")
                            Toast.makeText(this, getString(R.string.QR_scan_result_invalid), Toast.LENGTH_SHORT).show()
                            return
                        }
                        Toast.makeText(this, "スキャン MAC: $formattedMacAddress", Toast.LENGTH_LONG).show()

                        var targetPosition = defaultHSTSpinnerList.displayValues.indexOf(formattedMacAddress)
                        if (targetPosition != -1) {
                            spinnerdefaultHST.setSelection(targetPosition, true)
                        } else {
                            defaultHSTSpinnerList.displayNames.add(formattedMacAddress)
                            defaultHSTSpinnerList.displayValues.add(formattedMacAddress)
                            defaultHSTSpinnerAdapter.notifyDataSetChanged()
                            targetPosition = defaultHSTSpinnerList.displayValues.indexOf(formattedMacAddress)
                            if (targetPosition != -1) spinnerdefaultHST.setSelection(targetPosition, true)
                        }
                        // QRで読み取ったMACを 最後に接続したMAC として保存
                        saveSelectedString(formattedMacAddress, KEY_LAST_CONNECTED_MAC)
                        Timber.d( "Scanned MAC '$formattedMacAddress' selected, saved as LastConnectedMAC, initialDeviceSettings updated.")
                    } else {
                        Timber.w( "QR Scan result was empty.")
                        Toast.makeText(this, getString(R.string.QR_scan_result_empty), Toast.LENGTH_SHORT).show()
                    }
                }
                WRITE_FOLDER_REQUEST_CODE -> {
                    data?.data?.also { uri ->
                        Timber.d( "Write folder selected: $uri")
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            grantedWriteFolderUri = uri
                            // UI表示用のパス取得 (元の 'uri' を直接使う)
                            textviewwriteFolder.text = uri.path ?: uri.toString()

                            // saveSelectedString(uri.toString(), KEY_EXPORT_DIR_URI) // 適用ボタンを押すまでプリファレンスには保存しない
                        } catch (e: SecurityException) {
                            Timber.e( "Failed to take persistable URI permission for write folder", e)
                            Toast.makeText(this, "フォルダの権限取得に失敗しました。", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } else if (resultCode == Activity.RESULT_CANCELED) {
            Timber.i( "onActivityResult: Operation cancelled by user. requestCode=$requestCode")
        }
    }

    private fun openDirectoryPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or // 書き込みも必要なら
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            Timber.e( "Directory picker activity not found", e)
            Toast.makeText(this, "フォルダ選択機能を利用できません。", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAdvancedSettingsVisibility() {
        if (isAdvancedSettingsVisible) {
            advancedSettingsItemsContainer.visibility = View.VISIBLE
            iconAdvancedSettingsToggle.setImageResource(R.drawable.ic_remove_circle_outline)
        } else {
            advancedSettingsItemsContainer.visibility = View.GONE
            iconAdvancedSettingsToggle.setImageResource(R.drawable.ic_add_circle_outline)
        }
    }

    fun getFolderUriToSave(context: Context, documentFile: DocumentFile?, originalUri: Uri): Uri? {
        if (documentFile == null) {
            Timber.w( "getFolderUriToSave: DocumentFile is null for original URI: $originalUri")
            // DocumentFileがnullの場合、originalUriがTree Uriである可能性を考慮
            // (ACTION_OPEN_DOCUMENT_TREE で返されるuriは fromSingleUri で DocumentFile にならないことがある)
            val treeDocFile = DocumentFile.fromTreeUri(context, originalUri)
            if (treeDocFile != null && treeDocFile.isDirectory) {
                Timber.d( "getFolderUriToSave: Original URI is a tree URI for a directory: $originalUri")
                return originalUri // Tree URI そのものがフォルダを指している
            }
            Timber.e( "getFolderUriToSave: Could not interpret original URI as DocumentFile or Tree URI: $originalUri")
            return null
        }

        return if (documentFile.isDirectory) {
            Timber.d( "getFolderUriToSave: Selected DocumentFile is a directory: ${documentFile.uri}")
            documentFile.uri
        } else if (documentFile.isFile) {
            Timber.d( "getFolderUriToSave: Selected DocumentFile is a file: ${documentFile.uri}")
            val parentDocFile = documentFile.parentFile
            if (parentDocFile != null && parentDocFile.isDirectory) {
                Timber.d( "getFolderUriToSave: Parent directory URI for file: ${parentDocFile.uri}")
                parentDocFile.uri
            } else {
                Timber.w( "getFolderUriToSave: Could not get parent directory for file URI: ${documentFile.uri}")
                null
            }
        } else {
            // isDirectoryでもisFileでもない場合 (例: 不明なタイプ、アクセス不可など)
            Timber.w( "getFolderUriToSave: DocumentFile is neither a file nor a directory: ${documentFile.uri}")
            null
        }
    }

    private fun updateSpinnerSelectable(isConnected: Boolean, isOpened: Boolean) {
        if (isConnected) {
            spinnerScanPower.isEnabled = true
            spinnerQValue.isEnabled = true
            spinnerFrequency.isEnabled = true
            spinnerSessionid.isEnabled = true
            spinnerTriggersw.isEnabled = true
            spinnerSleepmode.isEnabled = true
            spinnerModulationformat.isEnabled = true
            spinnerMiller.isEnabled = true
            editTextReadIdentifier.isEnabled = true
            spinnerflagAB.isEnabled = true
            spinnerusbcharge.isEnabled = true
            spinnerpolarizedwave.isEnabled = true
        } else {
            spinnerScanPower.isEnabled = false
            spinnerQValue.isEnabled = false
            spinnerFrequency.isEnabled = false
            spinnerSessionid.isEnabled = false
            spinnerTriggersw.isEnabled = false
            spinnerSleepmode.isEnabled = false
            spinnerModulationformat.isEnabled = false
            spinnerMiller.isEnabled = false
            editTextReadIdentifier.isEnabled = false
            spinnerflagAB.isEnabled = false
            spinnerusbcharge.isEnabled = false
            spinnerpolarizedwave.isEnabled = false
        }
        if(isOpened) {
            spinnerDriverLogLevel.isEnabled = true
        } else {
            spinnerDriverLogLevel.isEnabled = false
        }
    }
}