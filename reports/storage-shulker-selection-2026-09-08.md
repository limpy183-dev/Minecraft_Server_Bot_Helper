# Storage shulker rejection — fix and replay

## Confirmed bug

Storage compared serialized ItemStack JSON strings verbatim. JSON object-field
order is not part of item identity, so an unchanged shulker could be rejected
when a saved fingerprint used a different field order. The same comparison
affected bag lookup, ender extraction/return and recovered-item identification.

Storage.sameFingerprint now compares parsed JSON values. Contents, item type,
metadata and array order still must match; object-field order and whitespace
do not. Missing/changed, ambiguous and protected bag selections now produce
separate messages. Protection and fail-stop behavior remain enforced.

## Requested world/profile replay

- Source: Modrinth Fabric 26.2, save New World - Basalt Destroyer Test 2026-09-07.
- Loaded profile: Main sweep config, using the normal profile loader.
- Dimension: minecraft:the_nether.
- Tests ran in fresh copies, leaving the user's save and profile untouched.
- The saved profile had no storage targets and had storage/base destroyer off.
  The replay enabled both and selected test shulkers and diamond filters.
- Fixture: a cleared platform around 1126, 129, 126; survival inventory with
  shulkers, diamonds, an ender chest and a Silk Touch pickaxe. Existing player
  effects/fire were cleared after initial fixture runs hit the damage stop.
- Only fingerprint field order was changed to reproduce the bug; actual
  shulker contents and inventory slot were unchanged and unprotected.

With the old text comparison restored temporarily, the bag route failed at
preparation with an unchanged shulker in slot 9. The diagnostic split was
already present in that run. See storage-profile-before.log.

With the fix, the same replay passed (storage-profile-after.log, exit 0):

| Scenario | Result |
| --- | --- |
| Bag shulker with reordered fingerprint | Stored 16 diamonds and recovered box, 177 ticks |
| Ender chest inspection | Placed, inspected and recovered chest, 171 ticks |
| Ender shulker with reordered fingerprint | Stored 3 diamonds, returned to slot 7, recovered chest, 342 ticks |

Server inventory assertions verified deposited contents and container recovery.

This proves a false-rejection bug and its fix. It cannot establish that field
order caused the user's original session: the available saved profile contains
none of that session's storage selections. A real content change or moved,
ambiguous selection still requires reselection and now has a clearer message.

## Rerun

```powershell
$env:MOVRAND_STORAGE_WORLD = "$env:APPDATA/ModrinthApp/profiles/Fabric 26.2/saves/New World - Basalt Destroyer Test 2026-09-07"
$env:MOVRAND_STORAGE_PROFILE = "$env:APPDATA/ModrinthApp/profiles/Fabric 26.2/config/movrand-profiles/Main sweep config.json"
./gradlew.bat --no-daemon runClientGameTest
```

For the existing storage suite, unset those two variables, set
MOVRAND_STORAGE_ONLY=true, and run ./gradlew.bat --no-daemon build runClientGameTest.
The build includes fingerprint self-checks for reordered fields, different
item types, changed contents and invalid fingerprints.

## Final validation and installation

The full build (all self-checks) and existing storage client game tests passed:
BUILD SUCCESSFUL, 40 tasks, exit 0. See storage-regression-build.log. Existing
occupied-return-slot, missing-Silk-Touch and unsafe-extraction stops still passed.

Installed build/libs/movrand-1.0.0.jar into the Modrinth Fabric 26.2 mods folder.
SHA-256 was verified against the built artifact. Previous jar backup:
C:\Users\damia\AppData\Roaming\ModrinthApp\profiles\Fabric 26.2\backups\movrand-before-storage-fix-20260908-220939.jar
