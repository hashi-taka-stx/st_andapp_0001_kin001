package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.content.res.Resources
import android.view.ViewConfiguration

object ViewUtils {

    fun getNavigationBarHeight(context: Context): Int {
        val resources: Resources = context.resources
        val resourceId: Int = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0 && hasSoftKeys(context)) { // ソフトキー（バーチャルナビゲーションバー）があるかチェック
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

    private fun hasSoftKeys(context: Context): Boolean {
        val hasMenuKey = ViewConfiguration.get(context).hasPermanentMenuKey()
        val hasBackKey = true
        val resources: Resources = context.resources
        val showNavBarResId: Int = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        val hasSoftwareKeys = if (showNavBarResId != 0) {
            resources.getBoolean(showNavBarResId)
        } else {
            // リソースがない場合は、物理キーの有無から推測
            !hasMenuKey
        }

        return hasSoftwareKeys
    }
}