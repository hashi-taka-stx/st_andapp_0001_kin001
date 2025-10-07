package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context

object DatabaseContract {
    const val DATABASE_NAME = "st_andapp_0001_kin001.db"
    const val DATABASE_VERSION = 1

    object TableName {
        const val TABLE_NAME_MASTER = "master"
        const val TABLE_NAME_LOCATION = "location"
        const val TABLE_NAME_PRODUCT = "product"
        const val TABLE_NAME_STOCK = "stock"
    }

    object DataType {
        const val TEXT = "TEXT"
        const val INTEGER = "INTEGER"
        const val REAL = "REAL"
        const val BLOB = "BLOB"
        const val BOOLEAN = "BOOLEAN"
    }

    object ScanResult {
        const val UNCHECKED_NUM = 0
        const val MATCH_NUM = 1
        const val UNDER_NUM = 2
        const val OVER_NUM = 3
    }

    /**
     * テーブルのカラム定義のための共通インターフェース。
     * カラム名をstringリソースで管理する。
     */
    interface DefinesColumn {
        val columnNameResId: Int  // R.string.db_col_xxx を指定
        val dataType: String
        val constraints: String

        /**
         * Contextを使用して、stringリソースIDから実際のカラム名を取得します。
         */
        fun getColumnName(context: Context): String = context.getString(columnNameResId)

        /**
         * "CREATE TABLE"文で使用するカラム定義文字列を生成します。
         * 例: "barcode_no INTEGER NOT NULL"
         */
        fun getColumnDefinition(context: Context): String =
            "${getColumnName(context)} $dataType $constraints".trim()

    }

    /**
     * Masterテーブルのカラム定義。
     */
    enum class MasterColumn(
        override val columnNameResId: Int,
        override val dataType: String,
        override val constraints: String = "",
        val csvHeaderResId: Int? = null // CSVヘッダーの日本語名リソースID
    ) : DefinesColumn {
        ID(R.string.db_col_id, "INTEGER", "PRIMARY KEY AUTOINCREMENT"),
        BARCODE_NO(R.string.db_col_barcode_no, "TEXT", "NOT NULL", R.string.csv_header_barcode_no),
        STOCK_DATE(R.string.db_col_stock_date, "TEXT", "", R.string.csv_header_stock_date),
        LOCATION(R.string.db_col_location, "TEXT", "", R.string.csv_header_location),
        PRODUCT_NAME(R.string.db_col_product_name, "TEXT", "", R.string.csv_header_product_name),
        BOOK_INVENTORY(R.string.db_col_book_inventory, "INTEGER", "", R.string.csv_header_book_inventory),
        PHYSICAL_INVENTORY(R.string.db_col_physical_inventory, "INTEGER", "DEFAULT NULL", R.string.csv_header_physical_inventory),
        PRODUCT_EPC(R.string.db_col_product_epc, "TEXT", "", R.string.csv_header_product_epc),
        SCAN_RESULT(R.string.db_col_scan_result, "TEXT", "", R.string.csv_header_scan_result);

        fun getCsvHeader(context: Context): String? = csvHeaderResId?.let { context.getString(it) }

        /**
         * csvHeaderResIdから対応するデータベースカラム名 (R.string.db_col_xxx のキー部分) を推測する。
         * これは、CSVヘッダー定義のname属性とdb_col_xxxのname属性に強い関連性がある場合に機能する。
         * (例: csv_header_barcode_no -> db_col_barcode_no -> "barcode_no")
         * このロジックは、DatabaseHelperでのマッピングを補助するために使用できる。
         */
        fun getAssociatedDbColumnKeyFromCsvHeader(context: Context): String? {
            return csvHeaderResId?.let {
                try {
                    val csvHeaderResourceName = context.resources.getResourceEntryName(it) // "csv_header_barcode_no"
                    // "csv_header_barcode_no" から "barcode_no" を取り出し、"db_col_barcode_no" のキー部分と一致させる
                    val derivedKey = csvHeaderResourceName.removePrefix("csv_header_")
                    // この derivedKey を使って、対応する db_col_xxx のリソースIDを探し、そのキーを返す (少し複雑)
                    // より単純には、このderivedKey自体がdb_col_xxxのキー部分と一致することを期待する
                    if (derivedKey.isNotEmpty()) derivedKey else null
                } catch (e: Exception) {
                    null
                }
            }
        }
        fun isNullable(): Boolean {
            // "NOT NULL" が含まれていなければ、基本的にNULL許容
            // "PRIMARY KEY" は通常 NOT NULL を暗黙的に含むが、SQLiteではそうでない場合もあるので、明示的にNOT NULLもチェック
            val constraintsUpper = constraints.uppercase() // 大文字小文字を区別しない比較のため
            return !constraintsUpper.contains("NOT NULL") && !constraintsUpper.contains("PRIMARY KEY") || constraintsUpper.contains("DEFAULT NULL")
        }
    }

