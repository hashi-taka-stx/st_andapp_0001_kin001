package jp.co.softtex.st_andapp_0001_kin001

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import timber.log.Timber

import jp.co.toshibatec.TecRfidSuite as ToshibaTecSdk

class NavigationHelper(
    private val activity: Activity,
    private val drawerContainerId: Int,
    private val navDrawerLayoutId: Int,
    private val navListViewId: Int,
    private val navHeaderLayoutId: Int,
    private val navHeaderCloseButtonId: Int,
    private val toolbarTitleViewId: Int,
    private val toolbarMenuButtonId: Int,
    private val toolbarBackButtonId: Int,
    private val toolbarBatteryIconId: Int,
    private val toolbarBatteryTextId: Int,
    private val toolbarHintViewId: Int,
    private val toolbarConnectionIconId: Int
) {

    private lateinit var drawerContainer: ViewGroup
    private lateinit var navDrawerView: View
    private lateinit var navListView: ListView
    private lateinit var toolbarTitleTextView: TextView
    private lateinit var toolbarHintTextView: TextView
    private lateinit var toolbarMenuButton: ImageButton
    private lateinit var toolbarBackButton: ImageButton
    private lateinit var navHeaderCloseButton: ImageButton

    private var headerBatteryTextView: TextView? = null
    private var headerBatteryIconView: ImageView? = null
    private var headerConnectionIconView: ImageView? = null

    private var isDrawerInitialized = false
    private var isDrawerOpen: Boolean = false
    private var isAnimating: Boolean = false
    private val animationDuration = 150L
    private val interpolator = AccelerateDecelerateInterpolator()

    private val TAG = "NavigationHelper"

    interface NavigationItemSelectedListener {
        fun onNavigationItemSelected(position: Int, title: String)
    }

    private var listener: NavigationItemSelectedListener? = null

    fun setNavigationItemSelectedListener(listener: NavigationItemSelectedListener) {
        this.listener = listener
    }

    fun setupToolbarAndDrawer(
        screenTitle: String,
        showBackButton: Boolean,
        customBackButtonAction: (() -> Unit)? = null
    ) {
        toolbarTitleTextView = activity.findViewById(toolbarTitleViewId)
        toolbarHintTextView = activity.findViewById(toolbarHintViewId)
        toolbarMenuButton = activity.findViewById(toolbarMenuButtonId)
        toolbarBackButton = activity.findViewById(toolbarBackButtonId)

        toolbarTitleTextView.text = screenTitle

        if (showBackButton) {
            toolbarBackButton.visibility = View.VISIBLE
            toolbarMenuButton.visibility = View.GONE
            toolbarBackButton.setOnClickListener {
                Timber.d("Toolbar back button clicked")
                customBackButtonAction?.invoke() ?: run {
                    Timber.i("Custom action is null, calling activity.onBackPressed()")
                    activity.onBackPressed()
                }
            }
        } else {
            toolbarBackButton.visibility = View.GONE
            toolbarMenuButton.visibility = View.VISIBLE
            toolbarMenuButton.setOnClickListener {
                toggleDrawer()
            }
        }

        try {
            headerBatteryIconView = activity.findViewById(toolbarBatteryIconId)
            headerBatteryTextView = activity.findViewById(toolbarBatteryTextId)
            headerConnectionIconView = activity.findViewById(toolbarConnectionIconId)
            // nullチェックログは維持
        } catch (e: Exception) {
            Timber.e("Error finding status icons in toolbar", e)
        }

        try {
            drawerContainer = activity.findViewById(drawerContainerId)
            navDrawerView = drawerContainer.findViewById(navDrawerLayoutId)
                ?: throw IllegalStateException("View with ID $navDrawerLayoutId (navDrawerView) not found within drawerContainer (ID: $drawerContainerId).")
            navListView = navDrawerView.findViewById(navListViewId)
                ?: throw IllegalStateException("ListView with ID $navListViewId not found within navDrawerView (ID: $navDrawerLayoutId).")
            val navHeaderView = LayoutInflater.from(activity).inflate(navHeaderLayoutId, navListView, false)
            navListView.addHeaderView(navHeaderView, null, false)
            navHeaderCloseButton = navHeaderView.findViewById(navHeaderCloseButtonId)
                ?: throw IllegalStateException("Close button with ID $navHeaderCloseButtonId not found within navHeaderLayout (ID: $navHeaderLayoutId).")
        } catch (e: Exception) { // より汎用的な Exception でキャッチ
            Timber.e("Error during Navigation Drawer view setup: ${e.message}", e)
            return // 初期化に失敗したらここで終了
        }

        navHeaderCloseButton.setOnClickListener {
            closeDrawer()
        }

        var menuTitles: Array<String>
        try {
            menuTitles = activity.resources.getStringArray(R.array.navigation_drawer_menulist)
        } catch (e: Resources.NotFoundException) {
            Timber.e("Error loading navigation_drawer_menulist string-array. Defaulting to empty list.", e)
            menuTitles = emptyArray()
        }
        val menuItemsForAdapter = menuTitles.map { title -> mapOf("title" to title) }
        val adapter = SimpleAdapter(
            activity,
            menuItemsForAdapter,
            android.R.layout.simple_list_item_1,
            arrayOf("title"),
            intArrayOf(android.R.id.text1)
        )
        navListView.adapter = adapter
        navListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val actualPosition = position - navListView.headerViewsCount
            if (actualPosition >= 0 && actualPosition < menuTitles.size) {
                val selectedTitle = menuTitles[actualPosition]
                Timber.i("Navigation item clicked: Position $actualPosition, Title: $selectedTitle")
                listener?.onNavigationItemSelected(actualPosition, selectedTitle)
                closeDrawer()
            } else {
                Timber.w("Navigation item click ignored: Clicked on header or invalid position. Position: $position, Actual Position: $actualPosition")
            }
        }

        isDrawerInitialized = false // 初期状態は false

        val initialDrawerContainerVisibility = drawerContainer.visibility
        val initialNavDrawerViewVisibility = navDrawerView.visibility

        if (navDrawerView.width == 0) {
            Timber.d("setupToolbarAndDrawer: navDrawerView width is 0. Initial visibilities: container=$initialDrawerContainerVisibility, navView=$initialNavDrawerViewVisibility")
            if (initialDrawerContainerVisibility == View.GONE) {
                Timber.d("setupToolbarAndDrawer: Temporarily setting drawerContainer to INVISIBLE.")
                drawerContainer.visibility = View.INVISIBLE
            }
            if (initialNavDrawerViewVisibility == View.GONE) {
                Timber.d("setupToolbarAndDrawer: Temporarily setting navDrawerView to INVISIBLE.")
                navDrawerView.visibility = View.INVISIBLE
            }
        }

        navDrawerView.post {
            if (navDrawerView.width > 0) {
                Timber.d("setupToolbarAndDrawer (post): navDrawerView width is ${navDrawerView.width}. Initializing drawer.")
                navDrawerView.translationX = navDrawerView.width.toFloat()
                isDrawerInitialized = true
            } else {
                Timber.d("setupToolbarAndDrawer (post): navDrawerView width is still 0. Adding OnLayoutChangeListener.")
                navDrawerView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ) {
                        if (v.width > 0 && !isDrawerInitialized) {
                            Timber.d("setupToolbarAndDrawer (OnLayoutChange): navDrawerView width is ${v.width}. Initializing drawer.")
                            v.translationX = v.width.toFloat()
                            isDrawerInitialized = true
                            v.removeOnLayoutChangeListener(this) // リスナーを解除
                        } else if (v.width == 0 && !isDrawerInitialized) {
                            Timber.w("OnLayoutChange: navDrawerView width is still 0.")
                        }
                    }
                })
            }
            Timber.d("setupToolbarAndDrawer (post-logic): Restoring initial visibilities if changed. isDrawerInitialized: $isDrawerInitialized")
            if (drawerContainer.visibility == View.INVISIBLE && initialDrawerContainerVisibility == View.GONE) {
                Timber.d("setupToolbarAndDrawer (post-logic): Restoring drawerContainer visibility to GONE.")
                drawerContainer.visibility = View.GONE
            }
            if (navDrawerView.visibility == View.INVISIBLE && initialNavDrawerViewVisibility == View.GONE) {
                if (isDrawerInitialized) { // 初期化成功時のみ GONE に戻すのが安全か
                    Timber.d("setupToolbarAndDrawer (post-logic): navDrawerView initialized. Restoring navDrawerView visibility to GONE.")
                    navDrawerView.visibility = View.GONE
                } else {
                    Timber.w("setupToolbarAndDrawer (post-logic): navDrawerView NOT initialized. Leaving navDrawerView as INVISIBLE for now to aid openDrawer retries.")
                }
            }
        }
    }

    fun setupToolbarHint(hint: String) {
        toolbarHintTextView.text = hint
    }

    fun updateConnectionStatus(isConnected: Boolean) {
        headerConnectionIconView?.let {
            if (isConnected) {
                it.setImageResource(R.drawable.rfid_reader_connected_icon) // 接続中アイコン
                // 必要であれば tint も設定 (XMLで白に固定されている場合は不要なことも)
            } else {
                it.setImageResource(R.drawable.rfid_reader_disconnected_icon) // 切断中アイコン
            }
        }
    }

    fun updateBatteryLevel(level: Int, state: Int) { // バッテリーレベルは 0-100 のような値を想定
        headerBatteryIconView?.let {
            when {
                state == ToshibaTecSdk.ChargingACAdaper -> {
                    it.setImageResource(R.drawable.baseline_battery_charging_full_24)
                }
                state == ToshibaTecSdk.ChargingUSB -> {
                    it.setImageResource(R.drawable.baseline_battery_charging_full_24)
                }
                else -> {
                    when {
                        level < 0 -> it.setImageResource(R.drawable.baseline_battery_unknown_24)
                        level <= 5 -> it.setImageResource(R.drawable.baseline_battery_0_bar_24)
                        level <= 20 -> it.setImageResource(R.drawable.baseline_battery_1_bar_24)
                        level <= 35 -> it.setImageResource(R.drawable.baseline_battery_2_bar_24)
                        level <= 50 -> it.setImageResource(R.drawable.baseline_battery_3_bar_24)
                        level <= 65 -> it.setImageResource(R.drawable.baseline_battery_4_bar_24)
                        level <= 80 -> it.setImageResource(R.drawable.baseline_battery_5_bar_24)
                        else -> it.setImageResource(R.drawable.baseline_battery_full_24)
                    }
                }
            }
        }
        // テキストの更新
        headerBatteryTextView?.let {
            if (level < 0) { // バッテリーレベルが不明または取得失敗の場合
                it.text = activity.getString(R.string.toolbar_battery_unknown) // 例: "--%" や "N/A"
            } else {
                it.text = activity.getString(R.string.toolbar_battery_level_format, level) // 例: "%d%%" -> "88%"
            }
        }
    }

    fun toggleDrawer() {
        if (isAnimating) {
            return
        }

        if (isDrawerOpen) {
            closeDrawer()
        } else {
            openDrawer()
        }
    }

    private var openDrawerAttemptCount = 0
    private val MAX_OPEN_DRAWER_ATTEMPTS = 5

    fun openDrawer() {
        if (isAnimating || isDrawerOpen) {
            return
        }

        if (!::drawerContainer.isInitialized || !::navDrawerView.isInitialized) {
            Timber.e("openDrawer: Views not initialized (drawerContainer or navDrawerView). Cannot open drawer.")
            return
        }

        if (!isDrawerInitialized) {
            if (openDrawerAttemptCount < MAX_OPEN_DRAWER_ATTEMPTS) {
                openDrawerAttemptCount++
                navDrawerView.post {
                    openDrawer()
                }
            } else {
                Timber.e("openDrawer: Drawer still not initialized after $MAX_OPEN_DRAWER_ATTEMPTS attempts. Aborting openDrawer.")
                openDrawerAttemptCount = 0
                if (::drawerContainer.isInitialized) drawerContainer.visibility = View.GONE
            }
            return
        }
        openDrawerAttemptCount = 0

        if (navDrawerView.width == 0) {
            Timber.e("openDrawer: navDrawerView width is 0 even after initialization attempts. Cannot open drawer.")
            if (::drawerContainer.isInitialized) drawerContainer.visibility = View.GONE
            return
        }
        drawerContainer.visibility = View.VISIBLE
        navDrawerView.visibility = View.VISIBLE

        isAnimating = true
        val animator = ObjectAnimator.ofFloat(navDrawerView, View.TRANSLATION_X, 0f)
        animator.duration = animationDuration
        animator.interpolator = interpolator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
            }
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                isDrawerOpen = true
            }
            override fun onAnimationCancel(animation: Animator) {
                isAnimating = false
                Timber.w("openDrawer: Animation was cancelled. Drawer state might be inconsistent.")
                if (::navDrawerView.isInitialized && navDrawerView.width > 0) {
                    navDrawerView.translationX = navDrawerView.width.toFloat()
                }
                isDrawerOpen = false
                if (::drawerContainer.isInitialized) {
                    drawerContainer.visibility = View.GONE
                }
                if (::navDrawerView.isInitialized) {
                    navDrawerView.visibility = View.GONE
                }
            }
        })
        animator.start()
    }

    fun closeDrawer() {
        if (isAnimating || !isDrawerOpen) {
            return
        }

        if (!::drawerContainer.isInitialized || !::navDrawerView.isInitialized || !isDrawerInitialized || navDrawerView.width == 0) {
            Timber.w("closeDrawer: Views not initialized, drawer not ready, or width is 0. Aborting animation and hiding drawer directly.")
            if (::drawerContainer.isInitialized) {
                drawerContainer.visibility = View.GONE
            }
            if (::navDrawerView.isInitialized) {
                navDrawerView.visibility = View.GONE // navDrawerView も GONE に
                if (navDrawerView.width > 0) {
                    navDrawerView.translationX = navDrawerView.width.toFloat()
                } else {
                    val displayMetrics = DisplayMetrics()
                    activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
                    navDrawerView.translationX = displayMetrics.widthPixels.toFloat()
                    Timber.w("closeDrawer: navDrawerView width was 0, used screen width for fallback translationX (to right).")
                }
            }
            isDrawerOpen = false
            isAnimating = false
            return
        }

        isAnimating = true
        val targetTranslationX = navDrawerView.width.toFloat()

        val animator = ObjectAnimator.ofFloat(navDrawerView, View.TRANSLATION_X, targetTranslationX)
        animator.duration = animationDuration
        animator.interpolator = interpolator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                // 必要であれば onAnimationStart での処理
            }
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                isDrawerOpen = false
                drawerContainer.visibility = View.GONE
                navDrawerView.visibility = View.GONE
            }
            override fun onAnimationCancel(animation: Animator) {
                isAnimating = false
                Timber.w("closeDrawer: Animation was cancelled. Forcing drawer to closed state.")
                if (::drawerContainer.isInitialized) {
                    drawerContainer.visibility = View.GONE
                }
                if (::navDrawerView.isInitialized) {
                    navDrawerView.visibility = View.GONE
                    if (navDrawerView.width > 0) {
                        navDrawerView.translationX = navDrawerView.width.toFloat()
                    }
                }
                isDrawerOpen = false
            }
        })
        animator.start()
    }
    fun onDestroy() {
        listener = null
    }

    fun isDrawerOpen(): Boolean {
        return isDrawerOpen
    }
}