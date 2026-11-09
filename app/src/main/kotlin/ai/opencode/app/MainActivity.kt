package ai.opencode.app

import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.share.ShareConversation
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.PerfFrameSampler
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.touch.TouchRecoveryClient
import ai.opencode.feature.composer.DictationPermissionClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.voice.VoiceMicrophonePermission
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import ai.opencode.devtools.DevTools
import ai.opencode.devtools.DevToolsOverlay
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.system.SessionWindowTarget
import kotlinx.coroutines.launch

/**
 * The app's window (iOS's scene). The window's [Store] lives in a retained [RootStore] (iOS
 * `WindowStore`) so configuration changes, split screen and freeform resizes keep state and
 * in-flight effects; [AppFeature] holds navigation (connection vs Home) as state, like iOS
 * `AppView`. On a tablet each further window is a task of its own (`singleInstancePerTask`,
 * [SceneWindows]); a phone has one.
 *
 * Intents are iOS's `onOpenURL` and notification taps: [route] turns each into an
 * [AppFeature.Action]. Later ones reach [onNewIntent].
 */
class MainActivity : ComponentActivity() {
    private val root: RootStore by viewModels()
    private val store get() = root.store

    /** The microphone prompt in flight (one at a time; a second ask joins it). */
    private var microphoneRequest: CompletableDeferred<Boolean>? = null
    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRequest?.complete(granted)
        microphoneRequest = null
    }
    private var removeRequesters: List<() -> Unit> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    private class TouchModeExit(val at: Long, val hadFocus: Boolean)
    private val typedNavigationKeys = setOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private var touchModeLeft: TouchModeExit? = null

    /**
     * A typed key with nothing focused leaves touch mode, and the framework then gives key focus to
     * the first focusable control (Home's ☰), so a space or Return pressed it and opened the drawer
     * (QA T-02). iOS gives buttons no key focus, so typing with no field focused does nothing:
     * the key that left touch mode takes that focus back and goes nowhere. Tab and the arrows
     * still move focus, as do later keys. The framework counts Space and Return as navigation
     * keys too and consumes their press to leave touch mode before it gets here, so for those
     * the release (pressed before touch mode was left) takes the focus back (QA F-70).
     */
    private fun dropsTypedFocus(event: KeyEvent): Boolean {
        val exit = touchModeLeft ?: return false
        val consumedPress = event.action == KeyEvent.ACTION_UP && event.keyCode in typedNavigationKeys
        if (event.action != KeyEvent.ACTION_DOWN && !consumedPress) return false
        touchModeLeft = null
        val focused = currentFocus
        if (event.downTime > exit.at || exit.hadFocus || focused == null) return false
        val content = findViewById<ViewGroup>(android.R.id.content)
        val focusability = content.descendantFocusability
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        focused.clearFocus()
        content.descendantFocusability = focusability
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Debug lab hooks run before anything reads the store (iOS runs them before `OpenCodeApp.store`).
        if (savedInstanceState == null) LabLaunch.apply(intent)
        // The window's store: the first window's, or a further window's on its own session.
        root.resolve(this, RootStore.target(intent, savedInstanceState))
        val splash = installSplashScreen()
        LaunchBloomController.start()
        splash.setKeepOnScreenCondition { LaunchBloomController.keepsCover(store.state.isColdLaunchContentReady) }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> store.send(AppFeature.Action.ForegroundChanged(true))
                    // iOS `scenePhase == .active`: take what the share sheet left.
                    Lifecycle.Event.ON_RESUME -> {
                        SceneWindows.resumed(root)
                        store.send(AppFeature.Action.PendingSharesRequested)
                        registerPermissionRequesters()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        removeRequesters.forEach { it() }
                        removeRequesters = emptyList()
                    }
                    Lifecycle.Event.ON_STOP -> if (!isChangingConfigurations) store.send(AppFeature.Action.ForegroundChanged(false))
                    else -> Unit
                }
            },
        )
        // Developer tools (summon gesture, Tuning Panel, perf HUD): inert outside debug builds unless Developer Mode is on.
        if (savedInstanceState == null) {
            DevTools.handleLaunchIntent(intent)
            // iOS starts these as it makes the app's store, once the lab's launch switches are read.
            PerfTimeline.appLaunched()
            PerfFrameSampler.startIfRequested()
        }
        setContent { DevToolsOverlay { AppView(store) } }
        window.decorView.viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
            touchModeLeft = if (inTouchMode) null else TouchModeExit(SystemClock.uptimeMillis(), currentFocus != null)
        }
        val callback = window.callback
        window.callback = object : Window.Callback by callback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = dropsTypedFocus(event) || callback.dispatchKeyEvent(event)
        }
        if (root.follows) followShownSession()
        // A recreated activity already handled its launch intent.
        if (savedInstanceState == null) handle(intent)
    }

    /**
     * Calls and dictation ask for the microphone as they start, as iOS's
     * `requestRecordPermission()` does. A call's first ask also asks for notifications (once), so
     * its ongoing notification (iOS's Live Activity, which needs no permission) can show.
     */
    private fun registerPermissionRequesters() {
        removeRequesters.forEach { it() }
        removeRequesters = listOf(
            dependency(VoiceMicrophonePermission).register {
                val granted = requestMicrophone()
                val notifications = dependency(NotificationPermissionClient)
                if (granted && notifications.status() == NotificationPermission.NotDetermined) notifications.request()
                granted
            },
            dependency(DictationPermissionClient).register { requestMicrophone() },
        )
    }

    private suspend fun requestMicrophone(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val pending = microphoneRequest ?: CompletableDeferred<Boolean>().also {
            microphoneRequest = it
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return pending.await()
    }

    /**
     * iOS `SessionWindowFollow`: on a tablet the window's restorable value follows the session it
     * shows (saved in [onSaveInstanceState]), and a session the cached list didn't have opens
     * once Home is up.
     */
    private fun followShownSession() {
        lifecycleScope.launch {
            store.states.collect { state ->
                val pending = root.pendingTarget ?: return@collect
                val home = state.home ?: return@collect
                if (root.shown?.sessionID == pending.sessionID) {
                    root.pendingTarget = null
                    return@collect
                }
                if (!home.snapshotLoaded || home.server.persistenceID != pending.serverID) return@collect
                root.pendingTarget = null
                store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(pending.sessionID)))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!root.follows) return
        val shown = root.shown ?: root.pendingTarget ?: return
        outState.putString(SessionWindowTarget.EXTRA_SERVER_ID, shown.serverID)
        outState.putString(SessionWindowTarget.EXTRA_SESSION_ID, shown.sessionID)
    }

    /**
     * A finger coming down arms touch recovery's sampler (iOS `TouchRecoveryArmRecognizer` on
     * every window): it only watches, never consumes.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) dependency(TouchRecoveryClient).touched()
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) root.activityFinished()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A window asked again for its own session shows it (iOS `onContinueUserActivity`).
        if (intent.action == SessionWindowTarget.ACTION && root.follows) {
            val next = RootStore.target(intent, null) ?: return
            if (next != root.shown) store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(next.sessionID)))
            return
        }
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val action = route(intent) ?: return
        LaunchBloomController.completeColdLaunch()
        if (action is AppFeature.Action.NotificationOpened) {
            // iOS `SceneWindows.store(for:primary:)`: the window showing that session, else the
            // one in use on its server, else the one doing the app-wide work.
            val window = SceneWindows.window(action.destination, receiving = root)
            if (window !== root) SceneWindows.bringToFront(window, this)
            window.store.send(action)
            return
        }
        store.send(action)
    }

    companion object {
        /**
         * iOS `AppView.onOpenURL` and `SessionNotifications.onOpen`: a session notification's tap,
         * an `opencode://` system action link, or a pairing link opened from the camera, a
         * browser, a chat or an `opencode://pair` / `opencode-ios://pair` deep link.
         */
        fun route(intent: Intent?): AppFeature.Action? {
            intent ?: return null
            if (intent.action == NotificationDestination.ACTION_OPEN) {
                val server = intent.getStringExtra(NotificationDestination.EXTRA_SERVER) ?: return null
                val session = intent.getStringExtra(NotificationDestination.EXTRA_SESSION) ?: return null
                return AppFeature.Action.NotificationOpened(NotificationDestination(server, session))
            }
            if (intent.action != Intent.ACTION_VIEW) return null
            val url = intent.dataString ?: return null
            // ScrawlKit's setup links (iOS `.scrawl()`) aren't part of the Android app.
            val host = intent.data?.host?.lowercase()
            if (host == "scrawl" || host == "tether") return null
            // Continue in OpenCode: the share waiting in Shares/ says where to go.
            if (ShareConversation.isContinueURL(url)) return AppFeature.Action.PendingSharesRequested
            SystemAction.fromUrl(url)?.let { return AppFeature.Action.SystemActionReceived(it) }
            if (!OpenCodePairingLink.shouldHandleOpenURL(url)) return null
            return AppFeature.Action.PairingURLOpened(url)
        }
    }
}

