# Entity restoration after reopening a world

Repair for Chunkis **4.2.1** on the `26.3` branch, investigated on 11 November 2026.
The replaced `v4.2.0-mc26.3` release did not contain this repair.
See the [release validation report](entity-replay-recovery.md) for this branch.

## Symptoms and cause

Dropped items and mobs can be absent after reopening even though their complete entity payloads
are still saved in CIS and attached to the loaded chunk's pending list.

The replay queue limits work to two milliseconds per dimension tick. It previously shared one
cursor across all dimensions. An Overworld drain could exhaust its budget, then a Nether or End
tick with no work would reset that cursor. The next Overworld tick restarted from the beginning.
A sufficiently large prefix of entities in chunks not yet ready for entity loading could repeatedly
consume the budget, leaving later, ready entities waiting indefinitely.

The old cursor also remembered only a chunk, so it could restart at that chunk's first entity
rather than the exact unprocessed UUID.

## Repair

- Retain a separate cursor for each dimension. Completing one dimension's drain cannot reset another.
- Resume at the complete queue key: chunk, dimension and UUID. If the entry was removed, resume at its
  successor or wrap around the current queue snapshot.
- Clear all cursor state with the queue when the server stops.

The two-millisecond budget, readiness checks, spawn rules and duplicate detection remain in place.
No stored layout changes: CIS remains version 12 and entity fallback snapshots remain version 1.
Existing saved pending payloads can be restored without a migration. This does not reconstruct
entities that were never saved or legitimately despawned.

## Reproduction and validation

The installed mod matched the published release's SHA-256. The copied profile used C2ME
`0.4.2-alpha.0.115+26.3`, Lithium `0.26.3+mc26.3`, FerriteCore `9.0.0` and
ScalableLux `0.3.0-alpha.0.6+26.3` for the server-side reproduction. This is a queue scheduling
failure, not evidence that a particular optional mod caused it.

- The release build saved three pigs and three dropped diamond stacks in three positions, covering
  an edited chunk, an existing terrain record and newly generated terrain. After an ordinary server
  stop and cold reopen, all six UUIDs were absent from the live world. Their payloads remained on disk
  and in pending state. Repeated retries with normal level ticks did not restore them.
- The repaired queue restored all six UUIDs from those same saved files without changing the format.
- A fresh copy of the original profile world contained 454 stored root entity UUIDs in an 81-chunk
  sample. Forcing those chunks with simulation frozen restored all 454. Saving and cold reopening
  restored the same 454 again. This is an automated dedicated-server copy test, not a manual play session.
- Full Gradle build, Checkstyle, 258 unit tests and all 47 game tests passed. Five new deterministic
  tests cover a blocked prefix across dimension ticks, resumption within a dense chunk, independent
  dimension cursors, cursor removal/wraparound and server-lifecycle clearing.
- All 47 game tests also passed with the four server-side mods above.
- Three alternating quick natural-terrain benchmark pairs completed, and the quick regression guard
  ran successfully. No saved quick baseline was available; these are functional checks, not a new
  performance comparison. The full idle-machine timing benchmark was not run.
- All 102 files in the original Prism world retained their hashes after the copied-world tests.

The probes, source hashes and raw logs are retained locally under the ignored
`.gradle/entity-loss-26.3-2026-11-11/` directory. Copied test worlds and benchmark data are not shipped
in the mod JAR.
