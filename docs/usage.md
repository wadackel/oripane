# Using Oripane

Setting up the Mac and the phone, and what every gesture, menu and setting does. Building both
sides is in the [README](../README.md#building); what pairing protects and what a paired phone can
do is in [`security.md`](security.md).

## On the Mac

The Mac side is the menu bar app "Oripane". While it is open, it waits for the phone without a terminal.

```sh
tools/app install     # build, sign, put it in ~/Applications/Oripane.app and open it (the same command updates it)
tools/app status      # whether it runs, its version and commit, what its signature is tied to, the login item
tools/app quit        # quit (waits until the virtual display is released)
tools/app open        # open
tools/app uninstall   # quit, remove the login item and delete the app (the certificate and pairings stay)
```

1. **Install**: run `tools/app install`. The first time, it creates the signing certificate "Oripane Signing" in a keychain of its own (`~/Library/Keychains/oripane-signing.keychain-db`). Permissions such as Screen Recording are tied to this certificate, so reinstalling keeps them.
2. **Grant permissions**: the first launch opens the "Oripane Setup" window. Work through it from the top. The menu's "Setup…" opens it again at any time.
    1. **Screen Recording**: "Open Settings…" opens System Settings; turn Oripane on there. Once granted, "Relaunch" in the window restarts the app so it can capture the screen.
    2. **Accessibility**: turn it on the same way. From then on, taps and typing on the phone reach the Mac.
    3. **Local Network**: after the relaunch for Screen Recording, macOS asks for this; choose "Allow". Without it, video does not arrive over the home Wi-Fi and only Tailscale connects. If you declined, turn it on from "Open Settings…".
    4. **Open at Login**: when on, Oripane starts when you log in. The menu's "Open at Login" toggles it too.
3. **Pair**: when a new phone connects, the Mac shows a "Pair with a new phone?" dialog with where the request came from. Press "Allow" if the large code matches the one on the phone, "Deny" if it does not. From then on the phone authenticates with the saved key. Another phone asking meanwhile waits its turn; "Allow" works a second after each request appears, and an address you denied is refused for 30 seconds.
4. **Unpair a phone**: the menu's "Paired Phones" lists the paired phones. Choose one and press "Unpair…" to delete its key and disconnect it if connected. To use that phone again, pair it again.

The top of the menu shows the state (waiting, the connected phone and its mode, missing permissions). "Show Log" opens `~/Library/Logs/Oripane/host.log`.

- **The signing key**: `tools/app install` signs without asking, and in exchange any other program running as you can sign itself as Oripane and gain the permissions you gave it. [`security.md`](security.md#same-user-programs) explains why and how to remove the key.
- **Signing with an Apple certificate**: `ORIPANE_SIGN_IDENTITY="Apple Development: …" tools/app install` signs with that certificate. With the self-signed one, the Local Network permission can be lost after an update (Apple recommends a certificate it issued).
- **Screen Recording prompts**: macOS 15 and later occasionally ask whether to keep allowing an app that records the screen continuously. This is not a bug; keep allowing it.

## On the phone

```sh
tools/phone install --dry-run   # what it would build and install, changing nothing
tools/phone install             # build HEAD's release APK and install it over wireless debugging
tools/phone status              # whether the phone and the Mac run HEAD's build
```

`tools/phone` needs the phone paired for wireless debugging with the Mac once, and an adb server it can use; it says how to start one when there is none. When the Mac's build is behind, it reinstalls Oripane.app too, quitting it and opening it again only if it was running. Over USB, build with `cd android && ./gw :app:assembleRelease` and install with `adb install -r app/build/outputs/apk/release/app-release.apk`.

The first screen (Home) shows a large card for one Mac and a tile for every Mac below it (on the inner screen, the card on the left and the tiles on the right). Tap a tile to put that Mac on the card, choose the display mode on the card ("Extend" for a second display, "Mirror" for mirroring, "Match" for a mirror reshaped to the phone, for using the Mac away from home; the last one used stays chosen) and press the white round Connect button.

- **The card**: the Mac used last until another tile is tapped. Under its name, its state: green "Ready · LAN" when it is found on this Wi-Fi, gray with what is known (Tailscale, when it was last used) otherwise. Its colour is the colour of the last screen seen from that Mac; a Mac never connected to stays gray. When a connection fails, the card says why and offers a quiet "Try again" in place of Connect. The route is chosen automatically ("Away from home" below).
- **The tiles**: the saved Macs (newest first), then Macs on the same LAN that have never been connected (a yellow dot). A Mac never connected before shows "Nearby · not added yet" on the card and a "+" that pairs it the first time. The last tile adds a Mac by address.
- **Details (⋮)**: open with "⋮" on the card or a long press on a saved Mac's tile to give it a name on this phone only, or to delete its record ("Remove from this phone"). Removing it keeps the pairing on the Mac (to delete that, use "Unpair…" in the Mac's "Paired Phones" menu).
<<<<<<< HEAD
- **Add a Mac (+ at the top, or the last tile)**: enter an IPv4 address or a name (Tailscale MagicDNS names work), choose the display mode and press "Connect". When no Mac is saved or found, Home shows "Add by address".
- **Settings (the icon at the top right)**: an index of sections, each with its current values; on the inner screen the chosen section shows beside the index. **Video**: Resolution (100 / 75 / 50%), Frame rate (60 / 30 fps), Data saver, Display size (Larger text / More space) and Show latency; these take effect from the next connection, and a Mac started with `--scale` or `--fps` overrides them. **Touch & pointer**: Touch mode, Cursor speed and Incognito keyboard. **Gestures**: Direct gestures and Trackpad gestures. **Radial menu**. **Scrolling**: the speed and direction of Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia. Reset all settings is at the end of the index.
||||||| parent of b2b42b9 (Set cursor and scroll speeds on nine-step sliders)
- **Add a Mac (+ at the top, or the last tile)**: enter an IP address or a name (Tailscale MagicDNS names work), choose the display mode and press "Connect". When no Mac is saved or found, Home shows "Add by address".
- **Settings (the icon at the top right)**: an index of sections, each with its current values; on the inner screen the chosen section shows beside the index. **Video**: Resolution (100 / 75 / 50%), Frame rate (60 / 30 fps), Data saver, Display size (Larger text / More space) and Show latency; these take effect from the next connection, and a Mac started with `--scale` or `--fps` overrides them. **Touch & pointer**: Touch mode, Cursor speed and Incognito keyboard. **Gestures**: Direct gestures and Trackpad gestures. **Radial menu**. **Scrolling**: the speed and direction of Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia. Reset all settings is at the end of the index.
=======
- **Add a Mac (+ at the top, or the last tile)**: enter an IP address or a name (Tailscale MagicDNS names work), choose the display mode and press "Connect". When no Mac is saved or found, Home shows "Add by address".
- **Settings (the icon at the top right)**: an index of sections, each with its current values; on the inner screen the chosen section shows beside the index. **Video**: Resolution (100 / 75 / 50%), Frame rate (60 / 30 fps), Data saver, Display size (Larger text / More space) and Show latency; these take effect from the next connection, and a Mac started with `--scale` or `--fps` overrides them. **Touch & pointer**: Touch mode, Cursor speed and Incognito keyboard. **Gestures**: Direct gestures and Trackpad gestures. **Radial menu**. **Scrolling**: the speed and direction of Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia. Cursor speed and the scroll speeds are sliders of nine steps with − and + for one step either side; the figure beside the name is the speed as a multiple of the default (1×), and the bar on the slider marks the default. Slow, Normal and Fast from earlier versions are steps 3, 5 and 7. Reset all settings is at the end of the index.
>>>>>>> b2b42b9 (Set cursor and scroll speeds on nine-step sliders)

When the connection drops, the app reconnects on its own. While it does, "Reconnecting…" shows over the video.

Touch mode is either "Direct" (press under the finger; the default) or "Trackpad" (the finger moves a cursor). The default bindings are below; "Direct gestures" and "Trackpad gestures" in Settings change them per mode.

| Gesture | Direct | Trackpad |
|---|---|---|
| One-finger tap | Click there | Click at the cursor |
| Two taps in a row | Double-click | Double-click |
| One-finger drag | Move with the left button held | Move the cursor |
| Second touch held briefly, then moved | Move with a double-click held (select by word) | Move the cursor (Settings can make it move with the left button held) |
| Long press | Same as a one-finger drag | Opens the radial menu (below) |
| Three-finger drag | Nothing | Move with the left button held |
| Two-finger swipe | Scroll | Scroll |
| Two-finger tap | Right-click | Right-click |

Each gesture that fires once (the long press, the two-finger tap and long press, the three-finger tap, and the four-finger swipes, pinch and spread, below) can be given anything the radial menu can hold: its built-in items, and shortcuts of your own made with "Custom shortcut…" at the bottom of the gesture's list. It can also be given the radial menu or nothing. Every one of them but the long press can be given a click; the long press can start a drag instead. The long press opens the radial menu under the finger; any other gesture opens it left open where the fingers were, for a tap. Lock screen asks first, whatever sent it. "Double tap and hold" can start a drag, zoom or scroll: after a tap, touch again within 0.15 seconds and 24 dp of it, hold still for 0.2 seconds, then move (down to zoom in and up to zoom out about the second touch, or as with two fingers to scroll; Scroll inertia applies). Held still for 0.4 seconds, it starts without a move. A second touch that moves sooner moves the cursor as any touch does, and one lifted between a tap's length and 0.4 seconds sends nothing; a quicker one is still the second click of a double-click. It starts as nothing.

In Trackpad mode a drag goes on while any finger stays down, and the fingers' center moves the cursor. Lifting every finger keeps the button held for half a second: three fingers back carry the drag on, and a one-finger tap drops it.

In Trackpad mode the phone keeps the cursor position. Right after connecting it is at the center of the screen, and the Mac's cursor is visible in the video. The cursor follows the finger from the moment it touches; when the touch turns out to be a tap, a long press or a two-finger gesture, the cursor first goes back to where it was when the finger touched, so a slight slip never moves the click.

In Trackpad mode two scroll bands, fields of small dots, sit on the edges of the screen for scrolling with the thumb of the hand that holds the phone: one on the right edge, at first about two thirds of the way down, and one along the bottom, in the middle. A finger that lands on the side band (168 dp long and 44 dp in from the edge) scrolls as it moves up and down, and one on the bottom band (200 dp long, 44 dp up from the bottom) as it moves left and right, at two and a half times its travel (Finger scroll speed and direction apply too). The side band sits right on the edge: the system's back gesture is turned off over it, so a swipe there never goes back. The bottom band sits above the strip of the bottom edge the system keeps for its own gestures. While a band is held its dots swell under the finger and reach on past the band's ends; their colour comes from the Mac's picture under the band, darker over a light page and lighter over a dark one. Every other finger is ignored until all lift, and a touch that starts on a band never moves the cursor or clicks. Held still for half a second, the side band vibrates and follows the finger up and down instead, and stays where the finger lifts. With Haptics on (Settings → Scrolling), the phone ticks as the finger lands and every 28 dp of travel. "Position" in Settings → Scrolling moves the side band to the left edge or turns both bands off; Direct mode has no bands, since every point of the screen there presses the Mac under it.

With Scroll inertia on (the default), a scroll from a band or from two fingers glides on after a quick lift, slowing down, as on the phone itself; the next touch anywhere stops it. A finger that stopped before lifting does not glide.

The radial menu opens around the finger when it rests still for half a second (Trackpad mode by default; "Long press" in either mode's gestures can choose "Radial menu"). After a vibration a plate opens under the finger: three rows of three cells, the app's logo in the middle and the items in the eight cells around it, each in the cell chosen for it in Settings; the other cells stay empty. Slide toward an item and lift to send it; its name and keys show on a white tag above the plate, and a short note at the bottom says what was sent. The finger need not reach the item: the direction counts once it has moved a little, and a direction with an empty cell sends nothing. Lifting without sliding, or toward an empty cell, leaves the menu open for a tap on an item; a tap on an empty cell does nothing, and a tap on the logo, off the plate, or Back closes it. Volume, playback and brightness keep the menu open after they are sent, for the next tap; Lock screen asks first. Near an edge the plate moves inside the screen, with each item still in the same direction.

The menu starts with these eight items; Settings → Radial menu changes them:

| Direction | Item |
|---|---|
| Up | Mission Control |
| Up right | Copy (Cmd+C) |
| Right | The Space to the right |
| Down right | Paste (Cmd+V) |
| Down | Show desktop |
| Down left | The on-screen keyboard |
| Left | The Space to the left |
| Up left | Right-click where the long press began |

Settings → Radial menu shows the plate as it will open and lists its eight cells by direction, from the top clockwise. Tap a cell, on the plate or in the list, to put an item in it or replace the one there; drag an item on the plate to another cell to move it, trading places with an item already there (TalkBack offers Move clockwise and Move counterclockwise on the list); and empty a cell with the bin. Two to eight cells hold an item. A tap on a cell opens the list of built-in items: editing keys (Undo, Redo, Cut, Copy, Paste, Select all, Save, Find), the keyboard and right-click, Fill screen, and every action of the Mac page, each at most once: an item already in another cell names that cell and cannot be picked. "Custom shortcut…" there makes an item of your own: any of ⌃ ⌥ ⇧ ⌘ (or none, for Esc, F5 or an arrow alone) and one key: a letter, a number, F1–F12, an arrow, Return, Tab, Esc, Space, Delete, Forward delete, Home, End, Page up, Page down, or a symbol. Symbols are named as on a US keyboard, since the Mac receives the key at that position; on a JIS Mac the printed character may differ. The item shows its keys (⇧⌘4) or an icon you pick, and takes an optional name of up to 24 characters. "Restore defaults" puts back the eight items above.

In Trackpad mode, a finger resting still for half a second opens the menu too, and the cursor goes back to where the touch began. A finger that travels more than 6 dp in all before then, even back and forth within a tap's slop, or more than 2 dp in its last 0.15 seconds, is moving the cursor: it opens no menu, and whatever else the long press is set to does not happen either (a tap still clicks where the touch began). For a long press that drags, set "Trackpad gestures" → "Long press" to "Start drag". Updating the app turns a stored "Start drag" for the Trackpad long press into "Radial menu" once.

| Input | What the Mac does |
|---|---|
| A physical keyboard or mouse (DeX, Bluetooth) | Passed through as keys and mouse (the on-screen modifiers do not apply) |
| Two-finger pinch or spread | Zooms the video (1–4x, or up to the Fill screen zoom when that is more), keeping the point under the fingers' center in place; the zoomed video spreads over the black bars to the edges of the screen |

**Fill screen** zooms to the point where the black bars just disappear: the video then fills the screen in the direction the bars were, and runs off it in the other. Used again, or used while zoomed in at least that far, it goes back to 1x; on a screen without bars it only goes back. The point of the Mac under the gesture stays where it is: in Direct mode the center of the fingers (for the radial menu, where the long press began), in Trackpad mode the cursor. It goes on any gesture that fires once ("Direct gestures" or "Trackpad gestures" in Settings) or on the radial menu, and sends nothing to the Mac. The bars are widest in Mirror; Extend and Match make the video in the phone's shape, so there is little to fill.

The gestures of three and four fingers and the two-finger long press start the same in both modes, as on a Mac trackpad: the swipes go the way they do on a Mac set to drag with three fingers, and a thumb closing on three fingers (or all four closing) opens the apps as spreading them shows the desktop. Four fingers count as a pinch or a spread once their spread from the centre changes by a quarter before the centre has moved far enough for a swipe. Updating the app copies the swipes and the three-finger tap, which older versions shared between the modes, into both modes once.

| Gesture | What the Mac does |
|---|---|
| Four-finger swipe up | Mission Control |
| Four-finger swipe down | App Exposé (the windows of the current app) |
| Four-finger swipe left | The Space to the right |
| Four-finger swipe right | The Space to the left |
| Four-finger pinch | Apps |
| Four-finger spread | Show desktop |
| Three-finger tap | Nothing |
| Two-finger long press | Nothing |

A third finger placed after a two-finger scroll or pinch has started keeps the two-finger gesture going; it starts no three-finger drag either.

Touches work across the whole screen, black bars included. Where a zoomed video covers a black bar, a touch there lands on the video under it. In Direct mode a single finger that starts on a black bar still showing does nothing, since nothing is under it; two or more fingers work there as anywhere. A finger resting on a black bar counts like any other finger: in Trackpad mode, resting it there for half a second and then moving another finger drags with the left button held (with the radial menu on the long press, the second finger closes the menu first).

The video screen shows only a status dot in one corner and the menu button in the opposite one. Both sit on opaque gray plates with a soft shadow, so they stand out on bright and dark screens alike.

- **Status dot**: green is good; yellow means latency (100 ms or more), loss (1% or more) or round trip (100 ms or more) is high; gray means no stats have arrived yet, or the app is reconnecting (the dot in the menu's head is gray then too). When something changes, the dot opens into a short note for a few seconds: "Slow · 120 ms" (or the loss or round trip that is high) and "Back to 18 ms" afterwards, "AWDL is on", and "Back on LAN" or "Back on Tailscale" after a reconnect. While reconnecting it stays open with "Reconnecting…" and the attempt, and the dot pulses. Tap the dot for the detailed readout, and tap again to go back to the dot. With "Show latency" off in Settings, the dot and its notes about latency are hidden, and turning it off during a connection closes the readout too; the reconnect and AWDL notes still show.
- **Menu**: opens with a tap on the button or with Back. Its head shows the Mac (name, display mode, route and frame rate) and a red power button that disconnects. Below it, the rows that act now (Show keyboard, Reset zoom, Mac controls) and the rows that open a page (Display mode, Live settings). Back does not disconnect (not even while reconnecting). Back on a page of the menu (Mac controls, Display mode, Live settings) goes back to the menu, and Back on the menu closes it.
    - **Show keyboard**: shows the soft keyboard with a row of `⌘` `⌥` `⌃` `⇧` `Esc` `Tab` `←` `↑` `↓` `→` `⌫` above it; on a screen too narrow for all of them, as the cover screen held upright, the row scrolls sideways. A modifier tapped once applies to the next key only, tapped twice locks, and tapped three times turns off. While a modifier is on, typed characters are sent as keys (`⌘` and `c` copy on the Mac). Characters that are not keys (Japanese, for example) are sent as text without the modifiers. While Japanese is being converted, the reading shows in a strip above the row and nothing reaches the Mac until a candidate is chosen; Esc on the row drops it, and any other key of the row sends it first. The keyboard offers no English predictions or autocorrection, so letters reach the Mac as they are typed. With "Incognito keyboard" on in Settings, the keyboard is asked not to learn from what you type (Gboard shows its incognito icon; Japanese conversion still works); a keyboard that ignores the request keeps learning. Closing the keyboard with Back closes the row too, and sends text still being converted.
    - **Mac controls**: opens the list of Mac actions; a press runs it on the Mac. Mission Control, App Exposé, Space left / right, Show desktop, Apps, Launcher (Cmd+Space), Screenshot (Cmd+Shift+5), Lock screen, Previous app (Cmd+Tab), volume and mute, play, next and previous track, and brightness. Actions that change the screen close the menu before they run; volume, playback and brightness keep it open so they can be pressed repeatedly. The shortcuts assume the Mac's default bindings.
    - **Display mode**: the row shows the current mode and opens a page with "Extend" (a second display), "Mirror" (mirroring) and "Match", each with a line on what it does. Picking another mode closes the menu and switches without disconnecting; picking the current one goes back to the menu. The row cannot be opened while reconnecting or while the Mac streams its test pattern. Switching to Mirror removes the Mac's virtual display, and its windows move to the main screen. Match is for using the Mac away from home, when nobody looks at its screen: the Mac's main display becomes a mirror of a display with the phone's proportions, so the whole desktop fills the phone without black bars, and the Mac's own screen is rearranged to match until the session ends. When the Mac cannot set it up, it streams Mirror instead and the phone says "This Mac can't use Match right now. Showing Mirror." When the Mac was started with `--mode`, it does not switch and shows "The Mac has fixed the display mode." The chosen mode is used for the next connection too.
    - **Reset zoom**: shown only while zoomed, by a pinch or by Fill screen. Zoom also returns to 1x when the video size or the display mode changes.
<<<<<<< HEAD
    - **Live settings**: opens the settings that can change during a connection (Show latency, Data saver, Touch mode, Incognito keyboard, Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia). They take effect at once; Incognito keyboard from the next time the keyboard opens, or at once if it is still open.
- **Menu button position**: long-press and move it, and it follows the finger; on release it snaps to the nearest corner. The position is remembered. In a bottom corner it rises above the soft keyboard and its row while they are shown, so the menu stays one tap away.
||||||| parent of b2b42b9 (Set cursor and scroll speeds on nine-step sliders)
    - **Live settings**: opens the settings that can change during a connection (Show latency, Data saver, Touch mode, Incognito keyboard, Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia). They take effect at once; Incognito keyboard from the next time the keyboard opens, or at once if it is still open.
- **Menu button position**: long-press and move it, and it follows the finger; on release it snaps to the nearest corner. The position is remembered.
=======
    - **Live settings**: opens the settings that can change during a connection (Show latency, Data saver, Touch mode, Cursor speed, Incognito keyboard, Finger and Wheel scroll, and the scroll bands' Position, Haptics and Inertia). They take effect at once; Incognito keyboard from the next time the keyboard opens, or at once if it is still open.
- **Menu button position**: long-press and move it, and it follows the finger; on release it snaps to the nearest corner. The position is remembered.
>>>>>>> b2b42b9 (Set cursor and scroll speeds on nine-step sliders)

While zoomed, a tap lands on the point of the Mac visible under the finger. The visible area follows the cursor to the edge of the screen in Trackpad mode, and the finger during a drag in Direct mode, in each direction in which the zoomed video is larger than the screen; in a direction where it still fits, it stays centered.

When folding or rotating changes the screen size, the Mac's virtual display changes to the same aspect ratio. In Match the Mac's main display follows too. How much space the Mac sees depends on Display size: "Larger text" is half the phone's pixel count, and "More space" is 4/3 of that.

## For lower latency

- **Turn AWDL off**: turn off Universal Control, Handoff and AirDrop, and run `sudo ifconfig awdl0 down`. While AWDL runs, Wi-Fi leaves for another channel every few seconds, causing 40–75 ms spikes. When the host detects AWDL, the status dot says so for a few seconds and an "AWDL" badge stays next to it; tap the badge for a banner on how to turn AWDL off.
- During a connection the phone holds the Wi-Fi low-latency lock (`WIFI_MODE_FULL_LOW_LATENCY`) and keeps the screen on.
- Tapping the status dot shows the time from capture to display (total), to arrival (arrive), decoding (decode), the loss rate, and the number of frames repaired by FEC. While data saver is active it shows "data saver".

## Away from home

Run Tailscale on both the Mac and the phone, and leave the Mac running and unlocked.

- **Pair once at home**: pairing can only be approved at the Mac (the app's dialog, or the terminal of `tools/host run`). After one connection on the home LAN, the Mac becomes a saved Mac and the phone remembers its Tailscale address too.
- **Automatic route**: connecting to a saved Mac uses the LAN when Wi-Fi finds the same Mac, and Tailscale otherwise. When Wi-Fi drops, the phone reconnects over Tailscale; back on the home Wi-Fi with the same Mac found, it reconnects over the LAN (the video pauses for 1–2 seconds when it does).
- **Data saver**: Data saver in Settings is "Auto" (when not on Wi-Fi), "Always" or "Off". While active, the phone asks the Mac to cap the bitrate at 4 Mbps, cutting traffic to about a quarter. Text stays almost as readable.
- **When the pairing is gone**: after "Unpair…" on the Mac, for example, connecting to the saved Mac shows "The Mac no longer has this phone's pairing." and reconnecting stops. On the same LAN as the Mac, connect with "Add by address" to the same IP address as before to pair again. If the Mac's IP address has changed too, delete the record in Details and add it again.
