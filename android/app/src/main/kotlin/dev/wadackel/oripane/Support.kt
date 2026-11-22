package dev.wadackel.oripane

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Structured `event key=value` lines, so `adb logcat -s oripane` can be grepped. */
fun log(message: String) {
    Log.i("oripane", message)
}

fun nowMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1000

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.unb64(): ByteArray = Base64.decode(this, Base64.DEFAULT)

/** The material to authenticate with one Mac: the id it gave this phone and the shared key. */
class Pairing(val clientId: ByteArray, val pairKey: ByteArray)

/** Settings and saved Macs. The pairing keys never leave this app's private storage. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("oripane", Context.MODE_PRIVATE)

    var lastHost: String
        get() = sp.getString("lastHost", "") ?: ""
        set(v) = sp.edit().putString("lastHost", v).apply()

    var mode: String
        get() = sp.getString("mode", "extend") ?: "extend"
        set(v) = sp.edit().putString("mode", v).apply()

    var showStats: Boolean
        get() = runCatching { sp.getBoolean("showStats", true) }.getOrDefault(true)
        set(v) = sp.edit().putBoolean("showStats", v).apply()

    /** Where the streaming screen's menu button sits; not a setting, so a reset leaves it. */
    var menuCorner: MenuCorner
        get() = MenuCorner.of(runCatching { sp.getString(MenuCorner.KEY, null) }.getOrNull())
        set(v) = sp.edit().putString(MenuCorner.KEY, v.key).apply()

    var dataSaver: DataSaver
        get() = DataSaver.of(sp.getString("dataSaver", null))
        set(v) = sp.edit().putString("dataSaver", v.key).apply()

    /** Everything on the settings screen; each key is stored as a string (`SettingKeys`). */
    var settings: Settings
        get() = SettingKeys.read({ k -> runCatching { sp.getString(k, null) }.getOrNull() }, showStats)
        set(v) {
            val e = sp.edit()
            SettingKeys.write(v).forEach { (k, s) -> if (k == SettingKeys.SHOW_STATS) e.putBoolean(k, s == "true") else e.putString(k, s) }
            e.apply()
        }

    /** Writes one stored string, as the `set` test hook does. */
    fun putSetting(key: String, value: String) {
        if (key == SettingKeys.SHOW_STATS) sp.edit().putBoolean(key, value == "true").apply() else sp.edit().putString(key, value).apply()
    }

    /**
     * Brings stored settings up to the current version once. Version 1 forgets the stored trackpad
     * map: every save writes all its rows, so the old drag gestures would otherwise outlive the new
     * defaults. The direct map and the shared rows are kept. Version 2 turns a stored trackpad long
     * press that grabs into the radial menu, its new default, for the same reason; a grab chosen by
     * hand looks the same in storage, so it changes too, and the other rows stay. Version 3 moves
     * the four-finger swipes and the three-finger tap, which both modes shared, into each mode's map.
     */
    fun migrate() {
        val version = runCatching { sp.getInt(SETTINGS_VERSION, 0) }.getOrDefault(0)
        if (version >= 3) return
        fun stored(key: String) = runCatching { sp.getString(key, null)?.let(::JSONObject) }.getOrNull()
        val e = sp.edit()
        // Each step works on the trackpad map as the step before left it; the result is written once.
        var trackpad = stored(SettingKeys.MAP_TRACKPAD)
        var trackpadChanged = false
        if (version < 1) {
            trackpad = null
            trackpadChanged = true
        } else if (version < 2 && trackpad?.optString(Gesture.LONG_PRESS.key) == GestureAction.Drag.key) {
            trackpad.put(Gesture.LONG_PRESS.key, GestureAction.RadialMenu.key)
            trackpadChanged = true
        }
        stored(SHARED_ROWS_V2)?.let { mac ->
            val direct = stored(SettingKeys.MAP_DIRECT) ?: JSONObject()
            val moved = trackpad ?: JSONObject()
            for ((old, row) in sharedRowsV2) {
                val value = mac.opt(old) as? String ?: continue
                direct.put(row.key, value)
                moved.put(row.key, value)
            }
            e.putString(SettingKeys.MAP_DIRECT, direct.toString())
            trackpad = moved
            trackpadChanged = true
        }
        e.remove(SHARED_ROWS_V2)
        if (trackpadChanged) {
            val map = trackpad
            if (map == null) e.remove(SettingKeys.MAP_TRACKPAD) else e.putString(SettingKeys.MAP_TRACKPAD, map.toString())
        }
        e.putInt(SETTINGS_VERSION, 3).apply()
        log("settings migrated from=$version to=3")
    }

    var savedMacs: List<SavedMac>
        get() = runCatching { SavedMacs.decode(sp.getString("saved", null) ?: "[]") }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("saved", SavedMacs.encode(v)).apply()
}

private const val SETTINGS_VERSION = "settingsVersion"

/** Where the rows both modes shared were stored up to version 2 of the settings, and their new names. */
private const val SHARED_ROWS_V2 = "touch.mac"

private val sharedRowsV2 = listOf(
    "swipeUp" to Gesture.FOUR_FINGER_SWIPE_UP,
    "swipeDown" to Gesture.FOUR_FINGER_SWIPE_DOWN,
    "swipeLeft" to Gesture.FOUR_FINGER_SWIPE_LEFT,
    "swipeRight" to Gesture.FOUR_FINGER_SWIPE_RIGHT,
    "tap" to Gesture.THREE_FINGER_TAP,
)

/** The saved Macs as a JSON array, the form they take in [Prefs]. */
object SavedMacs {
    fun encode(macs: List<SavedMac>): String = JSONArray(macs.map { m ->
        JSONObject()
            .put("hostId", m.hostId)
            .put("name", m.name)
            .put("clientId", m.pairing.clientId.b64())
            .put("pairKey", m.pairing.pairKey.b64())
            .put("lanAddrs", JSONArray(m.lanAddrs))
            .put("tailnet", JSONArray(m.tailnet))
            .put("lastUsed", m.lastUsed)
            .apply { m.customName?.let { put("customName", it) } }
            .apply { m.tint?.let { put("tint", it) } }
    }).toString()

    fun decode(json: String): List<SavedMac> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SavedMac(
                hostId = o.getString("hostId"),
                name = o.getString("name"),
                pairing = Pairing(o.getString("clientId").unb64(), o.getString("pairKey").unb64()),
                lanAddrs = o.getJSONArray("lanAddrs").strings(),
                tailnet = o.getJSONArray("tailnet").strings(),
                lastUsed = o.getLong("lastUsed"),
                customName = o.optString("customName").ifEmpty { null },
                tint = if (o.has("tint")) o.getInt("tint") else null,
            )
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
