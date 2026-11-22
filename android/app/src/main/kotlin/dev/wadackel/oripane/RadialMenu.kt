package dev.wadackel.oripane

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What a radial menu item does: most send something to the Mac; the keyboard and Fill screen stay on the phone. */
sealed interface RadialSend {
    data class Mac(val action: MacAction) : RadialSend

    /** A press and a release of [code] with the modifiers of [meta] held. */
    data class Key(val code: Int, val meta: Int) : RadialSend

    /** Opens the on-screen keyboard. */
    data object Keyboard : RadialSend

    /** A right click where the long press began. */
    data object RightClick : RadialSend

    /** Fill screen, about where the long press began; nothing goes to the Mac. */
    data object FillScreen : RadialSend
}

/**
 * The built-in items a radial menu can hold, each at most once; which cell each sits in is the
 * settings' to say. [key] is the stored value, so it never changes.
 */
enum class RadialItem(val key: String, val label: String, val shortcut: String?, val send: RadialSend) {
    MISSION_CONTROL("missionControl", "Mission Control", "⌃↑", RadialSend.Mac(MacAction.MISSION_CONTROL)),
    COPY("copy", "Copy", "⌘C", RadialSend.Key(KeyEvent.KEYCODE_C, ModKey.CMD.meta)),
    SPACE_RIGHT("spaceRight", "Space right", "⌃→", RadialSend.Mac(MacAction.SPACE_RIGHT)),
    PASTE("paste", "Paste", "⌘V", RadialSend.Key(KeyEvent.KEYCODE_V, ModKey.CMD.meta)),
    SHOW_DESKTOP("showDesktop", "Show desktop", "F11", RadialSend.Mac(MacAction.SHOW_DESKTOP)),
    KEYBOARD("keyboard", "Keyboard", null, RadialSend.Keyboard),
    SPACE_LEFT("spaceLeft", "Space left", "⌃←", RadialSend.Mac(MacAction.SPACE_LEFT)),
    RIGHT_CLICK("rightClick", "Right click", null, RadialSend.RightClick),
    FILL_SCREEN("fillScreen", "Fill screen", null, RadialSend.FillScreen),
    UNDO("undo", "Undo", KeyEvent.KEYCODE_Z, ModKey.CMD.meta),
    REDO("redo", "Redo", KeyEvent.KEYCODE_Z, ModKey.SHIFT.meta or ModKey.CMD.meta),
    CUT("cut", "Cut", KeyEvent.KEYCODE_X, ModKey.CMD.meta),
    SELECT_ALL("selectAll", "Select all", KeyEvent.KEYCODE_A, ModKey.CMD.meta),
    SAVE("save", "Save", KeyEvent.KEYCODE_S, ModKey.CMD.meta),
    FIND("find", "Find", KeyEvent.KEYCODE_F, ModKey.CMD.meta),
    // A Mac action's shortcut is the key the host sends for it; null where the host sends none.
    APP_EXPOSE(MacAction.APP_EXPOSE, "⌃↓"),
    APPS(MacAction.APPS, null),
    LAUNCHER(MacAction.LAUNCHER, "⌘␣"),
    SCREENSHOT(MacAction.SCREENSHOT, "⇧⌘5"),
    LOCK_SCREEN(MacAction.LOCK_SCREEN, "⌃⌘Q"),
    PREVIOUS_APP(MacAction.PREVIOUS_APP, "⌘⇥"),
    VOLUME_UP(MacAction.VOLUME_UP, null),
    VOLUME_DOWN(MacAction.VOLUME_DOWN, null),
    MUTE(MacAction.MUTE, null),
    PLAY_PAUSE(MacAction.PLAY_PAUSE, null),
    NEXT_TRACK(MacAction.NEXT_TRACK, null),
    PREVIOUS_TRACK(MacAction.PREVIOUS_TRACK, null),
    BRIGHTNESS_UP(MacAction.BRIGHTNESS_UP, null),
    BRIGHTNESS_DOWN(MacAction.BRIGHTNESS_DOWN, null),
    ;

    constructor(key: String, label: String, code: Int, meta: Int) : this(key, label, keyCombo(code, meta), RadialSend.Key(code, meta))

    constructor(action: MacAction, shortcut: String?) : this(action.key, action.label, shortcut, RadialSend.Mac(action))