    /**
     * Locationテーブルのカラム定義。
     */
    enum class LocationColumn(
        override val columnNameResId: Int,
        override val dataType: String,
        override val constraints: String = ""
    ) : DefinesColumn {
        ID(R.string.db_col_id, "INTEGER", "PRIMARY KEY AUTOINCREMENT"),
        LOCATION(R.string.db_col_location, "TEXT", "UNIQUE NOT NULL");

        companion object {
            fun from(context: Context): Array<String> = arrayOf(LOCATION.getColumnName(context))
            fun to(): IntArray = intArrayOf(R.id.layout_row_location_textview_location)
        }
    }

    /**
     * Productテーブルのカラム定義。
     */
    enum class ProductColumn(
        override val columnNameResId: Int,
        override val dataType: String,
        override val constraints: String = ""
    ) : DefinesColumn {
        ID(R.string.db_col_id, "INTEGER", "PRIMARY KEY AUTOINCREMENT"),
        BARCODE_NO(R.string.db_col_barcode_no, "TEXT", "NOT NULL"),
        STOCK_DATE(R.string.db_col_stock_date, "TEXT", ""),
        LOCATION(R.string.db_col_location, "TEXT", ""),
        PRODUCT_NAME(R.string.db_col_product_name, "TEXT", ""),
        BOOK_INVENTORY(R.string.db_col_book_inventory, "INTEGER", ""),
        PHYSICAL_INVENTORY(R.string.db_col_physical_inventory, "INTEGER", "DEFAULT NULL"),
        PRODUCT_EPC(R.string.db_col_product_epc, "TEXT", "UNIQUE NOT NULL"), // ProductTableでのキー
        SCAN_RESULT(R.string.db_col_scan_result, "TEXT", "");

        companion object {
            fun from(context: Context): Array<String> = arrayOf(
                BARCODE_NO.getColumnName(context),
                PRODUCT_NAME.getColumnName(context),
                BOOK_INVENTORY.getColumnName(context),
                PHYSICAL_INVENTORY.getColumnName(context),
                SCAN_RESULT.getColumnName(context)
            )

            fun to(): IntArray = intArrayOf(
                R.id.layout_row_product_textview_barcode_no,
                R.id.layout_row_product_textview_product_name,
                R.id.layout_row_product_textview_book_inventory_num,
                R.id.layout_row_product_textview_physical_inventory_num
            )
        }
    }

    /**
     * Stockテーブルのカラム定義。
     */
    enum class StockColumn(
        override val columnNameResId: Int,
        override val dataType: String,
        override val constraints: String = ""
    ) : DefinesColumn {
        ID(R.string.db_col_id, "INTEGER", "PRIMARY KEY AUTOINCREMENT"),
        BARCODE_NO(R.string.db_col_barcode_no, "TEXT", "NOT NULL"),
        STOCK_DATE(R.string.db_col_stock_date, "TEXT", ""),
        LOCATION(R.string.db_col_location, "TEXT", ""),
        PRODUCT_NAME(R.string.db_col_product_name, "TEXT", ""),
        BOOK_INVENTORY(R.string.db_col_book_inventory, "INTEGER", ""),
        PHYSICAL_INVENTORY(R.string.db_col_physical_inventory, "INTEGER", "DEFAULT NULL"), // 棚卸在庫用
        PRODUCT_EPC(R.string.db_col_product_epc, "TEXT", "UNIQUE NOT NULL"),
        SCAN_RESULT(R.string.db_col_scan_result, "TEXT", "DEFAULT NULL");

        companion object {
            fun from(context: Context): Array<String> = arrayOf(
                BARCODE_NO.getColumnName(context),
                PRODUCT_NAME.getColumnName(context),
                PRODUCT_EPC.getColumnName(context),
                SCAN_RESULT.getColumnName(context)
            )

            fun to(): IntArray = intArrayOf()
        }
    }

