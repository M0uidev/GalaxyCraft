# Multiplayer online: playing with a friend outside your network (design)

Branch `feat/mp-online` (from `feat/mp-all` 1a817dd). Design only: nothing here is implemented, `Protocol.VERSION`
stays 8. Written 2026-10-15. Follows MP-1..MP-4 (`...-mp2-design`, `...-mp3-design`, `...-mp4-design`).

## 1. Where we are

All multiplayer traffic is **ordinary Minecraft custom payloads** (`net/MpPayloads`) over the vanilla connection of a
LAN-published integrated server. There is no socket of our own. Consequences:

- Anything that gets a Minecraft client to a Minecraft server also gets us multiplayer: the vanilla *Direct
  Connection* / *Add Server* screens already work with the mod today. The only thing missing for "a friend outside
  my network" is **reachability** (a path from the friend's machine to the host's TCP port) and **UX** around it.
- Vanilla login applies: the host's LAN world verifies each guest's Microsoft session with Mojang
  (`GXC_OFFLINE_LAN` turns that off, dev only, `GalaxyCraft.java`). Real players have a session, so this stays on.
- The friend runs a **full game**, not a thin client: Minecraft + the mod + Dolphin + the SMG2 disc image
  (the guest's own Dolphin draws the planets; `tools/gxmp.sh guest`). So the friend is already a player who went
  through the launcher install. "Join" can therefore live in the launcher and in the game, no web client.
- `Hello(version, worldId, galaxyJson)` / `HelloAck` already refuse a mod-version mismatch (`Protocol.refusal`).

## 2. Goals / non-goals

Goals: a friend with the game installed joins my world over the internet in under a minute; I choose who gets in;
no cost I have to run for the project; nothing leaks my home address to strangers; playable on a normal home
connection (host upload 5 Mbit/s or less).
Non-goals: public servers, dedicated server hosting, matchmaking, voice, more than `MAX_PLAYERS` (8, realistically 2-4).

## 3. Connection options

| | Port open / UPnP | Own relay | VPN (Tailscale / ZeroTier) | Invite code |
|---|---|---|---|---|
| What | Host forwards TCP 25565 (router UI or UPnP request); friend types `ip:port` | We run a public TCP forwarder; both sides dial *out* to it | Both machines join a private network; friend dials the host's private IP | A UX layer, not a transport: one short string that encodes where to connect |
| Works behind CGNAT (many ISPs, mobile hotspots) | **No** | Yes | Yes (NAT traversal + relays of the VPN vendor) | depends on the transport under it |
| Host needs router access | Yes (UPnP is often off, double NAT breaks it) | No | No | No |
| Home IP exposed | **Yes, to whoever gets the address** | No (relay sees it) | No (only members of the private network) | n/a |
| Who can even try to connect | Anyone on the internet (port scanners find 25565 within hours) | Anyone with the room id | Only invited members | n/a |
| Infra we run / pay | None | A VPS (about 5 USD/month), bandwidth (a 4 MB planet per join), abuse handling, uptime, updates | None (free tier) | None |
| New code in the game | UPnP client (library) + address display | Relay server + client dialer + room protocol + auth | None to connect; optional: show the address | Encode / decode + a field in the launcher |
| Friend installs / does | Nothing | Nothing | Install the VPN, accept an invite (2-3 min, once) | Pastes a code |
| Latency | Direct (best) | +1 hop through the relay | Direct when NAT traversal works, else vendor relay (still fine for this game) | n/a |
| Main failure | CGNAT, router, firewall: "does not work" with no way to diagnose | Our server down = nobody plays; we pay for everyone's traffic | Friend refuses to install a VPN; free-plan user limits | n/a |

Third-party tunnels (playit.gg style) are the "relay" column without us paying, but they put an unvetted vendor
agent and a public address in the path; not recommended as the default.

## 4. First recommendation (SUPERSEDED by section 9): private VPN (Tailscale) + invite code in the launcher

> The user's answers (section 9) made Tailscale conditional on a zero-step install; the feasibility study in
> section 9 found it does not meet that condition. Read section 9 for the current recommendation.

**One option: Tailscale as the transport, wrapped by a short invite code.** Reasoning: it is the only option that
(a) works for every home connection, including CGNAT, (b) costs nothing to run, (c) is closed by default (only people
I invite can reach my port, which removes the whole "public Minecraft server" attack surface), (d) needs no new
networking code, because the game already speaks plain Minecraft over TCP. The price is that the friend installs
one more app once. A relay would remove that step but turns a hobby project into a service with a bill, uptime
and abuse duties; if the game later grows enough to justify it, the invite code (below) is designed so a relay can
replace the transport without touching the rest.

ZeroTier is an equal fit technically; Tailscale is picked for the simpler invite flow (node sharing by link) and
account login the friend probably already has (Google / Microsoft).

### 4.1 What the player sees

**Host** (pause menu while in a world, next to Vanilla's *Open to LAN*): **"Open to a friend"**.
1. If Tailscale is not running, a panel explains it in two lines with a *Get Tailscale* link, and re-checks
   (`tailscale status --json`) every few seconds.
2. When it is: the world opens on the chosen port (default 25599, as `GXC_LAN_PORT`) and the screen shows
   **a code** like `SMG-4KQ7-2MXA-9` plus a *Copy* button and a *Invite a friend to my Tailscale* link
   (opens Tailscale's share/invite page for this machine; the host sends the link and the code in any chat).
3. A list "Friends in this world" with **Kick**; an *Accept new friends* toggle (see 5).

**Guest** (launcher main screen, and in game under *Multiplayer*): **"Join a friend"** with one text field:
1. Paste the code (or an `ip:port`, which also works for LAN and for people who prefer to type).
2. The launcher checks that Tailscale is installed and logged in; if not, same two-line help panel.
3. *Join* starts the game with `--quickPlayMultiplayer <ip>:<port>`; the loading screen shows planet progress
   ("Getting planet 2 of 3, 1.4 MB / 3.9 MB") instead of a silent wait.
Errors are written for players: "Your friend's world is not open", "Your friend runs mod version X, you have Y.
Update", "Tailscale is off".

The code is not a secret and is not an auth token; it only encodes where to dial: base32 of the host's tailnet IPv4
(4 bytes) + port (2 bytes) + 1 check character. Access is controlled by the VPN membership plus vanilla login
(and the host's accept toggle).

### 4.2 Costs

| Piece | Work |
|---|---|
| Code encode / decode (pure, unit tested) | 0.5 day |
| Launcher: *Join a friend* screen, Tailscale detection, error texts (JS + tests in `launcher/test`) | 1 day |
| Game: *Open to a friend* screen (pause menu mixin / screen), publish on a port, show code, friend list + kick, accept toggle | 1.5 days |
| Transfer pacing + progress for slow links (section 6) | 1-1.5 days |
| Real test over a VPN between two machines (the user and a friend, or a second PC; not possible with one machine) | needs the user |
| Total | about 4-5 working days, 0 USD/month |

Dependencies we do not control: Tailscale's free plan limits (users per tailnet; node sharing counts differently
from tailnet members, check current numbers before promising "N friends"); the friend's willingness to install it.

### 4.3 Alternatives kept in the design (not built now)
- **Direct address**: the guest field already accepts `ip:port`; a host with an open port or a public IPv6 address
  gets direct play for free, no extra work.
- **Relay later**: if a relay is ever built, the code becomes `room id + token`, the dialer is the only new client
  piece, and sections 5 and 6 apply unchanged.

## 5. Security

Threat model: strangers on the internet, a malicious friend, a malicious host.

- **Reachability is the first defense.** Over a VPN the port is invisible to the internet. This is the main reason
  for the recommendation. (A forwarded port would also need a rate limiter, a connection cap and a whitelist.)
- **Keep Mojang authentication on** for any non-loopback join. `GXC_OFFLINE_LAN` must stay a dev switch; add a test
  that a normal build does not read it for remote addresses.
- **Accept new friends** (host toggle, default on while the screen is open): the first time an account joins, the
  host sees "Amigo wants to join" and approves; approved UUIDs are remembered per world (`galaxycraft/friends.json`).
  Needed because a VPN share link can be forwarded by the friend. Uses the existing `Hello`; the host replies
  `HelloAck(ok=false)` with a text for refusals (`HelloAck` gets a `reason` string only if we bump the protocol,
  which this branch must not; until then the refusal is a disconnect message, which is what `Protocol.refusal` does).
- **Guests have no authority they should not have**: the server already validates positions (`MAX_COORD`,
  `MAX_SPEED`), rate-limits edits, bounds `Edits`, caps asked planets. Over the internet also cap per-guest
  payload bytes per second (a token bucket in `MpServer`) and drop a guest that exceeds it twice. Vanilla keeps
  commands off for LAN guests unless the host enables cheats; do not change that.
- **Guest trusting the host** (the less obvious direction): the guest decodes planet bytes, mob frames and ship
  records sent by the host. `PlanetXfer.MAX_BYTES` (96 MB) bounds the download; the gzip decode must also bound the
  *decompressed* size (zip bomb) and the codecs must bound counts. Review `PlanetXfer.Assembler`, `MobsCodec`,
  `EditCodec`, `StarCodec`, `ShipCodec`, `CargoCodec` for unbounded allocations before the first internet test.
  The UI says "Only join friends you trust" on the join screen.
- **Privacy**: a Tailscale IP is not the home address. Never log or display the public IP; the code contains only
  the tailnet IP. No telemetry, no account of our own.
- **Home network**: opening the world to a friend does not expose anything else on the host's machine; the shared
  memory bridge (`/dev/shm`) and the Dolphin process are local and unaffected.

## 6. Bandwidth of today's traffic, and what to compress

Figures are from the code (sizes of the writers in `MpPayloads`, `MobsCodec`; rates from `MpClient.SEND_TICKS=2`,
`MpMobs.EVERY=2`, drops 5 Hz, planets `PlanetXfer.BUDGET_PER_TICK`) and the sizes of real saved planets
(1.3-3.9 MB gzip each, `saves/*/galaxycraft/planets`). They are estimates; the first task of the plan is a
`GXC_MP_TRACE` byte counter per payload type so we measure instead of guess.

| Stream | Direction | Rate | Size | Steady load (2 players) |
|---|---|---|---|---|
| Player `State` / `Peer` | both | 10 Hz per player | up to 256 B, about 60 B typical; fan-out N*(N-1) | about 1-2 KB/s |
| `Where` (planet, feet, look) | guest -> host | 10 Hz | 28 B | 0.3 KB/s |
| `Mobs` snapshot | host -> guest on that planet | 10 Hz | about 74 B + entity data per mob (12 floats of frame) | 30 mobs: 22-35 KB/s |
| `Drops` | host -> guest | 5 Hz | about 40 B + stack per drop | under 2 KB/s |
| Ship `ShipPose` (pilot) | pilot -> host -> others | 10 Hz | about 80 B | 1-2 KB/s while flying |
| `Edits` | both | on change | cell deltas, deflate | bursts: 100 B - 16 KB, per-tick cap 16 KB |
| `Use`, `Break`, `Steer`, `Star*`, `Station*` | both | on action | 20-60 B | negligible |
| **Planet download** (`PlanetChunk`) | host -> guest | on join / when a planet is first held | **1.3-4 MB each; up to 8 held (MAX_ASKED)** | 10-25 MB worst case once, paced to 64 KB/tick = 1.3 MB/s |

Reading: the steady state is **small** (well under 50 KB/s per guest, dominated by mobs); a 1 Mbit/s uplink holds
it. The problem is the **join burst** and what it does to everything else:

1. **Pacing is fixed at 1.3 MB/s** (`BUDGET_PER_TICK`), tuned for LAN. On a 5 Mbit/s (0.6 MB/s) uplink the Netty
   send queue fills, and because poses, mobs and planet chunks share **one TCP stream**, head-of-line blocking
   delays every pose and mob packet by seconds while a planet downloads. *Change*: pace per connection from real
   back-pressure (stop queuing when the channel is not writable / bytes pending exceed N), start at about
   300 KB/s for non-loopback guests, and send the guest's current planet first (the guest already says which it
   needs; add priority: current, then nearest, then far views).
2. **A planet is re-downloaded whole after any edit** while the guest was away (etag mismatch; already listed as
   a known limit in MP-2). Over the internet that is 4 MB for one broken block. *Change*: a per-planet edit log
   on the host (seq + deltas, the `Edits` encoding) and a `PlanetAsk` that carries the guest's last seq: the host
   answers with the delta if the log still covers it, the full planet otherwise. Biggest saving, moderate work.
3. **Mobs frame floats** are the only steady cost worth shrinking: quantize position relative to the planet to
   16-bit steps (about 3 mm at radius 200) and angles to 1 byte, send velocity-less deltas when a mob stands
   still (skip unchanged mobs, full refresh every 2 s). Expected 3-4x less (about 7 KB/s for 30 mobs).
   Vanilla's connection compression (threshold 256 B by default) already deflates the big packets, but floats
   compress poorly, so quantizing is what helps.
4. **Planet bytes are already gzip**; do not deflate again. A better codec (zstd) on the *unpacked* cell data
   might give another 20-30 % but costs a native dependency; not worth it for v1.
5. **Per-guest budget meter** (section 5) doubles as a diagnostic: the host's friend list shows each guest's
   KB/s and ping so "it lags" has an answer.

Latency: the model is host-authoritative with client prediction for edits (MP-2), so building feels instant;
`Use` (doors, chests), `Break` and mob blows wait for the host's answer (a round trip, 40-150 ms on a normal
connection: fine). Mobs glide adaptively (`MpMobs.Remote.glide`), which tolerates jitter up to about 200 ms.
Flying a ship: pilot-authoritative with 10 Hz poses and extrapolation, fine; a remote *passenger* is not built.

## 7. Plan outline (after approval; not started)

1. Byte counters per payload (`GXC_MP_TRACE`), measure a real join on loopback and with a throttled link
   (`tc netem` on the loopback or a second PC): turns section 6 into numbers.
2. Back-pressure pacing + current-planet-first + progress text on the guest's loading screen.
3. Hardening review of the guest-side codecs (bounds), token-bucket per guest, accept list.
4. Code encode / decode + launcher *Join a friend*.
5. *Open to a friend* screen + friend list.
6. Planet delta log (largest bandwidth win; may be its own spec).
7. Mob quantization.
8. Real test through Tailscale with the user and a friend; checklist in `docs/MULTIJUGADOR.md`.
No `Protocol.VERSION` bump is needed for 1-5 (they change pacing, UI and the connection path); 6 and 7 change
payload layouts and need one bump, to be made by the orchestrator at merge time.

## 8. Open questions for the user (answered, see section 9)

1. Is "the friend installs Tailscale once" acceptable, or is zero-install a hard requirement (then: relay, with its
   cost and upkeep)?
2. How many friends at once do you expect (2-3 vs 8)? Decides whether the free VPN plan is enough.
3. Do you want the host's *Accept new friends* prompt, or is the VPN invitation enough?

## 9. Feasibility study and FIRM recommendation (2026-10-15, after the user's answers)

User's answers: (1) Tailscale only if the launcher can install it by itself and it works the same on Windows and
Linux; otherwise an alternative with **zero manual steps for the friend**. (2) Design for **8 guests** (maybe more).
(3) **No accept list**: like Minecraft, you join with an address/code and you are in; keep Mojang authentication
and the per-guest limits.

### 9.1 Tailscale: what is feasible and what is not

Verified (sources at the end; Linux parts tested here):

| Question | Finding |
|---|---|
| Install without root, Linux | Works. The static tarball (`tailscale_1.104.1_amd64.tgz`, 42 MB `tailscaled` + 33 MB `tailscale`) ran as uid 1000 with `--tun=userspace-networking --statedir=... --socket=...`: it starts, no TUN, no root, reports `Logged out.` (tested). Needs a short socket path (unix socket limit); TPM and UDP-buffer warnings are harmless. Tested here: starting only. NOT tested: login, joining a tailnet, forwarding (no Tailscale account in this session). |
| Install, Windows | The MSI takes `/qn /norestart` with `TS_NOLAUNCH=1 TS_UNATTENDEDMODE=always`, but it installs a **system service: UAC elevation is unavoidable** (one click for the friend), and it registers no key by itself: the key goes in a following `tailscale up --authkey=`. Not testable here (no Windows). A user-mode alternative on Windows is the same `tailscaled` in userspace mode from the data folder (not documented as supported; untested). |
| Embedding | `tsnet` / `libtailscale` are BSD-3-Clause: we may bundle and redistribute with the notice. Go toolchain needed to build a helper (`gxnet`); +about 30 MB per OS in the launcher download. |
| Login without the friend making an account | Only through **auth keys** (pre-approved, tagged, ephemeral, reusable, max 90 days). A key can be generated only by an Owner/Admin of a tailnet (UI or API/OAuth client). So the **host** needs a Tailscale account and must generate keys; the key travels inside the invite (a secret, long string, not a short code). The friend would otherwise need their own account (Google/Microsoft login) = a manual step. |
| Safety of that key | A friend joins the **host's** tailnet. Without an ACL restricting the key's tag to `host:25599`, the friend's machine can reach every device of the host's tailnet (SSH, NAS...). The host must edit the tailnet policy file once. Not acceptable as a default for a game. |
| Free plan limits | Personal plan: 6 users, unlimited devices (older pages say 3 users / 100 devices; the official docs say 6; non-commercial use only). Tagged devices should not count as users (not stated on the page; unverified). 8 guests on a plan that says 6 users is therefore uncertain. |

Verdict: **not viable as "zero manual steps"**: the host needs an account, must mint keys and write an ACL, the
friend needs UAC on Windows, and "8 guests on the free plan" is unverified. It also makes every host depend on
Tailscale's account system and terms. Tailscale remains a fine *optional* way to reach a host (the guest field accepts
`ip:port`), but it is not the product path.

