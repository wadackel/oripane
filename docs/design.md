# Oripane design language

How every screen of the Android app looks and which part each piece of UI takes: the pages shown
while not connected and the parts drawn over the video while streaming. The values live in
`android/app/src/main/kotlin/dev/wadackel/oripane/Theme.kt` (`Palette`, `Space`, `Radius`, `Sizes`, `Type`);
the shared parts in `PageParts.kt` and `Components.kt`. A new screen is built from those parts and
these rules; a value a screen spells out for itself is a rule missing here, to be added here first.

The language was settled in November 2026 with mockups the owner reviewed screen by screen. Its
character in words: cool, a tool, friendly, organic, minimal, soft. Its visual base is what Linear and
Tesla's apps share: a mid-dark neutral grey, plain rows of icons without cards or rules between
them, round grey buttons, one subject per screen, no small capital labels, soft corners.

## Foundations

### Colour

| Role | Token | Value |
| --- | --- | --- |
| Ground | `Palette.bg` | #1A1B1D |
| Surface (a group of rows, a card, a tile, a sheet, a plate over the video) | `surface1` | #232427 |
| Surface inside a surface, round secondary button | `surface2` | #2C2D31 |
| Third step (switch track off, outlines) | `surface3` | #36373C |
| Text | `text` | #F2F2F3 |
| Secondary text (values, notes, headings) | `textMuted` | #9D9EA4 |
| Faint text, chevrons | `textFaint` | #6E6F75 |
| Icons | `icon` | #C9CACF |
| Line between rows of one surface | `divider` | white 4 % |
| Top edge light of a raised surface | `highlight` | white 7 % |
| Recessed track (segments, icon square on a card) | `well` | black 26 % |
| Chosen segment's ground | `chosen` | white 10 % |
| Danger | `danger` | #FF7A70 |
| Status: good, warning, unknown | `statusGood`, `statusWarn`, `statusUnknown` | #3DD68C, #F2B84B, #6E6F75 |

- **White leads.** The one primary action, the chosen cell or tile, a switch that is on, and the
  name of the chosen radial item are white (`lead`) with dark ink on them (`onLead`). Nothing else
  is white-filled.
- **Teal is the mark's.** The mark's three greens (Brand, below) appear only in the mark. Controls,
  selections and links never use them, and `Palette` has no brand colour for them to reach for.
- **Colour means something or is not there.** No colour per function. Status colours appear only as
  a dot (7 dp) next to the words they qualify.
- **The video's colour** (below) is the only colour a surface takes, and only as a wash.
- Dark only: no light theme, no colours from the wallpaper.

### Type

- **Inter** for everything; **Inter Tight** for names and titles (page titles, a Mac's name, sheet
  titles, the wordmark). Both are variable fonts in `res/font/` (licences in
  `android/third_party/`).
- Sizes by role (`Type`): title 22 (Inter Tight, semibold, −0.02 em), sheet title 18 (Inter Tight),
  label 15 (medium), value 14 (tabular figures), heading 13 (medium, muted), segment 13.5 (medium),
  body 13, note 12 (muted). The page note above a group is 12.5. The wordmark in the home header is
  20 (Inter Tight, weight 580, +0.03 em).
- No uppercase labels and no letter-spaced small caps.

### Spacing

Every gap and padding is 8, 12, 16 or 24 dp (`Space`). Side margins are 16 on the cover screen and
24 on the inner one; padding inside a surface is 16; the gap between blocks reads as 16; tiles sit 8
apart.

### Corners

Radii are concentric by surface size (`Radius`): a surface 16, a part inside it 12, a part inside
that 9; sheets 22 at the top. A part's radius is its parent's minus the padding between them (the
radial plate: cell 12 + padding 10 = 22). Only pressable round things are circles.

### Depth

Pages are flat: surfaces sit on the ground without shadows or borders. Parts over the video are
plates: opaque `surface1`, a 1 px `highlight` along the top edge, and a soft shadow (`Modifier.plate`
in `StreamOverlays.kt`). No blur anywhere: the video is a `SurfaceView`, which Compose cannot see
through.

### Icons

Line icons on a 24 grid, 1.8 stroke, round caps and joins, drawn white and tinted by the caller
(`res/drawable/ic_*.xml`). 20 dp in a row, 18 dp in a round secondary button, 20 dp in the primary
one.

## Buttons and controls

- **Primary action:** a white round 44 dp button (`RoundButton(primary = true)`), one per screen at
  most. On a page with a form, the primary action is a white 44 dp pill as wide as the column
  (`PrimaryButton`).
- **Secondary actions:** round grey 36 dp buttons with a 44 dp touch target (`RoundButton`): back,
  settings, details, add. A secondary action with words is a grey pill (`SecondaryButton`).
