package dev.wadackel.oripane

/**
 * The `--es set "k=v,k=v"` test hook: settings written to `Prefs` before the app starts. Keys are
 * the `Prefs` keys; a mapping row is `touch.map.<mode>.<gesture>=<action or built-in item>`, and the
 * radial menu `radial.menu=copy+paste+keyboard`. Built-in items only, `+` between them: `,` parts
 * the hook and `;` ends a shell command, and a custom shortcut's name may hold either. Eight parts
 * fill the cells clockwise from the top, an empty part leaving its cell empty (`copy++++paste+++`);
 * fewer spread as [RadialEntry.spread] does.
 */
object SettingsHook {
    data class Parsed(
        val writes: Map<String, String>,
        val rows: List<Triple<TouchMode, Gesture, GestureAction>>,
        /** The radial menu's items, one or null per cell, when given and readable. */
        val radialMenu: List<RadialItem?>?,
        val ignored: List<String>,
    )

    private val speedSteps = (SpeedStep.MIN..SpeedStep.MAX).map { it.toString() }

    private val choices: Map<String, List<String>> = mapOf(
        SettingKeys.SCALE to Settings.scales.map { it.toString() },
        SettingKeys.FPS to Settings.fpsChoices.map { it.toString() },
        SettingKeys.DATA_SAVER to DataSaver.entries.map { it.key },
        SettingKeys.UI_SCALE to Settings.uiScales.map { it.toString() },
        SettingKeys.TOUCH_MODE to TouchMode.entries.map { it.key },
        SettingKeys.CURSOR_SPEED to speedSteps,
        SettingKeys.TOUCH_SCROLL_SPEED to speedSteps,
        SettingKeys.TOUCH_SCROLL_REVERSE to listOf("true", "false"),
        SettingKeys.WHEEL_SCROLL_SPEED to speedSteps,
        SettingKeys.WHEEL_SCROLL_REVERSE to listOf("true", "false"),
        SettingKeys.SCROLL_STRIP to ScrollStrip.entries.map { it.key },
        SettingKeys.STRIP_HAPTICS to listOf("true", "false"),
        SettingKeys.SCROLL_BAND_SIZE to BandSize.entries.map { it.key },
        SettingKeys.SCROLL_INERTIA to listOf("true", "false"),
        SettingKeys.INCOGNITO_KEYBOARD to listOf("true", "false"),
        SettingKeys.SHOW_STATS to listOf("true", "false"),
        MenuCorner.KEY to MenuCorner.entries.map { it.key },
    )

    fun parse(spec: String): Parsed {
        val writes = linkedMapOf<String, String>()
        val rows = mutableListOf<Triple<TouchMode, Gesture, GestureAction>>()
        val ignored = mutableListOf<String>()
        var radialMenu: List<RadialItem?>? = null
        for (item in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val key = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            val row = mapRow(key, value)
            when {
                key == SettingKeys.RADIAL_MENU -> radialItems(value)?.let { radialMenu = it } ?: run { ignored += key }
                row != null -> rows += row
                choices[key]?.contains(value) == true -> writes[key] = value
                else -> ignored += key
            }
        }
        return Parsed(writes, rows, radialMenu, ignored)
    }

    /** Two to eight known items, each once, over the eight cells; null otherwise. */
    private fun radialItems(value: String): List<RadialItem?>? {
        val parts = value.split('+').map { it.trim() }
        val cells = parts.map { if (it.isEmpty()) null else RadialItem.of(it) ?: return null }
        val items = cells.filterNotNull()
        if (items.size !in RadialEntry.MIN..RadialEntry.MAX || items.distinct().size != items.size) return null
        if (cells.size == radialCells.size) return cells
        if (items.size != cells.size) return null
        return RadialEntry.spread(items.map(RadialEntry::Builtin)).map { (it as? RadialEntry.Builtin)?.item }
    }

    private fun mapRow(key: String, value: String): Triple<TouchMode, Gesture, GestureAction>? {
        val parts = key.split('.')
        if (parts.size != 4 || parts[0] != "touch" || parts[1] != "map") return null
        val mode = TouchMode.entries.firstOrNull { it.key == parts[2] } ?: return null
        val gesture = Gesture.entries.firstOrNull { it.key == parts[3] } ?: return null
        val action = TouchMap.action(gesture, value) ?: return null
        return Triple(mode, gesture, action)
    }

    fun apply(prefs: Prefs, spec: String) {
        val p = parse(spec)
        p.writes.forEach { (k, v) -> prefs.putSetting(k, v) }
        if (p.rows.isNotEmpty() || p.radialMenu != null) {
            var s = prefs.settings
            for ((mode, g, a) in p.rows) {
                s = s.withMap(mode, s.mapFor(mode).with(g, a))
            }
            p.radialMenu?.let { cells -> s = s.copy(radialMenu = cells.map { it?.let(RadialEntry::Builtin) }) }
            prefs.settings = s
        }
        p.ignored.forEach { log("set ignored key=$it") }
    }
}
