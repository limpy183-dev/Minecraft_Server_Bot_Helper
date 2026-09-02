<div align="center">

<img src="src/main/resources/assets/movrand/icon.png" width="96" alt="Movement &amp; Randomisation icon">

# Movement &amp; Randomisation

**A client-side Fabric mod that walks your player like a person, not a macro.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A?style=flat-square&logo=minecraft&logoColor=white)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-loader%200.19.3-DBD0B4?style=flat-square)](https://fabricmc.net)
[![Java](https://img.shields.io/badge/Java-25-E76F00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![Client side](https://img.shields.io/badge/side-client%20only-4C8EDA?style=flat-square)](#what-a-server-can-see)

</div>

---

It walks forward with randomised timing, steers to a set of coordinates, hops single-block
obstacles, and plays your own sound file when it gets stuck. Everything is configurable
from an in-game menu bound to `'` (apostrophe) — no config file editing, no restart.

|  | |
| --- | --- |
| 🎲 **Randomisation** | Pauses, turns, sprint bursts and view wobble drawn from distributions with the shapes a human's actually have — not `Math.random()` in a loop. |
| 🗺️ **Navigation** | A\* over block positions that walks, steps up, drops, mines through and bridges across, each priced in walked blocks. |
| 🧹 **Area sweep** | Cover a whole world border chunk by chunk, in a route that isn't a lawnmower pattern. |
| 📓 **Logging** | Every event to CSV, JSON and plain text, with an in-game viewer, map and death ladder. |
| 🛡️ **Safety** | A scored detection-risk checklist, safe-stop spots, auto-eat, and a hard stop on anything it can't explain. |
| ⛏️ **Base destroyer** | The one part that reaches out and changes the world: finds blocks, mines them, fights back, caps lava, empties its bag and sells. |

### Contents

- [Build](#build) · [Keys](#keys)
- [What each tab does](#what-each-tab-does) — [Setup guide](#setup-guide) · [Configs](#configs) · [Movement](#movement) · [Randomisation](#randomisation) · [Obstacles](#obstacles) · [Stuck &amp; alert](#stuck--alert) · [Area sweep](#area-sweep) · [Go to](#go-to) · [Food](#food) · [Logging](#logging) · [Log viewer](#log-viewer) · [Deaths](#deaths) · [Safe stop](#safe-stop) · [Containers](#containers) · [Safety](#safety) · [Map](#map) · [HUD &amp; theme](#hud--theme)
- [Base destroyer](#base-destroyer) — [How it gets there](#how-it-gets-there) · [What it breaks](#what-it-breaks) · [Inventory](#inventory) · [Selling](#selling) · [Fighting](#fighting) · [Water](#water) · [Humanisation](#humanisation)
- [What a server can see](#what-a-server-can-see) · [Working while the menu is open](#working-while-the-menu-is-open) · [Config files](#config-files) · [Note](#note)

## Build

```bash
./gradlew build
```

The jar lands in `build/libs/movrand-1.0.0.jar`. Drop it into `.minecraft/mods`
alongside [Fabric API](https://modrinth.com/mod/fabric-api).

Requirements: Fabric Loader 0.19.3+, Fabric API, Java 25.

To try it in a dev client instead: `./gradlew runClient`.

Several pieces carry runnable self-checks. They run on the real classpath, with assertions on:

```bash
./gradlew selfCheck -Pcheck=com.damia.movrand.Human
```

| Class | What it proves |
| --- | --- |
| `Rng` | the randomisation distributions have the shapes they claim |
| `Human` | the view wobble hits the amplitude you asked for, can never snap, an eased turn respects its rate limit, and the camera filter never overshoots, stalls or takes the long way round zero |
| `AreaCoverage` | every route finishes N chunks in exactly N targets, and scout finishes them in N/(2r+1)² stops |
| `Journal` | dedupe, CSV quoting, search, dimension listing, log-once, and that a stop right after a start is not swallowed |
| `WorldBounds` | a border's span and chunk count, including one smaller than the smallest map |
| `Deaths` | the cause ladder, and that lava outranks a fall outranks fire outranks a nearby mob |
| `ContainerScanner` | the height band, and that a group window clamps at the edges and never shrinks as it widens |
| `Presets` | every preset survives the clamps untouched and leaves no detection risk |
| `Risks` | every listed fix actually clears the risk it names |
| `ContainerScanner` | the height band migrates, follows or ignores the player as asked, and cannot invert |
| `Avoidance` | steering picks the smallest turn that works, never dithers, and always unwinds |
| `SafeStop` | a spot on the last few seconds of your own path is recognised, one off it is not, and the trail is forgotten on a reset |
| `PathFinder` | a route goes round a pillar rather than through it, refuses a sealed room without mining and finds the way out with it, never crosses a hazard, refuses a drop past the limit, and hands back its best partial route when the budget runs out |
| `Bot` | the yaw and pitch conventions, which are the one place a silent sign error mines the block behind you forever |
| `BlockTargets` | families are rules and select nothing when unticked, `_ore` is a suffix and not a substring, and the never-list holds |
| `Backpack` | protection beats a sale mark in both directions, and no delay can be set to zero |
| `Combat` | sword beats axe beats pickaxe, netherite beats diamond, and nothing that is not a weapon scores at all |
| `BaseDestroyer` | a written-off block is not picked again, the reaction delay cannot collapse, and the air latch keeps climbing until it is breathing rather than bobbing at the threshold |

`AreaCoverage` also times itself: 3000 targets over a 262,144-chunk area, and 200 targets on
every route at that size. The numbers it prints are the guard against the O(area) version
creeping back in.

## Keys

| Key | Action |
| --- | --- |
| `'` | Open the menu |
| `;` | Toggle movement on/off |
| `Tab` | Next section (shift+Tab for previous) |

Both are rebindable under **Options → Controls → Movement**.

## What each tab does

The sidebar is grouped and scrolls, so the list can keep growing.

### Setup guide
Start here. It scores how human the current settings look — a weighted checklist of the
things a reviewer would actually notice (timing variety, turn smoothness, view jitter,
whether the route is a lawnmower pattern) — and lists what is dragging the score down.

Below that, a **Checks** list catches combinations that compile fine and then do not do what
you meant: no random events enabled, a fixed run length, area sweep and go-to both on, an
alert reaction with the sound switched off, logging that never writes files.

Then the **detection risk report** — every setting a server could notice, what it would
notice, and a button that fixes it. See [What a server can see](#what-a-server-can-see).

Then seven ready-made setups, each listing exactly what it changes:

| | |
| --- | --- |
| **Cautious AFK** | Public servers. Slow, heavily varied, stops at the first sign of anything |
| **Balanced** | The sensible middle |
| **Base hunting** | Max detection range, logs everything, does not stop for a chest |
| **Getting somewhere** | You have coordinates and want to arrive |
| **Maximum humanisation** | Everything on and turned up. Slowest, least predictable |
| **Single player** | Fast coverage, no social paranoia |
| **Paranoid** | Stops at the faintest hint. Expect it to stop often |

Every preset is self-checked to survive the clamps untouched, enable at least some
randomisation, score at least 40 on the humanisation scale, and leave **no detection risk**
behind. Picking a setup blind should never hand you something a server could notice.

### Configs
Named copies of every setting, one `.json` each in `config/movrand-profiles/`. Save the
current setup under a name, load one back, duplicate, or delete (click twice to confirm).
Loading a profile stops movement first and replaces every value.

Each saved profile is read and judged in place, so the list shows its humanisation verdict
and any detection risk it carries **before** you switch to it — an unsafe one is marked with
a red edge and a ⚠ next to its name.

### Movement
The master switch, sprint/sneak, and the length of a *forward run* — the stretch of
straight walking between random events. Default 60–120 s, drawn from a Gaussian so most
runs land near 90 s and the occasional one is much shorter or longer.

### Randomisation
Five event types, each with a relative weight. When a forward run ends, exactly one of
them is drawn:

- **Step left / right** — a short sideways burst, optionally while still holding forward
  (a diagonal drift rather than an obvious sidestep).
- **Pause** — stand still for a moment.
- **Turn** — change heading by a random number of degrees, spent along a smoothstep curve
  so the turn accelerates and decelerates. A constant number of degrees per tick is a
  rectangular velocity pulse: instantly at full speed, instantly back to zero, which is not
  something a hand does. The rate you set is the *peak*, so a turn lasts half again as long
  as that number alone suggests.
- **Hop** — a jump for no reason.
- **Look around** — tilt the pitch and settle back.

Plus a constant sub-degree wobble on both yaw and pitch, and a **smoothing** filter on the
camera itself.

Everything that aims the camera produces steps. A turn's ease curve is sampled once a tick,
a dodge appears the instant a wall does, the area leash correction snaps on at a boundary.
Smoothing is a one-pole low-pass on the final heading, so those arrive as one continuous
movement instead of a staircase — and because no hand puts a crosshair somewhere and stops
it dead, the tail it leaves is the shape a mouse makes anyway.

| Setting | |
| --- | --- |
| Turning | how much of last tick's heading the camera keeps. 0 points straight at the target; 0.95 takes about a second to arrive |
| Looking up and down | the same for pitch, worth keeping higher — a hand moves it less and settles it more slowly |
| Glide / Normal / Sharp | 0.85·0.92, 0.70·0.88, 0.35·0.70 |

Higher is smoother and lags a little wider round corners, which is the only cost. The wobble
is added *after* the filter, never before it: feeding noise through a low-pass just averages
it away, and a pitch that holds one value bit-for-bit is the one thing a real player's never
does.

That wobble is a first-order autoregressive random walk, not a sine. The obvious way to
humanise a camera is to add a couple of sine waves to the yaw; it looks fine on screen and
it is the worst available choice, because a sum of fixed sines has a *line* spectrum. A few
minutes of rotation samples through an FFT show two razor-sharp peaks, at the same two
frequencies, at the same ratio, forever. A hand on a mouse produces broadband noise with no
peak at all. The sine is a fingerprint, not a disguise.

The walk has a smooth roll-off with nothing to lock onto, never repeats, and because the
stationary standard deviation of `x = φx + ε` is known exactly, the kick is scaled so the
amplitude you set is the amplitude you get — asserted to within 8% over 200,000 ticks.

The generator is seeded from the OS entropy pool on every start, so two sessions never
produce the same walk.

### Obstacles

**Going round things** is the first line. Every tick it probes a fan of headings a few
blocks ahead and takes the smallest turn that is actually walkable — walls, trees, cliffs,
ravines, pillars, lava. Once the original heading clears, the correction unwinds and it
carries straight on to wherever it was going.

That last part is free rather than clever: the dodge is held *separately* from the heading.
`baseYaw` still points at the target the whole time, the correction is just added on top of
it, so "carry on afterwards" needs no memory — the correction decays to zero and the
original heading is still sitting underneath it.

Two details are what separate this from a bot that grinds against a corner:

- **Shoulders.** A player is 0.6 blocks wide, so each ray is probed at both shoulders rather
  than down the centre line. A single centre ray happily calls a doorframe passable and then
  wedges on it.
- **Hysteresis.** Once it picks a side it keeps preferring that side until the way ahead is
  properly clear. Without it, a tree trunk with equal room either way makes it dither
  left-right on the spot forever.

It is **not a pathfinder**. It looks ahead, it does not search, so it cannot solve a maze.
A genuine dead end reports *boxed in* and falls through to the stuck detector, which is
where the unstick attempts and the alert already live.

| Setting | |
| --- | --- |
| Look ahead | 3 blocks ≈ one second of sprinting |
| Widest detour | how far off the intended heading it may turn. Under 90° it cannot double back, so a dead end becomes a stuck alert |
| Turn into a dodge at | a cap, not a fixed rate — the correction eases in and out on its own |
| Unwind at | slower cuts the corner wider, which reads as more human |
| Holes, lava, water | what counts as impassable |

**Jumping** still handles anything short enough to step over — 1 to 3 blocks, with a headroom
check so it never hops into a ceiling. Steering treats a clearable step as passable rather
than as a wall, so the two do not fight: it walks at the step and the jump takes it.

**Ledges** are now the fallback rather than the first response. A drop the steering can walk
around never reaches the ledge reaction at all; only one it cannot get round does.

**What it goes round**, beyond walls and holes:

| | |
| --- | --- |
| Lava | on |
| Anything that hurts | fire, magma, cactus, berry bushes, wither roses, dripstone, powder snow, cobwebs — on |
| Portals | nether, end, gateways — on |
| Water | off |

Most of that list has **no collision at all**, which is the whole reason it needs its own
test: to a wall detector, fire and a berry bush are open ground. Collision is not a danger
detector — in this game the two are nearly opposites, since the things that hurt are mostly
the things you can walk straight through. The hazard test also runs *before* the step-up
logic, because a cactus does have collision and would otherwise read as a one-block step the
bot could happily jump onto, and *before* the floor test on the way down, because magma is
a floor.

Portals are in the list for a different reason: they are not dangerous, but walking into one
takes the bot somewhere it was never asked to go, halfway through a sweep.

### Stuck & alert
Watches two things: whether you actually covered ground in the last few seconds, and
whether something outside the mod ate your forward key (a freeze, a portal, another mod).
It will try to free itself first — jump plus a random turn — and only then alert.

The alert plays **your own file** from a folder on disk, N times, at a volume and gap you
set. `.wav`, `.aiff` and `.au` are decoded in-process, which gives exact repeat timing.
`.mp3` and `.ogg` have no decoder in the JVM, so they are handed to the system player
(on Windows, through a hidden shim so no console window flashes over the game).

Default folder: `C:\Users\damia\Desktop\AI_Projects\Minecraft\Movement_And_Randomisation\Sound_alert`

### Area sweep
Pick a region and it walks every chunk in it.

Define the region three ways: draw a box on the **interactive chunk map**, press
**Corner A here** / **Corner B here** while standing where you want them, or type the four
numbers. The same map fills in as the sweep progresses, marks the current target, and
right-clicking a chunk ticks it off or puts it back.

| On the map | |
| --- | --- |
| Drag | move the view |
| Double-click, or **Redraw the area** | arm a new selection, then drag a box |
| Wheel | zoom, to 4096 chunks across |
| Right-click a chunk | tick it off, or put it back |
| **Centre on me** | snap back and follow again |

Redrawing needs arming because setting a new region discards the progress in the old one, and
a drag is far too easy to do by accident for something that costs a whole sweep. The arming
lasts exactly one drag. The grid border turns amber while it is live, so there is never any
doubt about what the next drag will do.

The map zooms to **4096 chunks across** — 65,000 blocks — with the wheel over the grid or the
zoom control beside it, and **Chunks either side** goes to 512, an area 16,000 blocks wide.

Both maps stop at the **world border**, which is also drawn on them in orange. Past it there
is nothing to show, so zooming further only shrinks what you came to look at; the zoom list
drops the options that no longer fit rather than offering one that lies. A vanilla border is
sixty million blocks across, so on an ordinary world this changes nothing visible — which is
the right answer, the world really is that big. On a server that sets one it is the whole
map. Both tabs print the border's size next to the zoom, so the ceiling is never a mystery.

Three things had to change for a big area to be usable, and all three were the same mistake
in different places — doing work proportional to the *area* rather than to what is actually
needed:

- **The map drew one rectangle per chunk.** A 500-chunk square is a quarter of a million
  fills per frame, most of them landing on the same pixel. The area is now one fill per
  screen row, and the covered chunks are rasterised into a pixel mask and drawn as horizontal
  runs — rebuilt only when the coverage or the view actually moves, so an idle map costs
  nothing at all. Grid lines thin out once they would be closer than six pixels, because
  below that they are a grey wash rather than a grid.
- **Every target rebuilt and sorted the whole area.** The route search allocated one array
  per unvisited chunk and sorted the lot, every time a chunk was ticked off. It now rings
  outward from where you are and stops as soon as it has enough — with one detail that keeps
  it exact rather than merely close: square rings hold distances from `r` to `r√2`, so it
  scans out to `⌈r√2⌉` before settling. Serpentine, spiral and random get their own early
  exits.
- **The progress count was recomputed every tick.** `isComplete()` runs each tick and walked
  the entire visited set for a number that had changed by one. It is now kept as it goes.

Coverage is also only written to disk when it has actually changed, since a swept area of
that size serialises to megabytes and that was happening on every menu close.

Six routes. **Organic** (the default) picks randomly among the nearest few unvisited
chunks and aims at a random point inside the one it chose, so the path wanders the way a
person searching a region wanders while still converging on full coverage — every route is
self-checked to finish in exactly one target per chunk. **Serpentine** is the fastest and
looks the most like a machine. All of the ordinary randomisation keeps running on top.

Chunks the container scan has already read count as covered without walking into them, so
a sweep set up for finding bases finishes far quicker than one that has to visit every
chunk centre. Progress survives a restart, and is discarded if you move the area.

**Scout** is that idea taken to its conclusion. If a scan reads every chunk within eight of
you, walking the ninth is the only thing that finds anything new — so the route visits only
the points whose scan squares tile the area, spread evenly so the last row lands inside it
rather than hanging off the edge. A 512-chunk area is 262,144 chunks and 961 stops. The
randomisation is the organic route's: a weighted pick among the nearest few stops rather
than always the closest, a random point inside the chosen chunk, and everything the walk
itself is already doing over the several hundred blocks between them. Whatever the squares
miss — the corners of a circular area, chunks an obstacle kept it out of — is mopped up
organically at the end. With the scan credit turned off there is nothing to space the stops
by, and it falls back to the organic route rather than stalling.

### Go to
Type an X and Z (or press **Use my position** to capture where you stand). The mod steers
onto the bearing and keeps all the randomisation running on top of it — random turns push
you off course by up to the *allowed wander*, and the *correction speed* pulls you back.
Low correction values give lazy, natural arcs instead of a straight line.

There is a watchdog for making no progress, and a configurable reaction on arrival.

> It steers, it does not pathfind. It will not dig, build, or climb around a mountain.

### Food
Eats when the hunger bar drops to a threshold you set, picking the highest-nutrition item
on the hotbar, skipping the things that poison you, and optionally saving golden apples. It
looks at the sky while eating so right-click cannot open a chest instead, drops sprint
(which would cancel the meal), and puts the previous item back in hand afterwards.

Hotbar only — moving a stack up from the backpack means faking container clicks, which is a
lot of protocol for something you solve by keeping food on the bar. Running out is its own
alert.

### Logging
Writes to the **`Logs/`** folder next to this README. Seventeen kinds of event, each one
switchable: storage clusters, landmarks, players spotted, deaths, damage, low health,
hostiles, stuck spots, safe stops, ledges, chat triggers, arrivals, finished sweeps, session
start/stop, dimension changes, running out of food, and hand-dropped pins.

Every entry carries the context you would otherwise have to remember:

| | |
| --- | --- |
| time, session | when, and which run |
| kind, note | what happened |
| dimension, x/y/z, chunk | where |
| biome | what it looked like |
| health, food | how you were doing |
| state, runtime | what the bot was doing at the time |

Three formats, written side by side: `.json` (what the viewer reads back), `.csv` (opens in
a spreadsheet, properly quoted), and `.txt` (aligned columns). One file, one per day or one
per session. Entries deduplicate by kind, distance and age, so standing next to the same
chest hall for ten minutes writes one line rather than three hundred.

### Log viewer
Reads back any file in the `Logs/` folder, or the live session. Filter with a row of
colour-coded chips showing how many of each kind the log holds, plus a search box that
reaches the kind, note, biome, dimension, world, coordinates and timestamp. Entries are
grouped under date headings, newest or oldest first; click one to copy its `/tp`.

**Dimension chips** sit above that: overworld, nether and the end, each with a count, any
combination on or off, plus **Only this one** for the one you are standing in. The vanilla
three are always listed even when the file holds none of them, so the filter can be set
before going somewhere rather than after; a modded dimension gets its own chip as soon as
one entry comes from it. The filter is shared with the map, the same way the kind chips are
— two filters over the same data that can disagree just read as a bug.

Every entry now records **which world or server it came from**, and the viewer shows only
the one you are on. Log files rotate by day rather than by world, so a single file happily
holds a morning on one server and an afternoon on another — without the filter you would be
reading two unrelated sets of coordinates as if they were one. Turn **This world or server
only** off to see everything in the file; the header tells you which worlds that is.

Entries written before this existed carry no world at all, and nothing can work out where
they came from. They are **left out** when the filter is on — showing them in every world at
once is what made the filter look broken in the first place. Both views tell you how many
there are and offer two ways out: show them anyway, or **adopt** them into the world you are
on, which rewrites the live log so they belong somewhere for good. Only do that if the log
really is all from one place; there is no undo.

### Deaths
A death entry used to say "died here", which you already knew. It now says what killed you
and everything the client knew a moment before it did:

```
Bot was slain by Zombie · looks like zombie · 6.0 damage on the last tick ·
nearest Zombie 1.2 blocks away · health 0.0/20.0 · food 14 · armour 8 ·
holding Iron Pickaxe · effects poison 2 · xp level 27 · on the ground ·
at 412.5, 63.0, -288.1 · items dropped here
```

Two problems had to be solved for that. The first is that **dying erases its own evidence** —
health is zero, the fall distance is reset, the fire is out, the mob has wandered off and the
inventory is on the ground. So the numbers are sampled while the player is still alive and
outlive them by a tick. The cheap reads happen every tick; the ones that cost a scan or an
allocation (nearest hostile, held item, effects) happen on a health drop, which is what a
death follows anyway.

The second is that **the client is never told why it lost health**, only that it did. The
cause comes from the server, via the exact component it hands the death screen. That arrives
in a *different packet* from the health update, and later — so the entry waits up to two
seconds for it rather than recording every death as unknown.

Underneath both there is an inference from the numbers alone, ordered by how sure each rung
is: lava, then a fall, drowning, freezing, burning, powder snow, starvation, then a mob
within five blocks, then the size of the last hit. It fills in when the server says nothing,
and sits beside the server's message when it says something vague like "Bot died".

### Safe stop
Stuck detection asks "have I covered ground lately". This asks the sharper question: *am I
moving at the speed my own key presses imply.* Sprinting is 0.28 blocks per tick, walking
0.22, sneaking 0.07 — anything much slower for a sustained window means something is wrong,
and it stops entirely.

It also hard-stops on a teleport, being put in a vehicle, a dimension change, a client
freeze, and on another mod moving the camera out from under it. Water, lava, falling and
riding are exempt, since those legitimately change your speed.

**Except being put back** (on by default) is for servers that reject a step and drop you
where you already were. A setback and a teleport arrive as the same position packet, so the
only thing separating them is where you land: the mod keeps the last five seconds of your
path, and a jump that ends on it is carried on from without a stop or an alert. **Counts as
the same place** is how near that has to be — raise it if setbacks still stop the bot, lower
it if a short teleport is being waved through. **Put back** counts how often it has happened
this session. Nothing is lost by ignoring them: a server that keeps rejecting movement means
you are not covering ground, and that is what stuck detection is for.

### Containers
Counts hoppers, chests, barrels, shulkers, droppers, furnaces and crafting stations in the
chunks around you and reacts once the total crosses a threshold. Reading is invisible;
*stopping* because of what it read is not — the mod flags that in red, and the fix is to
alert instead. You still get the sound and the log entry.

**Only count storage that sits together** (on by default) is what the threshold measures.
Without it the count was every container in the whole scan radius as one number — at the
default reach that is a thousand blocks in every direction, so three unrelated farms and a
village could add up to a base that is not there. **Group within** sets the patch size in
chunks either side: 0 is a single chunk, 2 is a 5×5 patch about 80 blocks across. The
logged position is the fullest chunk inside the winning patch, so the pin lands in the
middle of the base rather than at the edge of the search.

Widening the group costs nothing. It is a summed-area table, so any rectangle is four
lookups however big it is — a 64-chunk scan with a 33-chunk window is the same work as a
single-chunk one.

**Only the first time** (on by default) means a cluster whose coordinates are already in
the log is not written again *and does not set the reaction off again*. It used to re-fire
on every scan for as long as you were in range — an alert every three seconds, and with a
stop reaction, a stop you could not walk away from. The reaction now follows the log rather
than the scan, so once a base is recorded it is finished with.

"Already in the log" reaches the other files in `Logs/` too, not just the one being written
now. With the usual one-file-per-day rotation the live list holds today and nothing else,
so without that a base found yesterday would be news again this morning. Landmarks are
covered by the same toggle, for the same reason. Off falls back to the duplicate window on
the Logging tab, which lets the same base come back every half hour.

**Height** decides which slice of the world column counts. Only what falls inside it is
counted, alerted on, and written to the log — landmarks included.

| Mode | |
| --- | --- |
| **Whole world** | bedrock to build limit. Finds everything at any depth |
| **Around me** | a band N blocks above and below wherever you are standing |
| **Between two levels** | a fixed pair of Y levels, whatever height you are at |

The fixed band is the one worth reaching for when you know what you are looking for. Deep
storage without a surface chest in the count, or a skybase without the ground under it.
Six one-press bands are there for the common cases — **Deep**, **Surface**, **High**, and
three relative to where you stand (**Below me**, **Around my y ±32**, **Above me**).

The band is trimmed to the world's own limits, so a leftover overworld range does not report
`y -64 to 320` while you are standing in a nether that stops at 128. The **Last scan**
readout says how many containers the band threw away — a big number there means the band is
tighter than you meant it to be, which is otherwise indistinguishable from an empty world. Spawners, beacons and
enchanting tables ride the same sweep and go straight to the coordinate log. Reads each chunk's
block-entity map rather than sweeping block states — the server sends every block entity of
a loaded chunk, so the count is exact and costs almost nothing.

### Safety
Independent guards, each with its own **do nothing / alert / stop / alert + stop** choice:
low health, taking damage, low hunger, another player coming near, hostile mobs, standing
in liquid, a chat message containing one of your keywords (or your own name), a maximum
runtime, and leaving the world.

Two settings on this tab exist purely to keep the guards from giving you away:

- **Only stop for players you can see** (on by default). The client is told about players
  through walls and around corners. The alert always plays — a sound on your machine is not
  something anyone else can observe — but the *stop* is held back unless you actually had
  line of sight. Walking away from someone you could not have seen is exactly the check a
  staff member in vanish is performing. Same option for hostile mobs.
- **Wait a beat before stopping** (on by default, 180–520 ms, drawn fresh each time). A stop
  landing on the exact tick its trigger fired is the one thing here no reaction time
  explains, and a constant zero is easier to spot than any single stop.

### Map
The coordinate log drawn as a map instead of a list. Drag to pan, wheel to zoom about the
cursor, right-click to recentre, click a pin to copy its `/tp`. Pins are coloured by kind and
the hovered one gets a readout with its note and timestamp.

| Setting | |
| --- | --- |
| This world or server only | on by default — see below |
| Dimension chips | overworld / nether / end, any combination, shared with the log viewer |
| Nether pins at 8× | puts a nether coordinate where it comes out in the overworld |
| Only the last N hours | 0 shows everything |
| Follow the player | panning or zooming turns this off on its own |
| Grid, labels, sweep outline | what else gets drawn |
| World border | drawn in orange, and the widest the map will zoom |
| Kind chips | shared with the log viewer, so the two always agree |

Which dimension the map is *drawn in* follows the chips rather than being a separate
setting: filtered down to one, that one is the frame; with several showing, the map is a mix
and the frame is wherever you are standing. It only decides which way the 8× runs.

**Places only** leaves the pins with a location worth walking back to — storage, landmarks,
players, spawners — and drops the moments (session start, chat triggers, arrivals).

### HUD & theme
Corner overlay with state, uptime, next-event countdown, destination and container count.
Eight accent colours, backdrop dimming, world blur.


## Base destroyer

Its own section in the sidebar, under a rule, because everything above the rule watches and
walks and everything below it reaches out and changes the world.

Turn **Take bases apart automatically** on, then use the movement toggle as usual — this
replaces what that toggle does rather than running on its own. From there it finds the
blocks you picked, walks to them, mines them, fights back when something hits it, caps lava
and water, bridges gaps, picks up its own drops, empties its bag and sells.

### How it gets there

The wander steering answers "is there a wall in front of me", which is enough to cross a
field and not enough to leave a room. So there is a real search underneath this: A\* over
block positions, with walking, stepping up, dropping, mining through and bridging across all
priced against each other in walked blocks. **A broken block is worth** and **A placed block
is worth** are those prices — raise the first and it goes round anything it can, lower it and
it digs straight there.

A swing is then priced for the block it actually is, from the block's hardness and the best
tool on your bar. This matters more than it sounds: at one flat price per broken block, a
wall is always cheaper than a corridor, so the route through obsidian beats walking ten
blocks round it and the bot spends its afternoon tunnelling. Cobblestone is a second and
obsidian is most of a minute, and once the search knows that it goes round on its own.

**Only break what I picked** keeps the route inside your selection. On, because picking
redstone and containers is not a request for a hole through the wall in front of them; off
lets it tunnel through anything breakable, which is the only way into a room with no door.

**Work through interruptions** is on, and it matters more than it sounds. The safety stops
in the sections above were written for a bot that wanders quietly and wants to be told when
anything happens — and a base destroyer trips nearly all of them by doing its job. A room
with twelve chests in it is a container cluster. Taking a heart of damage is damage. Sneaking
along the edge of a bridge is moving at a third of walking speed, which reads as being held
up. A doorway with a mob in it is being stuck. Every one of those was **Alert + stop**, and a
stop switches the whole mod off — so turning the destroyer on was a way of turning the mod
off a few seconds later. While it is working they now alert and log instead. Low health,
hunger, another player and the runtime limit still stop it: none of those are things the job
causes.

A shut wooden door or gate is opened rather than mined. Iron is not — a hand does nothing to
iron, so that one really is a wall until the route goes round it.

Lava is crossed by putting a block on it, as part of the route, at twice the price of an
ordinary placement: worth doing, never the first idea, and never at all when there is dry
ground going the same way.

Running out of **Search budget** is not a failure. The best partial route is walked anyway
and replanned from further along, which is why a low budget makes the bot wander toward
things rather than stand still thinking about them.

Hazards use the same definition the wander steering uses, so a route is never planned through
something the dodge would refuse to walk into.

### What it breaks

**Families** are rules rather than lists — "anything whose name ends in `_ore`" keeps working
on a base built out of something you did not think of, and survives not knowing that the
thing you call a comparator is `comparator` and not `redstone_comparator`. Redstone is on out
of the box. On top of that, the **search box** adds individual blocks by name, and
**Never break these** wins over everything. Bedrock, portals, command blocks and anything the
game itself calls unbreakable are excluded and cannot be added back.

The scan skips whole 16-block sections whose palette contains nothing selected, so a wide
radius through plain stone is nearly free. Only chunks the server has already sent are ever
read.

### Inventory

The control is the inventory. Left click a slot to **protect** it, right click to mark it
**for sale**.

Protected means never sold, never dropped, and never spent as scaffolding — one meaning, so a
protected stack of shulker boxes cannot quietly become a bridge. Protection wins over the
sale mark in both directions, so the two lists never have to be kept consistent by hand:
right-clicking a protected slot takes the protection off first, which is plainly what you
meant.

Nothing is sold until you pick slots. That is the whole safety story — there is no clever
heuristic deciding what is valuable.

### Selling

Sends the command, waits for the menu, shift-clicks everything from the sale slots into it,
clicks the confirm button, waits, closes. Every step waits a randomised moment, and no delay
can be set to zero.

The confirm button is found **by item** rather than by position — `lime_stained_glass_pane`
by default. Position is the server's layout choice; the item is what you are actually looking
at. Among matches the bottom-right one wins, because that is where a confirm button lives and
the highest slot index in a chest grid *is* the bottom right. A slot number is there as a
fallback for a server that does something else.

### Fighting

It defends and does not hunt. **Only once something hits you** is on by default, and turning
it off means swinging at things you may not be able to see — the same mistake as stopping for
a chest through a wall, and flagged as such. Swings are discrete and wait for the attack
cooldown, with a randomised gap on top, because a perfectly periodic swing is a signature.

Players are off by default. A bot that swings at people is a different thing from a bot that
mines.

### Water

Three separate things, and it is worth knowing which one is doing the work.

**Not getting in** is **Treat water as a wall** on the Obstacles tab, off by default. On, the
pathfinder refuses to route through water at all — worth turning on for a base near a lake,
because otherwise a route along the bottom of one is a perfectly valid route as far as the
search is concerned.

**Not letting it spread** is **Cover water and lava**, on by default. Caps an exposed surface
within a few blocks with a block before mining near it.

**Getting out** is **Come up for air**, on by default, and it is the only one of the three
that helps once the bot is already under. It watches the air bar and drops everything —
above the fight, because a fight underwater with no air left is not one worth winning — to
swim up, breaking the ceiling above its head if there is one. That last part matters more
than it sounds: mining into an aquifer from below is how the job usually gets itself wet, and
in that situation swimming up on its own does nothing at all.

It latches. Once it starts climbing it keeps climbing until it is breathing again, rather
than surfacing to one tick above the line, going back to work and spending the whole bar
bobbing an inch under the water.

### What it does to the existing guards

Two settings had to give way, and both are the same kind of contradiction rather than an
override.

**Stop when damaged** stands down while the destroyer is running with **Fight back** on.
"Stop the moment anything hits me" and "fight back when something hits me" cannot both run —
with both on the stop always wins, on the same tick as the hit, and the fight never starts.
Low health and **Back away when hurt** are what keep the bot alive instead.

**Pause while a screen is open** ignores the sell menu, because the mod opened it on purpose.
Pausing there would leave a sale half finished with the bag still full.

Everything else on Safety and Stuck & alert applies unchanged.

### Humanisation

Nothing here writes a rotation or a key. Each tick the job hands back an intent, and the
controller pushes it through the same wobble, easing and camera filter a wandering bot uses.
There is one camera in this mod and it does not know what job it is doing.

Two knobs are specific to working, both on the Base destroyer tab. **Aim smoothing while
working** is deliberately tighter than the wander camera: vanilla throws mining progress away
the moment the crosshair leaves the block, so too much smoothing here does not mine slowly,
it never finishes. **Aim wobble while working** scales the noise down rather than off — a
rotation stream with no noise in it at all is the single easiest thing to pick out of a log,
and mining is where the bot spends most of its rotations.

**Reaction before each decision** sits in front of everything the job decides, so nothing
lands on the same tick as the thing that caused it.

## What a server can see

Worth being precise, because it is easy to worry about the wrong half.

**Nothing about the movement itself can be caught.** The mod holds vanilla key bindings
down. Vanilla reads them, vanilla physics computes the motion, vanilla sends the packet.
There is no modified speed, no flight, no reach, no packet the client would not otherwise
have sent. The server receives exactly what it would from a hand on the keyboard. The
container scan, the log, the map and the alert sound never touch the network at all: they
read chunks the server already sent, and write files on this machine.

**What can be caught is behaviour**, in two kinds.

*Signatures* — anything periodic, perfectly linear, or bit-for-bit constant in a rotation
stream. This is why the wobble is a random walk rather than a sine, why turns ease in and
out rather than running at a fixed rate, why the pitch gets its own noise instead of
converging on exactly `0.0` and holding it for an hour, and why the reaction delay is drawn
fresh each time rather than being a constant zero.

*Acting on what you could not have seen* — the serious one. The scanner can find a chest
hall sixty blocks underground. Stopping because of it is a decision no legitimate player
could make. So is walking away the moment a player you have no line of sight to comes near,
which is precisely what someone in vanish is testing for. Reading hidden information is
invisible; acting on it is not. The line-of-sight gates exist for exactly this, and they
withhold only the observable half — the alert still fires.

The **Setup guide** tab lists whatever the current settings still carry, at three
severities, each with a one-click fix and a **Make every one of them safe** button. A red
edge appears in the gutter next to the offending control on its own tab, with the reason
underneath it in red. Fresh defaults leave two, both of which are features you explicitly
asked for rather than accidents:

| | |
| --- | --- |
| **Stop on a storage cluster** | halting next to a base you cannot see |
| **No time limit** | an unbroken multi-hour session is the oldest AFK heuristic there is |

The base destroyer changes that calculus and the risk list says so. It is the one switch in
the mod that is genuinely, unambiguously detectable: the scan reads blocks through walls and
the bot then walks to them and mines them, for hours. Everything else in this mod is about
how the bot *looks*. That switch is about what it *does*, and no amount of wobble makes
walking straight to a block you have never seen into something a person did.

None of this makes automation allowed. Being hard to notice is not permission.

## Working while the menu is open

The bot keeps walking with the menu up — `KeyboardInput` reads the key mappings with no
screen gate, so writing them each tick works either way. Turn the backdrop dimming down on
the HUD tab and watch the map and counters move while it runs.

## Config files

`config/movrand.json` — settings.
`config/movrand-profiles/*.json` — one file per saved config.
`config/movrand-coverage.json` — which chunks the sweep has finished.
`Logs/movrand-*.json|csv|txt` — the coordinate log.

Every setting in the menu is in there and safe to hand-edit; values are clamped on load, so
an inverted range will not break anything.

## Note

Automating movement breaks the rules on many multiplayer servers. Nothing in
[What a server can see](#what-a-server-can-see) changes that — it is about not leaving a
signature, not about having permission. Whether you may run this somewhere is between you
and that server.
