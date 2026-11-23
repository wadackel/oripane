package dev.wadackel.oripane

import android.icu.text.BreakIterator
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

// The settings screen's values: their types, defaults and stored forms.

enum class TouchMode(val key: String) {
    DIRECT("direct"),
    TRACKPAD("trackpad"),
}

/**
 * A speed's step on its slider, [MIN] to [MAX] with [DEFAULT] in the middle. Each step multiplies
 * the speed by the same factor, so a step feels the same size anywhere on the scale; the three
 * choices this replaced, Slow, Normal and Fast, fall on steps 3, 5 and 7 with their old speeds.
 */
@JvmInline
value class SpeedStep(val step: Int) {
    init {
        require(step in MIN..MAX) { "speed step $step" }
    }

    /** Finger and wheel scroll against the default: ×√2 a step, a quarter to four times. */
    val scrollFactor get() = 2.0.pow((step - DEFAULT) / 2.0)

    /** The trackpad cursor against the default: ×√1.5 a step, so steps 3 and 7 keep Slow's 0.8 and Fast's 1.8. */
    val cursorFactor get() = 1.5.pow((step - DEFAULT) / 2.0)

    val scroll get() = scrollFactor.toFloat()

    /** The cursor's gain before its acceleration. */
    val cursor get() = (CURSOR_DEFAULT * cursorFactor).toFloat()

    operator fun plus(n: Int) = SpeedStep((step + n).coerceIn(MIN, MAX))

    operator fun minus(n: Int) = plus(-n)

    companion object {
        const val MIN = 1
        const val MAX = 9
        const val DEFAULT = 5
        private const val CURSOR_DEFAULT = 1.2
        val default = SpeedStep(DEFAULT)

        /** The stored step, or the choice it was stored as before the slider; null for anything else. */
        fun of(stored: String?): SpeedStep? = stored?.toIntOrNull()?.takeIf { it in MIN..MAX }?.let(::SpeedStep) ?: before[stored]

        private val before = mapOf("slow" to SpeedStep(3), "normal" to SpeedStep(5), "fast" to SpeedStep(7))
    }
}

/**
 * What a gesture does. The fixed actions are a gesture's own (a click, a drag, a scroll); [Run]
 * does anything the radial menu can hold, a built-in item or a shortcut of the user's own. [key]
 * is the stored value; a custom shortcut has none of its own and is stored as an object.
 */
sealed interface GestureAction {
    val key: String

    data object Click : GestureAction { override val key = "click" }

    data object Drag : GestureAction { override val key = "dragStart" }

    /** Three-finger drag in the direct mode: the fingers move the picture and send nothing. */
    data object Pan : GestureAction { override val key = "pan" }

    data object Scroll : GestureAction { override val key = "scroll" }

    /** Double tap and hold only: the finger's travel up and down zooms the video. */
    data object Zoom : GestureAction { override val key = "zoom" }

    data object RadialMenu : GestureAction { override val key = "radialMenu" }

    data object None : GestureAction { override val key = "none" }

    data class Run(val entry: RadialEntry) : GestureAction {
        override val key get() = (entry as? RadialEntry.Builtin)?.item?.key ?: CUSTOM
    }

    companion object {
        const val CUSTOM = "custom"
        val RIGHT_CLICK = Run(RadialEntry.Builtin(RadialItem.RIGHT_CLICK))
        val FILL_SCREEN = Run(RadialEntry.Builtin(RadialItem.FILL_SCREEN))
    }
}

/**
 * A gesture of the mapping table: its [choices] of the gesture's own, and with [catalog] also
 * everything the radial menu can hold. The order is the order of the settings page.
 */