    @get:DrawableRes
    val icon get() = RadialIcon.of(key)!!.res

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * The icons of the radial menu: one per built-in item, under the item's key, and a few plain ones a
 * custom shortcut may take. Only [id] is stored; a resource id changes from build to build.
 */
enum class RadialIcon(val id: String, @DrawableRes val res: Int) {
    MISSION_CONTROL("missionControl", R.drawable.ic_mission_control),
    COPY("copy", R.drawable.ic_copy),
    SPACE_RIGHT("spaceRight", R.drawable.ic_arrow_right),
    PASTE("paste", R.drawable.ic_paste),
    SHOW_DESKTOP("showDesktop", R.drawable.ic_show_desktop),
    KEYBOARD("keyboard", R.drawable.ic_keyboard),
    SPACE_LEFT("spaceLeft", R.drawable.ic_arrow_left),
    RIGHT_CLICK("rightClick", R.drawable.ic_right_click),
    FILL_SCREEN("fillScreen", R.drawable.ic_fill_screen),
    UNDO("undo", R.drawable.ic_undo),
    REDO("redo", R.drawable.ic_redo),
    CUT("cut", R.drawable.ic_cut),
    SELECT_ALL("selectAll", R.drawable.ic_select_all),
    SAVE("save", R.drawable.ic_save),
    FIND("find", R.drawable.ic_find),
    APP_EXPOSE("appExpose", R.drawable.ic_app_expose),
    APPS("apps", R.drawable.ic_apps),
    LAUNCHER("launcher", R.drawable.ic_launcher_search),
    SCREENSHOT("screenshot", R.drawable.ic_screenshot),
    LOCK_SCREEN("lockScreen", R.drawable.ic_lock),
    PREVIOUS_APP("previousApp", R.drawable.ic_previous_app),
    VOLUME_UP("volumeUp", R.drawable.ic_volume_up),
    VOLUME_DOWN("volumeDown", R.drawable.ic_volume_down),
    MUTE("mute", R.drawable.ic_mute),
    PLAY_PAUSE("playPause", R.drawable.ic_play_pause),
    NEXT_TRACK("nextTrack", R.drawable.ic_next_track),
    PREVIOUS_TRACK("previousTrack", R.drawable.ic_previous_track),
    BRIGHTNESS_UP("brightnessUp", R.drawable.ic_brightness_up),
    BRIGHTNESS_DOWN("brightnessDown", R.drawable.ic_brightness_down),
    STAR("star", R.drawable.ic_star),
    BOOKMARK("bookmark", R.drawable.ic_bookmark),
    BOLT("bolt", R.drawable.ic_bolt),
    FLAG("flag", R.drawable.ic_flag),
    CIRCLE("circle", R.drawable.ic_circle),
    SQUARE("square", R.drawable.ic_square),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }

        /** The icons that stand for no built-in item. */
        val generic = listOf(STAR, BOOKMARK, BOLT, FLAG, CIRCLE, SQUARE)
    }
}

/** How a custom shortcut shows in its circle: its keys as text, or an icon. */
sealed interface RadialLook {
    data object Text : RadialLook

    data class Icon(val icon: RadialIcon) : RadialLook
}

/** What one cell of the radial menu holds: a built-in item, or a key combination of the user's own. */
sealed interface RadialEntry {
    /** The name on the band above the ring and in the note after sending. */
    val label: String

    /** The keys, shown beside the name; null for an item that sends none. */
    val combo: String?

    val send: RadialSend

    data class Builtin(val item: RadialItem) : RadialEntry {
        override val label get() = item.label
        override val combo get() = item.shortcut
        override val send get() = item.send
    }

    /** [name] is trimmed and null when empty; without one the keys name it. */
    data class Custom(val code: Int, val meta: Int, val name: String?, val look: RadialLook) : RadialEntry {
        override val combo get() = keyCombo(code, meta)
        override val label get() = name ?: combo
        override val send get() = RadialSend.Key(code, meta)

        /** What TalkBack reads for the circle: the name and the keys spelt out. */
        val spoken get() = listOfNotNull(name, spokenCombo(code, meta)).joinToString(", ")
    }

    companion object {
        /** The menu before any change: the eight items it always had, clockwise from the top. */
        val defaults: List<RadialEntry?> = listOf(
            RadialItem.MISSION_CONTROL, RadialItem.COPY, RadialItem.SPACE_RIGHT, RadialItem.PASTE,
            RadialItem.SHOW_DESKTOP, RadialItem.KEYBOARD, RadialItem.SPACE_LEFT, RadialItem.RIGHT_CLICK,
        ).map(::Builtin)

        /** How many cells hold an entry, at least and at most; the rest stay empty. */
        const val MIN = 2
        const val MAX = 8

        /**
         * [entries] over the eight cells as the menu laid them out before cells could be chosen:
         * evenly, so four sit up, right, down and left. A menu stored then, and the test hook's short
         * form, read this way and open as they did.
         */
        fun spread(entries: List<RadialEntry>): List<RadialEntry?> {
            val cells = arrayOfNulls<RadialEntry>(radialCells.size)
            entries.forEachIndexed { i, e -> cells[(i * 8.0 / entries.size).roundToInt() % 8] = e }
            return cells.toList()
        }
    }
}

