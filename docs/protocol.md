# Oripane protocol v1

The Mac host streams one display to one Android client, over the LAN or a Tailscale tailnet. Two channels:

| Channel | Transport | Direction | Carries |
|---|---|---|---|
| Control | TCP, host port 47800 | both | handshake, display configuration, keyframe requests, keys, text, clock sync, warnings |
| Media | UDP, host port 47801 | both | video fragments and parity (host → client), pointer input and feedback (client → host) |

All integers are big-endian. `b64` is standard base64 with padding.

## Control framing

Every control message is `u32 length` followed by `length` bytes. Before authentication the bytes are UTF-8 JSON. After `auth_ok` every message is sealed (see Session crypto) and the plaintext is UTF-8 JSON. Every JSON message has a string field `t`.

## Pairing (first contact)

Numeric comparison with a commitment, so that neither side can choose its key after seeing the other's.

1. C → H `{"t":"pair_commit","v":1,"name":<string>,"commit":b64(SHA256(clientPub))}`
2. H → C `{"t":"pair_hostkey","hostPub":b64(hostPub)}`
3. C → H `{"t":"pair_reveal","clientPub":b64(clientPub)}`. The host checks `SHA256(clientPub) == commit`.
4. Both compute `shared = X25519(own private, peer public)` and
   `sas = u32(SHA256("oripane-sas" ‖ hostPub ‖ clientPub)[0..4]) mod 1_000_000`, shown as 6 digits.
5. The user compares the two codes and approves on the host.
   H → C `{"t":"pair_ok","clientId":b64(16 random bytes)}` or `{"t":"pair_denied"}`.
6. `pairKey = HKDF-SHA256(ikm: shared, salt: hostPub ‖ clientPub, info: "oripane-pair", 32)`.
   The host stores `clientId → (name, pairKey)`; the client stores `(clientId, pairKey)` under the host's `hostId` from `auth_ok`, so one pairing serves every address the host is reached at.

`hostPub` and `clientPub` are fresh X25519 keys per pairing (32 raw bytes). After `pair_ok` the client continues with authentication on the same connection.

## Authentication (every session)

1. C → H `{"t":"auth_hello","v":1,"clientId":b64,"nonce":b64(32 random)}`
2. H → C `{"t":"auth_challenge","nonce":b64(32 random),"proof":b64(HMAC-SHA256(pairKey, "host" ‖ cn ‖ hn))}`, or `{"t":"auth_unknown"}` when the client id is not paired.
3. C → H `{"t":"auth_proof","proof":b64(HMAC-SHA256(pairKey, "client" ‖ cn ‖ hn))}`
4. H → C (sealed from here on) `{"t":"auth_ok","sessionId":<u32>,"hostId":b64(16 bytes),"name":<string>,"tailnet":[<IPv4 string>, …]}`

`hostId` is the host's lasting identity (random, kept in the host's state directory). `name` is the computer name. `tailnet` lists the host's IPv4 addresses in 100.64.0.0/10 on `utun` interfaces at the time of this message, empty without Tailscale. They arrive in the first sealed message, so they come from the authenticated host.

`sessionKey = HKDF-SHA256(ikm: pairKey, salt: cn ‖ hn, info: "oripane-session", 32)`. Proofs are compared in constant time. The client verifies the host's proof before sending its own.

## Discovery

The host advertises `_oripane._tcp` over Bonjour with the TXT record `id=b64(hostId)`, unless started with `--no-bonjour`. A client matches the `id` against the Macs it has saved to know a LAN address for one of them; Bonjour does not cross a tailnet, so a client away from home uses the `tailnet` addresses from the last `auth_ok`.

## Session crypto

AES-256-GCM with `sessionKey`. Nonce = `dir(u8) ‖ 0x000000 ‖ counter(u64)`, so the four directions never share a nonce:

| dir | Use | counter |
|---|---|---|
| 0x01 | control H → C | message index from 0 |
| 0x02 | control C → H | message index from 0 |
| 0x03 | media H → C | packet `seq` |
| 0x04 | media C → H | packet `seq` |

Sealed control payload = `ciphertext ‖ tag(16)`; the counter is implicit (TCP is ordered). AAD is empty for control.

## Media packets

```
u8  version (=1)
u8  type
u32 sessionId
u64 seq          per-direction, starts at 0, strictly increasing
...  ciphertext ‖ tag(16)      AAD = the 14 header bytes above
```

| type | Name | Direction |
|---|---|---|
| 1 | video fragment | H → C |
| 2 | video parity | H → C |
| 3 | input | C → H |
| 4 | feedback | C → H |
| 5 | hello | C → H |
| 6 | hello ack | H → C |

The client sends `hello` (empty plaintext) after `auth_ok` and then once per second. The host takes the source address of the newest authenticated `hello` as the client's media endpoint, which is what makes the emulator's NAT (client at `10.0.2.2`) work.

### Video fragment (type 1) plaintext

```
u32 frameId
u16 fragIndex
u16 fragCount
u8  flags        bit0 = keyframe
u64 captureUs    host monotonic clock, microseconds
u32 hostUs       capture → first packet sent, microseconds
u32 frameBytes   total Annex-B bytes of the frame
...  payload     fragIndex * F .. min(frameBytes, (fragIndex+1) * F)
```

`F` (fragment size) is 1100 bytes, so a packet is at most 14 + 25 + 1100 + 16 = 1155 bytes.

Frames are Annex-B access units in the codec the `stream` message names: H.264 or HEVC. The first keyframe and every keyframe carry the parameter sets in-band (SPS and PPS for H.264; VPS, SPS and PPS for HEVC).

### Video parity (type 2) plaintext