### 9.2 Alternatives without manual installation

| | ZeroTier | Own hole punching (libp2p / STUN + QUIC) | **Minimal relay we run** |
|---|---|---|---|
| Friend's steps | Install ZeroTier One (admin / driver install) and join a network id, or embed libzt | None if embedded | **None** |
| Host's steps | An account at ZeroTier Central and a network; the free plan is now **10 devices** (official pricing page), below 8 guests + host once more people play | None | **None** (one click, gets a code) |
| Works behind CGNAT / symmetric NAT | Mostly (own relays) | Only about 80-90 % of pairs; the rest **needs a relay anyway** | Always (both sides dial out) |
| Code to write | Small, but an external install and account | Large (UDP reliable transport, NAT discovery, signaling server, relay fallback): the signaling server and fallback relay are the same server we are trying to avoid | About 300 lines server + a Java client in the mod |
| Cost | Free up to 10 devices, paid after | Same server cost plus the large code | A small VPS (about 5 USD/month; traffic below) |

Hole punching saves relay bandwidth but cannot remove the relay, so it is a later optimization, not an alternative.

### 9.3 Recommendation: a minimal dumb relay, speaking plain TCP, both ends inside the mod

**Firm recommendation: run one tiny TCP relay (`tools/relay/`) and put the relay client in the mod (Java, so it is the same on
Windows and Linux, no install, no launcher dependency, also works when started from the Mojang launcher).**

