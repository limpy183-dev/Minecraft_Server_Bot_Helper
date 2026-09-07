# Basalt farm benchmark: predictions and protocol

Recorded before the matrix and full-family outcomes were inspected. An initial west-side pilot had already demonstrated 13/15 redstone-block recovery and two deferred targets.

Source: the supplied `New World.rar`, Minecraft 26.2, DataVersion 4903. The save records Hard difficulty and Nether world spawn (1126,65,126). The source is extracted read-only for the benchmark; each scenario starts in a fresh independent copy. Original Baritone caches are excluded to test fresh navigation. Farm geometry, water, lava, redstone, TNT, weather and difficulty are retained.

The bot runs the production MovementController, BaseDestroyer and Baritone in a real Fabric client with integrated-server survival physics. Test-only observers record decisions and confirmed server edits. No production behaviour is patched. Supplies: unenchanted diamond pickaxe, axe and sword; 256 cobblestone; 64 steak; no armour or fire resistance. Start health/food are set to 20. Chat, focus and container-scan interruptions are disabled. Other safety rules remain enabled.

Selection tests use explicit redstone blocks or the lamp. Full-family uses the mod's redstone family, including hoppers, slime and piston heads. Radius 80 and vertical radius 40 cover the farm; loaded-chunks mode is disabled to give a bounded scope. Fast destroyer tuning and terrain defaults are used, including 0.35-block near-tie randomness, reachable-first ordering, route mining through unselected blocks, mining-drop protection, and proactive lava covering. Drop-only cases use one spawned diamond, a 48-block collection radius and disable proactive covering to isolate fetching. Diamond probes are artificial; their surrounding terrain is the supplied farm.

| Case | Prediction / criterion |
|---|---|
| West selected pilot | Most redstone blocks recoverable; geometry can defer individual blocks. Pilot: 13 recovered; two repeatedly deferred. |
| North/east/south selected | Results may differ with vantage; all 15 are in scan range. Successful recovery means inventory gain, not simply a missing block. |
| Lower floor + overhang | Bot must descend 11 blocks and navigate beneath machinery to collect a diamond; taking a straight unsupported drop should be rejected or cause measurable damage. |
| Gap platforms | Descend from glass platform to neighbouring obsidian support and collect diamond. Placement may be avoidable; a pass alone does not prove bridging. |
| Enclosed room | Reach and break lamp (1146,62,121) from room above. Route excavation is permitted; blocked headroom or no valid working cell may defer it. |
| Lava edge | Collect selected redstone around active liquid without damage; mine protection should prepare an unsafe target or defer it, rather than destroy its drop. |
| Water channel | Follow the diamond moving in the real channel and collect it without drowning; moving drops may require replanning. |
| Full redstone | Expect substantial dismantling but cannot assume complete recovery of 2,954 components with a finite inventory and one unenchanted tool. Observe whether capacity, hazards, retry cycles or poor ordering become the limiting factor. |

Nearest is not unconditional: reachable targets take precedence, and random selection is limited to 0.35 blocks within an equal priority group. Failed targets are deferred for 45 seconds or until the player moves 8 blocks. Target priority uses current eye-to-block geometry; it does not compute route cost for every candidate. Therefore it cannot promise globally shortest dismantling travel.

Report elapsed simulation ticks at nominal 20 TPS separately from real launch time. Derive damage as the sum of downward health changes, not just start minus finish (food can heal it). Server break and placement events take precedence over HUD counters. Separate vanished redstone, confirmed player breaks, and inventory gains: piston changes, support loss, flowing water and explosions can remove blocks without collecting them.

Efficiency analysis will inspect decision candidates, route path nodes, travel distance, revisits and reversals. Straight-line distance is only a lower bound; no claim of global optimality follows from it. An outcome stopped by safety or a time budget is incomplete, never a pass.