/** A key a custom shortcut may use: its [label] in a combination, its [spoken] name, and its group in the list. */
data class RadialKey(val code: Int, val label: String, val spoken: String, val group: String)

private fun keys(group: String, vararg k: Triple<Int, String, String>) = k.map { (code, label, spoken) -> RadialKey(code, label, spoken, group) }

/**
 * Every key the host turns into a Mac key (`ALLOWED_KEYCODES`) but the keypad's Enter, which looks
 * like Return. Symbols are named as on a US keyboard: the host maps a key by its US position.
 */
val radialKeys: List<RadialKey> =
    ('A'..'Z').mapIndexed { i, c -> RadialKey(KeyEvent.KEYCODE_A + i, "$c", "$c", "Letters") } +
        ('0'..'9').mapIndexed { i, c -> RadialKey(KeyEvent.KEYCODE_0 + i, "$c", "$c", "Numbers") } +
        (1..12).map { RadialKey(KeyEvent.KEYCODE_F1 + it - 1, "F$it", "F$it", "Function keys") } +
        keys(
            "Arrows",
            Triple(KeyEvent.KEYCODE_DPAD_LEFT, "←", "Left arrow"),
            Triple(KeyEvent.KEYCODE_DPAD_RIGHT, "→", "Right arrow"),
            Triple(KeyEvent.KEYCODE_DPAD_UP, "↑", "Up arrow"),
            Triple(KeyEvent.KEYCODE_DPAD_DOWN, "↓", "Down arrow"),
        ) +
        keys(
            "Editing",
            Triple(KeyEvent.KEYCODE_ENTER, "↩", "Return"),
            Triple(KeyEvent.KEYCODE_TAB, "⇥", "Tab"),
            Triple(KeyEvent.KEYCODE_ESCAPE, "⎋", "Escape"),
            Triple(KeyEvent.KEYCODE_SPACE, "␣", "Space"),
            Triple(KeyEvent.KEYCODE_DEL, "⌫", "Delete"),
            Triple(KeyEvent.KEYCODE_FORWARD_DEL, "⌦", "Forward delete"),
        ) +
        keys(
            "Moving",
            Triple(KeyEvent.KEYCODE_MOVE_HOME, "↖", "Home"),
            Triple(KeyEvent.KEYCODE_MOVE_END, "↘", "End"),
            Triple(KeyEvent.KEYCODE_PAGE_UP, "⇞", "Page up"),
            Triple(KeyEvent.KEYCODE_PAGE_DOWN, "⇟", "Page down"),
        ) +
        keys(
            "Symbols",
            Triple(KeyEvent.KEYCODE_COMMA, ",", "Comma"),
            Triple(KeyEvent.KEYCODE_PERIOD, ".", "Period"),
            Triple(KeyEvent.KEYCODE_MINUS, "-", "Minus"),
            Triple(KeyEvent.KEYCODE_EQUALS, "=", "Equals"),
            Triple(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "Left bracket"),
            Triple(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "Right bracket"),
            Triple(KeyEvent.KEYCODE_BACKSLASH, "\\", "Backslash"),
            Triple(KeyEvent.KEYCODE_SEMICOLON, ";", "Semicolon"),
            Triple(KeyEvent.KEYCODE_APOSTROPHE, "'", "Apostrophe"),
            Triple(KeyEvent.KEYCODE_SLASH, "/", "Slash"),
            Triple(KeyEvent.KEYCODE_GRAVE, "`", "Grave accent"),
        )

/** The modifiers in the order a Mac writes them: ⌃⌥⇧⌘. */
val radialModifiers = listOf(ModKey.CTRL, ModKey.ALT, ModKey.SHIFT, ModKey.CMD)

/** Every meta bit a custom shortcut may hold. */
val RADIAL_META = radialModifiers.fold(0) { m, k -> m or k.meta }

fun radialKey(code: Int) = radialKeys.firstOrNull { it.code == code }

/** A key combination as a Mac writes it, e.g. ⇧⌘4. */
fun keyCombo(code: Int, meta: Int) =
    radialModifiers.filter { meta and it.meta != 0 }.joinToString("") { it.label } + (radialKey(code)?.label ?: "?")

/** A key combination read out, e.g. "Shift Command 4". */
fun spokenCombo(code: Int, meta: Int) =
    (radialModifiers.filter { meta and it.meta != 0 }.map { it.spoken } + (radialKey(code)?.spoken ?: "Unknown key")).joinToString(" ")

/** The menu's measures, in dp. */
object RadialSize {
    /** An entry's circle in the settings' lists. */
    const val ITEM = 38f