How it works:
- **Host** ("Open to a friend", pause menu): the mod publishes the LAN world as today, opens one outbound TCP
  connection to the relay (`HOST <room> <secret>`), and gets a **short code** = the room id (10 base32 chars, 50 bits,
  random, e.g. `SMG-4KQ7-2MXA-9`; the relay hostname is built in, or carried as a prefix for self-hosters).
- **Guest** ("Join a friend", in the game and in the launcher): paste the code. The mod starts a loopback listener
  on `127.0.0.1:<port>` and connects the screen to it; each connection to it goes `JOIN <room>` to the relay, the
  relay tells the host's control connection `INCOMING <id>`, the host opens a data connection `ACCEPT <id>` and
  pipes it to its own LAN port. The relay then **splices bytes** between the two data connections.
- The relay **cannot read or alter the game**: after the Minecraft login handshake the stream is encrypted with a
  key bound to the Mojang session, so keeping Mojang authentication on (decision 3) also protects the relay path.
  The host sees every guest as coming from the relay (no guest IP leaks to the host and the host's IP never reaches
  the guest). No accept list: knowing the code is the join, exactly like a Minecraft address.
- Per-guest limits (token bucket, decode bounds) are in the game as in section 5/6; the relay adds: max rooms,
  max connections per room (default 16), max bytes/s per connection and per room, idle timeout, a cap on new
  rooms per source IP, `HOST` secret so only the creator can claim an id.
