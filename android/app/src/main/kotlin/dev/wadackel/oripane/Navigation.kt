/**
 * The screens shown while not connected, where back goes from each, and which scene a connection
 * state shows.
 */
package dev.wadackel.oripane

/** The screen shown while not connected; connecting screens come from the connection state. */
sealed interface Screen {
    val logName: String

    data object Connections : Screen { override val logName = "connections" }
    data object Add : Screen { override val logName = "add" }
    data class Detail(val hostId: String) : Screen { override val logName = "detail" }
    /** Settings, which keeps which of its sections is shown itself: see [SettingsScreen]. */
    data object Settings : Screen { override val logName = "settings" }
    data class Map(val mode: TouchMode) : Screen { override val logName = "map_${mode.key}" }
    data object RadialMenu : Screen { override val logName = "radial_menu" }

    /**
     * A shortcut of the user's own for the radial menu's [cell]: new, in place of what the cell holds,
     * or, with [edit], the custom entry in it loaded to change.
     */
    data class RadialShortcut(val cell: Int, val edit: Boolean) : Screen { override val logName = "radial_shortcut" }

    /** A shortcut of the user's own for [gesture] of [mode]'s map: new, or with [edit] the one it has, loaded to change. */
    data class GestureShortcut(val mode: TouchMode, val gesture: Gesture, val edit: Boolean) : Screen { override val logName = "gesture_shortcut" }
}

/** Where back goes from this screen; the home screen has nowhere to go. */
fun Screen.parent(): Screen? = when (this) {
    Screen.Connections -> null
    Screen.Add, is Screen.Detail, Screen.Settings -> Screen.Connections
    Screen.RadialMenu, is Screen.Map -> Screen.Settings
    is Screen.RadialShortcut -> Screen.RadialMenu
    is Screen.GestureShortcut -> Screen.Map(mode)
}

/** The part of the app a connection state shows; states that share one keep its views. */
enum class Scene { HOME, CONNECTING, PAIRING, STREAM }

fun sceneKey(state: ClientState?) = when (state) {
    null, is ClientState.Failed, ClientState.Closed -> Scene.HOME
    ClientState.Connecting -> Scene.CONNECTING
    is ClientState.Pairing -> Scene.PAIRING
    is ClientState.Streaming, is ClientState.Reconnecting -> Scene.STREAM
}
