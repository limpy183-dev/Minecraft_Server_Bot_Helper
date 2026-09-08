"""Build the evidence report after basalt_analyse.py; incomplete launches are never counted."""
import collections, json, pathlib, sys

root=pathlib.Path(sys.argv[1]).resolve()
results=json.loads((root/'summary.json').read_text(encoding='utf-8')); byname={r['case']:r for r in results}
def link(name,label=None):return f'[{label or name}]({(root/name).as_posix()})'
def itemlist(items):return ', '.join(f'{n} {k.removeprefix("minecraft:")}' for k,n in items.items()) or 'none'
lines=['# Basalt farm destroyer test — Minecraft 26.2','',
'The production bot was tested in independent copies of the supplied Nether farm. No production code was changed. This report distinguishes selected blocks removed, confirmed player breaks, placements, and collected inventory; those are different measurements.','']
full=byname.get('full-redstone')
if full:
    lines += [f"**Full-family result: {full['remaining_redstone']:,} of the original {full['original_redstone']:,} redstone-family blocks remain in the surveyed volume after {full['seconds']:.0f} nominal game seconds.** The run ended with: {full['end']['reason']}. This is not a completed demolition." if full['remaining_redstone'] else '**The final survey contains no redstone-family blocks.**', '']
    stop=root/'full-redstone/stopped-for-stagnation.json'
    if stop.exists():lines += ['The harness stopped after 180 game seconds without a confirmed player break, covering multiple retry windows.','']
else:lines += ['The long full-family run is still pending; this is an interim report.','']
provenance=json.loads((root/'provenance.json').read_text(encoding='utf-8'))
if provenance.get('installed_tested_copy'):
    lines += [f"The final tested save is installed in Modrinth’s Fabric 26.2 profile as [New World - Basalt Destroyer Test 2026-09-07](<{provenance['installed_tested_copy']}>). The original archive’s SHA-256 was rechecked after testing and is unchanged.",'']
lines += ['## Main findings','',
'- Four 120-second selected-block runs each recovered **13 of 15 redstone blocks**, with zero observed damage. The same two blocks remained: **(1118,70,129)** and **(1122,70,117)**.',
'- The protection-disabled control broke those two blocks at ticks **80 and 216**, and recovered both with no observed damage. The standard runs repeatedly failed their protection/preparation route. This is a diagnosis, not a recommendation to disable protection.',
'- Target sorting works within the current shortlist. The wider full-family audit also found closer reachable blocks outside that shortlist; see the exact examples below.',
'- Native Baritone edits are undercounted by the bot HUD. For example, the west run confirmed **20 breaks / 9 placements**, while its final counters showed **13 mined / 0 placed**.',
'- The full run removed **372 of 2,954 selected blocks (12.6%)**, with **408 confirmed player breaks, 197 placements and 11.0 HP of sampled damage**. An upward return route grazed lava after a low-floor pickup; the low-health guard stopped the run at 12 minutes 22 seconds.',
'- Lower-floor fetching, a nearby platform gap, a two-block-high overhang and interception of a water-transported drop succeeded without damage. The enclosed lamp required an 80-second recovery with substantial excavation.','',
'## World and test conditions','',
'Survey bounds: **X=1108–1184, Y=40–85, Z=84–158**, inclusive. Redstone occupies X=1115–1177, Y=62–77, Z=90–152. The archive confirms Nether spawn (1126,65,126), Hard difficulty, release 26.2 / DataVersion 4903.','',
'| Feature | Count in survey volume |','|---|---:|','| Redstone-family blocks | 2,954 |','| Lava blocks | 812 (307 source, 505 flowing) |','| Water blocks | 625 (42 source, 583 flowing) |','| Waterlogged blocks | 18 |','| Bubble columns | 4 |','| Loose item entities initially | 0 |','| Unloaded chunks in initial farm survey | 0 of 30 |','| TNT / fire blocks | 3 / 1 |','| Installed basalt blocks | 323 |','',
'There are **647 water-containing cells** when waterlogged blocks and bubble columns are included. The separate wider offline survey used different bounds and counted 1,439 lava and 630 water blocks; those numbers must not be substituted for the farm-volume counts.','',
'Containers hold **70,812 basalt items**: 6,876 in selected hoppers and 63,936 in chests. They are potential released contents, not loose drops or installed blocks. Piston heads are selected by the family but do not imply one collectible piston-head item each.','',
'Each case begins with a fresh copy, fresh Baritone cache, survival mode, 20 health, full food, an unenchanted diamond pickaxe/axe/sword, 256 cobblestone and 64 steak. No armour or fire resistance. Farm terrain and fluids are retained. The normal cases use terrain defaults plus fast tuning, 80-block scan radius and 40-block vertical radius. Chat, focus and container-scan interruptions are disabled. Mining through unselected route obstructions is allowed.','',
'The archive disables mob spawning and monster spawning despite Hard difficulty. Fire, fall and drowning damage are enabled, natural regeneration is enabled, keep-inventory is disabled, and TNT explosions are enabled. These runs therefore do not test combat under normal hostile spawning.','',
'The spawned diamonds in fetching cases are explicit probes. Their surrounding terrain is the real farm. Those cases use a 48-block pickup radius and disable proactive liquid covering. The control disables mining-drop protection; the priority audit disables target randomness. Each case’s exact config and start position are saved in its folder.','',
'## Outcomes','',
'Times are observed game ticks divided by 20. Background client throttling made parts of the long run slower in wall time; these are not wall-clock throughput benchmarks. Damage is the sum of downward per-tick health changes, so healing does not erase earlier sampled damage.','',
'| Scenario | Seconds | Inventory goal / outcome | Server breaks | Placements | Damage (HP) |','|---|---:|---|---:|---:|---:|']
for r in results:
    n=r['case'];inv=r['end']['inventory']
    if n.endswith('selected') or n in ('lava-edge','working-position-control'):outcome=f"{inv.get('minecraft:redstone_block',0)}/15 redstone blocks"
    elif n=='enclosed-room':outcome=f"{inv.get('minecraft:redstone_lamp',0)}/1 lamp"
    elif n in ('gap-platforms','lower-floor-overhang','overhang-only','water-channel'):outcome=f"{inv.get('minecraft:diamond',0)}/1 probe diamond"
    elif n=='unloaded-boundary':outcome='Completion guard; no diamond-block target exists'
    elif n=='lava-adjacent-pistons':outcome='15 pistons collected; 3 original lava cells capped'
    else:outcome=f"{r['remaining_redstone']:,} redstone remain in surveyed loaded cells"
    lines.append(f"| {link(n+'/metrics.json',n)} | {r['seconds']:.0f} | {outcome} | {sum(r['breaks'].values())} | {sum(r['placements'].values())} | {r['damage']:.1f} |")
