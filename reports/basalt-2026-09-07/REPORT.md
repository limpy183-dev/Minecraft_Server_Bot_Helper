# Basalt farm destroyer test — Minecraft 26.2

The production bot was tested in independent copies of the supplied Nether farm. No production code was changed. This report distinguishes selected blocks removed, confirmed player breaks, placements, and collected inventory; those are different measurements.

**Full-family result: 2,582 of the original 2,954 redstone-family blocks remain in the surveyed volume after 742 nominal game seconds.** The run ended with: Health down to 11.6. This is not a completed demolition.

The final tested save is installed in Modrinth’s Fabric 26.2 profile as [New World - Basalt Destroyer Test 2026-09-07](<C:/Users/damia/AppData/Roaming/ModrinthApp/profiles/Fabric 26.2/saves/New World - Basalt Destroyer Test 2026-09-07>). The original archive’s SHA-256 was rechecked after testing and is unchanged.

## Main findings

- Four 120-second selected-block runs each recovered **13 of 15 redstone blocks**, with zero observed damage. The same two blocks remained: **(1118,70,129)** and **(1122,70,117)**.
- The protection-disabled control broke those two blocks at ticks **80 and 216**, and recovered both with no observed damage. The standard runs repeatedly failed their protection/preparation route. This is a diagnosis, not a recommendation to disable protection.
- Target sorting works within the current shortlist. The wider full-family audit also found closer reachable blocks outside that shortlist; see the exact examples below.
- Native Baritone edits are undercounted by the bot HUD. For example, the west run confirmed **20 breaks / 9 placements**, while its final counters showed **13 mined / 0 placed**.
- The full run removed **372 of 2,954 selected blocks (12.6%)**, with **408 confirmed player breaks, 197 placements and 11.0 HP of sampled damage**. An upward return route grazed lava after a low-floor pickup; the low-health guard stopped the run at 12 minutes 22 seconds.
- Lower-floor fetching, a nearby platform gap, a two-block-high overhang and interception of a water-transported drop succeeded without damage. The enclosed lamp required an 80-second recovery with substantial excavation.

## World and test conditions

Survey bounds: **X=1108–1184, Y=40–85, Z=84–158**, inclusive. Redstone occupies X=1115–1177, Y=62–77, Z=90–152. The archive confirms Nether spawn (1126,65,126), Hard difficulty, release 26.2 / DataVersion 4903.

| Feature | Count in survey volume |
|---|---:|
| Redstone-family blocks | 2,954 |
| Lava blocks | 812 (307 source, 505 flowing) |
| Water blocks | 625 (42 source, 583 flowing) |
| Waterlogged blocks | 18 |
| Bubble columns | 4 |
| Loose item entities initially | 0 |
| Unloaded chunks in initial farm survey | 0 of 30 |
| TNT / fire blocks | 3 / 1 |
| Installed basalt blocks | 323 |

There are **647 water-containing cells** when waterlogged blocks and bubble columns are included. The separate wider offline survey used different bounds and counted 1,439 lava and 630 water blocks; those numbers must not be substituted for the farm-volume counts.

Containers hold **70,812 basalt items**: 6,876 in selected hoppers and 63,936 in chests. They are potential released contents, not loose drops or installed blocks. Piston heads are selected by the family but do not imply one collectible piston-head item each.

Each case begins with a fresh copy, fresh Baritone cache, survival mode, 20 health, full food, an unenchanted diamond pickaxe/axe/sword, 256 cobblestone and 64 steak. No armour or fire resistance. Farm terrain and fluids are retained. The normal cases use terrain defaults plus fast tuning, 80-block scan radius and 40-block vertical radius. Chat, focus and container-scan interruptions are disabled. Mining through unselected route obstructions is allowed.

The archive disables mob spawning and monster spawning despite Hard difficulty. Fire, fall and drowning damage are enabled, natural regeneration is enabled, keep-inventory is disabled, and TNT explosions are enabled. These runs therefore do not test combat under normal hostile spawning.