enum class Gesture(val key: String, val label: String, val choices: List<GestureAction>, val catalog: Boolean = false) {
    TAP("tap", "Tap", listOf(GestureAction.Click, GestureAction.RIGHT_CLICK, GestureAction.None)),
    LONG_PRESS("longPress", "Long press", listOf(GestureAction.Drag, GestureAction.RadialMenu, GestureAction.None), catalog = true),
    DOUBLE_TAP_HOLD("doubleTapHold", "Double tap and hold", listOf(GestureAction.Drag, GestureAction.Zoom, GestureAction.Scroll, GestureAction.None)),
    /** A drag in the trackpad mode, the picture moving in the direct mode: [choicesIn] tells them apart. */
    THREE_FINGER_DRAG("threeFingerDrag", "Three-finger drag", listOf(GestureAction.Drag, GestureAction.Pan, GestureAction.None)),
    TWO_FINGER_TAP("twoFingerTap", "Two-finger tap", ONE_SHOT_CHOICES, catalog = true),
    TWO_FINGER_SWIPE("twoFingerSwipe", "Two-finger swipe", listOf(GestureAction.Scroll, GestureAction.None)),
    TWO_FINGER_LONG_PRESS("twoFingerLongPress", "Two-finger long press", ONE_SHOT_CHOICES, catalog = true),
    THREE_FINGER_TAP("threeFingerTap", "Three-finger tap", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_SWIPE_UP("fourFingerSwipeUp", "Four-finger swipe up", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_SWIPE_DOWN("fourFingerSwipeDown", "Four-finger swipe down", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_SWIPE_LEFT("fourFingerSwipeLeft", "Four-finger swipe left", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_SWIPE_RIGHT("fourFingerSwipeRight", "Four-finger swipe right", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_PINCH("fourFingerPinch", "Four-finger pinch", ONE_SHOT_CHOICES, catalog = true),
    FOUR_FINGER_SPREAD("fourFingerSpread", "Four-finger spread", ONE_SHOT_CHOICES, catalog = true),
    ;

    /** The fixed choices in [mode]: three fingers grab only on the trackpad and move the picture only in the direct mode. */
    fun choicesIn(mode: TouchMode) = when {
        this != THREE_FINGER_DRAG -> choices
        mode == TouchMode.DIRECT -> choices - GestureAction.Drag
        else -> choices - GestureAction.Pan
    }

    fun takes(a: GestureAction, mode: TouchMode) = a in choicesIn(mode) || (catalog && a is GestureAction.Run)
}

/** The fixed choices of a gesture that fires once: the rest is the radial menu's vocabulary. */
private val ONE_SHOT_CHOICES = listOf(GestureAction.Click, GestureAction.RadialMenu, GestureAction.None)

/** One touch mode's mapping from gestures to actions. */
data class TouchMap(val actions: Map<Gesture, GestureAction>) {
    operator fun get(g: Gesture): GestureAction = actions.getValue(g)

    fun with(g: Gesture, a: GestureAction) = TouchMap(actions + (g to a))

    fun toJson(): String = JSONObject().apply {
        Gesture.entries.forEach { g ->
            val e = (get(g) as? GestureAction.Run)?.entry as? RadialEntry.Custom
            put(g.key, if (e != null) RadialMenuJson.encodeEntry(e) else get(g).key)
        }
    }.toString()

    /** The `settings` log's form: as stored, with `custom` for a shortcut of the user's own, whose name is free text. */
    fun describe(): String = JSONObject().apply { Gesture.entries.forEach { put(it.key, get(it).key) } }.toString()

    companion object {
        fun defaults(mode: TouchMode) = TouchMap(
            mapOf(
                Gesture.TAP to GestureAction.Click,
                Gesture.LONG_PRESS to if (mode == TouchMode.DIRECT) GestureAction.Drag else GestureAction.RadialMenu,
                Gesture.DOUBLE_TAP_HOLD to GestureAction.None,
                Gesture.THREE_FINGER_DRAG to if (mode == TouchMode.DIRECT) GestureAction.Pan else GestureAction.Drag,
                Gesture.TWO_FINGER_TAP to GestureAction.RIGHT_CLICK,
                Gesture.TWO_FINGER_SWIPE to GestureAction.Scroll,
                Gesture.TWO_FINGER_LONG_PRESS to GestureAction.None,
                Gesture.THREE_FINGER_TAP to GestureAction.None,
                // As on a Mac trackpad: swiping left brings the Space on the right, and the thumb's
                // pinch opens the apps as the spread shows the desktop.
                Gesture.FOUR_FINGER_SWIPE_UP to item(RadialItem.MISSION_CONTROL),
                Gesture.FOUR_FINGER_SWIPE_DOWN to item(RadialItem.APP_EXPOSE),
                Gesture.FOUR_FINGER_SWIPE_LEFT to item(RadialItem.SPACE_RIGHT),
                Gesture.FOUR_FINGER_SWIPE_RIGHT to item(RadialItem.SPACE_LEFT),
                Gesture.FOUR_FINGER_PINCH to item(RadialItem.APPS),
                Gesture.FOUR_FINGER_SPREAD to item(RadialItem.SHOW_DESKTOP),
            ),
        )

        private fun item(i: RadialItem) = GestureAction.Run(RadialEntry.Builtin(i))

        /** Row by row: a missing row or an action the gesture cannot take keeps that row's default. */
        fun fromJson(json: String?, mode: TouchMode): TouchMap {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            val d = defaults(mode)
            return TouchMap(
                Gesture.entries.associateWith { g ->
                    val stored = o?.opt(g.key)
                    val a = if (stored is JSONObject) RadialMenuJson.customEntry(stored)?.let(GestureAction::Run) else action(g, stored as? String, mode)
                    a?.takeIf { g.takes(it, mode) } ?: d[g]
                },
            )
        }

        /** A stored string as an action [g] can take in [mode]: one of its own, or a built-in item for a gesture with the catalog. */
        fun action(g: Gesture, key: String?, mode: TouchMode): GestureAction? =
            g.choicesIn(mode).firstOrNull { it.key == key } ?: RadialItem.of(key)?.takeIf { g.catalog }?.let(::item)
    }
}

/** Which side of the screen the trackpad mode's side scroll band runs down, if any; off also hides the bottom band. */
enum class ScrollStrip(val key: String) {
    OFF("off"),
    LEFT("left"),
    RIGHT("right"),
}

/** How deep the scroll bands' dot fields reach in from the edge, and how large their dots are. */
enum class BandSize(val key: String) {
    SMALL("small"),
    MEDIUM("medium"),
}

data class ScrollSetting(val speed: SpeedStep = SpeedStep.default, val reverse: Boolean = false) {
    fun apply(v: Float) = v * speed.scroll * if (reverse) -1 else 1
}

data class Settings(
    val scale: Double = 1.0,
    val fps: Int = 60,
    val dataSaver: DataSaver = DataSaver.AUTO,
    val uiScale: Double = 2.0,
    val touchMode: TouchMode = TouchMode.DIRECT,
    val mapDirect: TouchMap = TouchMap.defaults(TouchMode.DIRECT),
    val mapTrackpad: TouchMap = TouchMap.defaults(TouchMode.TRACKPAD),
    /** The radial menu, one entry or null per cell, clockwise from the top; both touch modes share it. */
    val radialMenu: List<RadialEntry?> = RadialEntry.defaults,
    val cursorSpeed: SpeedStep = SpeedStep.default,
    val touchScroll: ScrollSetting = ScrollSetting(),
    val wheelScroll: ScrollSetting = ScrollSetting(),
    /** Trackpad mode only: a band down one side and one along the bottom that scroll under one finger. */
    val scrollStrip: ScrollStrip = ScrollStrip.RIGHT,
    /** Where the side band's centre sits, as a share of the side's height from its top. */
    val stripKnobAt: Float = STRIP_KNOB_DEFAULT,
    val scrollBandSize: BandSize = BandSize.MEDIUM,
    /** A tick under the finger as it lands on a band and as it travels along it. */
    val stripHaptics: Boolean = true,
    /** Scrolls from the bands and from two fingers glide on after the lift, slowing down. */
    val scrollInertia: Boolean = true,
    /** Asks the soft keyboard not to learn from what is typed (`IME_FLAG_NO_PERSONALIZED_LEARNING`). */
    val incognitoKeyboard: Boolean = false,
    val showStats: Boolean = true,
) {
    val map get() = mapFor(touchMode)

    fun mapFor(mode: TouchMode) = if (mode == TouchMode.DIRECT) mapDirect else mapTrackpad

    fun withMap(mode: TouchMode, m: TouchMap) = if (mode == TouchMode.DIRECT) copy(mapDirect = m) else copy(mapTrackpad = m)

    /**
     * The one-line `settings` log: every key with its stored value, but the radial menu and the
     * gesture maps with `custom` for a shortcut of the user's own: its name is free text with spaces.
     */
    fun describe(): String = SettingKeys.write(this).entries.joinToString(" ") { (k, v) ->
        val shown = when (k) {
            SettingKeys.RADIAL_MENU -> RadialMenuJson.describe(radialMenu)
            SettingKeys.MAP_DIRECT -> mapDirect.describe()
            SettingKeys.MAP_TRACKPAD -> mapTrackpad.describe()
            else -> v
        }
        "$k=$shown"
    }

    companion object {
        val defaults = Settings()
        val scales = listOf(1.0, 0.75, 0.5)
        val fpsChoices = listOf(60, 30)
        val uiScales = listOf(2.0, 1.5)
        const val STRIP_KNOB_DEFAULT = 0.64f
        val stripKnobRange = 0.05f..0.95f
    }
}

/**
 * The `Prefs` keys and their stored strings. A value outside its table, unreadable or of another
 * type reads as the default, so a bad write never wedges the app.
 */
object SettingKeys {
    const val SCALE = "video.scale"
    const val FPS = "video.fps"
    const val DATA_SAVER = "dataSaver"
    const val UI_SCALE = "video.uiScale"
    const val TOUCH_MODE = "touch.mode"
    const val MAP_DIRECT = "touch.map.direct"
    const val MAP_TRACKPAD = "touch.map.trackpad"
    const val RADIAL_MENU = "radial.menu"
    const val CURSOR_SPEED = "touch.cursorSpeed"
    const val TOUCH_SCROLL_SPEED = "scroll.touch.speed"
    const val TOUCH_SCROLL_REVERSE = "scroll.touch.reverse"
    const val WHEEL_SCROLL_SPEED = "scroll.wheel.speed"
    const val WHEEL_SCROLL_REVERSE = "scroll.wheel.reverse"
    const val SCROLL_STRIP = "scroll.strip"
    const val STRIP_KNOB = "scroll.strip.knob"
    const val STRIP_HAPTICS = "scroll.strip.haptics"
    const val SCROLL_BAND_SIZE = "scroll.band.size"
    const val SCROLL_INERTIA = "scroll.inertia"
    const val INCOGNITO_KEYBOARD = "keyboard.incognito"
    const val SHOW_STATS = "showStats"

    /** Every key but `showStats` (a boolean in `Prefs`) as the string it is stored as. */
    fun write(s: Settings): Map<String, String> = linkedMapOf(
        SCALE to s.scale.toString(),
        FPS to s.fps.toString(),
        DATA_SAVER to s.dataSaver.key,
        UI_SCALE to s.uiScale.toString(),
        TOUCH_MODE to s.touchMode.key,
        MAP_DIRECT to s.mapDirect.toJson(),
        MAP_TRACKPAD to s.mapTrackpad.toJson(),
        RADIAL_MENU to RadialMenuJson.encode(s.radialMenu),
        CURSOR_SPEED to s.cursorSpeed.step.toString(),
        TOUCH_SCROLL_SPEED to s.touchScroll.speed.step.toString(),
        TOUCH_SCROLL_REVERSE to s.touchScroll.reverse.toString(),
        WHEEL_SCROLL_SPEED to s.wheelScroll.speed.step.toString(),
        WHEEL_SCROLL_REVERSE to s.wheelScroll.reverse.toString(),
        SCROLL_STRIP to s.scrollStrip.key,
        STRIP_KNOB to s.stripKnobAt.toString(),
        STRIP_HAPTICS to s.stripHaptics.toString(),
        SCROLL_BAND_SIZE to s.scrollBandSize.key,
        SCROLL_INERTIA to s.scrollInertia.toString(),
        INCOGNITO_KEYBOARD to s.incognitoKeyboard.toString(),
        SHOW_STATS to s.showStats.toString(),
    )

    /** [get] returns the stored string or null; [showStats] is read separately as it stays a boolean. */
    fun read(get: (String) -> String?, showStats: Boolean): Settings {
        val d = Settings.defaults
        val mode = TouchMode.entries.firstOrNull { it.key == get(TOUCH_MODE) } ?: d.touchMode
        fun speed(key: String) = SpeedStep.of(get(key)) ?: SpeedStep.default
        fun bool(key: String) = get(key) == "true"
        // For a switch that starts on: only a stored "false" turns it off.
        fun boolOn(key: String) = get(key) != "false"
        return Settings(
            scale = Settings.scales.firstOrNull { it.toString() == get(SCALE) } ?: d.scale,
            fps = Settings.fpsChoices.firstOrNull { it.toString() == get(FPS) } ?: d.fps,
            dataSaver = DataSaver.of(get(DATA_SAVER)),
            uiScale = Settings.uiScales.firstOrNull { it.toString() == get(UI_SCALE) } ?: d.uiScale,
            touchMode = mode,
            mapDirect = TouchMap.fromJson(get(MAP_DIRECT), TouchMode.DIRECT),
            mapTrackpad = TouchMap.fromJson(get(MAP_TRACKPAD), TouchMode.TRACKPAD),
            radialMenu = RadialMenuJson.decode(get(RADIAL_MENU)),
            cursorSpeed = speed(CURSOR_SPEED),
            touchScroll = ScrollSetting(speed(TOUCH_SCROLL_SPEED), bool(TOUCH_SCROLL_REVERSE)),
            wheelScroll = ScrollSetting(speed(WHEEL_SCROLL_SPEED), bool(WHEEL_SCROLL_REVERSE)),
            scrollStrip = ScrollStrip.entries.firstOrNull { it.key == get(SCROLL_STRIP) } ?: d.scrollStrip,
            stripKnobAt = get(STRIP_KNOB)?.toFloatOrNull()?.takeIf { it in Settings.stripKnobRange } ?: d.stripKnobAt,
            stripHaptics = boolOn(STRIP_HAPTICS),
            scrollBandSize = BandSize.entries.firstOrNull { it.key == get(SCROLL_BAND_SIZE) } ?: d.scrollBandSize,
            scrollInertia = boolOn(SCROLL_INERTIA),
            incognitoKeyboard = bool(INCOGNITO_KEYBOARD),
            showStats = showStats,
        )
    }
}

/**
 * The radial menu's stored form, a JSON array of eight, one per cell clockwise from the top:
 * `{"item":"copy"}` for a built-in item,
 * `{"code":11,"meta":65537,"name":"Area shot","look":"text"}` (or `"look":"icon:star"`) for a
 * shortcut of the user's own, and `null` for an empty cell. A shorter array is the form from before
 * cells could be chosen, and spreads as it opened then ([RadialEntry.spread]). As with the gesture
 * maps, a bad value never wedges the app: an entry it cannot read leaves its cell empty, a built-in
 * item stored twice keeps its first cell, and a menu left outside two to eight entries reads as the
 * default.
 */
object RadialMenuJson {
    const val NAME_MAX = 24

    fun encode(entries: List<RadialEntry?>): String = JSONArray(
        entries.map { e ->
            when (e) {
                null -> JSONObject.NULL
                is RadialEntry.Builtin -> JSONObject().put("item", e.item.key)
                is RadialEntry.Custom -> encodeEntry(e)
            }
        },
    ).toString()

    fun decode(json: String?): List<RadialEntry?> {
        val a = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return RadialEntry.defaults
        val read = (0 until a.length()).map { i -> (a.opt(i) as? JSONObject)?.let(::entry) }
        val inRange = RadialEntry.MIN..RadialEntry.MAX
        if (read.size == radialCells.size) return firstOnly(read).takeIf { m -> m.count { it != null } in inRange } ?: RadialEntry.defaults
        val items = firstOnly(read.filterNotNull()).filterNotNull()
        return if (items.size in inRange) RadialEntry.spread(items) else RadialEntry.defaults
    }

    /** [entries] with a built-in item that came before emptied. */
    private fun firstOnly(entries: List<RadialEntry?>) = entries.mapIndexed { i, e -> e.takeUnless { it is RadialEntry.Builtin && it in entries.subList(0, i) } }

    /** A shortcut of the user's own as stored, in the menu and on a gesture alike. */
    fun encodeEntry(e: RadialEntry.Custom): JSONObject = JSONObject().put("code", e.code).put("meta", e.meta).apply { e.name?.let { put("name", it) } }
        .put("look", when (val look = e.look) {
            RadialLook.Text -> "text"
            is RadialLook.Icon -> "icon:${look.icon.id}"
        })

    private fun entry(o: JSONObject): RadialEntry? {
        val item = o.has("item")
        if (item == o.has("code")) return null
        if (item) return RadialItem.of(o.optString("item"))?.let(RadialEntry::Builtin)
        return customEntry(o)
    }

    /** A stored shortcut of the user's own, or null for a key the host cannot send or a modifier outside ⌃⌥⇧⌘. */
    fun customEntry(o: JSONObject): RadialEntry.Custom? {
        val code = o.optInt("code", -1)
        val meta = o.optInt("meta", 0)
        if (radialKey(code) == null || meta and RADIAL_META.inv() != 0) return null
        val look = o.optString("look").takeIf { it.startsWith("icon:") }?.let { RadialIcon.of(it.removePrefix("icon:")) }?.let(RadialLook::Icon) ?: RadialLook.Text
        return RadialEntry.Custom(code, meta, name(o.optString("name")), look)
    }

    /** A name as entered: trimmed, null when empty, at most [NAME_MAX] characters. */
    fun name(raw: String?) = raw?.trim()?.let(::clip)?.trimEnd()?.ifEmpty { null }

    /** At most [NAME_MAX] characters, never part of one. */
    fun clip(s: String) = characterEnds(s).getOrNull(NAME_MAX - 1)?.let { s.substring(0, it) } ?: s

    /** How many characters [s] holds as a person counts them, as [NAME_MAX] limits them. */
    fun length(s: String) = characterEnds(s).size

    /**
     * Where each character of [s] ends. A character is a grapheme cluster rather than a `Char` or a
     * code point: an emoji with a skin tone, or a family joined by zero-width joiners, is one.
     * android.icu's iterator rather than java.text's, which on the tests' JDK splits those.
     */
    private fun characterEnds(s: String): List<Int> {
        val it = BreakIterator.getCharacterInstance().apply { setText(s) }
        return generateSequence { it.next().takeIf { end -> end != BreakIterator.DONE } }.toList()
    }

    /** The `settings` log's form, one part per cell and an empty part for an empty cell: `copy+++paste++++custom`. */
    fun describe(entries: List<RadialEntry?>) = entries.joinToString("+") { e ->
        when (e) {
            null -> ""
            is RadialEntry.Builtin -> e.item.key
            is RadialEntry.Custom -> "custom"
        }
    }
}

/** What the phone asks the host for, from the window and the settings. */
fun displayConfig(width: Int, height: Int, dpi: Int, hz: Int, mode: String, s: Settings) =
    DisplayConfig(width, height, dpi, hz, mode, s.scale, s.fps, s.uiScale)