lines += ['', 'These are independent copies: collected items must not be summed as if they came from one farm. Selected-family removal does not mean the entire structure or its stored contents were cleared.','',
'The lower-floor probe descended from Y=76 to Y=65 through the machinery. The gap probe descended from a glass platform to neighbouring obsidian and needed no placement: it proves gap traversal, not mandatory bridging. The overhang probe stayed at Y=65 beneath a ceiling at Y=67. The water probe was transported through the channel and intercepted at its outlet; the player spent **zero ticks in water**, so this does not establish underwater navigation or drowning recovery.','',
'## Target order and route efficiency','',
'The stricter **shortlist-recheck** repeated the global comparison with live block states, breakability and protected-world-block exclusions: **10 misses in 100 decisions**, with zero ordering violations inside the shortlist. At tick 2085 the chosen block (1131,72,122) was 4.11 blocks away while reachable wire (1133,74,120) was 2.48 away. At tick 2351 an unreachable block 6.81 away was chosen while reachable wire (1130,74,134) was 2.69 away. The earlier full-run audit found 38 misses in 386 decisions but used broader eligibility; the stricter repeat confirms the defect independently.','',
'The 75-decision dedicated priority audit found no missed closer eligible target. The longer full-family run reached other parts of the farm and exposed a stale-shortlist problem. Reachable-first ordering and up to 0.35 blocks of near-tie randomness are intentional. The strict repeat excludes retries and protected targets, and checks breakability.','']
audited=[r for r in results if r.get('global_audited_decisions')]
for r in audited:
    lines.append(f"- {r['case']}: {r['decisions']} decisions, {r['priority_violations']} violations inside the shortlist; {len(r['global_priority_misses'])} farther selections against the independent audit of original target positions still containing eligible selected blocks.")
examples=[d for r in audited for d in r['global_priority_misses']][:8]
if examples:
    lines += ['', '| Tick | Chosen target | Distance | Closer reachable target | Distance |','|---:|---|---:|---|---:|']
    for d in examples:
        a=d['global_audit'];lines.append(f"| {d['tick']} | {d['pos']} | {d['distance']:.2f} | {a['nearest']} | {a['distance']:.2f} |")
lines += ['',
'Root cause: `BaseDestroyer.work` consumes a live cached candidate before advancing/refreshing the scan. Resetting the scan cooldown after movement does not refresh the shortlist while it still has candidates. In one skip, the cached list had 466 candidates and the last scan was still incomplete after 15 chunks. Re-ranking that list cannot select a closer block absent from it.','',
'| Starting side | Actual travel, blocks | Observed path segments | A→B→A cell reversals |','|---|---:|---:|---:|']
for n in ('west-selected','north-selected','east-selected','south-selected'):
    r=byname[n];lines.append(f"| {n} | {r['travel_blocks']:.1f} | {r['route_count']} | {r['immediate_cell_reversals']} |")