The spawned diamonds in fetching cases are explicit probes. Their surrounding terrain is the real farm. Those cases use a 48-block pickup radius and disable proactive liquid covering. The control disables mining-drop protection; the priority audit disables target randomness. Each case’s exact config and start position are saved in its folder.

## Outcomes

Times are observed game ticks divided by 20. Background client throttling made parts of the long run slower in wall time; these are not wall-clock throughput benchmarks. Damage is the sum of downward per-tick health changes, so healing does not erase earlier sampled damage.

| Scenario | Seconds | Inventory goal / outcome | Server breaks | Placements | Damage (HP) |
|---|---:|---|---:|---:|---:|
| [east-selected](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/east-selected/metrics.json) | 120 | 13/15 redstone blocks | 23 | 6 | 0.0 |
| [enclosed-room](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/enclosed-room/metrics.json) | 80 | 1/1 lamp | 21 | 3 | 0.0 |
| [full-redstone](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/full-redstone/metrics.json) | 742 | 2,582 redstone remain in surveyed loaded cells | 408 | 197 | 11.0 |
| [gap-platforms](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/gap-platforms/metrics.json) | 2 | 1/1 probe diamond | 0 | 0 | 0.0 |
| [lava-adjacent-pistons](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/lava-adjacent-pistons/metrics.json) | 60 | 15 pistons collected; 3 original lava cells capped | 32 | 13 | 0.0 |
| [lava-edge](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/lava-edge/metrics.json) | 120 | 13/15 redstone blocks | 23 | 15 | 0.0 |
| [lower-floor-overhang](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/lower-floor-overhang/metrics.json) | 7 | 1/1 probe diamond | 1 | 0 | 0.0 |
| [north-selected](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/north-selected/metrics.json) | 120 | 13/15 redstone blocks | 28 | 12 | 0.0 |
| [overhang-only](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/overhang-only/metrics.json) | 1 | 1/1 probe diamond | 0 | 0 | 0.0 |
| [priority-audit](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/priority-audit/metrics.json) | 120 | 2,879 redstone remain in surveyed loaded cells | 75 | 35 | 0.0 |
| [shortlist-recheck](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/shortlist-recheck/metrics.json) | 150 | 2,856 redstone remain in surveyed loaded cells | 97 | 34 | 0.0 |
| [south-selected](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/south-selected/metrics.json) | 120 | 13/15 redstone blocks | 19 | 13 | 0.0 |
| [unloaded-boundary](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/unloaded-boundary/metrics.json) | 40 | Completion guard; no diamond-block target exists | 0 | 0 | 0.0 |
| [water-channel](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/water-channel/metrics.json) | 13 | 1/1 probe diamond | 0 | 0 | 0.0 |
| [west-selected](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/west-selected/metrics.json) | 120 | 13/15 redstone blocks | 20 | 9 | 0.0 |
| [working-position-control](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/working-position-control/metrics.json) | 60 | 6/15 redstone blocks | 14 | 6 | 0.0 |

These are independent copies: collected items must not be summed as if they came from one farm. Selected-family removal does not mean the entire structure or its stored contents were cleared.

The lower-floor probe descended from Y=76 to Y=65 through the machinery. The gap probe descended from a glass platform to neighbouring obsidian and needed no placement: it proves gap traversal, not mandatory bridging. The overhang probe stayed at Y=65 beneath a ceiling at Y=67. The water probe was transported through the channel and intercepted at its outlet; the player spent **zero ticks in water**, so this does not establish underwater navigation or drowning recovery.

## Target order and route efficiency

The stricter **shortlist-recheck** repeated the global comparison with live block states, breakability and protected-world-block exclusions: **10 misses in 100 decisions**, with zero ordering violations inside the shortlist. At tick 2085 the chosen block (1131,72,122) was 4.11 blocks away while reachable wire (1133,74,120) was 2.48 away. At tick 2351 an unreachable block 6.81 away was chosen while reachable wire (1130,74,134) was 2.69 away. The earlier full-run audit found 38 misses in 386 decisions but used broader eligibility; the stricter repeat confirms the defect independently.

