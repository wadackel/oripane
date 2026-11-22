package dev.wadackel.oripane

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadialMenuTest {
    private val defaults = Settings.defaults.radialMenu

    private fun open(x: Float = 500f, y: Float = 500f, width: Float = 1000f, height: Float = 1000f, entries: List<RadialEntry?> = defaults) =
        RadialMenuState.opened(entries, x, y, 0.3f, 0.4f, width, height, margin = 120f)

    private fun at(degrees: Double, count: Int) =
        Math.toRadians(degrees).let { radialSector((100 * Math.sin(it)).toFloat(), (-100 * Math.cos(it)).toFloat(), 24f, count) }

    @Test
    fun eachOfTheEightDirectionsPicksItsSectorClockwiseFromTheTop() {
        assertThat(radialSector(0f, -50f, 24f, 8)).isEqualTo(0)
        assertThat(radialSector(50f, -50f, 24f, 8)).isEqualTo(1)
        assertThat(radialSector(50f, 0f, 24f, 8)).isEqualTo(2)
        assertThat(radialSector(50f, 50f, 24f, 8)).isEqualTo(3)
        assertThat(radialSector(0f, 50f, 24f, 8)).isEqualTo(4)
        assertThat(radialSector(-50f, 50f, 24f, 8)).isEqualTo(5)
        assertThat(radialSector(-50f, 0f, 24f, 8)).isEqualTo(6)
        assertThat(radialSector(-50f, -50f, 24f, 8)).isEqualTo(7)
    }

    @Test
    fun aSectorReachesHalfwayToEachNeighbour() {
        assertThat(at(22.0, 8)).isEqualTo(0)
        assertThat(at(23.0, 8)).isEqualTo(1)
        assertThat(at(338.0, 8)).isEqualTo(0)
        assertThat(at(337.0, 8)).isEqualTo(7)
    }

    @Test
    fun everyCountSplitsTheCircleEvenlyFromTheTop() {
        for (count in 2..8) {
            val step = 360.0 / count
            for (i in 0 until count) {
                assertThat(at(i * step, count)).isEqualTo(i)
                assertThat(at(i * step + step / 2 - 1, count)).isEqualTo(i)
                assertThat(at(i * step + step / 2 + 1, count)).isEqualTo((i + 1) % count)
            }
        }
    }

    @Test
    fun withinTheDeadZoneNothingIsPicked() {
        assertThat(radialSector(0f, 0f, 24f, 8)).isNull()
        assertThat(radialSector(16f, -16f, 24f, 8)).isNull()
        assertThat(radialSector(0f, -25f, 24f, 8)).isEqualTo(0)
        assertThat(radialSector(16f, -16f, 24f, 2)).isNull()
    }

    @Test
    fun theMenuStaysWhereTheFingerOpenedItAwayFromTheEdges() {
        val s = open()
        assertThat(s.centerX to s.centerY).isEqualTo(500f to 500f)
    }

    @Test
    fun nearAnEdgeOrCornerTheMenuMovesInsideButPicksFromTheFinger() {
        val corner = open(990f, 995f)
        assertThat(corner.centerX to corner.centerY).isEqualTo(880f to 880f)
        assertThat(open(5f, 10f).let { it.centerX to it.centerY }).isEqualTo(120f to 120f)
        assertThat(corner.moved(990f, 945f, 24f).selected).isEqualTo(0)
    }

    @Test
    fun onAScreenNarrowerThanTheMenuItSitsInTheMiddle() {
        val s = open(10f, 500f, width = 200f)
        assertThat(s.centerX).isEqualTo(100f)
    }

    @Test
    fun slidingSelectsThePlaceInThatDirectionAndBackToTheMiddleSelectsNone() {
        var s = open()
        s = s.moved(560f, 440f, 24f)
        assertThat(s.selected).isEqualTo(1)
        assertThat(s.fingerX to s.fingerY).isEqualTo(560f to 440f)
        s = s.moved(505f, 505f, 24f)
        assertThat(s.selected).isNull()
    }

    @Test
    fun aMenuOfFourPicksUpRightDownAndLeftAndNothingBetween() {
        val four = listOf(RadialItem.UNDO, RadialItem.VOLUME_UP, RadialItem.LOCK_SCREEN, RadialItem.COPY).map { RadialEntry.Builtin(it) }
        val s = open(entries = RadialEntry.spread(four))
        assertThat(s.moved(500f, 400f, 24f).selected).isEqualTo(0)
        assertThat(s.moved(600f, 500f, 24f).selected).isEqualTo(2)
        assertThat(s.moved(500f, 600f, 24f).selected).isEqualTo(4)
        assertThat(s.moved(400f, 500f, 24f).selected).isEqualTo(6)
        assertThat(s.moved(530f, 440f, 24f).selected).isNull()
        assertThat(s.moved(520f, 440f, 24f).selected).isEqualTo(0)
    }

    @Test
    fun aShortListSpreadsEvenlyOverTheCellsAsTheMenuDidBeforeCellsCouldBeChosen() {
        val items = defaults.filterNotNull()
        fun cells(count: Int) = RadialEntry.spread(items.take(count)).mapIndexedNotNull { cell, e -> cell.takeIf { e != null } }
        assertThat(cells(8)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(cells(6)).containsExactly(0, 1, 3, 4, 5, 7).inOrder()
        assertThat(cells(4)).containsExactly(0, 2, 4, 6).inOrder()
        assertThat(cells(3)).containsExactly(0, 3, 5).inOrder()
        assertThat(cells(2)).containsExactly(0, 4).inOrder()
        assertThat(RadialEntry.spread(items.take(3)).filterNotNull()).containsExactlyElementsIn(items.take(3)).inOrder()
    }

    @Test
    fun anItemInAnyCellIsPickedTowardItAndAnEmptyCellPicksNothing() {
        val copy = RadialEntry.Builtin(RadialItem.COPY)
        val undo = RadialEntry.Builtin(RadialItem.UNDO)
        val s = open(entries = listOf(null, null, undo, null, null, null, null, copy))
        assertThat(s.moved(450f, 450f, 24f).selected).isEqualTo(7)
        assertThat(s.moved(600f, 500f, 24f).selected).isEqualTo(2)
        assertThat(s.moved(500f, 400f, 24f).selected).isNull()
        assertThat(s.placeIn(7)).isEqualTo(7)
        assertThat(s.placeIn(0)).isNull()
    }

    @Test
    fun aSwapTradesTwoCellsOrMovesAnItemIntoAnEmptyOne() {
        val copy = RadialEntry.Builtin(RadialItem.COPY)
        val undo = RadialEntry.Builtin(RadialItem.UNDO)
        val menu = listOf(copy, null, undo, null, null, null, null, null)
        assertThat(radialSwap(menu, 0, 2)).isEqualTo(listOf(undo, null, copy, null, null, null, null, null))
        assertThat(radialSwap(menu, 0, 7)).isEqualTo(listOf(null, null, undo, null, null, null, null, copy))
        assertThat(radialSwap(menu, 2, 2)).isEqualTo(menu)
    }

    @Test
    fun twoEqualCustomEntriesAreTwoPlaces() {
        val shot = RadialEntry.Custom(KeyEvent.KEYCODE_4, ModKey.SHIFT.meta or ModKey.CMD.meta, null, RadialLook.Text)
        val s = open(entries = RadialEntry.spread(listOf(shot, shot))).moved(500f, 600f, 24f)
        assertThat(s.selected).isEqualTo(4)
        assertThat(s.entries[s.selected!!]).isEqualTo(shot)
    }

    @Test
    fun aTapOnTheOpenMenuLandsOnTheCellUnderItOrOffThePlate() {
        val s = open().copy(sticky = true)
        assertThat(s.cellAt(500f - 64f, 500f, 64f, 103f)).isEqualTo(6)
        assertThat(s.cellAt(500f, 500f + 90f, 64f, 103f)).isEqualTo(4)
        assertThat(s.cellAt(500f + 100f, 500f - 100f, 64f, 103f)).isEqualTo(1)
        assertThat(s.cellAt(505f, 500f, 64f, 103f)).isNull()
        assertThat(s.cellAt(500f, 300f, 64f, 103f)).isNull()
        assertThat(s.placeIn(6)).isEqualTo(6)
        val four = open(entries = RadialEntry.spread(defaults.filterNotNull().take(4)))
        assertThat(four.placeIn(2)).isEqualTo(2)
        assertThat(four.placeIn(1)).isNull()
    }

    @Test
    fun aComboGoesOnOneCapPerModifierAndOneForTheKey() {
        assertThat(keycapsOf("⌘C")).containsExactly("⌘", "C").inOrder()
        assertThat(keycapsOf("⌃⌥⇧⌘F12")).containsExactly("⌃", "⌥", "⇧", "⌘", "F12").inOrder()
        assertThat(keycapsOf("F11")).containsExactly("F11")
        assertThat(keycapsOf("⌃↑")).containsExactly("⌃", "↑").inOrder()
    }

    @Test
    fun theDefaultMenuIsTheEightItemsClockwiseFromMissionControlAtTheTop() {
        assertThat(defaults.map { (it as RadialEntry.Builtin).item.key }).containsExactly(
            "missionControl", "copy", "spaceRight", "paste", "showDesktop", "keyboard", "spaceLeft", "rightClick",
        ).inOrder()
    }

    @Test
    fun eachItemSendsWhatItsLabelSays() {
        val cmd = ModKey.CMD.meta
        val keys = mapOf(
            RadialItem.COPY to RadialSend.Key(KeyEvent.KEYCODE_C, cmd),
            RadialItem.PASTE to RadialSend.Key(KeyEvent.KEYCODE_V, cmd),
            RadialItem.UNDO to RadialSend.Key(KeyEvent.KEYCODE_Z, cmd),
            RadialItem.REDO to RadialSend.Key(KeyEvent.KEYCODE_Z, cmd or ModKey.SHIFT.meta),
            RadialItem.CUT to RadialSend.Key(KeyEvent.KEYCODE_X, cmd),
            RadialItem.SELECT_ALL to RadialSend.Key(KeyEvent.KEYCODE_A, cmd),
            RadialItem.SAVE to RadialSend.Key(KeyEvent.KEYCODE_S, cmd),
            RadialItem.FIND to RadialSend.Key(KeyEvent.KEYCODE_F, cmd),
        )
        keys.forEach { (item, send) ->
            assertThat(item.send).isEqualTo(send)
            assertThat((send as RadialSend.Key).code).isIn(ALLOWED_KEYCODES)
        }
        assertThat(RadialItem.KEYBOARD.send).isEqualTo(RadialSend.Keyboard)
        assertThat(RadialItem.RIGHT_CLICK.send).isEqualTo(RadialSend.RightClick)
        val macItems = RadialItem.entries.filter { it.send is RadialSend.Mac }
        assertThat(macItems.map { (it.send as RadialSend.Mac).action }).containsExactlyElementsIn(MacAction.entries)
        macItems.forEach { item ->
            val action = (item.send as RadialSend.Mac).action
            assertThat(item.key).isEqualTo(action.key)
            assertThat(item.label).isEqualTo(action.label)
        }
        assertThat(RadialItem.entries).hasSize(29)
        assertThat(RadialItem.entries.map { it.key }).doesNotContain("custom")
        assertThat(RadialItem.entries.map { it.key }.toSet()).hasSize(29)
    }

    @Test
    fun everyBuiltinItemHasItsOwnIcon() {
        RadialItem.entries.forEach { assertThat(RadialIcon.of(it.key)).isNotNull() }
        assertThat(RadialItem.entries.map { it.icon }.toSet()).hasSize(29)
        assertThat(RadialIcon.generic.map { it.id }).containsExactly("star", "bookmark", "bolt", "flag", "circle", "square").inOrder()
    }

    @Test
    fun aComboPutsTheModifiersInTheMacOrderBeforeTheKey() {
        val all = ModKey.entries.fold(0) { m, k -> m or k.meta }
        assertThat(keyCombo(KeyEvent.KEYCODE_Z, ModKey.CMD.meta or ModKey.SHIFT.meta)).isEqualTo("⇧⌘Z")
        assertThat(keyCombo(KeyEvent.KEYCODE_K, all)).isEqualTo("⌃⌥⇧⌘K")
        assertThat(keyCombo(KeyEvent.KEYCODE_F12, all)).isEqualTo("⌃⌥⇧⌘F12")
        assertThat(keyCombo(KeyEvent.KEYCODE_ESCAPE, 0)).isEqualTo("⎋")
        assertThat(keyCombo(KeyEvent.KEYCODE_F5, 0)).isEqualTo("F5")
        assertThat(keyCombo(KeyEvent.KEYCODE_SEMICOLON, 0)).isEqualTo(";")
        assertThat(spokenCombo(KeyEvent.KEYCODE_4, ModKey.CMD.meta or ModKey.SHIFT.meta)).isEqualTo("Shift Command 4")
        assertThat(spokenCombo(KeyEvent.KEYCODE_PAGE_DOWN, ModKey.CTRL.meta)).isEqualTo("Control Page down")
    }

    @Test
    fun theEditItemsShowTheComboTheySend() {
        listOf(RadialItem.UNDO, RadialItem.REDO, RadialItem.CUT, RadialItem.SELECT_ALL, RadialItem.SAVE, RadialItem.FIND, RadialItem.COPY, RadialItem.PASTE).forEach {
            val send = it.send as RadialSend.Key
            assertThat(it.shortcut).isEqualTo(keyCombo(send.code, send.meta))
        }
    }

    @Test
    fun theKeyListIsEveryKeyTheHostMapsButTheKeypadEnter() {
        assertThat(radialKeys.map { it.code }.toSet()).isEqualTo(ALLOWED_KEYCODES - KeyEvent.KEYCODE_NUMPAD_ENTER)
        assertThat(radialKeys.map { it.code }).containsNoDuplicates()
    }

    @Test
    fun aCustomEntryIsNamedByItsComboUnlessGivenAName() {
        val meta = ModKey.SHIFT.meta or ModKey.CMD.meta
        val plain = RadialEntry.Custom(KeyEvent.KEYCODE_4, meta, null, RadialLook.Text)
        assertThat(plain.label).isEqualTo("⇧⌘4")
        assertThat(plain.combo).isEqualTo("⇧⌘4")
        assertThat(plain.send).isEqualTo(RadialSend.Key(KeyEvent.KEYCODE_4, meta))
        assertThat(plain.copy(name = "Area shot").label).isEqualTo("Area shot")
    }

    @Test
    fun theOpenedMenuKeepsTheVideoPointForARightClick() {
        val s = open()
        assertThat(s.videoX to s.videoY).isEqualTo(0.3f to 0.4f)
        assertThat(s.selected).isNull()
        assertThat(s.sticky).isFalse()
    }
}
