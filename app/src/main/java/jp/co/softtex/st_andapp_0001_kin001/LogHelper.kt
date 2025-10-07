package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.text.uppercase

object LogLevel {
    const val VERBOSE = Log.VERBOSE
    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR
    const val ASSERT = Log.ASSERT
    const val SUPPRESS = 8 // ログを一切出力しない特別なレベル

    fun toString(level: Int): String {
        return when (level) {
            VERBOSE -> "VERBOSE"
            DEBUG -> "DEBUG"
            INFO -> "INFO"
            WARN -> "WARN"
            ERROR -> "ERROR"
            ASSERT -> "ASSERT"
            SUPPRESS -> "SUPPRESS"
            else -> "UNKNOWN ($level)"
        }
    }

    fun fromString(levelStr: String): Int {
        return when (levelStr.uppercase()) {
            "VERBOSE" -> VERBOSE
            "DEBUG" -> DEBUG
            "INFO" -> INFO
            "WARN" -> WARN
            "ERROR" -> ERROR
            "ASSERT" -> ASSERT
            "SUPPRESS" -> SUPPRESS
            else -> INFO // 不明な文字列の場合はINFOをデフォルトとする
        }
    }
}

/**
 * 動的にログレベルを制御し、Logcat出力とLogHelperへのファイル書き込みを行うTimber Tree。
 */
class DynamicLogLevelTree(
    private val appContextForTree: Context // Tree初期化専用のContext
) : Timber.DebugTree() {

    val TAG = "DynamicLogLevelTree"

    companion object {
        @Volatile
        var currentMinLogLevel: Int = LogLevel.INFO // 安全な初期値

        // このメソッドは、MyApplicationでTreeをplantする直前に呼ぶことを想定
        fun initializeAndGetInitialLogLevel(context: Context): Int {
            currentMinLogLevel = LogHelper.getLogPreference(context.applicationContext,SettingsActivity.KEY_APP_LOG_LEVEL,LogLevel.INFO)
            return currentMinLogLevel
        }

        fun updateMinLogLevel(context: Context) {
            val newLevel = LogHelper.getLogPreference(context.applicationContext,SettingsActivity.KEY_APP_LOG_LEVEL,LogLevel.INFO)
            if (newLevel != currentMinLogLevel) {
                currentMinLogLevel = newLevel
                Timber.i( "Log level updated to: ${LogLevel.toString(currentMinLogLevel)}")
            }
        }
    }

    init {
        currentMinLogLevel = LogHelper.getLogPreference(appContextForTree.applicationContext,SettingsActivity.KEY_APP_LOG_LEVEL,LogLevel.INFO)
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return ((priority >= currentMinLogLevel) && (currentMinLogLevel != LogLevel.SUPPRESS))
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) {
            return
        }

        // 1. Logcatへの出力 (DebugTreeの機能を利用)
        // Timber.tag()で指定されたタグがあればそれが使われ、なければ自動生成される
        super.log(priority, tag, message, t)

        // 2. LogHelperへのファイル書き込みとメモリ上のリストへの追加
        // ファイルログ用のタグは、Logcatと同じものを使う試み
        // super.logが使うタグは直接取得できないため、明示的なタグがあればそれ、なければスタックから推測
        val fileLogTag = tag ?: inferTagFromStack(Throwable()) // 現在のスタックから推測

        val logTimestamp = LogHelper.getCurrentTimeString(LogHelper.TIME_FORMAT_LOG_NUM)
        var logEntry = "$logTimestamp "
        if (fileLogTag != null) {
            logEntry += "[$fileLogTag] "
        }
        if( priority >= 0) {
            logEntry += "(${LogLevel.toString(priority)})\n"
        }
        logEntry += message

        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            logEntry += "\nSTACKTRACE:\n$sw"
        }
        LogHelper.addLogInternal(logEntry)
    }

    /**
     * Timber.d() などが呼び出された場所のクラス名をタグとして推測する。
     * @param throwable スタックトレース取得用のThrowableインスタンス
     */
    private fun inferTagFromStack(throwable: Throwable): String? {
        val stackTrace = throwable.stackTrace
        val customTreeCallDepth = stackTrace.indexOfFirst { it.className == DynamicLogLevelTree::class.java.name }

        // DynamicLogLevelTree.log の呼び出し元を探す
        val timberApiCallIndex = customTreeCallDepth + 2

        if (timberApiCallIndex >= 0 && timberApiCallIndex < stackTrace.size) {
            val element = stackTrace[timberApiCallIndex]
            var autoTag = element.className.substringAfterLast('.')
            autoTag = autoTag.substringBefore('$')
            return autoTag
        }
        return null // 推測できなかった場合
    }
}