lines += ['',
'These travel totals include work approaches and pickup detours. Different starts and random movement mean they are not a controlled shortest-route ranking. No independent optimal route was computed for the full changing world. The evidence establishes stalls, retries, revisits and ordering defects; it does **not** establish that any successful path was globally optimal.','',
'Every case has `routes.csv` and `routes.json` with per-path start/end tick, duration, actual travel, planned nodes, geometric length, planner nodes considered and predicted remaining tick cost. A segment ends when the observed path changes or navigation releases control; it may be cancelled rather than completed. Straight-line endpoint distance is only a lower bound.','',
f"![Actual trajectories]({(root/'routes.png').as_posix()})",'',
'## Failures, retries and oscillation','',
'| Case | Deferred blocks | Watchdog stall signals | Repeated A→B→A→B→A sequences |','|---|---:|---:|---:|']
for r in results:
    retries='See tick retry map / launch log' if r['case']=='west-selected' else str(len(r['retries']))
    stuck='Not separately instrumented' if r['case']=='west-selected' else str(len(r['stuck']))
    lines.append(f"| {r['case']} | {retries} | {stuck} | {len(r['oscillation_sequences'])} |")
lines += ['',
'Reversal counts include legitimate backtracking and are not automatically bugs. The stricter oscillation detector requires five alternating cell entries within 120 ticks while keeping the same non-null block target. Detection windows can overlap and are not unique incident counts. The north case repeated Y=73↔74 at X=1134,Z=102 during ticks 73–98 while targeting (1134,75,101).','',
'The enclosed lamp case triggered three watchdog signals, deferred once, then recovered: 21 confirmed breaks and three placements for one selected lamp, ending at Y=59. It also collected four detached levers, illustrating why inventory gains and player break counts differ.','']
failure_counts=collections.Counter((tuple(e['pos']),e['detail']) for r in results for e in r['retries'])
lines += ['| Repeated deferred coordinate | Reason | Count across independent runs |','|---|---|---:|']
for (pos,why),n in failure_counts.most_common(15):lines.append(f'| {pos} | {why} | {n} |')
lines += ['', 'All deferred events and the full remaining-block list are retained in each case’s `metrics.json`, `events.json` and `after.json`. Remaining blocks were not necessarily attempted.','',
]
lines += ['## Focused safety diagnostics','',
'**Unplaceable protection floor.** Both persistent redstone-block failures require three catch-floor cells. For (1118,70,129) these are (1117,69,128), (1118,69,128), (1119,69,128); for (1122,70,117) they are (1121,69,116–118). The diagnostic found zero feasible placement work cells within the ±6 search for each required cell. The only adjacent non-air supports were thin repeater/comparator blocks, which fail `fullPlacementSupport`. Mining rays existed from 94 and 87 work cells respectively. The preparation route lacks a way to construct intermediate anchors; merely widening mining reach does not solve it.','',
'**Lava covering works in some real farm locations.** The focused piston case placed cobblestone into original lava cells (1131,64,97), (1131,64,95), and (1137,64,103), at ticks 169, 173 and 726, collected 15 pistons and took no damage. The earlier lava-edge case capped no original lava and establishes safe-edge routing only. The full run placed blocks into 28 cells that originally held lava, yet still encountered the corner hazard below.','',
'**Unloaded-area completion guard passed.** With render distance reduced to two chunks, all 30 farm chunks remained loaded, but the configured wider scan contained 49 loaded and 50 unloaded chunks. Across 800 ticks the bot reported the incomplete scan or waited; it never declared DONE/OFF. A nonexistent diamond-block target isolated this guard from mining progress.','',
'**Full-run damage incident.** After descending safely to Y=54 to collect a target-block drop, the return route climbed toward sticky piston (1120,62,131). It included an upward parkour step from (1123,55,133) to (1121,56,133), followed by ascending/pillaring to Y=61. At tick 14760 the player was at (1121.642,56,133.861), in lava, with 16 HP; at tick 14765 health was 12.8 HP. The landing column was air, but adjacent cell (1121,56,134) contained lava in both the original and final saved terrain. The player’s body extended into that neighbouring cell. This supports a body-clearance/approach failure; the exact contribution of planning versus steering was not isolated by an A/B replay.','',
'The guard stopped at tick 14838 after health reached 11.6; final health was 11.73 because regeneration continued. Total sampled downward changes were 11.0 HP, greater than net loss because of healing. One final in-flight sticky-piston break was confirmed at tick 14835. The bot neither died nor completed the farm. The three original TNT blocks remained intact.','',
f"![Final route and damage]({(root/'damage-trace.png').as_posix()})",'',
'## Collected items by case','']
for r in results:lines += [f"**{r['case']}** — observed inventory increases: {itemlist(r['pickups'])}.",'']
lines += ['Inventory increases measure pickups at client-tick resolution. They can include recollection of previously placed blocks. Food consumption and building-stack depletion are reported separately as inventory decreases in `metrics.json`. Supplied starting tools, food and blocks are excluded from the gains.','',
f"![Time by controller phase]({(root/'phase-time.png').as_posix()})",'',
'## Interpretation and next changes','',
'1. Refresh or top up the candidate scan as the player moves and after removals; verify decisions against all currently eligible targets. Preserve the explicit reachable/storage priorities and near-tie slack.',
'2. Investigate the required drop-protection placements before changing mining reach. The two persistent failures become recoverable when protection is disabled, even though actual mining rays exist. Fix access/preparation while retaining protection around lava and deep drops.',
'3. Count confirmed native Baritone breaks and placements in the production HUD. `Pathing.tick` returns early through the native branch, bypassing the bookkeeping used by the other executor.',
'4. Reduce unnecessary transitions between mining, collecting and preparing. Inspect the route CSVs before changing path costs: repeated approach work and stale goal selection can dominate an otherwise reasonable individual route.',
'5. Plan for finite supplies and full inventories before claiming unattended dismantling. Selected hoppers alone can release 6,876 basalt items; tool durability, food, expendable-block reserve and retained contents matter.',
'6. Replay the lava-corner return route and enforce clearance for the player’s full body through parkour landings and subsequent steering. Compare smoothing on/off before attributing the failure to either planner or movement execution. Keep the low-health guard.',
'',
'## Prediction versus observation','',
'| Pre-run expectation | Observed result |','|---|---|',
'| Most selected redstone blocks recoverable; vantage may matter | All four starts recovered the same 13/15, exposing a repeatable preparation limitation rather than a starting-side solution. |',
'| Lower-floor and overhang collection possible without unsafe dropping | Both succeeded without damage; the separate gap did not require bridging. |',
'| Enclosed lamp may require excavation or deferral | One deferral and three watchdog signals, then lamp recovery after 80 seconds and 21 breaks. |',
'| Moving water-channel drop may require replanning | Diamond intercepted at outlet; player never entered water. |',
'| Protection should prepare or defer unsafe targets | Deferred the two unsupported catch-floor targets; capped lava successfully in the focused case. It did not prevent the later lava-corner route incident. |',
'| Full run likely limited by resources, hazards or retry cycles | Low health was the actual stopping condition, after 12.6% of surveyed redstone was removed; finite resources did not trigger this stop. |',
'| Current-eye distance should control equivalent target choices | Correct within the shortlist, but closer eligible blocks outside it were skipped in the stricter repeat. |','',
'## Evidence and limits','',
'All normal production self-checks passed (28 checks). The harness and observers compile. One earlier matrix client closed during water testing: completed cases were retained, the incomplete water attempt was excluded and rerun. The launcher printed BUILD SUCCESSFUL despite that test exception, so completion is checked from case evidence rather than the Gradle exit message alone.','',
'The early west pilot records per-tick retry maps but predates separate selection/stall events. Later cases add those observers; the final harness additionally exposes item targets, item retries and protection destinations. Old traces are not claimed to contain fields added later.','',
'Exported journals are filtered to each case’s session. Historical journal entries loaded by the application are excluded from this report’s evidence. Per-tick health and confirmed server events are used where journal/HUD summaries undercount activity.','',
'This is one run per scenario, plus the focused controls, not a statistical reliability estimate. Water interception did not exercise swimming. Combat, death recovery, selling, server protections, tool exhaustion and every possible liquid/terrain layout are not proven by these runs. The whole-world optimum and full loot conservation through arbitrary physics were not established.','',
link('predictions.md','Pre-run predictions and protocol')+' · '+link('provenance.json','Archive hash and version')+' · '+link('summary.json','Machine-readable summary')+' · '+link('worlds','Retained tested world copies'),'',
'Rerun with `MOVRAND_BASALT_SOURCE` pointing to the extracted original save, `MOVRAND_BASALT_OUTPUT` to a **fresh** output folder, and `MOVRAND_BASALT_CASES` to comma-separated scenario names, then run `./gradlew.bat runClientGameTest`. Rebuild the report with the three Python scripts `basalt_analyse.py`, `basalt_plot.py`, and `basalt_report.py`, passing the output folder. A missing `end.json` means an incomplete launch, regardless of the process exit code.','']
(root/'REPORT.md').write_text('\n'.join(lines),encoding='utf-8')
print(root/'REPORT.md')
