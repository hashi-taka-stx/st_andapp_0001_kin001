package jp.co.softtex.st_andapp_0001_kin001

import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import kotlin.math.min
import timber.log.Timber

class DisplayLogActivity : BasePaddedActivity() {
    private lateinit var appLogListView: ListView
    private lateinit var appLogAdapter: ArrayAdapter<String>
    private lateinit var closeButton: ImageButton
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val displayLogList: MutableList<String> = mutableListOf()
    private var logSnapshot: List<String> = emptyList()
    private var currentPage = 0
    private var isLoading = false
    private var currentDisplayPageSize = LogHelper.DISPLAY_PAGE_SIZE

    private val TAG = "DisplayLogActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate:")
        try {
            /* ビューの生成 */
            setView()
            loadLogSnapshotAndDisplay(false)
        } catch (e: Exception) {
            Timber.e( "onCreate: Exception", e)
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
        } catch (e: Exception) {
            Timber.e( "onResume: Exception", e)
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
        } catch (e: Exception) {
            Timber.e( "onDestroy: Exception", e)
        }
    }

    private fun setView() {
        try {
            // レイアウトを読み込む
            setContentView(R.layout.activity_display_log)

            closeButton = findViewById(R.id.toolbar_close_button)
            // 閉じるボタンのリスナーを設定する
            closeButton.setOnClickListener {
                finish()
            }

            swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
            swipeRefreshLayout.setOnRefreshListener {
                loadLogSnapshotAndDisplay(true)
            }

            appLogListView = findViewById(R.id.log_list_view)
            appLogAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayLogList)
            appLogListView.adapter = appLogAdapter
            
            appLogListView.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (!isLoading && totalItemCount > 0 && (firstVisibleItem + visibleItemCount >= totalItemCount - 2)) {
                        paginateFromSnapshot()
                    }
                }
            })
        } catch (e: Exception) {
            Timber.e( "setView: Exception", e)
            e.printStackTrace()
        }
    }

    private fun loadLogSnapshotAndDisplay(isUserInitiatedRefresh: Boolean) {
        if (isUserInitiatedRefresh) {
            swipeRefreshLayout.isRefreshing = true
        }

        // LogHelperから現在のログリストのコピーを取得
        val snapshotReverced = LogHelper.getCompleteLogListSnapshot()
        logSnapshot = snapshotReverced.reversed()

        displayLogList.clear()
        currentPage = 0
        isLoading = false

        if (logSnapshot.isEmpty()) {
            Timber.i("Log snapshot is empty.")
            appLogAdapter.notifyDataSetChanged() // 空のリストを反映
        } else {
            paginateFromSnapshot(true)
        }
        if (isUserInitiatedRefresh) {
            swipeRefreshLayout.isRefreshing = false
        }
        appLogListView.setSelection(0) // スナップショット更新時はリストの先頭に
    }

    private fun paginateFromSnapshot(isInitialLoad: Boolean = false) {
        if (isLoading) {
            return
        }
        if (logSnapshot.isEmpty()) {
            Timber.d("Snapshot is empty, cannot paginate.")
            return
        }
        isLoading = true

        val totalSnapshotSize = logSnapshot.size
        // page は 0-indexed で古い方から数える
        val startIndex = currentPage * currentDisplayPageSize

        if (startIndex >= totalSnapshotSize && !isInitialLoad) {
            isLoading = false
            return
        }

        val endIndex = min(startIndex + currentDisplayPageSize, totalSnapshotSize)

        if (startIndex >= endIndex && !isInitialLoad) { // startIndexがendIndex以上なら新しいデータはない
            isLoading = false
            return
        }

        val newLogsPage = if (startIndex < endIndex) {
            logSnapshot.subList(startIndex, endIndex) // スナップショットは既に古い順のはず
        } else {
            emptyList()
        }

        if (newLogsPage.isNotEmpty()) {
            displayLogList.addAll(newLogsPage) // 古い順に追加
            appLogAdapter.notifyDataSetChanged()
            currentPage++
        } else {
            if (isInitialLoad) {
                Timber.w("Initial pagination from snapshot returned no logs, though snapshot was not empty earlier.")
            } else {
            }
        }
        isLoading = false
    }
}