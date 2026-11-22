# Security design

What Oripane protects, where it keeps its keys, and what it leaves out of its logs. This is a
design document; the byte-level handshake is in [`protocol.md`](protocol.md), and reporting a
vulnerability is covered in [`SECURITY.md`](../SECURITY.md).

## Threat model

The Mac side holds the Screen Recording and Accessibility permissions, and a paired phone sees the
Mac's screen and clicks and types on it. The realistic concerns, in order:

1. **Anyone who can reach the Mac's port.** The host listens on every interface and advertises
   itself over Bonjour, so everyone on the LAN, on the tailnet, or on any network that routes to
   port 47800 can ask it to pair. None of them should get the screen or input without the person
   at the Mac agreeing.
2. **A machine in the middle at pairing time.** Pairing runs before there is any shared key, over
   the same network.
3. **The phone's storage leaving the phone.** A cloud backup or a transfer to a new phone would
   carry the pairing keys with it.
4. **A leak through logs.** A `host.log` or a `logcat` capture is shared far more casually than a
   key is.

Out of scope: other programs that run as the same macOS user (see "Same-user programs" below), a
rooted phone or one with an attacker holding it unlocked, and someone at the Mac itself.

## Pairing

Pairing is a numeric comparison: the phone and the Mac each show a six-digit code derived from both
sides' fresh X25519 keys, and the person at the Mac is asked to approve only if the two match. The
phone commits to its key before it sees the host's, so a machine in the middle has to fix its own
keys before it can know which code they produce: it gets one guess per pairing, which matches with
a chance of one in a million. The derivation is in
[`protocol.md`](protocol.md#pairing-first-contact).

Only the Mac can approve, in the app's "Pair with a new phone?" dialog or on the terminal of
`tools/host run`. The phone only shows its code and waits; nothing on it can approve. The dialog
and the terminal guard against an approval nobody meant:

- **Allow works one second after the request appears**, and the terminal ignores an answer typed
  within a second of its question. A click or a `y` meant for the request before cannot approve
  this one.
- **Allow has no keyboard shortcut.** Return typed for another app, once the panel has been
  clicked, would otherwise approve a phone. Esc denies once the panel has been clicked.
- **The panel does not take the keyboard.** It opens in front without activating the app, so typing
  elsewhere stays there.
- **A denied address is refused for 30 seconds** without asking again, so a machine on the LAN
  cannot keep the question on the screen.
- **One question at a time.** Up to three more requests wait behind it, one per address; any more
  are refused without asking.
- **The phone's name is cleaned where it arrives.** Control characters and line separators are
  removed and the name is cut to 64 characters before it reaches the dialog, the terminal, the log
  and `pairings.json`; on the terminal the code is printed on its own line before the name, so a
  name cannot pose as the code. The name the phone sends is its model (`android.os.Build.MODEL`),
  not a name the owner gave it.
- **A phone that gives up withdraws its question.** An Allow after that reaches no one.

The `--auto-approve` option accepts every request without asking. It exists for the emulator and
the test host (`tools/testhost` passes it only with the synthetic test pattern, never with the
screen).

A pairing does not expire. "Unpair…" in the menu's "Paired Phones" deletes its key on the Mac and
disconnects the phone if it is connected; that phone has to pair again to come back. Removing a
Mac on the phone ("Remove from this phone") deletes only the phone's copy.

### What the phone trusts

The phone connects by address in three ways: an address typed into "Add a Mac", a tile on Home for
a Mac found over Bonjour that is not saved yet, and the launch extras under "Test hooks" below. On
each it pairs with whatever answers at that address and approves the request. The comparison protects the Mac: a host that approves itself becomes a new saved Mac on the
phone, and the phone then streams that host's screen and sends it what is typed. Comparing the code
on the phone with the one on the Mac's dialog is what tells the two apart.

Once saved, a Mac is known by its `hostId`, and a host has to prove the saved pairing key before
the phone streams from it. `hostId` is public (the Bonjour TXT record carries it), so a host that
claims a saved Mac's `hostId` may update that record only when it authenticated with that record's
key, or when it is the re-pairing of that record described next. A host that claims it otherwise
is refused, and the record keeps its key and its Tailscale addresses.

When a saved Mac answers `auth_unknown` (it no longer has the pairing), connecting from its saved
record (its tile on Home) stops there: the phone does not pair again on its own. Connecting by
address is different. If a saved Mac used that address, its key is tried first, and on
`auth_unknown` the phone pairs again; a host that then reports that saved Mac's `hostId` takes over
its record, key and Tailscale addresses included. A machine that gets a LAN address a saved Mac used
and announces itself over Bonjour without that Mac's `hostId` shows up as a Mac not saved yet, and
connecting to it leads there. The code comparison matters here as at first contact.

## Authentication and encryption

Every session starts with mutual proof of the pairing key: the host proves it first, the phone
checks that proof before sending its own, and proofs are compared in constant time. The session key
is derived from the pairing key and two fresh nonces; from `auth_ok` on, every control message and
every media packet (video to the phone, pointer input and feedback to the Mac) is sealed with
AES-256-GCM. Each of the four directions has its own nonce space, and a media packet's header is
authenticated with it. The details are in [`protocol.md`](protocol.md#session-crypto).

- **Replays are dropped.** The host applies a media packet only when its sequence number is greater
  than the last one it accepted, and a control message decrypts only at its own position in the
  stream.
- **Media from anyone else is dropped.** The session id travels in the clear, so anyone on the path
  can send packets that carry it; they fail to decrypt and are dropped, counted in one log line a
  second at most.
- **No forward secrecy beyond the pairing key.** The nonces travel in the clear, so whoever holds a
  pairing key and recorded a session can decrypt it, including sessions from before an unpairing.
- **Before `auth_ok` the control channel is plain JSON.** It carries the pairing messages, the
  phone's client id and name, the proofs and the nonces; none of them reveals a key.

## What a paired phone can do

A paired phone can do, through Oripane, what the person at the Mac can do with a mouse and a
keyboard:

- **See the screen.** In Extend, the virtual display the session adds; in Mirror, the Mac's main
  display; in Match, the main display, which then mirrors a display shaped like the phone. Whatever
  is on that display is streamed, the cursor included.
- **Click, drag, scroll and right-click** on the streamed display.
- **Press any key, with any modifiers, and type text.** Keys and text go to whichever app has the
  keyboard focus, on any display.
- **Run the Mac actions by name**: Mission Control, App Exposé, Spaces, Show desktop, Apps,
  Launcher, Screenshot, Lock screen, Previous app, volume, playback and brightness. The host knows
  this list and ignores any other name; the phone cannot name an app or a command to run.
- **Change the display**: add a virtual display (Extend and Match), and in Match rearrange the Mac's
  own screen until the session ends.
- **Take the session over.** One phone streams at a time; a second paired phone that connects
  replaces the first, which is told not to reconnect.

Without the Accessibility permission, or with `--dry-run-input`, input is logged and never reaches
the Mac. The protocol carries no files, clipboard or audio, but through the screen and the keyboard
a paired phone can do anything the logged-in user can, such as open Terminal and run a command.

## Where the keys live

### On the Mac

The host's state directory is `~/Library/Application Support/oripane` (or `ORIPANE_STATE_DIR`),
created with mode 0700. It holds:

- `pairings.json` (0600): each paired phone's id, name, pairing key and dates, as plain JSON. It is
  not in the Keychain.
- `host.json` (0600): the host's lasting `hostId`, which is public.

The log, `~/Library/Logs/Oripane/host.log`, is 0600 too, and moves to `host.log.1` past 10 MB.

### On the phone

Each saved Mac's pairing key sits with its record in the app's private shared preferences, readable
only by the app. It is not encrypted with the Android Keystore: the private storage is what keeps
it. It never leaves that storage:

- `android:allowBackup="false"` stops the cloud backup and `adb backup`.
- `data_extraction_rules.xml` excludes every domain from both the cloud backup and the device
  transfer, since `allowBackup="false"` alone does not stop a transfer to a new phone on
  Android 12 and later.

A new phone therefore starts with no saved Macs and pairs again.

The release APK is signed with the debug key of the machine that built it. Anyone who has that
machine's `~/.android/debug.keystore` can build an update the phone accepts over the installed app,
and that update reads the pairing keys.

## Same-user programs

macOS knows the app that the Screen Recording and Accessibility permissions were granted to by its
code signature. Oripane does not defend against other programs that run as the same user, and two paths
let one of them use Oripane's permissions:

- **The signing key.** `tools/app install` signs with a self-signed certificate, "Oripane Signing",
  in a keychain of its own, `~/Library/Keychains/oripane-signing.keychain-db`. The keychain's
  password is in `~/Library/Application Support/oripane-signing/password` (mode 0600, in a 0700
  folder), so an install signs without asking. Any program running as you can read that password,
  sign itself as Oripane, and get the Screen Recording and Accessibility permissions you gave
  Oripane. To remove the key, delete that folder and the keychain; the next install creates a new
  certificate, and the permissions have to be granted again. With `ORIPANE_SIGN_IDENTITY` set,
  `tools/app install` signs with that certificate instead and creates neither the keychain nor the
  password file.
- **The pairing keys.** `pairings.json` is readable by any program running as you. With a key from
  it, a program can connect to the host as that phone, from this Mac, and get the screen and input
  without asking anyone.

## What is not logged

Both sides log one `event key=value` line per event, the host to `host.log` and stderr, the phone
to `logcat`. The lines are meant to be shared in a bug report, so they leave out what could spell
a secret or retrace what was done:

- **No keys and no text.** A key logs whether it went down and how many modifiers came with it, not
  which key; typed text logs its length. On the phone the soft keyboard logs the length of what it
  sent or dropped. Two lines do name a key. The host logs the Android key code of a key it has no
  Mac key for (`input key_unmapped`) and drops the key: the numeric keypad of a physical keyboard
  is one, so digits typed on it reach the log one by one and never reach the Mac. The phone logs
  the key code and modifiers of a custom shortcut sent from the radial menu or a gesture.
- **No pointer path.** Pointer moves and scrolls are counted in the periodic `stats` line rather
  than logged one by one; clicks log their click count, not where they landed. When input does not
  reach the Mac, with `--dry-run-input` or while the Accessibility permission is missing, the host
  logs each event instead, with its position on the display: until Accessibility is granted, the
  log holds where every tap landed.
- **No keys or proofs.** No pairing key, session key, nonce or proof is written to a log.
- **No video.** Frames are logged by their size and timing.

They do record, so that a connection can be followed: addresses on both ends, the phone's model
name, the Mac's computer name, the names of Macs found over Bonjour, the six-digit pairing code
(useful only during the pairing it belongs to), and the names of the Mac actions.

Text from the other side is not escaped everywhere. The host cleans a phone's name where it
arrives, and before authentication an unexpected message reaches the log escaped, inside a
`control protocol_error` line. After authentication, strings the phone sends (an unknown message
type, an unknown action name, the display mode) are logged as received, line breaks included; so,
on the phone, are the Mac's name and the names of services found over Bonjour, which anyone on the
LAN can announce. A log line that holds such text is not proof of what happened.

## Test hooks

The phone's app reads launch extras (`--es host`, `--ez connect`, `--es set`, `--es seed`,
`--ei port` and others, listed in `MainActivity.onCreate`) so that `tools/emu` can drive it. They
are read in the release build too, from any intent that starts the app, and the app's launcher
activity is exported. Another app on the phone can therefore:

- start Oripane connected to the most recently used saved Mac (`--ez connect true --ez saved true`)
  in a mode it picks (`--es mode`), with no question on either side. On the Mac that adds a virtual
  display (Extend) or rearranges the main screen (Match), and a phone already streaming is
  disconnected and told not to reconnect;
- start Oripane connecting to an address it chooses (`--es host`), which leads to a pairing as in
  "What the phone trusts" above, re-pairing of a saved Mac at that address included;
- change settings (`--es set`), Incognito keyboard among them, or add placeholder Macs
  (`--es seed`).

It cannot read a pairing key or see the stream, and every pairing it leads to still needs the
person at the host to approve it, with the code shown on the phone.
