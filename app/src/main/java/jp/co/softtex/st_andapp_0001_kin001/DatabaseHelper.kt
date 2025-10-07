package jp.co.softtex.st_andapp_0001_kin001

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber

class DatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(
        context,
        DatabaseContract.DATABASE_NAME,
        null,
        DatabaseContract.DATABASE_VERSION
    ) {

    companion object {
        private const val TAG = "DatabaseHelper"
    }

    override fun onCreate(db: SQLiteDatabase) {
        try {
            Timber.d( "onCreate: Creating tables...")
            db.execSQL(
                DatabaseContract.getCreateTableSql(
                    context,
                    DatabaseContract.TableName.TABLE_NAME_MASTER
                )
            )
            db.execSQL(
                DatabaseContract.getCreateTableSql(
                    context,
                    DatabaseContract.TableName.TABLE_NAME_LOCATION
                )
            )
            Timber.d( "onCreate: Tables created successfully.")
        } catch (e: Exception) {
            Timber.e( "Error in onCreate", e)
            throw e
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) {
            db.setForeignKeyConstraintsEnabled(true)
            Timber.d( "onConfigure: Foreign key constraints enabled.")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            Timber.w( "onUpgrade: Upgrading database from version $oldVersion to $newVersion.")
            val masterTableName = DatabaseContract.TableName.TABLE_NAME_MASTER
            val locationTableName = DatabaseContract.TableName.TABLE_NAME_LOCATION


            db.execSQL("DROP TABLE IF EXISTS $masterTableName")
            db.execSQL("DROP TABLE IF EXISTS $locationTableName")
            Timber.d( "onUpgrade: Tables dropped.")
            onCreate(db)
        } catch (e: Exception) {
            Timber.e( "Error in onUpgrade", e)
            throw e
        }
    }

    fun clearTable(tableName: String): Int {
        val db = this.writableDatabase
        var deletedRows = -1
        try {
            deletedRows = db.delete(tableName, null, null)
            Timber.i( "$deletedRows rows deleted from table $tableName.")
        } catch (e: Exception) {
            Timber.e( "Error clearing table $tableName", e)
        }
        return deletedRows
    }

    fun insertItem(tableName: String, values: ContentValues): Long {
        val db = this.writableDatabase
        var rowId = -1L
        try {
            // MasterテーブルまたはStockテーブルへの挿入時に book_exist と physical_exist を自動設定
            if (tableName == DatabaseContract.TableName.TABLE_NAME_MASTER || tableName == DatabaseContract.TableName.TABLE_NAME_STOCK) {
                val bookInventoryColName = DatabaseContract.MasterColumn.BOOK_INVENTORY.getColumnName(context)
                val physicalInventoryColName = DatabaseContract.MasterColumn.PHYSICAL_INVENTORY.getColumnName(context)

                // book_inventory
                if (values.containsKey(bookInventoryColName)) {
                    val bookInventory = values.getAsInteger(bookInventoryColName) ?: 0
                }

                // physical_inventory
                if (!values.containsKey(physicalInventoryColName)) {
                    values.put(physicalInventoryColName, 0)
                }
            }
            rowId = db.insertWithOnConflict(tableName, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId != -1L) {
                Timber.i( "Item inserted/replaced into $tableName with row ID $rowId. Values: $values")
            } else {
                Timber.w( "Failed to insert/replace item into $tableName. Values: $values")
            }
        } catch (e: SQLiteConstraintException) {
            Timber.e( "Constraint violation inserting/replacing item into $tableName: $values", e)
        } catch (e: Exception) {
            Timber.e( "Error inserting/replacing item into $tableName: $values", e)
        }
        return rowId
    }

    fun getItems(
        tableName: String,
        columns: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String? = null
    ): MutableList<MutableMap<String, Any?>> {
        val items: MutableList<MutableMap<String, Any?>> = mutableListOf()
        val db = this.readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(tableName, columns, selection, selectionArgs, null, null, orderBy)
            cursor?.use { c ->
                val columnNamesFromCursor = c.columnNames
                while (c.moveToNext()) {
                    val item = mutableMapOf<String, Any?>()
                    for (colName in columnNamesFromCursor) {
                        val colIndex = c.getColumnIndexOrThrow(colName)
                        try {
                            when (c.getType(colIndex)) {
                                Cursor.FIELD_TYPE_NULL -> item[colName] = null
                                Cursor.FIELD_TYPE_INTEGER -> {
                                    // 全てのINTEGER型をLongとして取得する
                                    item[colName] = c.getLong(colIndex)
                                }
                                Cursor.FIELD_TYPE_FLOAT -> item[colName] = c.getDouble(colIndex)
                                Cursor.FIELD_TYPE_STRING -> item[colName] = c.getString(colIndex)
                                Cursor.FIELD_TYPE_BLOB -> item[colName] = c.getBlob(colIndex)
                                else -> {
                                    Timber.w("Unknown column type for $colName in $tableName at row ${c.position}, getting as string.")
                                    item[colName] = c.getString(colIndex) // 不明な場合はStringとして試みる
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w( "Error getting value for column $colName in table $tableName at row ${c.position}.", e)
                            item[colName] = null // エラー時はnullを設定
                        }
                    }
                    items.add(item)
                }
            }
        } catch (e: Exception) {
            Timber.e( "Error getting items from table $tableName", e)
        } finally {
            cursor?.close()
        }
        return items
    }

    fun updateItemField(
        tableName: String,
        id: Long, // 主キー(_id)の値
        columnNameToUpdate: String,
        valueToUpdate: Any?
    ): Boolean {
        val db = this.writableDatabase
        val values = ContentValues()

        // 更新する値の型に応じてContentValuesにセット
        when (valueToUpdate) {
            null -> values.putNull(columnNameToUpdate)
            is String -> values.put(columnNameToUpdate, valueToUpdate)
            is Int -> values.put(columnNameToUpdate, valueToUpdate)
            is Long -> values.put(columnNameToUpdate, valueToUpdate)
            is Float -> values.put(columnNameToUpdate, valueToUpdate)
            is Double -> values.put(columnNameToUpdate, valueToUpdate)
            is Boolean -> values.put(columnNameToUpdate, if (valueToUpdate) 1 else 0) // BooleanはIntegerに
            is ByteArray -> values.put(columnNameToUpdate, valueToUpdate)
            else -> {
                Timber.e( "Unsupported data type for updateItemField: ${valueToUpdate?.javaClass?.name} for column $columnNameToUpdate")
                return false
            }
        }

        // 主キーカラム名を取得 (テーブルによって異なる可能性があるため、より汎用的な方法も検討可)
        val idColumnName = when (tableName) {
            DatabaseContract.TableName.TABLE_NAME_MASTER -> DatabaseContract.MasterColumn.ID.getColumnName(context)
            DatabaseContract.TableName.TABLE_NAME_LOCATION -> DatabaseContract.LocationColumn.ID.getColumnName(context)
            else -> "_id" // フォールバック
        }

        val selection = "$idColumnName = ?"
        val selectionArgs = arrayOf(id.toString())
        var updatedRows = 0
        try {
            updatedRows = db.update(tableName, values, selection, selectionArgs)
            if (updatedRows > 0) {
                Timber.i( "Item ID $id in table $tableName updated successfully. Column: $columnNameToUpdate, Values: $values")
            } else {
                Timber.w( "No item found with ID $id in table $tableName, or value was the same. Column: $columnNameToUpdate")
            }
        } catch (e: Exception) {
            Timber.e( "Error updating item ID $id in table $tableName. Column: $columnNameToUpdate", e)
        }
        return updatedRows > 0
    }

    fun createLocationList(csvData: List<Map<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearTable(DatabaseContract.TableName.TABLE_NAME_LOCATION)
            val locations = mutableSetOf<String>()
            val csvLocationHeader = DatabaseContract.MasterColumn.LOCATION.getCsvHeader(context)
                ?: DatabaseContract.MasterColumn.LOCATION.getColumnName(context)

            csvData.forEach { row ->
                val locationValueFromCsv = row[csvLocationHeader]
                if (locationValueFromCsv != null) {
                    locations.add(locationValueFromCsv.trim())
                }
            }

            val dbLocationColumnName = DatabaseContract.LocationColumn.LOCATION.getColumnName(context)
            Timber.d( "Unique locations to be inserted/ignored: $locations") // どんな置き場名が処理対象かログ出力

            locations.forEach { locationString ->
                val values = ContentValues().apply {
                    put(dbLocationColumnName, locationString) // locationString は空文字列 "" の場合もある
                }
                db.insertWithOnConflict(
                    DatabaseContract.TableName.TABLE_NAME_LOCATION,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
            Timber.i( "Location list created/updated successfully with ${locations.size} unique locations.")
        } catch (e: Exception) {
            Timber.e( "Error in CreateLocationList", e)
        } finally {
            db.endTransaction()
        }
    }

    @SuppressLint("Range")
    fun getTargetList(
        filterLocation: String?,
        filterProductEpc: String?,
        distinctColumnName: DatabaseContract.MasterColumn?
    ): MutableList<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        val db = this.readableDatabase
        var cursor: Cursor? = null

        try {
            val selectionArgs = mutableListOf<String>()
            val selectionClauses = mutableListOf<String>()

            // locationでの絞り込み条件
            filterLocation?.let {
                selectionClauses.add("${DatabaseContract.MasterColumn.LOCATION.getColumnName(context)} = ?")
                selectionArgs.add(it)
            }

            // product_epcでの絞り込み条件
            filterProductEpc?.let {
                selectionClauses.add("${DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)} = ?")
                selectionArgs.add(it)
            }

            val selection = if (selectionClauses.isNotEmpty()) selectionClauses.joinToString(" AND ") else null
            val selectionArgsArray = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

            // DISTINCT句の組み立て
            val distinct = distinctColumnName?.let { true } ?: false

            // クエリ実行
            val groupBy = if (distinct && distinctColumnName != null) {
                distinctColumnName.getColumnName(context)
            } else {
                null
            }

            cursor = db.query(
                DatabaseContract.TableName.TABLE_NAME_MASTER,
                null,
                selection,
                selectionArgsArray,
                groupBy,
                null,
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val rowMap = mutableMapOf<String, Any?>()
                    DatabaseContract.MasterColumn.values().forEach { column ->
                        val columnName = column.getColumnName(context)
                        val columnIndex = cursor.getColumnIndex(columnName)
                        if (columnIndex != -1) { // カラムが存在することを確認
                            when (column.dataType) {
                                DatabaseContract.DataType.INTEGER -> rowMap[columnName] = cursor.getLong(columnIndex)
                                DatabaseContract.DataType.TEXT -> rowMap[columnName] = cursor.getString(columnIndex)
                                DatabaseContract.DataType.REAL -> rowMap[columnName] = cursor.getDouble(columnIndex)
                                DatabaseContract.DataType.BLOB -> rowMap[columnName] = cursor.getBlob(columnIndex)
                                DatabaseContract.DataType.BOOLEAN -> rowMap[columnName] = cursor.getInt(columnIndex) == 1 // SQLiteはBOOLEANを0か1で格納
                                else -> rowMap[columnName] = cursor.getString(columnIndex) // 不明な型はTEXTとして扱う
                            }
                        }
                    }
                    list.add(rowMap)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    fun getMasterEpcList(): MutableList<MutableMap<String, Any>> {
        val epcList = mutableListOf<MutableMap<String, Any>>()
        val db = this.readableDatabase
        var cursor: Cursor? = null // finallyブロックで閉じるために外で宣言

        // カラム名を事前に取得
        val idColumnName = DatabaseContract.MasterColumn.ID.getColumnName(context)
        val epcColumnName = DatabaseContract.MasterColumn.PRODUCT_EPC.getColumnName(context)

        // クエリでIDとPRODUCT_EPCの両方を選択する
        val query = "SELECT $idColumnName, $epcColumnName FROM ${DatabaseContract.TableName.TABLE_NAME_MASTER}"
        Timber.d( "getMasterEpcList: Executing query: $query")

        try {
            cursor = db.rawQuery(query, null)
            if (cursor != null && cursor.moveToFirst()) {
                // カラムインデックスをループ外で一度だけ取得
                val idColIndex = cursor.getColumnIndexOrThrow(idColumnName)
                val epcColIndex = cursor.getColumnIndexOrThrow(epcColumnName)

                do {
                    val item = mutableMapOf<String, Any>()
                    val id = cursor.getLong(idColIndex)
                    val epc = cursor.getString(epcColIndex)

                    item[idColumnName] = id // マップのキーも変数名を使用
                    item[epcColumnName] = epc // マップのキーも変数名を使用
                    epcList.add(item)
                } while (cursor.moveToNext())
                Timber.d( "getMasterEpcList: Found ${epcList.size} items.")
            } else {
                Timber.d( "getMasterEpcList: No items found or cursor is null.")
            }
        } catch (e: Exception) {
            Timber.e( "Error in getMasterEpcList while querying or processing data.", e)
        } finally {
            cursor?.close()
        }
        return epcList
    }

    fun logAllMasterItems(tag: String = "DatabaseHelper") {
        val db = this.readableDatabase
        val cursor = db.query(
            DatabaseContract.TableName.TABLE_NAME_MASTER,
            null, // null ですべての列を選択
            null,
            null,
            null,
            null,
            null
        )
        Timber.tag(tag).d( "--- Master Table Contents START ---")
        if (cursor.moveToFirst()) {
            do {
                val sb = StringBuilder()
                for (i in 0 until cursor.columnCount) {
                    sb.append(cursor.getColumnName(i)).append("=").append(cursor.getString(i)).append(", ")
                }
                Timber.tag(tag).d( "Row: ${sb.toString().trimEnd(',', ' ')}")
            } while (cursor.moveToNext())
        } else {
            Timber.tag(tag).d( "Master table is empty.")
        }
        cursor.close()
        Timber.tag(tag).d( "--- Master Table Contents END ---")
    }
}