object LogHelper {
    //　ログファイル出力設定
    private const val MAX_FILE_SIZE = 4 * 1024 * 1024 // ファイルの最大サイズ(4MB)
    private const val MAX_FOLDER_SIZE = 512L * 1024 * 1024 // フォルダ内のログファイル容量(512MB)
    private const val MAX_FILE_COUNT = 1000 // ファイルの最大数(1000ファイル)
    private const val MAX_FILE_AGE_DAYS = 365 // ログローテート期間(1年)
    private const val LOG_FOLDER_NAME = "log"
    private const val LOG_FILE_NAME = "app_log_"
    private const val LOG_FILE_EXTENSION = ".log"

    // ログ表示設定
    const val DISPLAY_MAX_LOG_SIZE = 1000
    const val DISPLAY_PAGE_SIZE = 50

    // ログフォーマット指定
    const val TIME_FORMAT_LOG_NUM = 1
    const val TIME_FORMAT_FIlE_NUM = 2
    const val TIME_FORMAT_LOG = "yyyy-MM-dd HH:mm:ss.SSS"
    const val TIME_FORMAT_FILE = "yyyyMMdd_HHmmss-SSS"

    const val TAG = "LogHelper"


    // 表示用のログリスト
    val appLogList: MutableList<String> = Collections.synchronizedList(mutableListOf())
    // スナップショット取得
    fun getCompleteLogListSnapshot(): List<String> {
        synchronized(appLogList) {
            return ArrayList(appLogList) // 新しいArrayListとしてコピーを返す
        }
    }

    private lateinit var appContext: Context
    private var currentLogFile: File? = null
    private var logFolder: File? = null
    private var log_age: Int = MAX_FILE_AGE_DAYS

    // ファイル入出力を初期化
    fun initializeFileLogging(context: Context) {
        appContext = context.applicationContext // ★ Contextを最初に設定
        Timber.i("Initializing file logging...") // ★ LogHelper自体のログ

        try {
            val logAgePref = getLogPreference(
                appContext,
                SettingsActivity.KEY_APP_LOG_ROTATE_DAYS,
                MAX_FILE_AGE_DAYS
            )
            if (logAgePref != log_age) {
                log_age = logAgePref
                Timber.i("Log rotation age updated to: $log_age days")
            }

            logFolder = File(appContext.filesDir, LOG_FOLDER_NAME)
            if (!logFolder!!.exists()) {
                Timber.tag(TAG)
                    .i("Log folder does not exist. Attempting to create: ${logFolder!!.absolutePath}")
                if (logFolder!!.mkdirs()) {
                    Timber.i("Log folder created successfully.")
                } else {
                    Timber.e("Failed to create log folder: ${logFolder!!.absolutePath}")
                    // フォルダ作成失敗時は、以降のファイル操作は無意味なので早期リターンも検討
                    return
                }
            } else {
                Timber.i("Log folder already exists: ${logFolder!!.absolutePath}")
            }

            // 初期ファイル作成とローテーション
            // createNewLogFileの前にrotateLogsを呼ぶと、既存の古すぎるファイルを先に消せる
            rotateLogs() // ログローテーション実行
            if (currentLogFile == null || !currentLogFile!!.exists() || currentLogFile!!.length() > MAX_FILE_SIZE) {
                createNewLogFile() // 新しいログファイル作成
            }
            if (currentLogFile == null || !currentLogFile!!.exists()) {
                Timber.e("Still no valid log file after initialization attempts.")
            } else {
                Timber.i("Current log file set to: ${currentLogFile?.name}")
            }

            Timber.i("File logging initialized.")

        } catch (e: Exception) {
            Log.e(TAG, "Exception occurred in initializeFileLogging.", e)
        }
    }