```
u32 frameId
u16 groupStart   index of the first fragment in the group
u8  groupCount   fragments in the group (1..255)
u16 fragCount
u8  flags
u64 captureUs
u32 hostUs
u32 frameBytes
u16 lenXor       XOR of the payload lengths in the group
...  parity      XOR of the group's payloads, each zero-padded to the longest
```

A group is `groupCount` consecutive fragments of one frame. With exactly one fragment of a group missing, its payload is `parity XOR (other payloads, padded)` truncated to `lenXor XOR (other lengths)`.

Every group gets a parity packet, a single-fragment frame included (its parity is a copy). The host halves the group size for keyframes. Frame ids are consecutive, so a gap between delivered frames means a frame was lost entirely: the client stops decoding non-keyframes and sends `keyframe`. It does the same for a frame still incomplete 200 ms after its first packet, which on a still screen no later frame would expose, and keeps at most 64 incomplete frames, dropping the oldest. A new stream starts with a keyframe, which may arrive before its `stream` message; the client asks for one only if none has come 300 ms later.

### Input (type 3) plaintext

A packet carries the newest up to 4 input events, oldest first. The host applies events whose `inputSeq` is greater than the last one it applied, in order, so a lost packet is covered by the next.

```
u8 count
repeat count:
  u32 inputSeq
  u8  kind
  ... kind-specific
```

| kind | Fields | Meaning |
|---|---|---|
| 1 pointer | `u8 action, f32 x, f32 y` | action 0 move, 1 down, 2 drag, 3 up, 4 down continuing the previous left click (the host counts it as the next click of a double or triple click, at most 3); x, y normalized 0..1 on the streamed display |
| 2 scroll | `f32 dx, f32 dy` | pixels on the client, times the client's speed setting (and negated for the reversed direction), positive = content moves down/right |
| 3 right click | `f32 x, f32 y` | |

### Feedback (type 4) plaintext

```
u32 lastFrameId
u32 packetsReceived   since the previous feedback
u32 packetsLost       since the previous feedback (gaps in seq)
u32 framesRecovered   by parity, since the previous feedback
u32 framesDropped     incomplete or skipped, since the previous feedback
u32 decodeUs          mean
u32 totalUs           mean capture → rendered, using the clock offset
```

Sent every 200 ms.

## Control messages after `auth_ok`

| t | Direction | Fields |
|---|---|---|
| `display` | C → H | `w`, `h` (pixels), `dpi`, `hz`, `mode` (`"extend"`, `"mirror"` or `"match"`: the Mac's main display becomes a mirror of a virtual display shaped like the panel); optional `scale` (stream size relative to `w`×`h`, 0.25–1, default 1), `fps` (1–120, default 60), `uiScale` (extend and match: the virtual display is `w`×`h` × 2 / `uiScale` pixels at HiDPI, 1.5–2, default 2), `codecs` (array of the codecs the client can decode at `w`×`h`, most preferred first, e.g. `["hevc","h264"]`; the client lists `"hevc"` only when a hardware decoder takes it; the host streams HEVC when the array lists `"hevc"` and H.264 otherwise, also when it is omitted or not an array of strings). Numbers may arrive as integers; a non-number counts as omitted. The host's `--scale` / `--fps` / `--codec` win |
| `stream` | H → C | `w`, `h`, `fps`, `codec` (`"h264"` or `"hevc"`), `mode`; optional `fallback` (`"match"` when match was asked for and the host could not set it up, so it streams `mode` `"mirror"`) — sent whenever the stream (re)starts; the client resets its decoder |
| `keyframe` | C → H | — |
| `key` | C → H | `code` (Android `KeyEvent` keycode), `down` (bool), `meta` (Android meta state) |
| `text` | C → H | `s` (string typed through the soft keyboard; while an on-screen modifier is on, typed characters that map to a key go as `key` with the modifier in `meta` instead) |
| `system` | C → H | `action`: a Mac feature by name, one of `missionControl`, `appExpose`, `spaceLeft`, `spaceRight`, `showDesktop`, `apps`, `launcher`, `screenshot`, `lockScreen`, `previousApp`, `volumeUp`, `volumeDown`, `mute`, `playPause`, `nextTrack`, `previousTrack`, `brightnessUp`, `brightnessDown`. The host carries it out on the streamed display, moving the cursor there first when it is elsewhere; an unknown name is logged and ignored |
| `limit` | C → H | `maxBitrate` (bps): a cap on the host's bitrate, 0 or less for none; a positive value below 2000000 counts as 2000000. Sent after `auth_ok` before `display`, and again when the cap changes. Unlike `display`, it does not rebuild the stream |
| `pause` | C → H | — The client has nothing to show the video on (sent half a second after its surface goes, so a fold that replaces the surface does not send it). The host stops capture and encoding and keeps the session and its virtual display; `display` messages meanwhile are only remembered |
| `resume` | C → H | — The surface is back. The host builds the stream again from the last `display` and sends `stream`; ignored when not paused |
| `ping` | C → H | `c` (client µs) |
| `pong` | H → C | `c` (echoed), `h` (host µs) |
| `warn` | H → C | `code` (`"awdl"`), `active` (bool) |
| `bye` | both | `reason` (optional). H → C `"replaced"`: another client took the session over, and this one should not reconnect. H → C `"stream_failed"`: the host could not start or keep the video going (capture, encoder); the client may reconnect. It comes with `detail`, a short reason for logs that a client need not show. A host that is shutting down closes without `bye` |

Clock offset: the client keeps the `pong` with the smallest round trip in the last 10 and sets `offset = h − (c + now) / 2`.

The host ends a session once nothing authenticated (a control message or a media packet) has arrived for 5 s, without `bye`. The client's `ping` and `hello`, each once a second, keep it alive.