- Not a new protocol for the game: `Protocol.VERSION` is not involved in the relay at all.

Costs and traffic:
- Server: one small VPS (1 vCPU, 1 GB, 1-20 TB included transfer; prices checked with the user before renting). The
  relay is I/O bound; thousands of concurrent splices fit.
- Traffic per 8-guest session: join bursts 8 x (10-25 MB worst case) = 80-200 MB once per session (less with the
  planet cache and, later, the delta log); steady 8 x 50 KB/s = 0.4 MB/s, both directions through the relay =
  about 3 GB per hour of play before quantization. Far below a 1 TB/month quota for dozens of hours.
- Work: relay server 1 day, mod client (host side + guest loopback + screens) 2.5 days, launcher field 0.5 day, abuse
  limits and tests 1 day. Plus the transport-independent work of section 7 (steps 1-3, 6-7).
- Operational duties: someone keeps the VPS updated and the domain paid; the relay is replaceable (the code carries
  the host name, and `--relay` / an env var points the game elsewhere), and the game still works over LAN / direct
  `ip:port` / Tailscale if the relay is down.

What could go wrong, honestly: the relay is a single point of failure and a recurring cost; the user must rent and
deploy it (I can write it and test it on loopback and through a throttled link, not deploy it); ISPs that block
outbound non-443 ports are avoided by also serving the relay on 443.

