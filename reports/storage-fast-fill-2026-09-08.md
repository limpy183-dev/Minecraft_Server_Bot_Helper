# Storage follow-up — 2026-09-08

Implemented Fast slot filling (default on): normal PICKUP left-click fills exactly
one destination slot, then returns excess to the source. No QUICK_MOVE / shift-click;
only configured destination slots participate. Each click still awaits server
confirmation and final source/destination/cursor totals are checked.

Storage recovery now handles drop-area preparation itself. Previously mining safety
could deny a container break while only the paused destroyer knew how to resolve it.
Placement searches now check eventual recovery areas, and recovery repositions if
out of reach. This addresses a code-level stall path; the reported session was not
reproduced because the user explicitly requested no tests.

Bag shulker selections toggle like ender shulker selections. Destination highlighting,
Clear slots and item filters now read current lists after Config.clampAll replaces
them, fixing stale highlights and controls (including Select all slots feedback).

Compilation/packaging only: gradlew --no-daemon assemble succeeded.
No tests, self-checks, or Minecraft launches were performed for this follow-up.
Installed into Modrinth Fabric 26.2; previous jar retained in its backups folder.
