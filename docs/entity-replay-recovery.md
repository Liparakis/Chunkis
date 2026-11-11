# Entity replay repair — Minecraft 26.3

Release: [Chunkis 4.2.1](https://github.com/Liparakis/Chunkis/releases/tag/v4.2.1-mc26.3).

Saved dropped items and mobs could remain invisible after reopening a world. Their CIS payloads
could still be present: the restore queue shared one progress cursor between dimensions. A Nether
or End tick could reset an Overworld drain after its two-millisecond budget expired, causing the
same blocked prefix to be retried while later, ready entries waited indefinitely.

This repair tracks progress per dimension and resumes at the complete chunk/dimension/UUID key,
including within a dense chunk. Server shutdown clears all cursor state. It retains the existing
time budget, readiness checks, spawn rules and duplicate handling. No stored layout changes or
migration are needed. Existing saved pending entities can be restored; missing data is not invented.

The defect was reproduced on a copied Minecraft 26.3 world: six test entities were saved but absent
on cold reopen; the repair restored all six from the same files. In a fresh copy of the original
world, all 454 sampled stored UUIDs restored and survived another save/cold reopen. The original
world remained unchanged. Those world-copy checks apply to 26.3; older targets are tested separately.

## Validation on this branch

- Full build and Checkstyle passed: **258 unit tests and 47 game tests**.
- All **47 game tests passed with C2ME 0.4.2-alpha.0.115+26.3 with Lithium/FerriteCore/ScalableLux**.
- An isolated dedicated server with C2ME saved a pig and a 17-diamond dropped stack in each of the
  Overworld, Nether and End. All six UUIDs and item counts matched after a cold reopen.
- Three alternating quick natural-terrain benchmark pairs completed and the quick regression guard
  returned success. No saved quick baseline was available; no new performance claim is made.
  The full idle-machine timing benchmark was not run.

Five deterministic regression tests cover blocked-prefix progress across dimension ticks,
resumption within a chunk, independent dimension cursors, removal/wraparound and lifecycle clearing.
Raw logs are retained locally under the ignored `.gradle/entity-replay-releases/` directory.
