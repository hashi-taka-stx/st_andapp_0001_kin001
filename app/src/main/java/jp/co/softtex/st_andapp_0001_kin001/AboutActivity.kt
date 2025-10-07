package jp.co.softtex.st_andapp_0001_kin001

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.support.v4.app.FragmentActivity
import android.widget.ImageButton
import android.widget.ImageView

import timber.log.Timber

class AboutActivity : FragmentActivity() {

    // ビュー
    private lateinit var appNameTextView: TextView
    private lateinit var versionNameTextView: TextView
    private lateinit var versionCodeTextView: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var buildDateTextView: TextView
    private lateinit var iconView: ImageView

    private val TAG = "AboutActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate.")
        try {
            /* ビューの生成 */
            setView()
        } catch (e: Exception) {
            Timber.e(e, "onCreate: Exception")
            e.printStackTrace()
            finish()
        }
    }

    private fun setView() {
        /* メイン画面のレイアウトを読み込む */
        setContentView(R.layout.activity_about)

        iconView = findViewById<ImageView>(R.id.app_icon_image_view)

        iconView.setImageResource(R.drawable.ic_launcher_foreground)

        appNameTextView = findViewById(R.id.app_name_text_view)
        versionNameTextView = findViewById(R.id.version_name_text_view)
        versionCodeTextView = findViewById(R.id.version_code_text_view)
        buildDateTextView = findViewById(R.id.build_date_text_view)

        // アプリ名を設定
        appNameTextView.text = getString(R.string.app_name)

        // ビルド日時を設定
        if (BuildConfig.BUILD_TIME.isNotEmpty()) {
            buildDateTextView.text = BuildConfig.BUILD_TIME
        } else {
            buildDateTextView.text = "N/A" // またはエラー表示
        }


        try {
            val packageInfo: PackageInfo = getPackageInfo(this)
            versionNameTextView.text = packageInfo.versionName
            versionCodeTextView.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Failed to get package info")
            versionNameTextView.text = "N/A"
            versionCodeTextView.text = "N/A"
        } catch (e: Exception) {
            Timber.e(e, "An unexpected error occurred while getting package info")
            versionNameTextView.text = "Error"
            versionCodeTextView.text = "Error"
        }
        closeButton = findViewById(R.id.toolbar_close_button)

        // ツールバーのリスナーを設定する
        closeButton.setOnClickListener {
            finish()
        }
    }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun getPackageInfo(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }
}