The 75-decision dedicated priority audit found no missed closer eligible target. The longer full-family run reached other parts of the farm and exposed a stale-shortlist problem. Reachable-first ordering and up to 0.35 blocks of near-tie randomness are intentional. The strict repeat excludes retries and protected targets, and checks breakability.

- full-redstone: 386 decisions, 0 violations inside the shortlist; 38 farther selections against the independent audit of original target positions still containing eligible selected blocks.
- lava-adjacent-pistons: 17 decisions, 0 violations inside the shortlist; 3 farther selections against the independent audit of original target positions still containing eligible selected blocks.
- priority-audit: 75 decisions, 0 violations inside the shortlist; 0 farther selections against the independent audit of original target positions still containing eligible selected blocks.
- shortlist-recheck: 100 decisions, 0 violations inside the shortlist; 10 farther selections against the independent audit of original target positions still containing eligible selected blocks.
- working-position-control: 7 decisions, 0 violations inside the shortlist; 0 farther selections against the independent audit of original target positions still containing eligible selected blocks.

| Tick | Chosen target | Distance | Closer reachable target | Distance |
|---:|---|---:|---|---:|
| 1630 | [1127, 68, 115] | 3.28 | [1129, 71, 114] | 1.34 |
| 1706 | [1127, 68, 116] | 2.30 | [1127, 68, 114] | 1.05 |
| 1744 | [1124, 69, 116] | 2.23 | [1127, 68, 114] | 1.83 |
| 1807 | [1123, 69, 115] | 2.01 | [1125, 71, 115] | 1.17 |
| 1826 | [1123, 69, 116] | 1.76 | [1123, 70, 115] | 1.13 |
| 1845 | [1123, 69, 117] | 1.87 | [1123, 70, 115] | 1.00 |
| 1848 | [1122, 70, 117] | 1.56 | [1123, 70, 115] | 1.00 |
| 1858 | [1124, 70, 118] | 1.86 | [1123, 70, 115] | 1.40 |

Root cause: `BaseDestroyer.work` consumes a live cached candidate before advancing/refreshing the scan. Resetting the scan cooldown after movement does not refresh the shortlist while it still has candidates. In one skip, the cached list had 466 candidates and the last scan was still incomplete after 15 chunks. Re-ranking that list cannot select a closer block absent from it.

| Starting side | Actual travel, blocks | Observed path segments | A→B→A cell reversals |
|---|---:|---:|---:|
| west-selected | 277.0 | 31 | 5 |
| north-selected | 379.4 | 52 | 11 |
| east-selected | 329.0 | 33 | 9 |
| south-selected | 333.5 | 30 | 4 |

These travel totals include work approaches and pickup detours. Different starts and random movement mean they are not a controlled shortest-route ranking. No independent optimal route was computed for the full changing world. The evidence establishes stalls, retries, revisits and ordering defects; it does **not** establish that any successful path was globally optimal.

Every case has `routes.csv` and `routes.json` with per-path start/end tick, duration, actual travel, planned nodes, geometric length, planner nodes considered and predicted remaining tick cost. A segment ends when the observed path changes or navigation releases control; it may be cancelled rather than completed. Straight-line endpoint distance is only a lower bound.

![Actual trajectories](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/routes.png)

## Failures, retries and oscillation

