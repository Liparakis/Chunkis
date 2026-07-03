# MCA to CIS Migration Investigation Summary

## Overview

The offline MCA-to-CIS migration path (`OfflineMcaCisTranslator`) converts vanilla `.mca` region files into authoritative Chunkis CIS snapshots before world launch. This document records the validation strategy, comparison semantics, retirement rules, and test coverage introduced to guarantee lossless round-trips.

## Validation Result Type

`MigrationValidationResult` encapsulates success/failure with:
- `success` – boolean outcome
- `failureCode` – stable machine-readable identifier (e.g., `BLOCK_STATE_MISMATCH`)
- `details` – human-readable diagnostic (counts, positions, keys)

Returned by `validateMigratedChunk` and the seven named validation helpers inside `matchesMigratedChunkShape`.

## Named Validation Methods

`matchesMigratedChunkShape` was refactored into:
1. `validateBlocks` – position set + canonical state key comparison
2. `validateBlockEntities` – key presence + normalized NBT match
3. `validateEntities` – sorted stringified list equality
4. Structure/auxiliary metadata checks (via `NbtHelper.matches`)
5. Count checks for blocks, block entities, entities
6. Baseline/authoritative flag checks
7. Null/empty guard checks

## Block Comparison (Semantic)

`sameBlocks` replaced by `validateBlocks` using `canonicalBlockStateKey`:
- Registry ID (namespaced string)
- Properties sorted alphabetically by name
- Format: `minecraft:stone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`

This ignores internal palette id ordering and property iteration order.

## Block Entity Comparison

`validateBlockEntities` reports the first mismatching key (packed long position) and differing top-level keys when `NbtHelper.matches` fails. Volatile fields (`x`,`y`,`z`,`id`) are intentionally kept; any difference in their values or presence is reported.

## Entity Comparison

Entity payloads are stringified, sorted, and compared as lists. Order-independent equality is preserved.

## Logging Strategy

- First 10 mismatches per region are logged at WARN with full details (position + expected/actual).
- Subsequent mismatches are counted and summarized once at the end of the region.
- Validation failures surface `failureCode` + `details` in the error log.

## Retirement Rules (Strict – Unchanged)

A source `.mca` region is retired to `.backup` only when:
- `failedChunks == 0`
- `presentChunks == handledChunks`

Any validation failure, I/O error, or omitted placeholder failure keeps the original region in place. This rule is intentionally strict and was never relaxed.

## Unit Test Coverage (CisStorageMigratorTest + new tests)

- Round-trip identity for normal chunks
- Property preservation: stairs (facing, half, shape, waterlogged), slabs (type, waterlogged), fences (connected states), doors (hinge, half, facing, open), trapdoors, buttons, signs (rotation, waterlogged)
- Negative coordinates and regions `r.-1.-1`
- Block entity round-trips with complex NBT
- Entity list preservation (order independent)
- Empty placeholder omission (`isOmittableEmptyChunk`)
- Validation failure paths (count mismatch, state mismatch, missing markers)

All tests pass `lint` and `test` targets.
