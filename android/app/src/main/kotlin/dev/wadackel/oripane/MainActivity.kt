package dev.wadackel.oripane

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.wadackel.oripane.protocol.InputEvent
import kotlinx.coroutines.delay
import java.net.Inet4Address
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), ConnectionController.Ui {
    private lateinit var prefs: Prefs
    private lateinit var connection: ConnectionController
    private val client get() = connection.client
    private val handler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifi = false

    private var state by mutableStateOf<ClientState?>(null)
    private val streamUi = StreamScreenState(::log)
    private var viewport by mutableStateOf(Viewport())
    private var menuCorner by mutableStateOf(MenuCorner.BOTTOM_END)
    private var macToast: Toast? = null
    private val discovered = mutableStateListOf<Discovered>()
    private var screen by mutableStateOf<Screen>(Screen.Connections)
    /** Why the last connection started from Add a Mac failed; shown under its field. */
    private var addError by mutableStateOf<String?>(null)
    private var connectFromAdd = false
    private var savedMacs by mutableStateOf(emptyList<SavedMac>())
    private var settings by mutableStateOf(Settings.defaults)
    private var mode by mutableStateOf("extend")

    /** The Mac on the home screen's card, by [HomeEntry.key]; null for the first tile. */
    private var homeSelected by mutableStateOf<String?>(null)

    /** The Mac the last connection from the home screen went to, and its failure once it failed. */
    private var homeAttempt: String? = null
    private var homeFailure by mutableStateOf<HomeFailure?>(null)

    // The video views of the streaming screen; one set lives across reconnects and folds.
    private var touchInput: TouchInput? = null
    private var videoFrame: FrameLayout? = null
    private var videoSurface: SurfaceView? = null
    private var bandSampler: BandTintSampler? = null
    private var zoomLog: Runnable? = null
    // The soft keyboard's field, while the key row is shown.
    private var keyboardField: ImeField? = null
    /** The field exists but the window had no focus yet: the keyboard is asked for once it returns. */
    private var imeWaitingForFocus = false
    private val keyEvents by lazy { virtualKeyEvents() }
    private var zoomLoggedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Before the test hooks below, so a hook's rows are never migrated away.
        prefs.migrate()
        // The port hook (below) is read here: the controller takes its port when it is made.
        val portExtra = @Suppress("DEPRECATION") intent.extras?.get("port")
        val hookPort = launchPort(intent.hasExtra("port"), portExtra)
        if (hookPort != null) log("hook port=$hookPort") else if (intent.hasExtra("port")) log("hook port_ignored value=$portExtra")
        connection = ConnectionController(prefs, this, ::wifiNow, ::currentDisplay, hookPort ?: CONTROL_PORT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        watchNetwork()

        // Test hooks: `am start ... [--es set "<key>=<value>,…"] [--es host <ip or name>]
        // [--es mode extend|mirror|match] [--es stats true|false] [--es seed macs|nearby|unreachable|stale] [--ez saved true]
        // [--ez connect true] [--ei port <n>]`. `set` writes settings first (Prefs keys; `touch.map.<mode>.<gesture>=…`
        // for one mapping row; `radial.menu=copy+paste+…` for the radial menu's built-in items), so a
        // later `stats` wins over its `showStats`. With `connect`, `saved`
        // connects to the most recently used saved Mac and wins over `host`, which connects as if
        // typed into the address field. `seed` is described at [seed], `port` at [launchPort].
        intent.getStringExtra("set")?.let { SettingsHook.apply(prefs, it) }
        intent.getStringExtra("host")?.let { prefs.lastHost = it }
        intent.getStringExtra("mode")?.let { prefs.mode = it }
        intent.getStringExtra("stats")?.let { prefs.showStats = it == "true" }
        intent.getStringExtra("seed")?.let(::seed)
        savedMacs = prefs.savedMacs
        settings = prefs.settings
        mode = prefs.mode
        menuCorner = prefs.menuCorner

        setContent {
            OripaneTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.bg) {
                    // Material fade through: the old scene is gone before the new one fades in.
                    AnimatedContent(
                        targetState = state,
                        contentKey = ::sceneKey,
                        transitionSpec = { fadeIn(tween(210, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
                        label = "scene",
                    ) { s ->
                        // The scene fading out keeps running a moment; it must not take back or open the menu.
                        val active = transition.targetState == EnterExitState.Visible
                        when (s) {
                            null, is ClientState.Failed, ClientState.Closed -> NotConnected()
                            ClientState.Connecting -> ConnectingScreen(connection.label, active)
                            is ClientState.Pairing -> PairingScreen(s.code, active)
                            // One scene key, so a reconnect keeps the same SurfaceView and decoder.
                            is ClientState.Streaming, is ClientState.Reconnecting -> StreamScreen(s, active)
                        }
                    }
                }
            }
        }
        if (intent.getBooleanExtra("connect", false)) {
            if (intent.getBooleanExtra("saved", false)) {
                val latest = prefs.savedMacs.maxByOrNull { it.lastUsed }
                if (latest != null) connect(ConnectionController.Entry.Saved(latest.hostId)) else log("saved none")
            } else if (prefs.lastHost.isNotBlank()) {
                connect(ConnectionController.Entry.Address(prefs.lastHost))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
        // Before the connection resumes, so its next attempt sees the Wi-Fi of now: a network the
        // background took away does not report coming back when it never changed. Told to the
        // connection too, which marks Wi-Fi lost by itself when a LAN route fails in the background.
        val w = wifiNow()
        setWifi(w)
        connection.setNetwork(w)
        connection.onResume()
        bandSampler?.start()
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
        connection.onPause()
        bandSampler?.stop()
    }

    override fun onDestroy() {
        disconnect()
        networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    // MARK: connection

    private fun currentDisplay(): DisplayConfig {
        val b = windowManager.currentWindowMetrics.bounds
        val dm = resources.displayMetrics
        val hz = display?.refreshRate?.toInt() ?: 60
        return displayConfig(b.width(), b.height(), ((dm.xdpi + dm.ydpi) / 2).toInt(), hz, prefs.mode, prefs.settings)
    }

    /** [fromAdd]: started from Add a Mac, where a failure to reach the address goes back to. */
    private fun connect(entry: ConnectionController.Entry, fromAdd: Boolean = false) {
        disconnect()
        connectFromAdd = fromAdd
        addError = null
        streamUi.newConnection()
        viewport = Viewport()
        closeKeyboard(null)
        connection.start(entry)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "oripane").apply {
            setReferenceCounted(false)
            acquire()
        }
        log("ui connect entry=$entry mode=${prefs.mode}")
    }

    private fun disconnect() {
        keepTint()
        connection.stop()
        wifiLock?.release()
        wifiLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveBars(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = currentDisplay()
        log("ui configuration w=${d.width} h=${d.height}")
        connection.refreshDisplay()
    }

    override fun onState(state: ClientState) {
        if (state is ClientState.Failed || state == ClientState.Closed) keepTint()
        this.state = state
        if (state is ClientState.Failed && !(connectFromAdd && state.reason == FailReason.CONNECT_FAILED)) homeFailure = HomeFailure(homeAttempt ?: "", state.message)
        if (state is ClientState.Streaming) homeFailure = null
        if (state is ClientState.Failed || state == ClientState.Closed) {
            // A typed address that could not be reached goes back to the field, to be corrected there.
            if (connectFromAdd && state is ClientState.Failed && state.reason == FailReason.CONNECT_FAILED) {
                screen = Screen.Add
                addError = state.message
                this.state = null
            } else {
                screen = Screen.Connections
            }
            savedMacs = prefs.savedMacs
        }
        if (state !is ClientState.Connecting && state !is ClientState.Pairing) connectFromAdd = false
        setImmersiveBars(state is ClientState.Streaming || state is ClientState.Reconnecting)
        if (state is ClientState.Failed) disconnect()
    }

    override fun onStream(stream: ClientState.Streaming) {
        val change = streamUi.onStream(stream)
        if (change.resetZoom) touchInput?.setViewport(Viewport())
        change.notice?.let { Toast.makeText(this, it.text, Toast.LENGTH_SHORT).show() }
    }

    override fun onStats(stats: Stats) = streamUi.onStats(stats)

    override fun onWarning(awdl: Boolean) = streamUi.onAwdlWarning(awdl)

    private fun selectMode(m: String) {
        mode = m
        prefs.mode = m
        streamUi.modePicked(m)
        connection.refreshDisplay(force = true)
    }

    private fun liveSetting(s: Settings, change: String) {
        val incognitoFlips = s.incognitoKeyboard != settings.incognitoKeyboard
        changeSettings(s)
        touchInput?.applySettings(s)
        if (incognitoFlips) keyboardField?.let { applyInputType(it) }
        log("ui live_setting $change")
    }

    private fun onViewport(v: Viewport) {
        viewport = v
        applyViewport()
        logZoom()
    }

    /** At most one `ui zoom=` line per 0.2 s, and always the latest viewport last. */
    private fun logZoom() {
        if (zoomLog != null) return
        val r = Runnable {
            zoomLog = null
            zoomLoggedAt = SystemClock.uptimeMillis()
            val v = viewport
            log("ui zoom=%.3f left=%.3f top=%.3f".format(v.zoom, v.left, v.top))
        }
        zoomLog = r
        handler.postDelayed(r, (zoomLoggedAt + 200 - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun applyViewport() {
        val frame = videoFrame ?: return
        val surface = videoSurface ?: return
        val t = viewport.transform(placementOf(frame))
        surface.pivotX = 0f
        surface.pivotY = 0f
        surface.scaleX = t.scale
        surface.scaleY = t.scale
        surface.translationX = t.translationX
        surface.translationY = t.translationY
    }

    override fun onDataSaver(active: Boolean) = streamUi.onDataSaver(active)

    /**
     * Wi-Fi is whether the default network has the Wi-Fi transport. Metered-ness would not do:
     * mobile data reported NOT_METERED on the owner's phone, and Tailscale's VPN network carries
     * the transport of the network under it (`WIFI|VPN` on Wi-Fi).
     */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setWifi(wifiNow())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val w = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                handler.post { setWifi(w) }
            }

            // Also called when the background takes the app's network away, which is no Wi-Fi loss.
            override fun onLost(network: Network) {
                handler.post { setWifi(wifiNow()) }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun wifiNow(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun setWifi(w: Boolean) {
        if (w == wifi) return
        wifi = w
        log("network wifi=$w")
        // A service found on the Wi-Fi just left is not reachable; what Bonjour finds after
        // it returns is listed afresh.
        if (!w) {
            discovered.clear()
            connection.setDiscovered(emptyList())
        }
        connection.setNetwork(w)
    }

    private fun setImmersiveBars(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Hardware keyboards (DeX, Bluetooth) go to the Mac as key events while streaming, with their
    // own modifiers only; the soft keyboard's keys go to the focused field, which applies the row's.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (streamUi.showKeyboard && isSoftKeyboardKey(event.deviceId, event.flags)) return super.dispatchKeyEvent(event)
        if (sendPhysicalKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun sendPhysicalKey(event: KeyEvent): Boolean {
        val c = client ?: return false
        if (state !is ClientState.Streaming || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // The device only: key codes in a log would spell out a password typed on the Mac.
                if (event.repeatCount == 0) log("key physical device=${event.deviceId}")
                c.sendKey(event.keyCode, true, event.metaState)
            }
            KeyEvent.ACTION_UP -> c.sendKey(event.keyCode, false, event.metaState)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && imeWaitingForFocus) {
            imeWaitingForFocus = false
            keyboardField?.let(::showIme)
        }
    }

    // MARK: discovery (Bonjour `_oripane._tcp`; does not work through the emulator's NAT)

    private fun startDiscovery() {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = log("nsd start_failed code=$errorCode")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {
                handler.post {
                    discovered.removeAll { it.name == info.serviceName }
                    connection.setDiscovered(discovered.toList())
                }
            }

            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        // IPv4 only: the host's media socket is AF_INET.
                        val v4 = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull { it is Inet4Address } else i.host as? Inet4Address
                        val addr = v4?.hostAddress ?: return
                        val id = i.attributes["id"]?.let { String(it) }
                        handler.post {
                            discovered.removeAll { it.name == i.serviceName }
                            discovered.add(Discovered(i.serviceName, addr, id))
                            connection.setDiscovered(discovered.toList())
                        }
                        log("nsd found name=${i.serviceName} addr=$addr id=${id ?: "-"}")
                    }
                })
            }
        }
        discovery = l
        runCatching { nsd.discoverServices("_oripane._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { (getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(l) }
    }

    // MARK: screens

    @Composable
    private fun ConnectingScreen(label: String, active: Boolean) {
        BackHandler(enabled = active) { disconnect(); state = null }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Palette.text, strokeWidth = 3.dp, trackColor = Palette.surface2)
                Text("Connecting to $label…", fontSize = 17.sp, color = Palette.text, textAlign = TextAlign.Center)
                TextButton(onClick = { disconnect(); state = null }) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    /** The screens of [Screen] while not connected; the connection's failure shows on the home screen. */
    @Composable
    private fun NotConnected() {
        val sc = screen
        LaunchedEffect(sc) { log("screen name=${sc.logName}") }
        PageStack(sc, Screen::parent, onBack = { screen = it }) { page ->
            when (page) {
                Screen.Connections -> HomeScreen(
                    saved = savedMacs,
                    discovered = discovered,
                    selected = homeSelected,
                    mode = mode,
                    failure = homeFailure,
                    onSelect = {
                        homeSelected = it.key
                        log("ui home_select entry=${if (it is HomeEntry.Saved) "saved" else "found"}")
                    },
                    onMode = ::rememberMode,
                    onConnect = ::connectFromHome,
                    onDetail = { screen = Screen.Detail(it.hostId) },
                    onAdd = { screen = Screen.Add },
                    onSettings = { screen = Screen.Settings },
                )
                Screen.Add -> AddScreen(
                    initial = prefs.lastHost,
                    mode = mode,
                    error = addError,
                    onChange = { prefs.lastHost = it; addError = null },
                    onConnect = { host, m ->
                        rememberMode(m)
                        screen = Screen.Connections
                        homeAttempt = null
                        connect(ConnectionController.Entry.Address(host), fromAdd = true)
                    },
                    onBack = { screen = Screen.Connections },
                )
                is Screen.Detail -> {
                    // Deleting the record moves to the list itself; nothing to show for a missing one.
                    savedMacs.firstOrNull { it.hostId == page.hostId }?.let { mac ->
                        DetailScreen(
                            mac = mac,
                            onRename = { name -> updateSaved { all -> all.map { if (it.hostId == mac.hostId) it.copy(customName = name) else it } } },
                            onDelete = {
                                updateSaved { all -> all.filter { it.hostId != mac.hostId } }
                                screen = Screen.Connections
                            },
                            onBack = { screen = Screen.Connections },
                        )
                    }
                }
                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = ::changeSettings,
                    onReset = { changeSettings(Settings.defaults) },
                    onMap = { screen = Screen.Map(it) },
                    onRadialMenu = { screen = Screen.RadialMenu },
                    onBack = { screen = Screen.Connections },
                )
                is Screen.Map -> MapScreen(
                    mode = page.mode,
                    map = settings.mapFor(page.mode),
                    onChange = { m -> changeSettings(settings.withMap(page.mode, m)) },
                    onShortcut = { g, edit -> screen = Screen.GestureShortcut(page.mode, g, edit) },
                    onBack = { screen = Screen.Settings },
                )
                is Screen.GestureShortcut -> {
                    val map = settings.mapFor(page.mode)
                    val initial = (map[page.gesture] as? GestureAction.Run)?.entry as? RadialEntry.Custom
                    // As for the radial menu: a reset under the page may take away the shortcut to edit.
                    val gone = remember(page) { page.edit && initial == null }
                    if (gone) {
                        LaunchedEffect(page) { screen = Screen.Map(page.mode) }
                    } else {
                        RadialShortcutScreen(
                            initial = if (page.edit) initial else null,
                            edit = page.edit,
                            onSave = { entry ->
                                changeSettings(settings.withMap(page.mode, map.with(page.gesture, GestureAction.Run(entry))))
                                screen = Screen.Map(page.mode)
                            },
                            onBack = { screen = Screen.Map(page.mode) },
                        )
                    }
                }
                Screen.RadialMenu -> RadialMenuScreen(
                    menu = settings.radialMenu,
                    onChange = { changeSettings(settings.copy(radialMenu = it)) },
                    onShortcut = { cell, edit -> screen = Screen.RadialShortcut(cell, edit) },
                    onBack = { screen = Screen.Settings },
                )
                is Screen.RadialShortcut -> {
                    val menu = settings.radialMenu
                    val initial = menu.getOrNull(page.cell) as? RadialEntry.Custom
                    // Judged once, as the screen opens: its own save fills the cell while it fades out.
                    // A reset under it can take the entry away; there is nothing to edit then.
                    val gone = remember(page) { page.edit && initial == null }
                    if (gone) {
                        LaunchedEffect(page) { screen = Screen.RadialMenu }
                    } else {
                        RadialShortcutScreen(
                            initial = if (page.edit) initial else null,
                            edit = page.edit,
                            onSave = { entry ->
                                changeSettings(settings.copy(radialMenu = menu.toMutableList().also { it[page.cell] = entry }))
                                screen = Screen.RadialMenu
                            },
                            onBack = { screen = Screen.RadialMenu },
                        )
                    }
                }
            }
        }
    }

    /** Connect from the home card: the chosen Mac, in the mode on the card. */
    private fun connectFromHome(entry: HomeEntry) {
        homeSelected = entry.key
        homeAttempt = entry.key
        homeFailure = null
        connect(
            when (entry) {
                is HomeEntry.Saved -> ConnectionController.Entry.Saved(entry.mac.hostId)
                is HomeEntry.Found -> ConnectionController.Entry.Address(entry.mac.addr)
            },
        )
    }

    /**
     * Keeps the colour of the last frame for the Mac's home card when a session that streamed ends.
     * A frame without colour keeps the colour before it: a grey card says no session has run yet.
     */
    private fun keepTint() {
        if (state !is ClientState.Streaming && state !is ClientState.Reconnecting) return
        val id = connection.connectedHostId ?: return
        val c = streamUi.bandTint.frame ?: return
        updateSaved { all -> all.map { if (it.hostId == id) it.copy(tint = c.toArgb()) else it } }
        log("ui keep_tint")
    }

    private fun rememberMode(m: String) {
        mode = m
        prefs.mode = m
    }

    /**
     * Fills the home screen for screenshots: the emulator reaches one Mac and Bonjour does not cross
     * its NAT. `macs` adds two saved Macs that cannot connect and one found Mac; `nearby` adds only
     * the found Mac. Not for use with `saved` or `connect`. `unreachable` and `stale` are at [seedRoutes].
     */
    private fun seed(kind: String) {
        if (kind == "unreachable" || kind == "stale") {
            seedRoutes(kind)
            return
        }
        if (kind != "macs" && kind != "nearby") {
            log("seed unknown=$kind")
            return
        }
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        if (kind == "macs") {
            val none = Pairing(ByteArray(16), ByteArray(32))
            prefs.savedMacs = prefs.savedMacs
                .upsert(SavedMac(hostId = "seed-studio", name = "Studio", pairing = none, lanAddrs = listOf("10.0.2.98"), lastUsed = now - 2 * day))
                .upsert(SavedMac(hostId = "seed-mbp", name = "MacBook Pro", pairing = none, tailnet = listOf("100.64.0.9"), lastUsed = now - 5 * day))
        }
        discovered.removeAll { it.addr == "10.0.2.97" }
        discovered.add(Discovered("iMac", "10.0.2.97", null))
        log("seed kind=$kind")
    }

    /**
     * Routes to race on the saved Mac the emulator reaches at 10.0.2.2: `unreachable` puts three
     * addresses that never answer before it; `stale` keeps only one of those and no tailnet, and
     * half a second later reports the Mac found by Bonjour at 10.0.2.2. For use with `saved`.
     */
    private fun seedRoutes(kind: String) {
        val mac = prefs.savedMacs.filter { "10.0.2.2" in it.lanAddrs }.maxByOrNull { it.lastUsed }
        if (mac == null) {
            log("seed none_at=10.0.2.2")
            return
        }
        val dead = listOf("10.255.255.1", "10.255.255.2", "10.255.255.3")
        // The newest record, so that `saved` connects to it.
        val newest = (prefs.savedMacs.maxOfOrNull { it.lastUsed } ?: 0) + 1
        val seeded = if (kind == "unreachable") mac.copy(lanAddrs = dead + "10.0.2.2", lastUsed = newest) else mac.copy(lanAddrs = dead.take(1), tailnet = emptyList(), lastUsed = newest)
        prefs.savedMacs = prefs.savedMacs.upsert(seeded)
        if (kind == "stale") {
            handler.postDelayed({
                discovered.add(Discovered(mac.name, "10.0.2.2", mac.hostId))
                connection.setDiscovered(discovered.toList())
            }, 500)
        }
        log("seed kind=$kind")
    }

    private fun updateSaved(change: (List<SavedMac>) -> List<SavedMac>) {
        prefs.savedMacs = change(prefs.savedMacs)
        savedMacs = prefs.savedMacs
    }

    private fun changeSettings(s: Settings) {
        prefs.settings = s
        settings = s
        connection.refreshDataSaver()
    }

    @Composable
    private fun PairingScreen(code: String, active: Boolean) {
        BackHandler(enabled = active) { disconnect(); state = null }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Pair with this Mac", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                // Inter's digits are proportional by default; tabular figures keep the groups even.
                Text(
                    code.chunked(3).joinToString(" "),
                    color = Palette.text,
                    style = LocalTextStyle.current.copy(fontSize = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, fontFeatureSettings = "tnum"),
                )
                Text(
                    "Make sure the Mac's dialog shows the same code, then click Allow there.",
                    color = Palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                TextButton(onClick = { disconnect(); state = null }) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    @Composable
    private fun StreamScreen(state: ClientState, active: Boolean) {
        val s = (state as? ClientState.Reconnecting)?.last ?: state as ClientState.Streaming
        val reconnecting = state is ClientState.Reconnecting
        BackHandler(enabled = active) { streamUi.setMenu(onBack(streamUi.menu), "back") }
        val color = statusColor(streamUi.stats, reconnecting)
        LaunchedEffect(color) { log("ui status=${color.logName}") }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val (frameW, frameH) = fitInside(constraints.maxWidth, constraints.maxHeight, s.width, s.height)
            AndroidView(
                factory = { ctx -> videoView(ctx) },
                modifier = Modifier.fillMaxSize(),
                update = {
                    val frame = videoFrame
                    if (frame != null && (frame.layoutParams.width != frameW || frame.layoutParams.height != frameH)) {
                        frame.layoutParams = FrameLayout.LayoutParams(frameW, frameH, Gravity.CENTER)
                    }
                },
            )
            if (streamUi.awdl.banner) {
                val below = if (awdlBannerBelowCorners(maxWidth.value)) Modifier.padding(top = CORNER_DP.dp) else Modifier
                AwdlBanner(onDismiss = streamUi::dismissAwdlBanner, modifier = Modifier.align(Alignment.TopCenter).then(below))
            }
            // Under the corners, which take their own touches over the strip.
            streamUi.bands?.let {
                HalftoneBands(it, settings.scrollBandSize, streamUi.bandTouch, streamUi.bandTouch?.y?.takeIf { streamUi.stripRelocating }, streamUi.bandTint, Modifier.fillMaxSize())
            }
            val st = streamUi.stats
            val note = streamUi.note?.takeIf { settings.showStats || it.always }
            // A reconnect keeps the dot open for as long as it lasts; its route names the note once it is back.
            var lastRoute by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(state) {
                if (state is ClientState.Reconnecting) lastRoute = state.route
            }
            LaunchedEffect(reconnecting) {
                if (!reconnecting && lastRoute != null) {
                    streamUi.say(backOnNote(lastRoute))
                    lastRoute = null
                }
            }
            val said = streamUi.note
            LaunchedEffect(said) {
                if (said != null) {
                    delay(said.ms)
                    streamUi.noteShown(said.id)
                }
            }
            StatusCorner(
                showDot = settings.showStats,
                color = if (reconnecting) StatusColor.GREY else note?.color ?: color,
                note = if (state is ClientState.Reconnecting) reconnectingText(state.message, state.attempt, state.route) else note?.text,
                waiting = reconnecting,
                expanded = streamUi.statsExpanded,
                details = st?.let { { StatsOverlay(it, s, streamUi.dataSaverOn) } },
                awdlMark = streamUi.awdl.mark,
                onToggle = streamUi::toggleStats,
                onMark = streamUi::tapAwdlMark,
                modifier = Modifier.align(menuCorner.opposite.alignment()),
            )
            MenuButton(
                corner = menuCorner,
                parentWidth = width,
                parentHeight = height,
                onTap = { streamUi.setMenu(MenuState.MENU, "button") },
                onCorner = { c ->
                    menuCorner = c
                    prefs.menuCorner = c
                    log("ui menu_corner=${c.key}")
                },
                modifier = Modifier.align(menuCorner.alignment()),
            )
            if (streamUi.showKeyboard) {
                KeyboardWatch(reconnecting)
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime)) {
                    val (text, cursor) = streamUi.preedit
                    if (text.isNotEmpty()) PreeditBar(text, cursor)
                    KeyRow(
                        mods = streamUi.modKeys,
                        onMod = ::rowMod,
                        onKey = ::rowKey,
                        onKeyReleased = { updateModKeys(streamUi.modKeys.afterKey()) },
                        field = { AndroidView(factory = { ctx -> keyboardSink(ctx) }, modifier = Modifier.size(width = 48.dp, height = 40.dp)) },
                    )
                }
            }
            LaunchedEffect(reconnecting) {
                if (reconnecting) streamUi.closeOverlays("reconnect")
            }
            LaunchedEffect(streamUi.menu) {
                if (streamUi.menu != MenuState.CLOSED) streamUi.closeOverlays("menu")
            }
            // A fold or a turn moves everything under a menu left open.
            LaunchedEffect(width, height) { streamUi.closeOverlays("resize") }
            // The stream screen stays through a reconnect and goes for any other state.
            DisposableEffect(Unit) { onDispose { streamUi.dropLockQuestion() } }
            // After the stream screen's own BackHandler, so that back closes the menu first.
            streamUi.radial?.let { if (active) RadialMenuOverlay(it, streamUi.bandTint.frame, onPick = ::pickRadial, onClose = streamUi::closeRadial) }
            // Over the ring, which stays open after a volume or track key; it takes no touches.
            streamUi.radialToast?.let { (entry, at) ->
                LaunchedEffect(at) {
                    delay(1400)
                    streamUi.clearToast()
                }
                RadialToast(entry, Modifier.align(Alignment.BottomCenter))
            }
            if (active && streamUi.lockQuestion) {
                ConfirmDialog(
                    title = LockConfirm.TITLE,
                    body = LockConfirm.BODY,
                    confirm = LockConfirm.CONFIRM,
                    onConfirm = {
                        val source = streamUi.lockConfirmed()
                        if (this@MainActivity.state is ClientState.Streaming) {
                            sendMac(MacAction.LOCK_SCREEN, source)
                            if (source == "radial") streamUi.showToast(RadialEntry.Builtin(RadialItem.LOCK_SCREEN))
                        }
                    },
                    onDismiss = streamUi::dropLockQuestion,
                )
            }
            if (active && streamUi.menu != MenuState.CLOSED) {
                MenuSheet(
                    MenuModel(
                        menu = streamUi.menu,
                        macName = connection.label,
                        status = menuStatus(streamUi.streamMode, connection.routeKind, streamUi.stats, reconnecting),
                        statusColor = color,
                        tint = streamUi.bandTint.frame,
                        reconnecting = reconnecting,
                        streamMode = streamUi.streamMode,
                        zoomed = viewport.zoom > 1f,
                        settings = settings,
                        onBack = { streamUi.setMenu(onBack(streamUi.menu), "back") },
                        onDismissed = { streamUi.setMenu(MenuState.CLOSED, "outside") },
                        onItemClosed = { streamUi.setMenu(MenuState.CLOSED, "item") },
                        onKeyboard = streamUi::openKeyboard,
                        onMode = ::selectMode,
                        onResetZoom = { touchInput?.setViewport(Viewport()) },
                        onSettingsPage = { streamUi.setMenu(MenuState.SETTINGS, "item") },
                        onMacPage = { streamUi.setMenu(MenuState.MAC, "item") },
                        onDisplayPage = { streamUi.setMenu(MenuState.DISPLAY, "item") },
                        onMac = { sendMac(it, "menu") },
                        onDisconnect = {
                            disconnect()
                            this@MainActivity.state = null
                            screen = Screen.Connections
                            savedMacs = prefs.savedMacs
                        },
                        onSetting = ::liveSetting,
                    ),
                )
            }
        }
    }

    /** Only while the picture is live: a tap on the frozen frame of "Reconnecting…" would click where the Mac no longer shows that. */
    private fun sendTouch(e: InputEvent) {
        if (state is ClientState.Streaming) client?.sendInput(e)
    }

    private fun sendMac(a: MacAction, source: String) {
        log("input mac action=${a.key} source=$source")
        client?.sendMac(a)
        // From the menu nothing on the Mac may show that it happened, as for the volume with no HUD.
        if (source == "menu") {
            macToast?.cancel()
            macToast = Toast.makeText(this, "Sent to the Mac: ${a.label}", Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /**
     * The radial menu's events, in the screen's pixels. A move or a lift after the menu closed
     * under the finger (a reconnect, the sheet opening) finds no menu and does nothing.
     */
    private fun onRadial(e: GestureEvent) {
        val density = resources.displayMetrics.density
        when (e) {
            is GestureEvent.RadialOpened -> {
                val area = videoFrame?.parent as? View ?: return
                streamUi.openRadial(RadialMenuState.opened(settings.radialMenu, e.x, e.y, e.videoX, e.videoY, area.width.toFloat(), area.height.toFloat(), RadialSize.MARGIN * density))
            }
            is GestureEvent.RadialMoved ->
                if (streamUi.moveRadial(e.x, e.y, RadialSize.DEAD_ZONE * density)) videoFrame?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            is GestureEvent.RadialReleased -> streamUi.releaseRadial(e.x, e.y, RadialSize.DEAD_ZONE * density)?.let(::pickRadial)
            GestureEvent.RadialCancelled -> streamUi.closeRadial("cancel")
            else -> {}
        }
    }

    /** Sends the entry in [cell] of the open ring. */
    private fun pickRadial(cell: Int) {
        val pick = streamUi.pick(cell) ?: return
        videoFrame?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (!runEntry(pick.entry, pick.videoX, pick.videoY, "radial")) return
        // The keyboard coming up says it already, and the note would sit under it.
        if (pick.entry.send != RadialSend.Keyboard) streamUi.showToast(pick.entry)
    }

    /**
     * Runs a radial menu entry, picked on the ring or given to a gesture; a right click and Fill
     * screen act at ([videoX], [videoY]) on the video. Lock screen asks first, and not while
     * reconnecting, as the Mac page cannot lock then either: false then, as nothing was sent yet.
     */
    private fun runEntry(entry: RadialEntry, videoX: Float, videoY: Float, source: String): Boolean {
        log(
            when (entry) {
                is RadialEntry.Builtin -> "input entry item=${entry.item.key} source=$source"
                // One shortcut the user set up, not typed text, so its keys may be logged.
                is RadialEntry.Custom -> "input entry item=custom code=${entry.code} meta=${entry.meta} source=$source"
            },
        )
        when (val send = entry.send) {
            is RadialSend.Mac -> if (send.action == MacAction.LOCK_SCREEN) {
                if (state is ClientState.Streaming) streamUi.askLock(source)
                return false
            } else {
                sendMac(send.action, source)
            }
            is RadialSend.Key -> client?.let {
                it.sendKey(send.code, true, send.meta)
                it.sendKey(send.code, false, send.meta)
            }
            RadialSend.Keyboard -> streamUi.openKeyboard()
            RadialSend.RightClick -> sendTouch(InputEvent.RightClick(videoX, videoY))
            RadialSend.FillScreen -> touchInput?.fillScreen(videoX, videoY)
        }
        return true
    }

    /**
     * Closes the row when the soft keyboard goes away, when it never came within a second, or when
     * a reconnect starts. `open` is logged when the keyboard is first seen.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun KeyboardWatch(reconnecting: Boolean) {
        val visible = WindowInsets.isImeVisible
        var seen by remember { mutableStateOf(false) }
        LaunchedEffect(reconnecting) { if (reconnecting) closeKeyboard("reconnect") }
        LaunchedEffect(visible) {
            when {
                visible -> if (!seen) {
                    seen = true
                    log("ui keyboard open")
                }
                seen -> closeKeyboard("close")
                else -> {
                    delay(1000)
                    closeKeyboard("ime_timeout")
                }
            }
        }
    }

    /**
     * Hides the row and the keyboard; [reason] null is a silent reset at a new connection. A
     * composition is sent when the user closed the keyboard and dropped on a reconnect or a reset,
     * where nothing shows what it would type into. That happens first, while the field is there.
     */
    private fun closeKeyboard(reason: String?) {
        if (!streamUi.showKeyboard) return
        keyboardField?.let { if (reason == "close" || reason == "ime_timeout") it.confirm("close") else it.discard("reconnect") }
        keyboardField?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        keyboardField = null
        imeWaitingForFocus = false
        streamUi.closeKeyboard(reason)
    }

    private fun updateModKeys(next: ModKeys) {
        if (streamUi.updateModKeys(next)) keyboardField?.let(::applyInputType)
    }

    /** The field builds its editor from the modifiers ([ImeField.onCreateInputConnection]); a restart has the keyboard ask again. */
    private fun applyInputType(f: ImeField) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(f)
    }

    /**
     * A key of the row. During a composition Esc drops it and goes no further; any other key sends
     * it first, so typed Japanese never vanishes. The restart has the keyboard forget its copy,
     * which it would otherwise set again on the next key.
     */
    private fun rowKey(code: Int, held: Boolean) {
        val f = keyboardField
        if (f != null && f.isComposing) {
            if (code == KeyEvent.KEYCODE_ESCAPE) {
                f.discard("esc")
                applyInputType(f)
                return
            }
            f.confirm("key")
            applyInputType(f)
        }
        sendRowKey(code, held)
    }

    /** A modifier of the row sends a composition first, without the modifier; one restart follows either way. */
    private fun rowMod(k: ModKey) {
        val f = keyboardField?.takeIf { it.isComposing }
        f?.confirm("modifier")
        val flips = streamUi.updateModKeys(streamUi.modKeys.tapped(k))
        if (flips) keyboardField?.let(::applyInputType) else if (f != null) applyInputType(f)
    }

    /**
     * One key with the row's modifiers, which a once-only modifier then gives up; a [held] send,
     * one of a key held down, keeps them until the key is released.
     */
    private fun sendRowKey(code: Int, held: Boolean = false) {
        val c = client ?: return
        c.sendKey(code, true, streamUi.modKeys.meta)
        c.sendKey(code, false, streamUi.modKeys.meta)
        if (!held) updateModKeys(streamUi.modKeys.afterKey())
    }

    private fun sendTyped(typed: String) {
        val c = client ?: return
        if (!streamUi.modKeys.anyOn) {
            if (typed.contains('\n')) {
                typed.replace("\n", "").takeIf { it.isNotEmpty() }?.let { c.sendText(it) }
                c.sendKey(KeyEvent.KEYCODE_ENTER, true, 0)
                c.sendKey(KeyEvent.KEYCODE_ENTER, false, 0)
            } else if (typed.isNotEmpty()) {
                c.sendText(typed)
            }
            return
        }
        val plan = KeyPlan.forText(typed, streamUi.modKeys, keyEvents)
        plan.sends.forEach {
            when (it) {
                is KeySend.Text -> c.sendText(it.text)
                is KeySend.Key -> {
                    c.sendKey(it.code, true, it.meta)
                    c.sendKey(it.code, false, it.meta)
                }
            }
        }
        // The count only: the characters may be a password.
        if (plan.dropped.isNotEmpty()) log("key modifier_dropped count=${plan.dropped.size}")
        updateModKeys(plan.modKeys)
    }

    private fun showIme(f: ImeField) {
        f.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(f, 0)
    }

    /**
     * The touch area: a [FrameLayout] over the whole screen, centring the video frame, a
     * [FrameLayout] the size of the video at 1x holding the [SurfaceView], which a zoom scales and
     * shifts. Touches go to the area and are measured from the unzoomed frame. The area does not
     * clip its children, so a zoomed video spreads over the black bars up to the screen's edges.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun videoView(ctx: Context): FrameLayout {
        val area = FrameLayout(ctx)
        area.clipChildren = false
        val frame = FrameLayout(ctx)
        area.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val v = SurfaceView(ctx)
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val s = prefs.settings
        // A new connection's input reports its own strip; the last one's mark must not linger until then.
        streamUi.resetStrip()
        val input = TouchInput(
            frame,
            s,
            ::onViewport,
            // The recognizer clicks and fills the screen itself, so no point on the video comes with a command.
            onCommand = { runEntry(it, 0f, 0f, "gesture") },
            onRadial = ::onRadial,
            onStrip = { e -> streamUi.onStrip(e)?.let { at -> liveSetting(settings.copy(stripKnobAt = at), "scroll.strip.knob=%.2f".format(at)) } },
            onStripArea = streamUi::stripAreaChanged,
            send = ::sendTouch,
        )
        touchInput = input
        videoFrame = frame
        videoSurface = v
        bandSampler?.stop()
        bandSampler = BandTintSampler(v, area, { streamUi.bands }, { settings.scrollBandSize }, streamUi::tintChanged).also { it.start() }
        log("touch mode=${s.touchMode.key}")
        // Once per connection: this view outlives folds and reconnects, which re-send Streaming.
        input.onStreamStarted()
        area.setOnTouchListener { _, e -> input.onTouch(e) }
        area.setOnGenericMotionListener { _, e -> input.onGeneric(e) }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyViewport() }
        v.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                log("surface created")
                connection.attachSurface(h.surface)
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                log("surface destroyed")
                connection.attachSurface(null)
            }
        })
        return area
    }

    /**
     * The soft keyboard's field: committed text goes out with the row's modifiers, keys through the
     * row's own path, and the composition shows over the row until it is committed.
     */
    private fun keyboardSink(ctx: Context): ImeField {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        lateinit var f: ImeField
        f = ImeField(
            ctx,
            ImeField.Callbacks(
                onText = ::sendTyped,
                onKey = { sendRowKey(it) },
                onPreedit = streamUi::setPreedit,
                onSelectionSettled = { cursor, start, end -> imm.updateSelection(f, cursor, cursor, start, end) },
                modifiersOn = { streamUi.modKeys.anyOn },
                incognito = { settings.incognitoKeyboard },
                onPhysicalKey = ::sendPhysicalKey,
                // Sent on a switch to the menu or the background; with no stream there is nowhere to send it.
                keepOnDetach = { streamUi.showKeyboard && state is ClientState.Streaming },
                log = ::log,
            ),
        )
        keyboardField = f
        // A field the keyboard closed without a word may have left its text in the strip.
        streamUi.setPreedit("", 0)
        f.background = GradientDrawable().apply {
            val density = ctx.resources.displayMetrics.density
            cornerRadius = 8 * density
            setColor(Palette.keyBg.toArgb())
            setStroke(density.roundToInt().coerceAtLeast(1), Palette.keyBorder.toArgb())
        }
        // The sheet's window may still hold the focus; asking for the keyboard then would go nowhere.
        f.post { if (f.hasWindowFocus()) showIme(f) else imeWaitingForFocus = true }
        return f
    }
}