    /**
     * 指定されたテーブル名の "CREATE TABLE" SQL文を生成します。
     * @param context Contextオブジェクト。カラム名リソースの解決に必要。
     * @param tableName 作成するテーブルの名前。TableName objectの定数を使用。
     * @return CREATE TABLE SQL文字列。
     * @throws IllegalArgumentException 未知のテーブル名が指定された場合。
     */
    fun getCreateTableSql(context: Context, tableName: String): String {
        val columnsEnumValues: Array<out DefinesColumn> = when (tableName) {
            TableName.TABLE_NAME_MASTER -> MasterColumn.values()
            TableName.TABLE_NAME_LOCATION -> LocationColumn.values()
            TableName.TABLE_NAME_PRODUCT -> ProductColumn.values()
            TableName.TABLE_NAME_STOCK -> StockColumn.values()
            else -> throw IllegalArgumentException("Unknown table name: $tableName")
        }

        val columnDefinitions = columnsEnumValues
            .joinToString(", ") { it.getColumnDefinition(context) }

        return "CREATE TABLE $tableName ($columnDefinitions)"
    }

    /**
     * MasterテーブルのCSVヘッダー文字列 (日本語) をキーとし、
     * 対応するMasterColumn enumを値とするマップを返します。
     * CSVのインポート/エクスポート時のカラム特定に使用します。
     * @param context Contextオブジェクト。リソースの解決に必要。
     * @return CSVヘッダー名とMasterColumnのマップ。
     */
    fun getMasterCsvHeaderToDbColumnMap(context: Context): Map<String, MasterColumn> {
        return MasterColumn.values()
            .filter { it.csvHeaderResId != null }
            .associateBy { context.getString(it.csvHeaderResId!!) }
    }

    /**
     * MasterテーブルのCSVエクスポート時に使用するヘッダーリスト（文字列のリスト）を返します。
     * `csv_config.xml` の `R.array.csv_master_table_headers` から取得します。
     * @param context Contextオブジェクト。リソースの解決に必要。
     * @return CSVエクスポート用のヘッダーリスト。
     */
    fun getMasterCsvExportHeaders(context: Context): List<String> {
        return context.resources.getStringArray(R.array.csv_master_table_headers).toList()
    }

    // Helper function to get all column names for a table, used by DatabaseHelper
    /**
     * 指定されたテーブルのすべてのカラム名（実際の文字列名）のリストを取得します。
     * @param context Contextオブジェクト。カラム名リソースの解決に必要。
     * @param tableName テーブル名。
     * @return カラム名のリスト。
     */
    fun getAllColumnNames(context: Context, tableName: String): List<String> {
        val columnsEnumValues: Array<out DefinesColumn> = when (tableName) {
            TableName.TABLE_NAME_MASTER -> MasterColumn.values()
            TableName.TABLE_NAME_LOCATION -> LocationColumn.values()
            TableName.TABLE_NAME_PRODUCT -> ProductColumn.values()
            TableName.TABLE_NAME_STOCK -> StockColumn.values()
            else -> throw IllegalArgumentException("Unknown table name for column names: $tableName")
        }
        return columnsEnumValues.map { it.getColumnName(context) }
    }

    /**
     * 指定されたテーブルのDefinesColumnの配列を取得します。
     * DatabaseHelperなどで、カラムのプロパティ（型、制約など）にアクセスする際に使用できます。
     * @param tableName テーブル名
     * @return DefinesColumnの配列
     */
    fun getColumnDefinitions(tableName: String): Array<out DefinesColumn> {
        return when (tableName) {
            TableName.TABLE_NAME_MASTER -> MasterColumn.values()
            TableName.TABLE_NAME_LOCATION -> LocationColumn.values()
            TableName.TABLE_NAME_PRODUCT -> ProductColumn.values()
            TableName.TABLE_NAME_STOCK -> StockColumn.values()
            else -> throw IllegalArgumentException("Unknown table name for column definitions: $tableName")
        }
    }

    fun getScanResultString(context: Context, scanResult: Int): String {
        /* 範囲外は空文字を返す */
        if (scanResult < 0 || scanResult >= context.resources.getStringArray(R.array.csv_scan_result_strings).size) {
            return ""
        } else {
            return context.resources.getStringArray(R.array.csv_scan_result_strings)[scanResult]
        }
    }

    /**
     * 「全ての置き場」を返します。
     */
    fun get_location_all(context: Context): String {
        return context.resources.getString(R.string.layout_row_location_all)
    }

    /**
     * 「デフォルトの置き場」を返します。
     */
    fun get_location_default(context: Context): String {
        return context.resources.getString(R.string.layout_row_location_default)
    }

}