- **Controls side by side share a height:** the display-mode control beside Connect is 44 dp, as
  Connect is. Large text makes its segments taller rather than cut the drawing or the name, and
  when the three names no longer fit side by side the segments stack, one above another, beside
  Connect: a mode's name is never split inside the word.
- **Danger:** something that cannot be undone sits on a surface of its own at the end of the page,
  red words and a red icon (`DangerButton`), and asks first (`ConfirmDialog`: the confirming word
  red, Cancel plain).
- **No save button:** every setting applies when it is changed.

## Rows and the part each setting takes

A page groups its rows on surfaces (`SettingsCard`); rows are at least 52 dp, separated by the faint
`divider`. One surface's rows of one kind share a height: a note under a label is given to every row
of the surface or to none.

| Part | When | Examples |
| --- | --- | --- |
| Segments (`Choices`, `SegmentedChoices`) | 2–3 options, each a short word, compared at a glance. 44 dp, every option on screen. | Resolution, frame rate, data saver, display size, cursor speed, scroll speed and direction, band position |
| Value row + sheet (`LinkRow` + `ChoiceSheet` / `DescribedChoiceSheet`) | 4 or more options, long names, or options that need a sentence each to tell apart. The row shows the current value; the sheet picks. | Touch mode, each gesture |
| Switch (`SwitchRow`) | On or off only. At the row's end; white when on. | Show latency, band haptics, scroll inertia, incognito keyboard |
| Link row (`LinkRow`) | Edited on a page of its own. Its state (a count) and a chevron at the end. | Direct gestures, trackpad gestures |
| Link out (`ExternalRow` in `About.kt`) | Leaves the app for the browser. Label, the address under it, and the link-out icon where a chevron would be. | Source code |
| Index row (`LinkRow` with `icon` and `sub`) | A section of settings: icon, name, a summary of its values, chevron. A section with one page opens that page directly. | Settings' index; Radial menu opens its editor |
| Danger (`DangerButton`) | Cannot be undone. Last on the page, on its own surface. | Reset all settings, Remove from this phone |
| Note (`Help`, a row's `note`) | Only when an option's name cannot say what it does. One sentence, 12 sp, muted. | Data saver's Auto, cursor speed (trackpad only) |
| Heading (`SectionLabel`) | Only when a page has two or more surfaces; 13 sp muted above the surface. A page with one surface has none. | Scrolling: Finger, Wheel, Bands |

- A section with fewer than two settings is not made: its setting joins the nearest section.
- A surface of one value row takes no heading even among surfaces that have one, since the row's
  label already names it and a heading would say the word twice (Key on the custom shortcut page).
- In the stream's menu sheet, where a second sheet cannot open over the first, a setting that would
  take a value row and a sheet takes segments with a note instead (Touch mode in Live settings).

## Screens

A fold, an unfold or a new display size keeps the page, the section and any open sheet; only the
layout changes with the width.

### Page

A page is one column, at most 560 dp wide and centred (`Page`). Its 56 dp header holds a round
back button and the title (`PageHeader`). Detail, Add a Mac, the gesture maps and the radial menu
editor are pages.

### Home

- **Structure:** a large card for the chosen Mac, then a tile for every Mac: two to a row on the
  cover screen, so a name has room beside its dot, and three on the inner screen, where the card
  sits on the left (380 dp) and the tiles to its right. No heading above the tiles.
  The header holds the mark and the wordmark, then Add a Mac and Settings as round grey buttons.
- **Card:** the Mac's kind icon in a 44 dp square, its name (22) and its state (a dot and 12 sp
  words), a details button; then the display mode (three segments with a small drawing and the
  mode's name, 44 dp at the default sizes; taller or stacked at large ones, as Buttons and
  controls says) and the white round Connect with the arrow-into-a-frame icon.
- **The card's colour:** the colour of the last frame seen from that Mac, kept on the phone when a
  session ends (`SavedMac.tint`), washed in from the card's top-left corner. A Mac never streamed
  from, or whose last frame had no colour to speak of, stays grey.
- **Tiles:** the kind icon, then the state's dot before the name. The chosen tile has a 1.5 dp white
  ring. A Mac that could not be reached is faded. The last tile, outlined 1 dp, adds a Mac. A tap
  picks the card's Mac; a long press on a saved Mac opens its details.
- **States:** a Mac found on this network is green ("Ready · LAN"); a saved Mac not seen here is
  grey with what is known (Tailscale, last used); one whose connection failed shows why and a quiet
  Try again in place of the mode and Connect; one found and not paired shows what pairing does and
  a white round Pair. With no Mac at all: the mark in two rings, what to do on the Mac, and Add by
  address.

