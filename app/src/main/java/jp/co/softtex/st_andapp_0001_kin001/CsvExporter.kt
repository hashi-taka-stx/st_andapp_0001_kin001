package jp.co.softtex.st_andapp_0001_kin001

import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private const val TAG = "CsvExporter"
    private const val CSV_DELIMITER = ","
    private const val UTF8_BOM = "\uFEFF"

    data class CsvParseResult(
        val headers: List<String>?,
        val dataRows: List<List<String>>,
        val success: Boolean,
        val errorMessage: String? = null
    )

    data class CsvColumnMappingConfig(
        val csvHeaderName: String,
        val targetKey: String,
        val expectedDataType: CsvDataType,
        val isOptional: Boolean = false
    )

    enum class CsvDataType {
        STRING,
        INTEGER,
        LONG,
        DOUBLE,
        BOOLEAN
    }

    class CsvParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class CsvWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun createCsvFileName(baseName: String, tag: String?, extention: String? = ".csv"): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val currentDate = sdf.format(Date())
        val tagPart = if (!tag.isNullOrBlank()) "_${tag.trim()}" else ""
        return "${baseName.trim()}${tagPart}_${currentDate}${extention}"
    }

    fun escapeCsvField(field: String?): String {
        if (field == null) return ""
        var result = field.replace("\"", "\"\"")
        if (field.contains(CSV_DELIMITER) || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            result = "\"$result\""
        }
        return result
    }

    fun convertInventoryResultToExportableList(
        resultInventory: List<Map<String, Any>>
    ): List<Map<String, Any?>> {
        return resultInventory.map { it.toMutableMap() }
    }

    fun parseCsvInputStream(inputStream: InputStream, charset: Charset = Charsets.UTF_8): CsvParseResult {
        try {
            BufferedReader(InputStreamReader(inputStream, charset)).use { reader ->
                var csvString = reader.readText()

                if (csvString.startsWith(UTF8_BOM)) {
                    csvString = csvString.substring(UTF8_BOM.length)
                    Timber.d("BOM detected and removed. csvString after BOM removal (first 50 chars): ${csvString.take(50)}") // ★追加
                } else {
                    Timber.d("BOM not detected with UTF8_BOM constant.") // ★追加
                }
                csvString = csvString.replace("\r\n", "\n").replace("\r", "\n")

                val lines = csvString.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

                if (lines.isEmpty()) {
                    return CsvParseResult(null, emptyList(), false, "CSV file is empty or contains only whitespace.")
                }

                val headerLine = lines.first()
                val headers = headerLine.split(CSV_DELIMITER).map { it.trim() }
                Timber.d("Parsed headers from CsvExporter: $headers") // ★追加

                val dataRows = mutableListOf<List<String>>()
                if (lines.size > 1) {
                    for (i in 1 until lines.size) {
                        val columns = lines[i].split(CSV_DELIMITER).map { it.trim() }
                        if (columns.size != headers.size && columns.any { it.isNotEmpty() }) {
                            Timber.w( "Row ${i+1} has ${columns.size} columns, expected ${headers.size}. Row: $columns")
                        }
                        dataRows.add(columns)
                    }
                }
                return CsvParseResult(headers, dataRows, true)
            }
        } catch (e: Exception) {
            Timber.e( "Error parsing CSV input stream", e)
            return CsvParseResult(null, emptyList(), false, "Failed to read or parse CSV stream: ${e.message}")
        }
    }

    fun validateCsvHeaders(actualHeaders: List<String>?, expectedHeaders: List<String>): Boolean {
        if (actualHeaders == null) {
            Timber.w( "Actual headers are null, cannot validate against expected: $expectedHeaders")
            return false
        }
        if (actualHeaders.size != expectedHeaders.size) {
            Timber.w( "Header size mismatch. Actual: ${actualHeaders.size} (${actualHeaders}), Expected: ${expectedHeaders.size} (${expectedHeaders})")
            return false
        }
        val isEqual = actualHeaders == expectedHeaders
        if (!isEqual) {
            Timber.w( "Header content mismatch. Actual: $actualHeaders, Expected: $expectedHeaders")
        }
        return isEqual
    }

    fun mapCsvRowsToTypedData(
        dataRows: List<List<String>>,
        actualCsvHeaders: List<String>,
        columnConfigs: List<CsvColumnMappingConfig>
    ): List<Map<String, Any?>> {
        val resultList = mutableListOf<Map<String, Any?>>()

        val configMap = columnConfigs.associateBy { it.csvHeaderName }

        val missingConfigs = actualCsvHeaders.filterNot { configMap.containsKey(it) }
        if (missingConfigs.isNotEmpty()) {
            Timber.e( "Missing mapping configuration for CSV headers: $missingConfigs. Check columnConfigs.")
        }

        for ((rowIndex, row) in dataRows.withIndex()) {
            if (row.size != actualCsvHeaders.size) {
                Timber.w( "Row ${rowIndex + 1} (1-based) has column count mismatch. Expected ${actualCsvHeaders.size}, got ${row.size}. Row: $row. Skipping row.")
                continue
            }

            val itemMap = mutableMapOf<String, Any?>()
            var rowHasPotentiallySkippedMandatoryData = false

            for (i in actualCsvHeaders.indices) {
                val csvHeader = actualCsvHeaders[i]
                val cellValueString = row[i].trim()
                val config = configMap[csvHeader]

                if (config == null) {
                    Timber.w( "No mapping config found for CSV header: '$csvHeader' at row ${rowIndex + 1}. Skipping this column for the row.")
                    continue
                }

                try {
                    val typedValue: Any? = when (config.expectedDataType) {
                        CsvDataType.STRING -> cellValueString
                        CsvDataType.INTEGER -> if (cellValueString.isBlank() && config.isOptional) null else cellValueString.toInt()
                        CsvDataType.LONG -> if (cellValueString.isBlank() && config.isOptional) null else cellValueString.toLong()
                        CsvDataType.DOUBLE -> if (cellValueString.isBlank() && config.isOptional) null else cellValueString.toDouble()
                        CsvDataType.BOOLEAN -> {
                            if (cellValueString.isBlank() && config.isOptional) {
                                null
                            } else {
                                when (cellValueString.lowercase(Locale.ROOT)) {
                                    "true", "1", "yes", "t", "〇", "有効" -> true
                                    "false", "0", "no", "f", "×", "無効" -> false
                                    else -> throw NumberFormatException("Invalid boolean string: '$cellValueString'")
                                }
                            }
                        }
                    }
                    itemMap[config.targetKey] = typedValue

                    if (typedValue == null && !config.isOptional && cellValueString.isNotBlank()) {
                        Timber.w( "Mandatory field '${config.csvHeaderName}' (target: '${config.targetKey}') resulted in null for non-blank value '$cellValueString' after parsing to ${config.expectedDataType}. Row ${rowIndex + 1}.")
                        rowHasPotentiallySkippedMandatoryData = true
                    }

                } catch (e: NumberFormatException) {
                    if (config.isOptional) {
                        itemMap[config.targetKey] = null
                        Timber.d( "Optional field '${config.csvHeaderName}' (target: '${config.targetKey}') parsing error for value '$cellValueString'. Setting to null. Row ${rowIndex + 1}.", e)
                    } else {
                        Timber.e( "Mandatory field '${config.csvHeaderName}' (target: '${config.targetKey}') parsing error for value '$cellValueString' to ${config.expectedDataType}. Row ${rowIndex + 1}.", e)
                        itemMap[config.targetKey] = null
                        rowHasPotentiallySkippedMandatoryData = true
                                            }
                }
            }

            if (itemMap.isNotEmpty() || !rowHasPotentiallySkippedMandatoryData) { // Add if map has some data, or if no mandatory data was skipped
                resultList.add(itemMap)
            } else if (rowHasPotentiallySkippedMandatoryData) {
                Timber.w( "Row ${rowIndex+1} was not added because it had errors in mandatory fields and resulted in an empty map: $itemMap")
            }
        }
        return resultList
    }

    fun writeCsvToStream(
        outputStream: OutputStream,
        headers: List<String>,
        dataItems: List<Map<String, Any?>>,
        charset: Charset = Charsets.UTF_8
    ) {
        if (headers.isEmpty() && dataItems.isNotEmpty()) {
            throw CsvWriteException("Cannot write CSV data without headers if dataItems are present.")
        }

        try {
            outputStream.bufferedWriter(charset).use { writer ->
                writer.write(UTF8_BOM)

                if (headers.isNotEmpty()) {
                    writer.write(headers.joinToString(CSV_DELIMITER) { escapeCsvField(it) })
                    writer.newLine()
                }

                for (itemMap in dataItems) {
                    val line = headers.joinToString(CSV_DELIMITER) { header ->
                        val value = itemMap[header]
                        escapeCsvField(value?.toString())
                    }
                    writer.write(line)
                    writer.newLine()
                }
                writer.flush()
            }
        } catch (e: IOException) {
            Timber.e( "IOException writing CSV data to stream", e)
            throw CsvWriteException("Failed to write CSV to stream due to IOException: ${e.message}", e)
        } catch (e: Exception) {
            Timber.e( "Unexpected error writing CSV data to stream", e)
            throw CsvWriteException("Unexpected error writing CSV to stream: ${e.message}", e)
        }
    }
}