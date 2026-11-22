package dev.wadackel.oripane

/**
 * A Mac feature the host carries out by name (the `system` control message). [keepsMenu]: the
 * stream menu, or the radial menu it was picked from, stays open after it, for the actions
 * pressed again and again.
 */
enum class MacAction(val key: String, val label: String, val keepsMenu: Boolean = false) {
    MISSION_CONTROL("missionControl", "Mission Control"),
    APP_EXPOSE("appExpose", "App Exposé"),
    SPACE_LEFT("spaceLeft", "Space left"),
    SPACE_RIGHT("spaceRight", "Space right"),
    SHOW_DESKTOP("showDesktop", "Show desktop"),
    APPS("apps", "Apps"),
    LAUNCHER("launcher", "Launcher"),
    SCREENSHOT("screenshot", "Screenshot"),
    LOCK_SCREEN("lockScreen", "Lock screen"),
    PREVIOUS_APP("previousApp", "Previous app"),
    VOLUME_UP("volumeUp", "Volume up", keepsMenu = true),
    VOLUME_DOWN("volumeDown", "Volume down", keepsMenu = true),
    MUTE("mute", "Mute", keepsMenu = true),
    PLAY_PAUSE("playPause", "Play/Pause", keepsMenu = true),
    NEXT_TRACK("nextTrack", "Next track", keepsMenu = true),
    PREVIOUS_TRACK("previousTrack", "Previous track", keepsMenu = true),
    BRIGHTNESS_UP("brightnessUp", "Brightness up", keepsMenu = true),
    BRIGHTNESS_DOWN("brightnessDown", "Brightness down", keepsMenu = true),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** A row of the Mac page: one action under its own name, or a few side by side after [label]. */
data class MacRow(val label: String?, val actions: List<MacAction>) {
    init {
        require(actions.size == 1 || label != null) { "a row of several actions needs a label" }
    }
}

data class MacSection(val title: String, val rows: List<MacRow>)

private fun one(a: MacAction) = MacRow(null, listOf(a))

/** The Mac page: every [MacAction] once, grouped, with the pairs pressed together on one row. */
val macMenuSections = listOf(
    MacSection(
        "Screen",
        listOf(
            one(MacAction.MISSION_CONTROL),
            one(MacAction.APP_EXPOSE),
            MacRow("Space", listOf(MacAction.SPACE_LEFT, MacAction.SPACE_RIGHT)),
            one(MacAction.SHOW_DESKTOP),
            one(MacAction.APPS),
        ),
    ),
    MacSection("Apps", listOf(one(MacAction.LAUNCHER), one(MacAction.PREVIOUS_APP), one(MacAction.SCREENSHOT))),
    MacSection(
        "Media",
        listOf(
            MacRow("Volume", listOf(MacAction.VOLUME_DOWN, MacAction.VOLUME_UP)),
            MacRow("Brightness", listOf(MacAction.BRIGHTNESS_DOWN, MacAction.BRIGHTNESS_UP)),
            one(MacAction.MUTE),
            MacRow("Music", listOf(MacAction.PREVIOUS_TRACK, MacAction.PLAY_PAUSE, MacAction.NEXT_TRACK)),
        ),
    ),
    MacSection("Other", listOf(one(MacAction.LOCK_SCREEN))),
)
