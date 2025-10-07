package jp.co.softtex.st_andapp_0001_kin001

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat // パーミッションリクエスト用
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat // パーミッションチェック用
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import timber.log.Timber

public class CameraActivity : FragmentActivity() {

    companion object {
        // 呼び出し元と結果をやり取りするためのキー
        const val EXTRA_SCAN_RESULT = "jp.co.softtex.st_andapp_0002.SCAN_RESULT"
        // パーミッションリクエスト用のコード (IntentIntegrator.REQUEST_CODE と同じ値を使うのが一般的だが、独自でも可)
        private const val CAMERA_PERMISSION_REQUEST_CODE = IntentIntegrator.REQUEST_CODE + 1 // 念のため重複を避ける
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // このActivityはUIを持たず、すぐにスキャナを起動するため setContentView は不要

        // カメラパーミッションの確認とリクエスト
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // パーミッションがない場合、リクエストする
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            // パーミッションが既にある場合、スキャンを開始
            startQrScan()
        }
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt(getString(R.string.camera_activity_scan_prompt))
            setCameraId(0)
            setBeepEnabled(true) // スキャン成功時にビープ音を鳴らす
            setBarcodeImageEnabled(false)
        }
        // スキャンを開始
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // ZXing Integration Library からの結果をパース
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)

        if (result != null) {
            if (result.contents == null) {
                Timber.i("QR Scan was cancelled.")
                setResult(Activity.RESULT_CANCELED)
            } else {
                val scanResult = result.contents
                Timber.d("QR Scan successful: $scanResult")
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_SCAN_RESULT, scanResult)
                }
                setResult(Activity.RESULT_OK, resultIntent)
            }
        } else {
            // これが呼ばれることは通常ないはずだが、念のため
            super.onActivityResult(requestCode, resultCode, data)
            Timber.w("QR Scan result was null, or not handled by IntentIntegrator.")
            setResult(Activity.RESULT_CANCELED) // 不明な場合はキャンセル扱い
        }
        finish() // スキャン後、またはキャンセル後はActivityを終了
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults) // 親クラスの処理も呼ぶ

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // カメラパーミッションが許可された
                Timber.d("Camera permission granted.")
                startQrScan()
            } else {
                // カメラパーミッションが拒否された
                Timber.e("Camera permission denied.")
                Toast.makeText(this, getString(R.string.camera_activity_permission_denied), Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED) // パーミッションがない場合はキャンセルとして終了
                finish()
            }
        }
    }
}