| Case | Deferred blocks | Watchdog stall signals | Repeated A→B→A→B→A sequences |
|---|---:|---:|---:|
| east-selected | 7 | 1 | 0 |
| enclosed-room | 1 | 3 | 0 |
| full-redstone | 33 | 7 | 30 |
| gap-platforms | 0 | 0 | 0 |
| lava-adjacent-pistons | 1 | 1 | 0 |
| lava-edge | 7 | 0 | 0 |
| lower-floor-overhang | 0 | 0 | 0 |
| north-selected | 5 | 1 | 1 |
| overhang-only | 0 | 0 | 0 |
| priority-audit | 3 | 1 | 0 |
| shortlist-recheck | 6 | 0 | 0 |
| south-selected | 4 | 0 | 0 |
| unloaded-boundary | 0 | 0 | 0 |
| water-channel | 0 | 0 | 0 |
| west-selected | See tick retry map / launch log | Not separately instrumented | 0 |
| working-position-control | 0 | 0 | 0 |

Reversal counts include legitimate backtracking and are not automatically bugs. The stricter oscillation detector requires five alternating cell entries within 120 ticks while keeping the same non-null block target. Detection windows can overlap and are not unique incident counts. The north case repeated Y=73↔74 at X=1134,Z=102 during ticks 73–98 while targeting (1134,75,101).

The enclosed lamp case triggered three watchdog signals, deferred once, then recovered: 21 confirmed breaks and three placements for one selected lamp, ending at Y=59. It also collected four detached levers, illustrating why inventory gains and player break counts differ.

| Repeated deferred coordinate | Reason | Count across independent runs |
|---|---|---:|
| (1122, 70, 117) | no safe working position in loaded terrain | 12 |
| (1118, 70, 129) | no safe working position in loaded terrain | 10 |
| (1129, 69, 129) | no safe working position in loaded terrain | 4 |
| (1154, 70, 125) | cannot keep a clear aim on Block of Redstone | 2 |
| (1127, 66, 123) | no safe working position in loaded terrain | 2 |
| (1131, 68, 129) | path search exceeded its deadline | 2 |
| (1129, 68, 129) | no safe working position in loaded terrain | 2 |
| (1130, 68, 123) | cannot get to Redstone Wire: no safe working position in loaded terrain | 2 |
| (1130, 66, 120) | no safe working position in loaded terrain | 2 |
| (1131, 69, 129) | no safe working position in loaded terrain | 2 |
| (1146, 62, 121) | cannot get to Redstone Lamp: route stopped making progress | 1 |
| (1122, 70, 116) | no safe working position in loaded terrain | 1 |
| (1130, 67, 116) | no safe working position in loaded terrain | 1 |
| (1132, 67, 116) | no safe working position in loaded terrain | 1 |
| (1131, 66, 123) | no safe working position in loaded terrain | 1 |

All deferred events and the full remaining-block list are retained in each case’s `metrics.json`, `events.json` and `after.json`. Remaining blocks were not necessarily attempted.

## Focused safety diagnostics

**Unplaceable protection floor.** Both persistent redstone-block failures require three catch-floor cells. For (1118,70,129) these are (1117,69,128), (1118,69,128), (1119,69,128); for (1122,70,117) they are (1121,69,116–118). The diagnostic found zero feasible placement work cells within the ±6 search for each required cell. The only adjacent non-air supports were thin repeater/comparator blocks, which fail `fullPlacementSupport`. Mining rays existed from 94 and 87 work cells respectively. The preparation route lacks a way to construct intermediate anchors; merely widening mining reach does not solve it.

**Lava covering works in some real farm locations.** The focused piston case placed cobblestone into original lava cells (1131,64,97), (1131,64,95), and (1137,64,103), at ticks 169, 173 and 726, collected 15 pistons and took no damage. The earlier lava-edge case capped no original lava and establishes safe-edge routing only. The full run placed blocks into 28 cells that originally held lava, yet still encountered the corner hazard below.

**Unloaded-area completion guard passed.** With render distance reduced to two chunks, all 30 farm chunks remained loaded, but the configured wider scan contained 49 loaded and 50 unloaded chunks. Across 800 ticks the bot reported the incomplete scan or waited; it never declared DONE/OFF. A nonexistent diamond-block target isolated this guard from mining progress.

