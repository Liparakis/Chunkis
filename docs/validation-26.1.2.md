# Chunkis 4.2.0 validation

Minecraft **26.1.2**, Java **25**, Fabric Loader **0.20.1**, Fabric API **0.155.3+26.1.2**.
The test PC has a Ryzen 7 5700X at 4.6 GHz and 32 GB DDR4-3600 RAM.

## Entity-only save repair

Exploring natural terrain with C2ME, Lithium and FerriteCore exposed an existing persistence gap:
vanilla can save an entity section after its terrain chunk is no longer held. Chunkis cancelled the native
save even when there was no tracked or persisted terrain delta to attach the entities to.

The instrumented run identified six saveable entity UUIDs. An offline audit of copies of its CIS regions,
unfinished-chunk records and vanilla entity regions found none of those six UUIDs. A focused regression
then reproduced the failure without ticking or despawning: saving a persistent cow through the actual
entity storage interface at a position with no held chunk produced neither a native record nor a CIS
record. This is separate from the earlier data-fixer investigation; it is not evidence that the version
port introduced the bug.

The repair saves a complete native entity snapshot under the dimension's `chunkis/entity-fallback/`
directory whenever terrain cannot safely accept it. It never creates a terrain delta. Once a position
uses this store, later entity saves continue there. Empty lists are durable snapshots too: they prevent
stale CIS payloads from resurrecting removed entities. Bulk and queued CIS replay defer to this store;
loading uses vanilla's entity data fixer and deserializer, including passenger trees.

Each gzip NBT file has an independent version-1 envelope, chunk coordinates and Minecraft DataVersion.
CIS terrain stays at version 12, and released CIS data remains readable. Unknown versions, corrupt input
and misplaced coordinates fail explicitly; an unreadable snapshot cannot be replaced by an ordinary
save. Writes force a temporary file to disk and atomically replace the previous snapshot before the
native write is cancelled. A filesystem without atomic replacement support fails the save explicitly.

The tradeoff is synchronous filesystem work for this fallback path and one small file per affected
chunk. Empty snapshots are retained, and ownership is not automatically transferred back to CIS.
No performance improvement is claimed for this repair. Do not open a 4.2.0 world with older Chunkis
versions: they do not know about this independent entity store.

## Automated checks

- Full Gradle build, Checkstyle, **252 unit tests** and **47 game tests** passed.
- New checks cover entity-only persistence with no held terrain, passenger UUIDs and riding relationships,
  empty replacements, stale queued CIS replay, opaque metadata, invalid coordinates, unknown versions,
  truncated input and preservation of an unreadable existing file.
- C2ME matrix passed **27 configurations × 47 game tests = 1,269 successful game tests**: all
  26 publisher-listed builds for 26.1.2, plus the latest listed C2ME with Lithium and FerriteCore.
- Copied-world upgrade/reopen passed both without optional mods and with C2ME
  0.4.0-alpha.0.65+26.1.2, Lithium mc26.1.2-0.24.7-fabric and FerriteCore 9.0.0-fabric.
  Both runs matched all fifteen full-chunk hashes, twelve chests and named persistent entity UUIDs,
  then retained changed block markers and item counts after saving and cold reopening.
  Storage guards were active for all three dimensions. All 86 original source files retained their hashes.
- Exploration workload passed with C2ME, Lithium and FerriteCore: three 4,096-block legs over
  natural terrain at three blocks per tick. An independent cold reopen of a copy restored all
  **24 entity UUIDs** present in its fallback snapshots through the actual world entity loader.
- The quick storage workload completed in both modes: 576 natural-terrain chunks, one measured
  round each, followed by `guard.py --profile quick`. No quick baseline exists, so the check
  provides workload-completion evidence rather than a performance regression verdict. The workloads
  used isolated launch directories and PID-specific timeouts, avoiding the benchmark shell wrapper's
  process-wide cleanup. Raw reports remain under the ignored `benchmarks/runs/4.2.0-entity-fix-quick/`.

## Scope

The copied source was an actual persisted Minecraft 1.21.11 world. Tests ran on isolated copies;
the source files were hash-audited. Deterministic fixtures add chests, items, named persistent entities
and block markers in Overworld, Nether and End, including negative coordinates and region boundaries.
Full-block hashes cover twelve fixture chunks and three pre-existing CIS chunks. Simulation was frozen
after control runs showed fluid updates could change hashes on the same Minecraft version. Switching
the old-version fixture from unguarded to guarded storage also changed 181 natural stone states in two
chunks, while placed markers, inventories and entities matched. A second guarded 1.21.11 control was
identical; the guarded upgrade comparisons use that stable baseline. This does not establish that every
naturally generated block is invariant when changing storage modes.