    private fun createNewLogFile() {
        if (logFolder == null) { // フォルダが初期化されていない場合
            Timber.e("Log folder is not initialized. Cannot create new log file.")
            currentLogFile = null
            return
        }
        if (!logFolder!!.exists()) {
            Timber.i("Log folder does not exist at createNewLogFile. Attempting to create: ${logFolder!!.absolutePath}")
            if (!logFolder!!.mkdirs()) {
                Timber.e("Failed to create log folder during createNewLogFile: ${logFolder!!.absolutePath}")
                currentLogFile = null
                return
            }
            Timber.i("Log folder created during createNewLogFile.")
        }

        val timestamp = getCurrentTimeString(TIME_FORMAT_FIlE_NUM)
        val logFileNameWithTimestamp = "$LOG_FILE_NAME$timestamp$LOG_FILE_EXTENSION"
        val newFile = File(logFolder, logFileNameWithTimestamp)

        try {
            if (newFile.createNewFile()) { // createNewFile() はファイルが新規作成された場合にtrueを返す
                currentLogFile = newFile
                Timber.i("New log file created: ${currentLogFile?.name} at ${currentLogFile?.absolutePath}")
            } else {
                if (newFile.exists()) {
                    Timber.w("Log file already existed (should be rare), using it: ${newFile.name}")
                    currentLogFile = newFile // 既存ファイルを現在のログファイルとして使用
                } else {
                    Timber.e("Failed to create new log file (createNewFile returned false, file does not exist): ${newFile.name}")
                    currentLogFile = null // 作成失敗
                }
            }
        } catch (e: java.io.IOException) { // より具体的な例外をキャッチ
            Timber.e(e, "IOException during createNewLogFile for ${newFile.name}")
            currentLogFile = null
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException during createNewLogFile for ${newFile.name}")
            currentLogFile = null
        } catch (e: Exception) { // その他の予期せぬ例外
            Timber.e(e, "Unexpected exception during createNewLogFile for ${newFile.name}")
            currentLogFile = null
        }
    }