**Full-run damage incident.** After descending safely to Y=54 to collect a target-block drop, the return route climbed toward sticky piston (1120,62,131). It included an upward parkour step from (1123,55,133) to (1121,56,133), followed by ascending/pillaring to Y=61. At tick 14760 the player was at (1121.642,56,133.861), in lava, with 16 HP; at tick 14765 health was 12.8 HP. The landing column was air, but adjacent cell (1121,56,134) contained lava in both the original and final saved terrain. The player’s body extended into that neighbouring cell. This supports a body-clearance/approach failure; the exact contribution of planning versus steering was not isolated by an A/B replay.

The guard stopped at tick 14838 after health reached 11.6; final health was 11.73 because regeneration continued. Total sampled downward changes were 11.0 HP, greater than net loss because of healing. One final in-flight sticky-piston break was confirmed at tick 14835. The bot neither died nor completed the farm. The three original TNT blocks remained intact.

![Final route and damage](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/damage-trace.png)

## Collected items by case

**east-selected** — observed inventory increases: 13 redstone_block, 3 note_block, 2 gray_concrete, 4 redstone, 2 netherrack, 1 piston.

**enclosed-room** — observed inventory increases: 1 tuff_brick_stairs, 1 prismarine_brick_stairs, 1 redstone_torch, 4 lever, 1 redstone_lamp, 16 netherrack, 2 crimson_roots, 1 nether_wart_block.

**full-redstone** — observed inventory increases: 42 note_block, 9 hopper, 20 observer, 84 piston, 13 target, 54 repeater, 137 redstone, 3 scaffolding, 5 comparator, 9 cobblestone, 1 iron_trapdoor, 5 gray_concrete, 9 basalt, 8 netherrack, 4 redstone_torch, 2 nether_wart_block, 4 sticky_piston.

**gap-platforms** — observed inventory increases: 1 diamond.

**lava-adjacent-pistons** — observed inventory increases: 15 piston, 5 netherrack, 2 cobblestone, 1 gray_concrete, 1 hopper, 2 basalt, 2 repeater, 1 soul_soil, 2 redstone, 1 sticky_piston.

**lava-edge** — observed inventory increases: 1 netherrack, 2 note_block, 13 redstone_block, 1 cobblestone, 2 gray_concrete, 1 slime_block, 1 sticky_piston, 3 redstone.

**lower-floor-overhang** — observed inventory increases: 1 note_block, 1 diamond.

**north-selected** — observed inventory increases: 4 netherrack, 13 redstone_block, 3 gray_concrete, 3 note_block, 2 piston, 1 slime_block, 3 redstone, 1 cobblestone.

**overhang-only** — observed inventory increases: none.

**priority-audit** — observed inventory increases: 22 piston, 4 observer, 5 hopper, 16 note_block, 16 redstone, 8 repeater, 1 netherrack, 4 target, 1 iron_trapdoor, 2 scaffolding.

**shortlist-recheck** — observed inventory increases: 22 piston, 6 observer, 5 hopper, 9 note_block, 4 target, 41 redstone, 11 repeater, 3 netherrack.

**south-selected** — observed inventory increases: 13 redstone_block, 2 gray_concrete, 1 slime_block, 3 redstone, 1 note_block.

**unloaded-boundary** — observed inventory increases: none.

**water-channel** — observed inventory increases: 1 diamond.

**west-selected** — observed inventory increases: 1 netherrack, 2 note_block, 13 redstone_block, 3 redstone, 1 slime_block, 1 gray_concrete.

**working-position-control** — observed inventory increases: 1 gray_concrete, 6 redstone_block, 1 piston, 3 netherrack, 1 note_block, 1 redstone.

Inventory increases measure pickups at client-tick resolution. They can include recollection of previously placed blocks. Food consumption and building-stack depletion are reported separately as inventory decreases in `metrics.json`. Supplied starting tools, food and blocks are excluded from the gains.

![Time by controller phase](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/phase-time.png)

## Interpretation and next changes

