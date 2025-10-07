package jp.co.softtex.st_andapp_0001_kin001

class ErrNo {
    companion object {
        const val SVC_OK: Int = 0 // 通常処理完了

        const val ERROR_ON_CRREATE: Int = -10

        const val ERROR_DEVICE_NAME: Int = -100
        const val ERROR_ON_INITIALIZE: Int = -101
        const val ERROR_ITEM_NOT_FOUND: Int = -102
        const val ERROR_NO_INTENT_EXTRA: Int = -103

        const val DB_ERROR: Int = -200

        const val ERROR_PERMISSION_DENIED: Int = -500

        const val EDITTEXT_RESULT_EMPTY: Int = 0
        const val EDITTEXT_RESULT_OK: Int = 1
        const val EDITTEXT_RESULT_ERROR: Int = -1
        const val EDITTEXT_RESULT_NON_NUMERIC: Int = -2

    }
}