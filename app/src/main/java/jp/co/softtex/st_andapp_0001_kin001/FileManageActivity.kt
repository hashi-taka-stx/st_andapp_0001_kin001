package jp.co.softtex.st_andapp_0001_kin001

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.support.v4.provider.DocumentFile
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast

import java.io.IOException
import kotlin.collections.map

// TecRfidSuite とその関連クラス
import timber.log.Timber
import java.io.InputStream

class FileManageActivity : BasePaddedActivity() {
    companion object {
        private const val TAG = "FileManageActivity"
        private val PREFS_NAME = "FileManagePrefs"
        /* MainActivity画面更新のフラグ */
        const val DATA_UPDATED_RESULT_CODE = 101
    }
    //データベースヘルパーの定義
    private lateinit var dbHelper: DatabaseHelper

    private var exportDirUri: Uri? = null
    private var importDirUri: Uri? = null
    private var savedBaseFileName: String? = null

    /* レイアウト */
    private lateinit var loadButton: Button
    private lateinit var saveButton: Button
    private lateinit var closeButton: ImageButton

    //リクエストコード
    private val REQUEST_CODE_PICK_FILE = 100
    private val REQUEST_CODE_PICK_DIRECTORY = 101


        override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate:")
        //DatabaseHelperクラスをインスタンス化
        dbHelper = DatabaseHelper(this)
        // プリファレンスの取得
        loadSavedDirectoryUri()
        try {
            /* ビューの設定 */
            setView()

        } catch (e: Exception) {
            Timber.e( "Error in onCreate", e)
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::dbHelper.isInitialized) { // dbHelperが初期化済みか確認
            dbHelper.close()
        }
        Timber.i( "onDestroy: Finished")
    }

    private fun setView() {
        /* メイン画面のレイアウトを読み込む */
        setContentView(R.layout.activity_file_manage)

        /* ボタンビューの初期化 */
        setButtonView()

    }

    private fun setButtonView() {
        /* ボタンのビュー要素取得 */
        loadButton = findViewById(R.id.load_file_fab)
        saveButton = findViewById(R.id.save_file_fab)
        closeButton = findViewById(R.id.toolbar_close_button)
        /* ボタン状態の初期化 */
        loadButton.isEnabled = true
        saveButton.isEnabled = true

        // 閉じるボタンのリスナーを設定する
        closeButton.setOnClickListener {
            finish()
        }
        /* 読み込みボタンのクリック処理 */
        loadButton.setOnClickListener {
            // ファイル選択画面を開く
            openFilePicker()
        }
        /* 書き出しボタンのクリック処理 */
        saveButton.setOnClickListener {
            // ファイル保存画面を開く
            openDirectoryPicker()
        }
    }
    /* setButtonView END */

    private fun openFilePicker() {
        val initialUriToBrowse: Uri? = importDirUri?: DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "documents_root")
        Timber.d( "Opening file picker with initial URI: $initialUriToBrowse")
        val defaultFileType = resources.getString(R.string.default_file_type)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if(initialUriToBrowse != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUriToBrowse)
            }
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
    }

    private fun openDirectoryPicker() {
        val initialUriToBrowse: Uri? = exportDirUri
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or // 書き込み権限
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if(initialUriToBrowse != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUriToBrowse)
            }
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_DIRECTORY)
    }

    private fun extractBaseNameFromCsvFileName(fileName: String): String? {
        if (!fileName.endsWith(".csv", ignoreCase = true)) {
            // Not a CSV file by extension, or unexpected format
            return null
        }
        val firstUnderscoreIndex = fileName.indexOf('_')
        return if (firstUnderscoreIndex > 0) { // Ensure underscore is not the first char and exists
            fileName.substring(0, firstUnderscoreIndex)
        } else if (!fileName.contains("_") && fileName.length > 4) {
            // ファイル名に "_" がなく、".csv"を除いた部分が実質的なベース名とみなせる場合
            // 例: "MyData.csv" -> "MyData"
            fileName.substring(0, fileName.length - 4)
        } else {
            // Underscore not found or file name too short (e.g., "_.csv")
            null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != Activity.RESULT_OK) {
            Timber.w( "onActivityResult: resultCode is not OK (is $resultCode) for requestCode $requestCode")
            return
        }

        when (requestCode) {
            REQUEST_CODE_PICK_FILE -> {
                data?.data?.let { selectedFileUri ->
                    handleSelectedFileForImport(selectedFileUri)
                } ?: Timber.w( "REQUEST_CODE_PICK_FILE: No data URI received.")
            }
            REQUEST_CODE_PICK_DIRECTORY -> {
                Timber.d( "onActivityResult: REQUEST_CODE_PICK_DIRECTORY")
                data?.data?.let { directoryUri ->
                    handleDirectorySelectionForExport(directoryUri)
                } ?: Timber.w( "REQUEST_CODE_PICK_DIRECTORY: No data URI received.")
            }
        }
    }

    private fun handleSelectedFileForImport(selectedFileUri: Uri) {
        Timber.d( "handleSelectedFileForImport: Processing fileUri=$selectedFileUri")
        try {
            // 選択されたファイルへの読み取り権限を永続的に取得
            // (ACTION_OPEN_DOCUMENT から返されたURIなので、通常は成功する)
            contentResolver.takePersistableUriPermission(selectedFileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Timber.d( "Read permission granted for selected file: $selectedFileUri")

            contentResolver.openInputStream(selectedFileUri)?.use { inputStream ->
                processCsvImportAndUpdateMasterTable(inputStream) // 実際のCSV処理とDB更新

                // ファイル名からベース名を取得して保存する処理 (これは残しても良いでしょう)
                val fileName = DocumentFile.fromSingleUri(this, selectedFileUri)?.name
                if (fileName != null) {
                    val baseName = extractBaseNameFromCsvFileName(fileName)
                    if (baseName != null) {
                        saveStringToPreferences(SettingsActivity.KEY_FILE_NAME_BASE, baseName)
                        Timber.i( "Successfully imported '$fileName'. Saved base name: '$baseName'")
                    } else {
                        Timber.w( "Could not extract base name from file name: $fileName")
                    }
                } else {
                    Timber.w( "Could not retrieve file name from URI: $selectedFileUri")
                }

                Toast.makeText(this, "'${fileName ?: "選択されたファイル"}' をインポートしました。", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_OK)
                if (!isFinishing && !isDestroyed) {
                    finish()
                }

            } ?: run {
                Timber.e( "Failed to open input stream for URI: $selectedFileUri")
                Toast.makeText(this, getString(R.string.csv_toast_error_open_file_failure), Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            Timber.e( "SecurityException while processing file $selectedFileUri", e)
            Toast.makeText(this, getString(R.string.csv_toast_error_permission_failure), Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Timber.e( "IOException during file processing for $selectedFileUri", e)
            Toast.makeText(this, getString(R.string.csv_toast_error_IO_exception), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e( "Unexpected error during file processing for $selectedFileUri", e)
            Toast.makeText(this, getString(R.string.csv_toast_read_error_exception_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDirectorySelectionForExport(directoryUri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(directoryUri, takeFlags)
            Timber.d( "Access granted to selected directory for export: $directoryUri")

            exportDirUri = directoryUri
            savetDirectoryUri(SettingsActivity.KEY_EXPORT_DIR_URI, directoryUri) // 設定に保存

            exportMasterData()
        } catch (e: SecurityException) {
            Timber.e( "Failed to take permission for export directory: $directoryUri", e)
            Toast.makeText(this, getString(R.string.csv_toast_output_dir_permission_failure), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e( "Error processing export directory selection for $directoryUri", e)
            Toast.makeText(this, getString(R.string.csv_toast_output_dir_exception_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun getDisplayableUriName(uri: Uri): String {
        // DocumentFileを使用して表示名を取得しようと試みる
        val docFile = DocumentFile.fromSingleUri(this, uri) ?: DocumentFile.fromTreeUri(this, uri)
        return docFile?.name ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun processCsvImportAndUpdateMasterTable(inputStream: InputStream) {
        try {
            val parseResult = CsvExporter.parseCsvInputStream(inputStream)

            if (!parseResult.success || parseResult.headers == null || parseResult.dataRows.isEmpty()) {
                val errorMessage = parseResult.errorMessage ?: getString(R.string.csv_import_error_empty_or_no_header)
                Timber.e( "CSV parsing failed or no data: $errorMessage")
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                return
            }

            val expectedCsvHeaders = DatabaseContract.getMasterCsvExportHeaders(this)
            if (!CsvExporter.validateCsvHeaders(parseResult.headers, expectedCsvHeaders)) {
                Timber.e( "CSV header validation failed. Actual: ${parseResult.headers}, Expected: $expectedCsvHeaders")
                return
            }

            val headerToMasterColumnMap = DatabaseContract.getMasterCsvHeaderToDbColumnMap(this)
            val columnConfigs = parseResult.headers.mapNotNull { csvHeader ->
                headerToMasterColumnMap[csvHeader]?.let { masterColumn ->
                    val csvDataType = when (masterColumn.dataType) {
                        DatabaseContract.DataType.INTEGER -> CsvExporter.CsvDataType.INTEGER
                        DatabaseContract.DataType.BOOLEAN -> CsvExporter.CsvDataType.BOOLEAN
                        DatabaseContract.DataType.REAL -> CsvExporter.CsvDataType.DOUBLE
                        DatabaseContract.DataType.TEXT -> CsvExporter.CsvDataType.STRING
                        DatabaseContract.DataType.BLOB -> null
                        else -> {
                            Timber.w( "Unsupported masterColumn.dataType for CSV conversion: ${masterColumn.dataType}")
                            null
                        }
                    }
                    csvDataType?.let {
                        CsvExporter.CsvColumnMappingConfig(
                            csvHeaderName = csvHeader,
                            targetKey = masterColumn.getColumnName(this),
                            expectedDataType = it,
                            isOptional = masterColumn.isNullable() // Assuming isNullable method in MasterColumn
                        )
                    }
                }
            }

            if (columnConfigs.size != parseResult.headers.size || columnConfigs.isEmpty()) {
                Timber.e( "Failed to derive valid column configurations from CSV headers. Configs derived: ${columnConfigs.size}, Headers found: ${parseResult.headers.size}")
                return
            }

            val mappedDataList = CsvExporter.mapCsvRowsToTypedData(
                parseResult.dataRows,
                parseResult.headers,
                columnConfigs
            )

            if (mappedDataList.isEmpty() && parseResult.dataRows.isNotEmpty()) {
                Timber.w( "No data could be mapped from CSV rows. All rows might have had errors or were empty after type conversion attempts.")
                // return // Optionally return if no data to process
            }

            val contentValuesList = convertMappedDataToMasterContentValues(mappedDataList)

            if (contentValuesList.isNotEmpty()) {
                dbHelper.clearTable(DatabaseContract.TableName.TABLE_NAME_MASTER)
                var successCount = 0
                contentValuesList.forEach { values ->
                    if (dbHelper.insertItem(DatabaseContract.TableName.TABLE_NAME_MASTER, values) != -1L) {
                        successCount++
                    }
                }
                Timber.i( "$successCount rows inserted into Master table from CSV file.")

                printMasterTableToLog() // Assumed helper method

                // Update Location Table
                val dataForLocationTable = parseResult.dataRows.mapNotNull { row ->
                    if (parseResult.headers != null && row.size == parseResult.headers.size) {
                        parseResult.headers.zip(row).toMap()
                    } else {
                        null // Skip malformed rows for location table update
                    }
                }
                if (dataForLocationTable.isNotEmpty()) {
                    dbHelper.createLocationList(dataForLocationTable)
                }

                val mainActivityIntent = Intent()
                setResult(DATA_UPDATED_RESULT_CODE, mainActivityIntent)
                finish()
            } else {
                Timber.i( "No valid data to insert into database after CSV processing.")
            }

        } catch (e: CsvExporter.CsvParseException) {
            Timber.e( "CSV Parsing error: ${e.message}", e)
        } catch (e: Exception) {
            Timber.e( "Error processing CSV and updating master table", e)
        }
    }

    private fun convertMappedDataToMasterContentValues(mappedDataList: List<Map<String, Any?>>): List<ContentValues> {
        val contentValuesList = mutableListOf<ContentValues>()
        for (itemMap in mappedDataList) {
            val values = ContentValues()
            var hasValues = false
            DatabaseContract.MasterColumn.values().forEach { masterColumn ->
                if (masterColumn == DatabaseContract.MasterColumn.ID) return@forEach
                val dbColumnName = masterColumn.getColumnName(this)
                val value = itemMap[dbColumnName]

                if (value != null) {
                    try {
                        when (masterColumn.dataType) {
                            DatabaseContract.DataType.INTEGER -> values.put(dbColumnName, (value as? Number)?.toInt() ?: value.toString().toInt())
                            DatabaseContract.DataType.BOOLEAN -> values.put(dbColumnName, if (value == true) 1 else 0)
                            DatabaseContract.DataType.REAL -> values.put(dbColumnName, (value as? Number)?.toDouble() ?: value.toString().toDouble())
                            DatabaseContract.DataType.TEXT -> values.put(dbColumnName, value.toString())
                            DatabaseContract.DataType.BLOB -> { /* Skip */ }
                        }
                        hasValues = true
                    } catch (e: NumberFormatException) {
                        Timber.w( "ConvertValue: Failed for column '$dbColumnName', value '$value'. Skipping.", e)
                    } catch (e: ClassCastException) {
                        Timber.w( "ConvertValue: Cast failed for column '$dbColumnName', value '$value'. Type was ${value.javaClass.name}. Skipping.", e)
                    }
                } else if (masterColumn.isNullable()) { // Check if DB column is nullable
                    values.putNull(dbColumnName)
                }
                // If not nullable and value is null, it's a data integrity issue, might log or skip row
            }
            if (hasValues && values.size() > 0) {
                contentValuesList.add(values)
            } else if (itemMap.isNotEmpty()) { // Log if a non-empty map resulted in no ContentValues
                Timber.w( "Row produced no values for ContentValues, itemMap: $itemMap")
            }
        }
        return contentValuesList
    }

    fun exportMasterData() {
        if (exportDirUri == null) {
            Toast.makeText(this, getString(R.string.csv_toast_output_dir_not_selected), Toast.LENGTH_LONG).show()
            openDirectoryPicker() // Prompt to select directory
            return
        }

        try {
            val itemsFromDb = dbHelper.getItems(DatabaseContract.TableName.TABLE_NAME_MASTER)
            if (itemsFromDb.isEmpty()) {
                return
            }

            var baseFileName = savedBaseFileName
            if( baseFileName == null ) {
                baseFileName = getString(R.string.default_csv_filename_base)
            }
            val exportTag = getString(R.string.default_csv_export_tag)
            val csvFileName = CsvExporter.createCsvFileName(baseFileName, exportTag)

            val documentFile = DocumentFile.fromTreeUri(this, exportDirUri!!)
                ?.createFile("text/csv", csvFileName)

            if (documentFile == null) {
                Toast.makeText(this, getString(R.string.csv_toast_error_creating_file), Toast.LENGTH_LONG).show()
                return
            }

            val csvHeaders = DatabaseContract.getMasterCsvExportHeaders(this)
            val csvHeaderToDbColumnMap = DatabaseContract.getMasterCsvHeaderToDbColumnMap(this)

            val dataForCsvExporter = itemsFromDb.map { dbRowMap ->
                csvHeaders.associateWith { csvHeader ->
                    csvHeaderToDbColumnMap[csvHeader]?.let { masterColumn ->
                        dbRowMap[masterColumn.getColumnName(this)]
                    }
                }
            }

            contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
                CsvExporter.writeCsvToStream(outputStream, csvHeaders, dataForCsvExporter)
                Timber.d( "Master CSV file saved to: ${documentFile.uri}")
                Toast.makeText(this, getString(R.string.csv_toast_saved_successfully_to, documentFile.name), Toast.LENGTH_LONG).show()
            } ?: run {
                Timber.e( "Failed to open output stream for Master CSV: ${documentFile.uri}")
                Toast.makeText(this, getString(R.string.csv_toast_error_opening_output_stream), Toast.LENGTH_LONG).show()
            }
        } catch (e: CsvExporter.CsvWriteException) {
            Timber.e( "CsvWriteException exporting Master data", e)
        } catch (e: IOException) {
            Timber.e( "IOException exporting Master data", e)
        } catch (e: Exception) {
            Timber.e( "Error exporting Master data", e)
        }
    }

    // Assumed helper method (not part of CsvExporter)
    private fun printMasterTableToLog() {
        val items = dbHelper.getItems(DatabaseContract.TableName.TABLE_NAME_MASTER)
        Timber.d( "Current Master Table Data (${items.size} rows):")
        items.forEachIndexed { index, mutableMap ->
            Timber.d( "Row ${index + 1}: $mutableMap")
        }
    }

    private fun savetDirectoryUri(prefs_key: String, uri: Uri) {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(prefs_key, uri.toString()).apply()
        Timber.d( "Saved export directory URI to SharedPreferences: $uri")
    }

    private fun saveStringToPreferences(key: String, value: String) {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        Timber.d( "Saved to SharedPreferences: $key = $value")
    }

    private fun loadSavedDirectoryUri() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        var uriString = prefs.getString(SettingsActivity.KEY_IMPORT_DIR_URI, null)
        if (uriString != null) {
            importDirUri = Uri.parse(uriString)
            Timber.d( "Loaded export directory URI from SharedPreferences: $importDirUri")
        }
        uriString = prefs.getString(SettingsActivity.KEY_EXPORT_DIR_URI, null)
        if (uriString != null) {
            exportDirUri = Uri.parse(uriString)
            Timber.d( "Loaded import directory URI from SharedPreferences: $exportDirUri")
        }
        val baseName = prefs.getString(SettingsActivity.KEY_FILE_NAME_BASE, null)
        if (baseName != null ) {
            savedBaseFileName = baseName
            Timber.d( "Loaded base file name from SharedPreferences: $savedBaseFileName")
        }

    }

    data class Result(val success: Boolean, val data: List<Map<String, Any?>> = emptyList())
}