1. Refresh or top up the candidate scan as the player moves and after removals; verify decisions against all currently eligible targets. Preserve the explicit reachable/storage priorities and near-tie slack.
2. Investigate the required drop-protection placements before changing mining reach. The two persistent failures become recoverable when protection is disabled, even though actual mining rays exist. Fix access/preparation while retaining protection around lava and deep drops.
3. Count confirmed native Baritone breaks and placements in the production HUD. `Pathing.tick` returns early through the native branch, bypassing the bookkeeping used by the other executor.
4. Reduce unnecessary transitions between mining, collecting and preparing. Inspect the route CSVs before changing path costs: repeated approach work and stale goal selection can dominate an otherwise reasonable individual route.
5. Plan for finite supplies and full inventories before claiming unattended dismantling. Selected hoppers alone can release 6,876 basalt items; tool durability, food, expendable-block reserve and retained contents matter.
6. Replay the lava-corner return route and enforce clearance for the player’s full body through parkour landings and subsequent steering. Compare smoothing on/off before attributing the failure to either planner or movement execution. Keep the low-health guard.

## Prediction versus observation

| Pre-run expectation | Observed result |
|---|---|
| Most selected redstone blocks recoverable; vantage may matter | All four starts recovered the same 13/15, exposing a repeatable preparation limitation rather than a starting-side solution. |
| Lower-floor and overhang collection possible without unsafe dropping | Both succeeded without damage; the separate gap did not require bridging. |
| Enclosed lamp may require excavation or deferral | One deferral and three watchdog signals, then lamp recovery after 80 seconds and 21 breaks. |
| Moving water-channel drop may require replanning | Diamond intercepted at outlet; player never entered water. |
| Protection should prepare or defer unsafe targets | Deferred the two unsupported catch-floor targets; capped lava successfully in the focused case. It did not prevent the later lava-corner route incident. |
| Full run likely limited by resources, hazards or retry cycles | Low health was the actual stopping condition, after 12.6% of surveyed redstone was removed; finite resources did not trigger this stop. |
| Current-eye distance should control equivalent target choices | Correct within the shortlist, but closer eligible blocks outside it were skipped in the stricter repeat. |

## Evidence and limits

All normal production self-checks passed (28 checks). The harness and observers compile. One earlier matrix client closed during water testing: completed cases were retained, the incomplete water attempt was excluded and rerun. The launcher printed BUILD SUCCESSFUL despite that test exception, so completion is checked from case evidence rather than the Gradle exit message alone.

The early west pilot records per-tick retry maps but predates separate selection/stall events. Later cases add those observers; the final harness additionally exposes item targets, item retries and protection destinations. Old traces are not claimed to contain fields added later.

Exported journals are filtered to each case’s session. Historical journal entries loaded by the application are excluded from this report’s evidence. Per-tick health and confirmed server events are used where journal/HUD summaries undercount activity.

This is one run per scenario, plus the focused controls, not a statistical reliability estimate. Water interception did not exercise swimming. Combat, death recovery, selling, server protections, tool exhaustion and every possible liquid/terrain layout are not proven by these runs. The whole-world optimum and full loot conservation through arbitrary physics were not established.

[Pre-run predictions and protocol](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/predictions.md) · [Archive hash and version](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/provenance.json) · [Machine-readable summary](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/summary.json) · [Retained tested world copies](C:/Users/damia/Desktop/AI_Projects/Minecraft/Movement_And_Randomisation/reports/basalt-2026-09-07/worlds)

Rerun with `MOVRAND_BASALT_SOURCE` pointing to the extracted original save, `MOVRAND_BASALT_OUTPUT` to a **fresh** output folder, and `MOVRAND_BASALT_CASES` to comma-separated scenario names, then run `./gradlew.bat runClientGameTest`. Rebuild the report with the three Python scripts `basalt_analyse.py`, `basalt_plot.py`, and `basalt_report.py`, passing the output folder. A missing `end.json` means an incomplete launch, regardless of the process exit code.