### Settings

- **Structure:** an index of sections, each opening its page; on the inner screen the index stays
  on the left and the chosen section shows in a surface on the right, its groups a step lighter.
  The two sit side by side only where the section's segments still show every label whole
  (`SPLIT_SETTINGS`); a narrower window, as in split screen, keeps the index and its pages. Side by
  side, Back leaves Settings. The chosen index row has a white ring, as a chosen tile does, and
  its icon and words stay where the other rows' are.
- **Sections:** Video (with the note that changes apply from the next connection), Touch & pointer,
  Gestures, Radial menu (opens its editor), Scrolling, About. Reset all settings is last, on its own surface.
- **About** holds no settings, so it comes after them; its row's summary is the version. Its page
  starts with the mark (64 dp), the name and the version with the commit it was built from, centred
  and on no surface; then Source code and License on one surface, and "Open source" with the number
  of notices on another: the fonts first, then one row per Maven group by name, each with its
  licences' short names at the end (joined with " / " when a group has more than one). A row opens
  a sheet with the notice in full, no wider than a page, each licence's text under its name when
  there is more than one; a font's sheet sets a line in that font at the title's size. Licence text
  (body, muted) is joined into paragraphs, not left at the file's 80 columns.

## Over the video: the stream's dialect

The parts over the video speak the same language with a few rules of their own, so they read on a
light screen and a dark one alike.

- **Plates:** every part sits on a plate (see Depth). White marks what is chosen: the radial menu's
  selected cell, the selected item's name tag above the plate.
- **The video's colour:** the plates of the radial menu and the menu sheet's head carry a wash of the
  colour under them, sampled from the same small copy of the video the scroll bands use
  (`BandTintSampler`, `BandTint.frame`): its hue at a set lightness, a third of the way in at the
  top-left corner, gone by 70 % (the sheet: 30 %, gone by 180 dp). A frame without colour gives no
  wash.
- **Radial menu:** one plate of 3 × 3 cells, the logo in the middle. No scrim over the video.
- **Menu button:** a 44 dp grey plate with the menu icon.
- **Status corner:** normally a 7 dp dot on a 22 dp plate, in the status colour. When something
  happens it opens into a short message beside the dot and closes back after a few seconds: slow
  ("Slow · 120 ms"), back to normal, AWDL on, back from a reconnect ("Back on LAN"). While
  reconnecting it stays open with the attempt, and the dot pulses. A tap shows the detailed stats.
- **Menu sheet:** the head names the Mac (icon, name, mode · route · frame rate) with Disconnect as
  a red power button. Then two surfaces of one-line 48 dp rows: what acts now (Show keyboard, Reset
  zoom while zoomed, Mac controls ›) and what opens a page (Display mode with its value ›, Live
  settings ›).
- **Scroll bands:** the halftone bands stay as shipped (`ScrollBands.kt`): the dots swell in the
  middle and taper to the ends and the inner side. Their ink comes from the video, never the brand.

## Brand

The mark is one sheet of paper with one step fold: the upper half slid along the fold, two straight
creases, and the narrow face between them. It borrows "ori" (fold) from the name's sound; the name
itself comes from oriel + pane. Three flat tones of one green, no outline, no shading:

| Plane | On dark | On white |
|---|---|---|
| Upper | #A0EEDA | #37E7BB |
| Lower | #18E2B0 | #07B086 |
| Fold face | #0D7E62 | #04674E |

The fold face is always painted under the upper plane, as the paper lies; drawn above it, the fold
reads wrong. On white the upper plane is 1.58:1, below the 3:1 guideline for graphics; that was
chosen by eye, and the lower plane and the fold face carry the outline.

- **Where it appears:** the launcher (`ic_launcher_foreground`, the mark at 0.78 of the circle it
  is fitted to; `ic_launcher_monochrome` for themed icons, the planes as white at 100, 62 and 36 %),
  the home header (26 dp, 8 dp before the wordmark), the empty home (26 dp in two rings), About
  (64 dp) and the radial menu's middle (26 dp), all from `oripane_symbol`. On the Mac: the Dock icon
  (`host/Bundle/make-icon.swift`, 0.63) and the menu bar (`Mark.swift`, a 16 pt template image whose
  planes are black at 100, 70 and 42 %; idle is the same at 45 %, and a problem still shows the
  warning triangle).
- **Wordmark:** "Oripane" in Inter Tight, weight 580, +0.03 em. Where the logo is shown large (a
  lockup of mark and word) the text is 0.73 of the mark's height, 0.15 of it away, and the i's dot
  is a small square turned 45° in the lower plane's green; that version has not been outlined yet.
  Small places, the app header among them, use the plain word.