These are automated server-side checks, not a manual client/portal playthrough or a guarantee for every
mod combination. The C2ME list covers the builds tagged for 26.1.2 by their publisher at test time;
it does not claim compatibility with future builds. Native file fixing returned Windows access
denied errors moving its temporary upgraded directory; an independent vanilla control and retries
succeeded. No cause was established for that transient failure.

Timing runs on this actively used PC cannot establish a performance regression verdict. The README's
published comparison remains the earlier benchmark; these checks do not replace it with new numbers.

## C2ME builds tested

Each row passed all 47 game tests on Minecraft 26.1.2. Links identify the exact publisher build.

| C2ME configuration | Result |
|:---|:---|
| [0.4.0-alpha.0.65+26.1.2](https://modrinth.com/mod/c2me-fabric/version/to1sDRnG) | 47 / 47 |
| [0.4.0-alpha.0.64+26.1.2](https://modrinth.com/mod/c2me-fabric/version/mt1A6r9k) | 47 / 47 |
| [0.4.0-alpha.0.62+26.1.2](https://modrinth.com/mod/c2me-fabric/version/o1m4A4Rk) | 47 / 47 |
| [0.4.0-alpha.0.54+26.1.2](https://modrinth.com/mod/c2me-fabric/version/riLBXg84) | 47 / 47 |
| [0.4.0-alpha.0.52+26.1.2](https://modrinth.com/mod/c2me-fabric/version/2TRA2j3R) | 47 / 47 |
| [0.4.0-alpha.0.44+26.1.2](https://modrinth.com/mod/c2me-fabric/version/P1edoXUI) | 47 / 47 |
| [0.4.0-alpha.0.35+26.1.2](https://modrinth.com/mod/c2me-fabric/version/22VT4H6y) | 47 / 47 |
| [0.4.0-alpha.0.34+26.1.2](https://modrinth.com/mod/c2me-fabric/version/FuYKtpKR) | 47 / 47 |
| [0.4.0-alpha.0.31+26.1.2](https://modrinth.com/mod/c2me-fabric/version/h6qi8QiJ) | 47 / 47 |
| [0.4.0-alpha.0.28+26.1.2](https://modrinth.com/mod/c2me-fabric/version/1qBYfTmr) | 47 / 47 |
| [0.4.0-alpha.0.26+26.1.2](https://modrinth.com/mod/c2me-fabric/version/N7aCY6uc) | 47 / 47 |
| [0.4.0-alpha.0.22+26.1.2](https://modrinth.com/mod/c2me-fabric/version/NEhjPLVa) | 47 / 47 |
| [0.4.0-beta.1.0+26.1.2](https://modrinth.com/mod/c2me-fabric/version/zzYSg63u) | 47 / 47 |
| [0.4.0-alpha.0.20+26.1.2](https://modrinth.com/mod/c2me-fabric/version/6d5uLLGd) | 47 / 47 |
| [0.4.0-alpha.0.17+26.1.2](https://modrinth.com/mod/c2me-fabric/version/v1RNsfu7) | 47 / 47 |
| [0.4.0-alpha.0.16+26.1.2](https://modrinth.com/mod/c2me-fabric/version/U1sqPi6C) | 47 / 47 |
| [0.4.0-alpha.0.13+26.1.2](https://modrinth.com/mod/c2me-fabric/version/f4MeSe4D) | 47 / 47 |
| [0.4.0-alpha.0.12+26.1.2](https://modrinth.com/mod/c2me-fabric/version/eB81jWsG) | 47 / 47 |
| [0.4.0-alpha.0.9+26.1.2](https://modrinth.com/mod/c2me-fabric/version/OP2HbGbP) | 47 / 47 |
| [0.4.0-alpha.0.4+26.1.2](https://modrinth.com/mod/c2me-fabric/version/MmyZoUyp) | 47 / 47 |
| [0.4.0-alpha.0.2+26.1.2](https://modrinth.com/mod/c2me-fabric/version/ViTwuKCV) | 47 / 47 |
| [0.3.7+alpha.0.69+26.1.2](https://modrinth.com/mod/c2me-fabric/version/iFyIEVsG) | 47 / 47 |
| [0.3.7+alpha.0.68+26.1.2](https://modrinth.com/mod/c2me-fabric/version/utLSz8Lf) | 47 / 47 |
| [0.3.7+alpha.0.67+26.1.2](https://modrinth.com/mod/c2me-fabric/version/lWpNUM5l) | 47 / 47 |
| [0.3.7+alpha.0.65+26.1.2](https://modrinth.com/mod/c2me-fabric/version/yrNQQ1AQ) | 47 / 47 |
| [0.3.7+alpha.0.64+26.1.2-rc-1](https://modrinth.com/mod/c2me-fabric/version/Fykexbsm) | 47 / 47 |
| [0.4.0-alpha.0.65+26.1.2 + Lithium + FerriteCore](https://modrinth.com/mod/c2me-fabric/version/to1sDRnG) | 47 / 47 |
