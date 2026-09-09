# Base destroyer route investigation — 9 September 2026

Tested copies of the Modrinth **Fabric 26.2** instance's **New World**, in the Nether,
starting at **1121.5, 69, 134.5**, with **Main sweep config**.
The original save and saved profile were not edited. Test players used survival mode,
diamond tools, 256 cobblestone and 64 steak, starting at full health; no invulnerability.

## Changes

- Separated the previously implicit pickup-distance requirement from lava containment and
  catch-floor preparation. **Base destroyer → Protect mined drops → Only mine targets
  within pickup range** preserves the previous restriction by default. Turning it off
  allows normal mining reach while retaining independent drop-area protection.
- Target ranking now respects that pickup setting. A visible block outside pickup range
  no longer counts as immediately mineable when the restriction is enabled. Blocks needing
  lava containment or catch-floor preparation also no longer outrank ready mining targets.
- Route heuristics now include Baritone's climbing/descending cost. Previously, a position
  directly above or below the goal could have zero estimated remaining cost.
- Extended the existing lava-edge clearance rule to ordinary and diagonal walking after
  a replay exposed a dangerous preparation route.
- Added repeatable profile-replay cases, unique test-save directories to avoid stale caches,
  and an assertion that fails a profile replay if the player dies.

## Evidence and limits

The baseline did **not** reproduce the reported prolonged `Working out a route` state:
the longest continuous `PLANNING` phase was four ticks in the saved-profile run and one
tick in the redstone-block-only run. Each baseline lasted 1,200 ticks. Preparation and
drop-collection journeys accounted for much more time. The changes address identifiable
problems in the code; these runs do not establish a general speedup or prove every cause
of the intermittent report is eliminated.

The saved profile selects the **Redstone and Storage families**, rather than only
`minecraft:redstone_block`. Both the exact profile and a variant changing only that selection
were tested. The `config.json` beside each trace records the effective settings.

With the new pickup restriction **off**, the later redstone-block-only test (`final/`)
mined **8 of the 9** redstone blocks in the surveyed base over **2,400 ticks**, ending at
**20 health**. Its longest continuous `PLANNING` phase was one tick. It was a timed sample,
not a completed demolition. The remaining redstone block was at **1154, 70, 93**.

Wider reach changed the dismantling order in the broader saved-profile test. An early
iteration (`fixed/`) died at a lava edge. After extending the walking clearance rules,
the 2,400-tick replay (`final/`) survived but still suffered lava and lingering fire damage,
ending at about **4 health**. The first default-on replay (`release/`) also died in flowing
lava after 840 ticks. This prompted the additional ready-target priority correction.
The pickup restriction remains **enabled by default**, preserving the user's existing setting.
These tests do not certify unattended demolition of all the farm's machinery as safe.

## Checks and local artifacts

- `gradlew build`: all subsystem self-checks passed.
- `MOVRAND_TERRAIN_ONLY=true gradlew runClientGameTest`: terrain regression tests passed,
  including the actual injected lava-edge route costs and a clear walking route.
- The pickup-toggle fixture first refused an overhead block outside pickup height, then
  mined it in **21 ticks** after the toggle was disabled, without moving or disabling
  drop protection.
- [summary.json](summary.json) contains per-run settings, server-confirmed break counts,
  health and planning durations. Random target/aim/movement choices remain enabled, so
  individual runs are not deterministic performance comparisons.

The local `baseline/`, `fixed/`, `final/` and `release/` directories contain JSONL tick
traces, server break/placement events, before/after surveys, screenshots, and retained
test worlds. Large raw artifacts and world copies are excluded from Git.
