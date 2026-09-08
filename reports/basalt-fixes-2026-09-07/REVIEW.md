# Base destroyer: report review and fixes

The original report identifies reproducible implementation defects. Its 13/15 selected-block result was not just a difficult starting position: the revised bot recovered all 15 redstone blocks, including both persistent failures, with protection enabled and no damage, and stopped after confirming the area was clear.

## Changes

- **Stale target shortlist:** advance the bounded global scan before choosing, even when cached candidates remain. Refresh the complete reach-sized neighbourhood at each decision. Recheck loaded state, breakability, retry exclusions and selection; preserve storage ordering, reachable-first preference and near-tie randomness. Clear an unfinished scan when restarting the destroyer.
- **Protection placement:** distinguish a clickable block face from a full standing floor. Permit valid thin-block and ceiling faces, with vanilla placement validation. Aim close to the actual face so rays do not enter a repeater through its top instead of its side. For genuinely unsupported floors, build toward the required cell from an existing anchor within four blocks, respecting the protected target, loaded terrain, supplies and the preparation deadline.
- **Placement/navigation oscillation:** check direct protection placements from crouched eye height, matching the pose used for placement. The initial replay exposed a standing/crouching visibility mismatch that repeatedly cancelled navigation before it could move.
- **Accurate counters:** record vanilla interaction attempts and count only matching server block updates or prediction acknowledgements. This covers native navigation, preparation and direct actions, including breaks replaced by flowing liquid. Ignore duplicate confirmations and rejected predictions. Native route progress and excavation/building phases are also exposed to the HUD.
- **Lava-corner clearance:** reject parkour approaches, landings and overshoot with neighbouring body-height hazards, and apply landing clearance to ascending steps and pillars. Safe upward parkour remains enabled. Existing health guards remain active.
- **Tool exhaustion:** with drop protection enabled, stop before breaking a block that requires a harvest tool when no suitable tool remains in the hotbar. Existing inventory-full and expendable-block reserve controls remain in force.

## Validation

The tests use independent copies of the original farm. Nominal seconds are game ticks divided by 20, not wall-clock performance measurements.

| Replay | Result | Confirmed breaks / placements | Sampled damage |
|---|---|---:|---:|
| First selected-block replay | Both originally stuck blocks recovered at ticks 128 and 387; 13/15 recovered in 120 seconds | 22 / 22; HUD matched | 0 HP |
| First family replay | 77 audited decisions, zero global priority misses; exposed the pose handoff defect | 76 / 48; HUD matched | 12 HP; health guard stopped it |
| Revised selected-block replay | **15/15 recovered in 125 seconds**, confirmed-empty completion | 25 / 24; HUD matched | **0 HP** |
| Final family priority replay | **84 audited decisions, zero global priority misses** in 150 seconds; 2,866 family blocks remain | 90 / 40; HUD matched | **0 HP** |
| Final unloaded-area guard | 40 seconds; 50 unloaded chunks reported; stayed in SCANNING throughout | 0 / 0 | **0 HP** |

The first family replay is retained as failed intermediate evidence, not a successful safety result. Its damage was observed while stationary during preparation, distinct from the original report's jumping incident.

The longer `run-2/shortlist-recheck` launch ended during client shutdown without an `end.json`. It is incomplete and excluded from the completed results. The final priority replay in `run-3` completed normally. It recorded four watchdog recovery signals and no target deferrals; recovery and route efficiency still have room for improvement. The original strict audit recorded 10 global priority misses in 100 decisions; the final repeat recorded 0 in 84. These are separate evolving-world runs, not a controlled throughput benchmark.

The report-processing scripts now read UTF-8 explicitly; Windows' default encoding corrupted Unicode status text when rebuilding metrics.

Focused live-world checks cover thin and ceiling placement faces, crouched-versus-standing ray visibility, fresh nearby targets despite a full stale shortlist, rejected/duplicate server confirmations, missing harvest tools, and safe versus lava-adjacent upward parkour. The in-source checks include bounded anchor construction and the original lava-corner coordinates.

Final verification on 2026-09-08: `gradlew.bat build` passed, including all production self-checks; the focused live suite and final farm launch both returned BUILD SUCCESSFUL with their expected completion evidence. Logs: `build/basalt-verified-build.log`, `build/basalt-mechanics-final.log`, and `build/basalt-fix-game-3.log`. The original archive SHA-256 was rechecked and remains `46f7a05aa23f8bf203eae3c3e24541c56e80e57ea1778493ccfbe9f5511aedbd`.

## Limits

This does not establish complete demolition of all 2,954 redstone-family blocks, optimal routing, arbitrary fluid safety or conservation of every container's contents. The local refresh guarantees nearby candidates are reconsidered; the wider scan is still incremental and bounded. The lava checks deliberately reserve clearance around manoeuvres, which can require a longer route or a deferral. Supplies, food, tool replacement and storage capacity still bound unattended operation.

Raw replay evidence is in `run-1`, `run-2` and `run-3` beside this review. Retained world copies are kept locally and ignored by Git. The original report and its evidence are unchanged.

## Reproduction

Run `./gradlew.bat allSelfChecks compileGametestJava` for the executable checks. For the focused live suite, unset `MOVRAND_BASALT_SOURCE`, set `MOVRAND_MECHANICS_ONLY=true`, then run `./gradlew.bat runClientGameTest`.

For farm replays, set `MOVRAND_BASALT_SOURCE` to the extracted original save, `MOVRAND_BASALT_OUTPUT` to a fresh directory, and `MOVRAND_BASALT_CASES=west-selected,shortlist-recheck`. `MOVRAND_BASALT_TICKS=4800` allows up to four nominal minutes per case, with normal completion and health guards still able to stop early. Run `./gradlew.bat runClientGameTest`, then `python tools/basalt_analyse.py <output-directory>`. Check each case's `end.json` and the test log, as well as the process exit status.