### 9.4 Which payloads need `Protocol.VERSION`
None of the relay, pacing, byte counters, decode limits or token bucket changes a payload. Payloads that would:
`PlanetAsk` (carries the guest's last edit seq, for the delta log), `Mobs` (quantized layout). Everything else stays.
A friendlier refusal text (`HelloAck.reason`) also needs one. Bump once, at merge.

### 9.5 Sources
- Tailscale free plans (6 users, node sharing): https://tailscale.com/docs/account/manage-plans/free-plans-discounts.md ; older FAQ (3 users): https://tailscale.com/pricing/faq
- Auth keys (who can create, 90 days, reusable / ephemeral / pre-approved / tags): https://tailscale.com/kb/1085/auth-keys/
- Windows MSI properties (`TS_NOLAUNCH`, `TS_UNATTENDEDMODE`, `/qn`): https://tailscale.com/kb/1189/install-windows-msi
- tsnet / libtailscale, BSD-3-Clause: https://pkg.go.dev/tailscale.com/tsnet ; https://github.com/tailscale/libtailscale
- ZeroTier pricing (10 devices on the free plan): https://www.zerotier.com/pricing.md
- Static Linux build tested here: https://pkgs.tailscale.com/stable/

## 10. Decision of the user (2026-10-15, later): NO relay, nothing paid, no Tailscale; direct IP + Hamachi

The relay of section 9 is **discarded for now** (kept as a future option, section 9.3 stays as its design). Plan:
exactly what Minecraft servers do today, direct `ip:port` (Direct Connection / Add Server, or the launcher's
*Join a friend* field), which also works over Hamachi (the host's `25.x.x.x` address). Mojang authentication stays on;
no accept list. What was verified or done, from the code (Minecraft 26.3 bytecode):

- `IntegratedServer.publishServer` calls `startTcpServerListener(null, port)`: **null address = every interface**, so
  the Hamachi adapter is reached with no change. The port is chosen by the player in the Open to LAN screen
  (`WorldOptionsScreen` has a port field) or `/publish [allowCommands] [port]`.
- Nothing in `Hello`/`HelloAck`/`Protocol` depends on the host name or the address (the version and the world id
  travel in the handshake; the guest keeps its data in `guest/<worldId>`), so a non-loopback address needs no code.
  Not run in two real games (the user asked for no games while away).
- Built: the host's chat shows the addresses friends can use (`HostAddresses`, Hamachi marked); the launcher has a
  *Join a friend* field (`launcher/src/core/address.js`, validated, `--quickPlayMultiplayer`); `docs/MULTIJUGADOR.md`.
- Improvements for slow links, none changes a payload (no `Protocol.VERSION` bump): `ByteMeter` (`GXC_MP_TRACE`),
  back-pressure pacing of planet transfers (`PlanetXfer.sendBudget`, reads the Netty channel through the
  `ConnectionAccess` mixin), the guest's current planet sent first (`Outbox` lanes), planet-file size limits
  (`PlanetStore.checkSize`), a per-guest inbound message limit with kick (`InboundGuard`).
- NOT done, and which need `Protocol.VERSION` (one bump at merge): the planet delta log (`PlanetAsk` carries the
  guest's last edit seq) and quantized `Mobs` frames; a refusal text in `HelloAck.reason`.