    /** The plate: three rows of three cells, the logo in the middle one. */
    const val CELL = 58f
    const val GAP = 6f
    const val PAD = 10f
    const val PITCH = CELL + GAP
    const val PLATE = 3 * CELL + 2 * GAP + 2 * PAD

    /** A move shorter than this from where the finger opened the menu picks nothing. */
    const val DEAD_ZONE = 24f

    /** How far the centre keeps from the screen's edges, so that the whole plate shows. */
    const val MARGIN = PLATE / 2 + 12f
}

/**
 * The plate's cells round the middle, clockwise from the top: (column, row), each -1..1. A menu is a
 * list of eight, one per cell in this order, null where the cell is empty.
 */
val radialCells = listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)

/** Each cell's direction from the middle, as the editor names it. */
val radialCellNames = listOf("Top", "Top right", "Right", "Bottom right", "Bottom", "Bottom left", "Left", "Top left")

/**
 * The cell, an index into [radialCells], at ([dx], [dy]) from the plate's middle on a plate of
 * [pitch] between cells and [half] its side; null for the middle and off the plate.
 */
fun radialCellAt(dx: Float, dy: Float, pitch: Float, half: Float): Int? {
    if (abs(dx) > half || abs(dy) > half) return null
    val at = (dx / pitch).roundToInt().coerceIn(-1, 1) to (dy / pitch).roundToInt().coerceIn(-1, 1)
    return radialCells.indexOf(at).takeIf { it >= 0 }
}

/** [menu] with the entries of cells [a] and [b] traded: a move, when one of them is empty. */
fun radialSwap(menu: List<RadialEntry?>, a: Int, b: Int): List<RadialEntry?> =
    menu.toMutableList().also { it[a] = menu[b]; it[b] = menu[a] }

/** The caps a combination is pressed on: one for each modifier, then the key, e.g. ⇧⌘F12 is ⇧, ⌘ and F12. */
fun keycapsOf(combo: String): List<String> {
    val modifiers = combo.takeWhile { c -> radialModifiers.any { it.label == c.toString() } }
    return modifiers.map(Char::toString) + listOfNotNull(combo.drop(modifiers.length).ifEmpty { null })
}

/**
 * The direction for a move of ([dx], [dy]) from the menu's middle, split into [count] parts: 0 is up
 * and the rest follow clockwise, each over the 360/[count] degrees around its direction. Null within
 * [deadZone].
 */
fun radialSector(dx: Float, dy: Float, deadZone: Float, count: Int): Int? {
    if (hypot(dx, dy) < deadZone) return null
    val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
    return Math.floorMod((degrees / (360.0 / count)).roundToInt(), count)
}

/**
 * An open radial menu, in the screen's pixels, with the [entries] it opened with, one per cell.
 * Picks while sliding are measured from where the finger opened it ([touchX], [touchY]), by
 * direction, one of the plate's eight cells; the plate is drawn at ([centerX], [centerY]), moved in
 * from the edges, and a tap on the menu left open ([sticky]) hits the cell under it. [selected] is a
 * cell, not an entry: a custom entry may sit twice.
 */
data class RadialMenuState(
    val entries: List<RadialEntry?>,
    val touchX: Float,
    val touchY: Float,
    val centerX: Float,
    val centerY: Float,
    val fingerX: Float,
    val fingerY: Float,
    /** The point on the video a right click takes, and the one Fill screen keeps in place. */
    val videoX: Float,
    val videoY: Float,
    val selected: Int? = null,
    val sticky: Boolean = false,
) {
    /** The finger at ([x], [y]): the cell in its direction, none toward an empty cell. */
    fun moved(x: Float, y: Float, deadZone: Float) =
        copy(fingerX = x, fingerY = y, selected = radialSector(x - touchX, y - touchY, deadZone, 8)?.let(::placeIn))

    /** [cell], an index into [radialCells], when it holds an entry; null when it is empty. */
    fun placeIn(cell: Int): Int? = cell.takeIf { entries.getOrNull(it) != null }

    /** The cell a tap at ([x], [y]) lands on, as [radialCellAt]; null for the middle and off the plate, which close the menu. */
    fun cellAt(x: Float, y: Float, pitch: Float, half: Float): Int? = radialCellAt(x - centerX, y - centerY, pitch, half)

    companion object {
        /** Opened by a finger at ([x], [y]) on a screen of [width] by [height], its centre [margin] inside the edges. */
        fun opened(entries: List<RadialEntry?>, x: Float, y: Float, videoX: Float, videoY: Float, width: Float, height: Float, margin: Float): RadialMenuState {
            fun inside(v: Float, size: Float) = if (size < 2 * margin) size / 2 else v.coerceIn(margin, size - margin)
            return RadialMenuState(entries, x, y, inside(x, width), inside(y, height), x, y, videoX, videoY)
        }
    }
}
