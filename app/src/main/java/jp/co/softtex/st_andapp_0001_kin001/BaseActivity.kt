package jp.co.softtex.st_andapp_0001_kin001

import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.view.View


abstract class BasePaddedActivity : FragmentActivity() { // AppCompatActivityを継承してもOK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStart() { // onResumeやレイアウトが確定するタイミングでも良い
        super.onStart()
        applyNavigationSpacerHeight()
    }

    private fun applyNavigationSpacerHeight() {
        val spacer = findViewById<View>(R.id.navigation_bar_spacer)
        spacer?.let {
            val navBarHeight = ViewUtils.getNavigationBarHeight(this)
            if (it.layoutParams.height != navBarHeight) {
                it.layoutParams.height = navBarHeight
                it.requestLayout() // 再レイアウトを要求
            }
        } ?: run {
        }
    }
}
