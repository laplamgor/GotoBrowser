package com.antest1.gotobrowser.Activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Rational
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.SslErrorHandler
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antest1.gotobrowser.Browser.WebViewL
import com.antest1.gotobrowser.Browser.WebViewManager
import com.antest1.gotobrowser.BuildConfig
import com.antest1.gotobrowser.Constants.DEFAULT_SUBTITLE_FONT_SIZE
import com.antest1.gotobrowser.Constants.PREF_BROADCAST
import com.antest1.gotobrowser.Constants.PREF_DOWNLOAD_RETRY
import com.antest1.gotobrowser.Constants.PREF_KEYBOARD
import com.antest1.gotobrowser.Constants.PREF_LANDSCAPE
import com.antest1.gotobrowser.Constants.PREF_MULTIWIN_MARGIN
import com.antest1.gotobrowser.Constants.PREF_PANELSTART
import com.antest1.gotobrowser.Constants.PREF_PIP_MODE
import com.antest1.gotobrowser.Constants.PREF_SUBTITLE_FONTSIZE
import com.antest1.gotobrowser.Constants.REQUEST_NOTIFICATION_PERMISSION
import com.antest1.gotobrowser.Helpers.BackPressCloseHandler
import com.antest1.gotobrowser.Helpers.KcUtils
import com.antest1.gotobrowser.Notification.ScreenshotNotification
import com.antest1.gotobrowser.R
import com.antest1.gotobrowser.ui.component.SettingsBottomSheet
import com.antest1.gotobrowser.ui.component.VerticalFloatingToolbar
import com.antest1.gotobrowser.ui.theme.GotobrowserTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class BrowserActivity : ComponentActivity() {
    companion object {
        const val FOREGROUND_ACTION = "${BuildConfig.APPLICATION_ID}.foreground"

        // Split-screen divider margin, in dp (matches the legacy 24px value).
        private const val MULTIWIN_MARGIN_DP = 24
    }

    private lateinit var viewModel: BrowserViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private var manager: WebViewManager? = null
    private var mContentView: WebViewL? = null
    private lateinit var screenshotNotification: ScreenshotNotification
    private lateinit var backPressCloseHandler: BackPressCloseHandler

    private var isInPictureInPictureMode: Boolean = false
    private val errorText = mutableStateOf("")
    private val subtitleTextValue = mutableStateOf("")
    private val closeButtonVisible = mutableStateOf(false)
    // Hoisted out of setContent so handleBackPress() can reveal the toolbar.
    private val toolbarVisible = mutableStateOf(false)
    // Settings bottom sheet + "reload required" prompt state, hoisted so the
    // back-press handler can close them before falling through to exit logic.
    private val settingsSheetVisible = mutableStateOf(false)
    private val refreshPromptVisible = mutableStateOf(false)
    // Settings that can be applied to the running browser without a reload.
    // Subtitle size is Compose state so the overlay recomposes immediately.
    private val subtitleFontSize = mutableStateOf(DEFAULT_SUBTITLE_FONT_SIZE)
    // Extra top/bottom padding (in dp) for the split-screen divider, managed by
    // updateMultiwindowMargin(). 0 when the feature is off or unsupported.
    private val multiwinMarginDp = mutableStateOf(0)

    /**
     * Settings whose changes do NOT need a WebView reload; they are applied to
     * the live browser by [applyLiveSetting] instead of prompting the user.
     */
    private val liveSettings = setOf(
        PREF_LANDSCAPE,
        PREF_MULTIWIN_MARGIN,
        PREF_SUBTITLE_FONTSIZE,
        PREF_DOWNLOAD_RETRY
    )

    @SuppressLint("SourceLockedOrientationActivity", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[BrowserViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        screenshotNotification = ScreenshotNotification(this)
        backPressCloseHandler = BackPressCloseHandler(this, true)

        sendIsFrontChanged(true)

        val intent = getIntent()
        viewModel.isKcBrowserMode = WebViewManager.OPEN_KANCOLLE == intent.action || Intent.ACTION_MAIN == intent.action
        // Only on a real cold launch: the URL opened by a share intent, a
        // configuration change or coming back from PiP should not re-check.
        val isAppLaunch = Intent.ACTION_MAIN == intent.action && savedInstanceState == null

        if (viewModel.sharedPref.getBoolean(PREF_LANDSCAPE, true)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }

        toolbarVisible.value = viewModel.sharedPref.getBoolean(PREF_PANELSTART, true)
        subtitleFontSize.value = settingsViewModel.getSubtitleFontSize()

        manager = WebViewManager(this)
        manager?.setDataDirectorySuffix()

        WebViewManager.clearKcCacheProxy()

        // Deferred until the content view exists, because the update check and
        // the Kcanotify warning both attach to android.R.id.content.
        if (isAppLaunch) {
            window.decorView.post {
                checkAppUpdateOnStart()
                warnIfKcanotifyBroadcastDisabled()
            }
        }

        setContent {
            GotobrowserTheme {
                val isK3dDialogVisible = remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxSize()) {
                    BrowserScreenContent(
                        viewModel = viewModel,
                        errorText = errorText,
                        subtitleTextValue = subtitleTextValue,
                        closeButtonVisible = closeButtonVisible,
                        subtitleFontSize = subtitleFontSize,
                        multiwinMarginDp = multiwinMarginDp,
                        manager = manager,
                        onViewCreated = { mContentView = it },
                        intent = intent,
                        activity = this@BrowserActivity,
                        onBackgroundTap = { toolbarVisible.value = !toolbarVisible.value }
                    )

                    VerticalFloatingToolbar(
                        visible = toolbarVisible.value,
                        onVisibleChange = { toolbarVisible.value = it }
                    ) {
                        val isMute by viewModel.isMuteMode.observeAsState(false)
                        val isCapture by viewModel.isCaptureMode.observeAsState(false)
                        val isLock by viewModel.isLockMode.observeAsState(false)
                        val isKeep by viewModel.isKeepMode.observeAsState(false)
                        val isCaption by viewModel.isCaptionMode.observeAsState(false)

                        PanelButton(id = R.drawable.refresh_icon, onClick = { showRefreshDialog() })
                        PanelButton(id = R.drawable.volume_off, active = isMute, onClick = { viewModel.toggleMuteMode() })
                        PanelButton(id = R.drawable.camera_icon, active = isCapture, onClick = {
                            if (!checkStoragePermissionGrated()) showStoragePermissionDialog()
                            viewModel.toggleCaptureMode()
                        })
                        PanelButton(id = R.drawable.screen_lock, active = isLock, onClick = { viewModel.toggleLockMode(); updateOrientationLock() })
                        PanelButton(id = R.drawable.light_mode, active = isKeep, onClick = { viewModel.toggleKeepMode() })
                        PanelButton(id = R.drawable.caption_icon, active = isCaption, onClick = { viewModel.toggleCaptionMode() })
                        if (viewModel.k3dPatcher.isPatcherEnabled) {
                            PanelButton(id = R.drawable.kantai3d_icon, onClick = { isK3dDialogVisible.value = true })
                        }
                        PanelButton(id = R.drawable.exit_to_app, onClick = { showLogoutDialog() })
                        // Settings sits second-last, just before the close button.
                        PanelButton(id = R.drawable.settings, onClick = { settingsSheetVisible.value = true })
                        PanelButton(id = R.drawable.help_icon, onClick = { openManual(this@BrowserActivity) })

                        Spacer(modifier = Modifier.height(4.dp))
                        IconButton(onClick = { toolbarVisible.value = false }) {
                            Icon(painterResource(id = R.drawable.close_icon), "Close", tint = Color.White)
                        }
                    }

                    if (isK3dDialogVisible.value) {
                        Kantai3dDialog(
                            patcher = viewModel.k3dPatcher,
                            onSave = { enabled ->
                                viewModel.k3dPatcher.isEffectEnabled = enabled
                                isK3dDialogVisible.value = false
                            },
                            onDismiss = { isK3dDialogVisible.value = false }
                        )
                    }

                    if (settingsSheetVisible.value) {
                        SettingsBottomSheet(
                            viewModel = settingsViewModel,
                            activity = this@BrowserActivity,
                            onDismissRequest = { settingsSheetVisible.value = false },
                            onSettingChanged = { key ->
                                if (key in liveSettings) {
                                    // Applied to the running browser; no reload needed.
                                    applyLiveSetting(key)
                                } else {
                                    refreshPromptVisible.value = true
                                }
                            }
                        )
                    }

                    if (refreshPromptVisible.value) {
                        SettingsRefreshDialog(
                            onRefreshNow = {
                                refreshPromptVisible.value = false
                                settingsSheetVisible.value = false
                                refreshPageOrFinish()
                            },
                            onChangeWithoutRefresh = { refreshPromptVisible.value = false }
                        )
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        setupSmoothPipAnimation()
        hideSystemBars()
    }

    fun isKcMode(): Boolean = viewModel.isKcBrowserMode
    fun isMuteMode(): Boolean = java.lang.Boolean.TRUE == viewModel.isMuteMode.value
    fun isCaptionAvailable(): Boolean = java.lang.Boolean.TRUE == viewModel.isCaptionMode.value
    fun isSubtitleAvailable(): Boolean = viewModel.isSubtitleLoaded
    fun setStartedFlag() {
        viewModel.isStartedFlag = true
    }

    fun setErrorText(text: String) { errorText.value = text }
    fun setSubtitleText(text: String) { subtitleTextValue.value = text }
    fun setCloseButtonVisible(visible: Boolean) { closeButtonVisible.value = visible }

    private fun updateOrientationLock() {
        val isLockMode = java.lang.Boolean.TRUE == viewModel.isLockMode.value
        if (viewModel.sharedPref.getBoolean(PREF_LANDSCAPE, false)) {
            requestedOrientation = if (isLockMode) {
                val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                if (rot == Surface.ROTATION_270) {
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            } else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            requestedOrientation = if (isLockMode) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    /**
     * Applies a setting that does not require a WebView reload.
     *
     * @param key one of [liveSettings]; others are ignored.
     */
    private fun applyLiveSetting(key: String) {
        when (key) {
            PREF_LANDSCAPE -> updateOrientationLock()
            PREF_MULTIWIN_MARGIN -> updateMultiwindowMargin()
            PREF_SUBTITLE_FONTSIZE -> subtitleFontSize.value = settingsViewModel.getSubtitleFontSize()
            PREF_DOWNLOAD_RETRY -> {
                // Read lazily by ResourceProcess right before each retry prompt,
                // so nothing to do here.
            }
        }
    }

    /**
     * Adds a small black margin on the side facing the split-screen divider so
     * the divider does not overlap the game area. Re-implements the old
     * setMultiwindowMargin() from the XML layout era using Compose state.
     */
    private fun updateMultiwindowMargin() {
        val enabled = viewModel.sharedPref.getBoolean(PREF_MULTIWIN_MARGIN, false)
        // isInMultiWindowMode() and split-screen only exist on API 24+.
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInMultiWindowMode()) {
            multiwinMarginDp.value = 0
            return
        }

        val windowRect = Rect()
        val screenRect = Rect()
        val decorView = window.decorView
        decorView.getWindowVisibleDisplayFrame(windowRect)
        decorView.getGlobalVisibleRect(screenRect)

        // In split-screen mode at least one window edge is aligned with the
        // screen edge; if none are, it is free-form mode and no bar is needed.
        val isFreeform = windowRect.top != screenRect.top &&
                windowRect.bottom != screenRect.bottom &&
                windowRect.left != screenRect.left &&
                windowRect.right != screenRect.right

        multiwinMarginDp.value = if (isFreeform) {
            0
        } else {
            val center = (screenRect.top + screenRect.bottom) / 2
            when {
                windowRect.top > center -> MULTIWIN_MARGIN_DP   // bottom half
                windowRect.bottom < center -> MULTIWIN_MARGIN_DP // top half
                else -> 0
            }
        }
    }

    private fun setupSmoothPipAnimation() {
        val pipEnabled = viewModel.sharedPref.getBoolean(PREF_PIP_MODE, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsPiPMode() && pipEnabled) {
            val sourceRectHint = Rect()
            mContentView?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom) {
                    mContentView?.getGlobalVisibleRect(sourceRectHint)
                    setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setSeamlessResizeEnabled(false)
                            .setSourceRectHint(sourceRectHint)
                            .setAutoEnterEnabled(true)
                            .setAspectRatio(Rational(1200, 720))
                            .build()
                    )
                }
            }
        }
    }

    fun handleBackPress() {
        // Let the settings overlay handle back first so the user does not exit
        // the app (or trigger the "press back again" prompt) while it is open.
        if (refreshPromptVisible.value) {
            refreshPromptVisible.value = false
            return
        }
        if (settingsSheetVisible.value) {
            settingsSheetVisible.value = false
            return
        }
        // Reveal the floating toolbar on back press, so the user can always
        // bring it back even when the edge-swipe reveal gesture is consumed
        // by the system back gesture. This coincides with the
        // "Press back again to exit" prompt. Setting the state to true while
        // the toolbar is already visible is a no-op.
        toolbarVisible.value = true
        backPressCloseHandler.handleOnBackPressed()
    }

    override fun onStop() {
        super.onStop()
        mContentView?.let { manager?.runMuteScript(it, true, true) }
        sendIsFrontChanged(false)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        // Recompute the split-screen divider margin when entering/leaving split screen.
        updateMultiwindowMargin()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateMultiwindowMargin()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            viewModel.k3dPatcher.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        // Anchor dialogs opened by the settings sheet to this window; re-set on
        // every resume because the ViewModel outlives the activity.
        settingsViewModel.setHostActivity(this)
        hideSystemBars()
        mContentView?.resumeTimers()
        sendIsFrontChanged(true)
        updateMultiwindowMargin()
        mContentView?.let { manager?.runMuteScript(it, java.lang.Boolean.TRUE == viewModel.isMuteMode.value) }
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        viewModel.k3dPatcher.setRotation(rot)
        viewModel.k3dPatcher.resume()
    }

    override fun onDestroy() {
        settingsViewModel.clearHostActivity(this)
        mContentView?.removeAllViews()
        mContentView?.destroy()
        super.onDestroy()
    }

    fun showWebkitErrorDialog(errorCode: Int, description: String, failingUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(KcUtils.getWebkitErrorCodeText(errorCode))
            .setCancelable(false)
            .setMessage((description + "\n\n" + failingUrl).trim { it <= ' ' })
            .setPositiveButton("Reload") { _, _ -> refreshPageOrFinish() }
            .setNegativeButton("Close") { dialog, _ -> dialog.cancel() }
            .show()
    }

    fun showSslErrorDialog(handler: SslErrorHandler, error: SslError) {
        MaterialAlertDialogBuilder(this)
            .setTitle(KcUtils.getSslErrorCodeTitle(error.primaryError))
            .setCancelable(false)
            .setMessage((KcUtils.getSslErrorCodeDescription(error.primaryError) + "\n\nurl: " + error.url).trim { it <= ' ' })
            .setPositiveButton("Close") { _, _ -> handler.cancel() }
            .setNegativeButton("Proceed") { _, _ -> handler.proceed() }
            .show()
    }

    private fun refreshPageOrFinish() {
        viewModel.connectorInfo = WebViewManager.getDefaultPage(this, viewModel.isKcBrowserMode)
        val info = viewModel.connectorInfo
        if (manager != null && info != null && info.size == 2) {
            mContentView?.let { manager?.refreshPage(it) }
        } else {
            finish()
        }
    }

    fun showRefreshDialog() {
        if (java.lang.Boolean.TRUE == viewModel.isNoRefreshPopupMode.value) {
            refreshPageOrFinish()
        } else {
            mContentView?.pauseTimers()
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_name))
                .setCancelable(false)
                .setMessage(getString(R.string.refresh_msg))
                .setPositiveButton(R.string.action_ok) { _, _ -> refreshPageOrFinish() }
                .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                    dialog.cancel()
                    mContentView?.resumeTimers()
                }
                .show()
        }
    }

    fun showLogoutDialog() {
        mContentView?.pauseTimers()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setCancelable(false)
            .setMessage(getString(R.string.logout_msg))
            .setPositiveButton(R.string.action_ok) { _, _ ->
                if (manager != null) {
                    mContentView?.let { manager?.logoutGame(it) }
                } else {
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                dialog.cancel()
                mContentView?.resumeTimers()
            }
            .show()
    }

    fun showScreenshotNotification(bitmap: Bitmap, uri: Uri) {
        screenshotNotification.showNotification(bitmap, uri)
    }

    /**
     * Asks GitHub for the latest release as soon as the app is launched, so the
     * user is told about a new version without having to open the settings.
     *
     * Guarded through the ViewModel so a recreated activity does not fire a
     * second request for the same launch.
     */
    private fun checkAppUpdateOnStart() {
        if (viewModel.isAppUpdateCheckStarted) return
        viewModel.isAppUpdateCheckStarted = true
        settingsViewModel.checkAppUpdate(this)
    }

    /**
     * Kcanotify needs broadcast mode to follow the game state, so warn once per
     * launch if it is installed while the setting is off.
     */
    private fun warnIfKcanotifyBroadcastDisabled() {
        val broadcastEnabled = viewModel.sharedPref.getBoolean(PREF_BROADCAST, true)
        if (broadcastEnabled || !KcUtils.isKcanotifyInstalled(this)) return

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.kcanotify_broadcast_dialog_title))
            .setCancelable(false)
            .setMessage(
                String.format(
                    Locale.US,
                    getString(R.string.kcanotify_broadcast_dialog_message),
                    getString(R.string.mode_broadcast),
                    getString(R.string.action_ok)
                )
            )
            .setPositiveButton(R.string.action_ok) { dialog, _ ->
                viewModel.sharedPref.edit().putBoolean(PREF_BROADCAST, true).apply()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun checkStoragePermissionGrated(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showStoragePermissionDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_name))
                .setCancelable(false)
                .setMessage(getString(R.string.noti_screenshot_permission_message))
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                }
                .setNegativeButton(R.string.action_cancel) { dialog, _ -> dialog.cancel() }
                .show()
        }
    }

    fun sendIsFrontChanged(isFront: Boolean) {
        val intent = Intent(FOREGROUND_ACTION)
        intent.putExtra("is_front", isFront)
        sendBroadcast(intent)
    }

    /**
     * Blocks the soft keyboard by making the WebView unfocusable when the user
     * turned the on-screen keyboard off. The preference is read directly, since
     * BrowserActivity is now the only entry point.
     */
    fun applyKeyboardSetting() {
        if (viewModel.sharedPref.getBoolean(PREF_KEYBOARD, true)) return
        mContentView?.isFocusableInTouchMode = false
        mContentView?.isFocusable = false
        mContentView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun supportsPiPMode(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    private fun openManual(context: Context) {
        val url = context.getString(R.string.manual_link)
        val intentBuilder = CustomTabsIntent.Builder()
        intentBuilder.setShowTitle(true)
        val params = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(context, R.color.colorSettingsBackground))
            .build()
        intentBuilder.setDefaultColorSchemeParams(params)
        intentBuilder.setUrlBarHidingEnabled(true)

        val customTabsIntent = intentBuilder.build()
        val customTabsApps = context.packageManager.queryIntentActivities(customTabsIntent.intent, 0)
        if (customTabsApps.isNotEmpty()) {
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } else {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(browserIntent)
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

// Top-level so both the activity's floating toolbar and the IDE preview can use it.
@Composable
fun PanelButton(id: Int, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painterResource(id = id), null,
            tint = if (active) Color(0xFFFFC400) else Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

// Prompts the user to reload the WebView after changing a setting.
@Composable
private fun SettingsRefreshDialog(
    onRefreshNow: () -> Unit,
    onChangeWithoutRefresh: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onChangeWithoutRefresh,
        title = { Text(text = stringResource(id = R.string.settings_refresh_required_title)) },
        text = { Text(text = stringResource(id = R.string.settings_refresh_required_msg)) },
        confirmButton = {
            TextButton(onClick = onRefreshNow) {
                Text(text = stringResource(id = R.string.settings_refresh_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onChangeWithoutRefresh) {
                Text(text = stringResource(id = R.string.settings_refresh_later))
            }
        }
    )
}

// Compose replacement for the old k3d_form.xml dialog.
@Composable
private fun Kantai3dDialog(
    patcher: com.antest1.gotobrowser.Helpers.K3dPatcher,
    onSave: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var effectEnabled by remember { mutableStateOf(patcher.isEffectEnabled) }

    val imageUrl = patcher.imageUrl
    val message = when {
        imageUrl == null -> stringResource(id = R.string.msg_kantai3d_init)
        patcher.isDepthMapLoaded -> String.format(Locale.US, stringResource(id = R.string.msg_kantai3d_loaded), imageUrl)
        else -> String.format(Locale.US, stringResource(id = R.string.msg_kantai3d_error), imageUrl)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.settings_mod_kantai3d_enable)) },
        text = {
            Column {
                Text(text = message, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = R.string.menu_tooltip_kantai3d),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = effectEnabled, onCheckedChange = { effectEnabled = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(id = R.string.msg_kantai3d_redirect))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(effectEnabled) }) {
                Text(text = stringResource(id = R.string.text_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.text_cancel))
            }
        }
    )
}

// Stateless overlay layer (subtitle, capture, close). Extracted so the real
// overlays can be rendered in an IDE preview without a ViewModel or WebView.
@Composable
fun BrowserOverlayLayer(
    showSubtitle: Boolean,
    subtitleText: String,
    subtitleVisible: Boolean,
    subtitleFontSize: Int,
    isCapture: Boolean,
    closeButtonVisible: Boolean,
    onSubtitleTap: () -> Unit,
    onCaptureClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Subtitle Overlay
        if (showSubtitle && subtitleVisible) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = subtitleText.ifEmpty { stringResource(id = R.string.subtitle_default) },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = subtitleFontSize.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { onSubtitleTap() }
                )
            }
        }

        // Camera Button (Square)
        if (isCapture) {
            IconButton(
                onClick = onCaptureClick,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f)).border(2.dp, Color.White)
            ) {
                Icon(painterResource(id = R.drawable.capture_icon), "Capture", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        // DMM Close Button
        if (closeButtonVisible) {
            IconButton(
                onClick = onCloseClick,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun BrowserScreenContent(
    viewModel: BrowserViewModel,
    errorText: MutableState<String>,
    subtitleTextValue: MutableState<String>,
    closeButtonVisible: MutableState<Boolean>,
    subtitleFontSize: MutableState<Int>,
    multiwinMarginDp: MutableState<Int>,
    manager: WebViewManager?,
    onViewCreated: (WebViewL) -> Unit,
    intent: Intent?,
    activity: BrowserActivity,
    onBackgroundTap: () -> Unit
) {
    val isCaption by viewModel.isCaptionMode.observeAsState(false)
    val isCapture by viewModel.isCaptureMode.observeAsState(false)
    val isKeep by viewModel.isKeepMode.observeAsState(false)
    val showFlash = remember { mutableStateOf(false) }

    val currentSubtitle = subtitleTextValue.value
    val subtitleVisible = remember { mutableStateOf(true) }
    LaunchedEffect(currentSubtitle) {
        if (currentSubtitle.isNotEmpty()) {
            subtitleVisible.value = true
        }
    }

    LaunchedEffect(isKeep) {
        if (isKeep) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onBackgroundTap() }
    ) {
        // WebView with proper "Scale to Fit" 15:9
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = 1200f / 720f
            val containerWidth = maxWidth.value
            val containerHeight = maxHeight.value

            val finalWidth: androidx.compose.ui.unit.Dp
            val finalHeight: androidx.compose.ui.unit.Dp

            if (containerWidth / containerHeight > ratio) {
                // Screen is wider than 15:9
                finalHeight = maxHeight
                finalWidth = maxHeight * ratio
            } else {
                // Screen is narrower than 15:9
                finalWidth = maxWidth
                finalHeight = maxWidth / ratio
            }

            AndroidView(
                factory = { ctx ->
                    WebViewL(ctx).apply {
                        onViewCreated(this)
                        addJavascriptInterface(viewModel.k3dPatcher, "kantai3dInterface")
                        manager?.setHardwareAcceleratedFlag()
                        activity.applyKeyboardSetting()
                        // Initial setup...
                        viewModel.connectorInfo = WebViewManager.getDefaultPage(activity, viewModel.isKcBrowserMode)
                        val info = viewModel.connectorInfo
                        if (info != null && info.size == 2) {
                            manager?.setWebViewSettings(this)
                            WebViewManager.enableBrowserCookie(this)
                            manager?.setWebViewClient(activity, this)
                            manager?.setPopupView(this)
                            manager?.openPage(this, info, viewModel.isKcBrowserMode)
                        }
                    }
                },
                modifier = Modifier
                    .size(width = finalWidth, height = finalHeight)
                    // Leave room for the split-screen divider so it does not
                    // overlap the game area (see BrowserActivity#updateMultiwindowMargin).
                    .padding(top = multiwinMarginDp.value.dp, bottom = multiwinMarginDp.value.dp)
                    .background(Color.Black)
                    .clickable(enabled = false) { } // Prevent clicks on WebView from toggling panel
            )

            if (errorText.value.isNotEmpty()) {
                Text(
                    text = errorText.value,
                    color = Color(0xFF7987A8),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }

            if (showFlash.value) {
                Box(modifier = Modifier.matchParentSize().background(Color.White).alpha(0.5f))
                LaunchedEffect(Unit) {
                    Handler().postDelayed({ showFlash.value = false }, 250)
                }
            }
        }

        BrowserOverlayLayer(
            showSubtitle = viewModel.isKcBrowserMode && isCaption,
            subtitleText = currentSubtitle,
            subtitleVisible = subtitleVisible.value,
            subtitleFontSize = subtitleFontSize.value,
            isCapture = isCapture,
            closeButtonVisible = closeButtonVisible.value,
            onSubtitleTap = { subtitleVisible.value = false },
            onCaptureClick = {
                manager?.captureGameScreen(activity.findViewById(android.R.id.content)) // Or use view reference
                showFlash.value = true
            },
            onCloseClick = { activity.finish() }
        )
    }
}

@Preview(name = "Browser - Overlays + Toolbar", showBackground = true, widthDp = 800, heightDp = 480)
@Composable
fun BrowserScreenPreview() {
    GotobrowserTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Placeholder for the WebView area (an actual WebView cannot render in Preview).
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(1200f / 720f)
                    .background(Color(0xFF101010)),
                contentAlignment = Alignment.Center
            ) {
                Text("WebView (not renderable in Preview)", color = Color.White)
            }

            BrowserOverlayLayer(
                showSubtitle = true,
                subtitleText = "Sample subtitle text",
                subtitleVisible = true,
                subtitleFontSize = 18,
                isCapture = true,
                closeButtonVisible = false,
                onSubtitleTap = {},
                onCaptureClick = {},
                onCloseClick = {}
            )

            VerticalFloatingToolbar(visible = true, onVisibleChange = {}) {
                PanelButton(id = R.drawable.refresh_icon, onClick = {})
                PanelButton(id = R.drawable.volume_off, active = true, onClick = {})
                PanelButton(id = R.drawable.camera_icon, active = true, onClick = {})
                PanelButton(id = R.drawable.screen_lock, onClick = {})
                PanelButton(id = R.drawable.light_mode, onClick = {})
                PanelButton(id = R.drawable.caption_icon, active = true, onClick = {})
                PanelButton(id = R.drawable.exit_to_app, onClick = {})
                PanelButton(id = R.drawable.settings, onClick = {})
                PanelButton(id = R.drawable.help_icon, onClick = {})
                Spacer(modifier = Modifier.height(4.dp))
                IconButton(onClick = {}) {
                    Icon(painterResource(id = R.drawable.close_icon), "Close", tint = Color.White)
                }
            }
        }
    }
}