    // ログローテーション
    private fun rotateLogs() {
        if (logFolder == null || !logFolder!!.exists()) {
            Timber.w("Cannot rotate logs, logFolder is null or does not exist.")
            return
        }
        Timber.d("Starting log rotation in folder: ${logFolder!!.absolutePath}")

        logFolder?.let { folder -> // logFolderがnullでないことを保証
            val files = folder.listFiles { _, name -> name.startsWith(LOG_FILE_NAME) && name.endsWith(LOG_FILE_EXTENSION) }
                ?.filterNotNull() // listFilesがnull要素を含む可能性を排除
                ?.sortedBy { it.lastModified() }
                ?.toMutableList()

            if (files == null || files.isEmpty()) {
                Timber.d("No log files found to rotate.")
                return
            }

            Timber.d("Found ${files.size} log files for potential rotation.")

            // 1. フォルダ全体のサイズに基づくローテーション
            var totalSize = files.sumOf { it.length() }
            Timber.d("Current total log folder size: $totalSize bytes (Max: $MAX_FOLDER_SIZE bytes)")
            while (totalSize > MAX_FOLDER_SIZE && files.isNotEmpty()) {
                val oldestFile = files.first()
                Timber.i("Total folder size ($totalSize) exceeds limit ($MAX_FOLDER_SIZE). Deleting oldest file (size based): ${oldestFile.name} (size: ${oldestFile.length()})")
                totalSize -= oldestFile.length()
                if (!oldestFile.delete()) {
                    Timber.w("Failed to delete old log file (size based): ${oldestFile.name}")
                }
                files.removeAt(0)
            }
            // 2. ファイル数に基づくローテーション
            Timber.d("Current log file count: ${files.size} (Max: $MAX_FILE_COUNT)")
            while (files.size > MAX_FILE_COUNT && files.isNotEmpty()) { // files.isNotEmpty() を追加
                val oldestFile = files.first()
                Timber.i("File count (${files.size}) exceeds limit ($MAX_FILE_COUNT). Deleting oldest file (count based): ${oldestFile.name}")
                if (!oldestFile.delete()) {
                    Timber.w("Failed to delete old log file (count based): ${oldestFile.name}")
                }
                files.removeAt(0) // ここで削除するので、上のtotalSizeの再計算は不要
            }
            // 3. ファイルの日付に基づくローテーション
            val currentTime = System.currentTimeMillis()
            val maxAgeMillis = log_age * 24L * 60 * 60 * 1000 // log_ageは日数
            Timber.d("Checking for files older than $log_age days (Max age millis: $maxAgeMillis).")
            val initialFileCountForAgeCheck = files.size
            files.removeAll { file ->
                val fileAge = currentTime - file.lastModified()
                val isTooOld = fileAge > maxAgeMillis
                if (isTooOld) {
                    Timber.i("File ${file.name} (age: $fileAge ms) is older than $log_age days. Deleting.")
                    if (!file.delete()) {
                        Timber.w("Failed to delete old log file (age based): ${file.name}")
                    }
                }
                isTooOld
            }
            val deletedByAgeCount = initialFileCountForAgeCheck - files.size
            if (deletedByAgeCount > 0) {
                Timber.d("$deletedByAgeCount files deleted based on age.")
            }
            Timber.d("Log rotation finished.")
        }
    }
    /**
     * DynamicLogLevelTreeから呼び出される内部的なログ追加メソッド。
     * フォーマット済みのメッセージを受け取り、メモリとファイルに保存する。
     */
    internal fun addLogInternal(formattedMessage: String) {
        // 1. メモリ上のリストに追加
        synchronized(appLogList) {
            if (appLogList.size >= DISPLAY_MAX_LOG_SIZE) {
                appLogList.removeAt(0) // 古いものから削除
            }
            appLogList.add(formattedMessage)
        }
        // 2. 非同期でファイル書き込み
        // AsyncTask は非推奨です。Kotlin Coroutines + Flow または WorkManager の使用を強く推奨します。
        // ここでは最小限の修正に留めますが、将来的な置き換えを検討してください。
        @Suppress("DEPRECATION")
        object : AsyncTask<String, Void, Void>() {
            @Deprecated("AsyncTask.doInBackground is deprecated")
            override fun doInBackground(vararg params: String?): Void? {
                val messageToLog = params[0] ?: return null

                synchronized(LogHelper) { // LogHelperオブジェクトで同期
                    if (!::appContext.isInitialized) {
                        // Timberが使えるか不明なため、標準Logを使用
                        Log.e("LogHelperCritical", "LogHelper appContext not initialized before writing to file (AsyncTask).")
                        return null
                    }

                    // logFolderのチェックは createNewLogFile 内でも行われるが、早期リターンとしてここにもあると良い
                    if (logFolder == null) {
                        Log.e("LogHelperCritical", "logFolder is null in AsyncTask. Cannot proceed to write log.")
                        return null
                    }
                    if (!logFolder!!.exists()) {
                        Timber.tag(TAG).e("logFolder does not exist in AsyncTask. Attempting to create it. Path: ${logFolder!!.absolutePath}")
                        if (!logFolder!!.mkdirs()) {
                            Timber.tag(TAG).e("Failed to create logFolder in AsyncTask. Path: ${logFolder!!.absolutePath}")
                            return null // フォルダが作れないなら書き込めない
                        }
                    }
                    // ファイルの存在とサイズチェック、必要なら再作成
                    // currentLogFile が null の場合、ファイルが存在しない場合、サイズオーバーの場合に新しいファイルを作成
                    if (currentLogFile == null || !currentLogFile!!.exists() || currentLogFile!!.length() > MAX_FILE_SIZE) {
                        Timber.tag(TAG).w("Log file issue in AsyncTask. Current: ${currentLogFile?.name}, Exists: ${currentLogFile?.exists()}, Length: ${currentLogFile?.length()}. Attempting to (re)create.")
                        createNewLogFile() // 新しいファイルを作成 (または試みる)
                        if (currentLogFile != null && currentLogFile!!.exists()) {
                            Timber.tag(TAG).i("New log file set after check in AsyncTask: ${currentLogFile!!.name}")
                            rotateLogs()
                        } else {
                            Timber.tag(TAG).e("Failed to ensure a valid log file in AsyncTask even after createNewLogFile. Cannot write log.")
                            return null // 有効なログファイルが確保できなければ書き込み中断
                        }
                    }
                    currentLogFile?.let { file -> // 安全のため再度nullチェック
                        try {
                            // formattedMessage には既にタイムスタンプとタグが含まれている想定
                            FileWriter(file, true).use { writer -> // 'true' で追記モード
                                writer.append("$messageToLog\n")
                            }
                        } catch (e: java.io.IOException) {
                            Timber.tag(TAG).e(e, "IOException writing to log file: ${file.absolutePath}")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Unexpected exception writing to log file: ${file.absolutePath}")
                        }
                    } ?: run {
                        Timber.tag(TAG).e("Critical: currentLogFile is null in AsyncTask at the point of writing, despite checks. Log not written: $messageToLog")
                    }
                }
                return null
            }
        }.execute(formattedMessage) // formattedMessage を AsyncTask に渡す
    }

    fun getCurrentTimeString(format: Int): String {
        val dateFormatStr = when (format) {
            TIME_FORMAT_LOG_NUM -> TIME_FORMAT_LOG
            TIME_FORMAT_FIlE_NUM -> TIME_FORMAT_FILE
            else -> {
                Timber.tag(TAG).w("Invalid time format number: $format")
                return ""
            }
        }
        return try {
            val dateFormat = SimpleDateFormat(dateFormatStr, Locale.getDefault())
            dateFormat.format(Date())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to format time string for format: $dateFormatStr")
            ""
        }
    }

    fun getLogPreference(context: Context, pref_key: String, default_value: Int): Int {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        return prefs.getInt(pref_key, default_value)
    }
}