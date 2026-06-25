# File Instrumentation Map

This audit covers all production Java source roots in this repository:

- `core/src/main/java`
- `fabric/src/main/java`

Validation sources are audited separately:

- `core/src/test/java`
- `fabric/src/test/java`
- `fabric/src/gametest/java`

## Classification Legend

- `CORE_HOOK_REQUIRED`
- `SUPPORTING_HOOK_REQUIRED`
- `INSPECTION_ONLY`
- `NO_DEBUG_HOOK_NEEDED`
- `UNKNOWN_NEEDS_REVIEW`
- `DEBUG_VALIDATION_SUPPORT` for tests and game tests only
- `PHASE_2_PARTIAL` status means initial save/load/restore tracing landed
- `PHASE_2_IMPLEMENTED` status means the file now exists specifically for the Phase 2 trace surface

## Source Audit Table: Production

| File | Classification | Status | Why |
| --- | --- | --- | --- |
| `core/src/main/java/io/liparakis/chunkis/Chunkis.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Static mod id/logger holder only. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkisDebugLevel.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Minimal runtime debug level switch; defaults to OFF. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkisDebugDomain.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Canonical domain taxonomy for structured events. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceEventType.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Canonical event-type taxonomy used by save/load/restore hooks. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceSeverity.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Minimal severity model for structured events. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceReason.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Machine-readable reason taxonomy for current Phase 2 outcomes. |
| `core/src/main/java/io/liparakis/chunkis/debug/DebugChunkKey.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Small optional chunk-coordinate payload for structured traces. |
| `core/src/main/java/io/liparakis/chunkis/debug/DebugRegionKey.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Small optional region-coordinate payload for structured traces. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceEvent.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Canonical immutable event record used by the flight recorder. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkTraceStore.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Bounded in-memory event store with monotonic ids and near-zero OFF-path cost. |
| `core/src/main/java/io/liparakis/chunkis/debug/ChunkisDebugConfig.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Global runtime switch controlling whether structured events are recorded. |
| `core/src/main/java/io/liparakis/chunkis/core/BlockInstruction.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Packed coordinate helper; debug at codec boundaries, not per helper call. |
| `core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Dirty/clean transitions now emit structured lifecycle evidence. |
| `core/src/main/java/io/liparakis/chunkis/core/CisChunkPos.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Immutable coordinate carrier only. |
| `core/src/main/java/io/liparakis/chunkis/core/Palette.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Palette insert/lookup failures matter for decode and network sync. |
| `core/src/main/java/io/liparakis/chunkis/spi/BlockRegistryAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | SPI interface only. |
| `core/src/main/java/io/liparakis/chunkis/spi/BlockStateAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | SPI interface only. |
| `core/src/main/java/io/liparakis/chunkis/spi/NbtAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | SPI interface only. |
| `core/src/main/java/io/liparakis/chunkis/spi/PropertyValueAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | SPI interface only. |
| `core/src/main/java/io/liparakis/chunkis/storage/bits/BitReader.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Bit-level utility; emit at decode caller, not every read. |
| `core/src/main/java/io/liparakis/chunkis/storage/bits/BitUtils.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Stateless math helpers only. |
| `core/src/main/java/io/liparakis/chunkis/storage/bits/BitWriter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Bit-level utility; emit at encode caller, not every write. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/AbstractCisDecoder.java` | `CORE_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Decode path for stored/network payloads; Phase 2 now classifies storage-boundary failures without instrumenting decoder internals. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/AbstractCisEncoder.java` | `CORE_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Encode path for save/network payloads, section decisions, metadata/block-entity/entity serialization. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/CisDecoder.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Storage decoder wrapper; failure classification currently happens at `CisStorage` rather than per decode sub-step. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/CisEncoder.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Storage encoder wrapper and global palette encode entrypoint. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/network/CisNetworkDecoder.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Client/network palette resolution and payload decode branch. |
| `core/src/main/java/io/liparakis/chunkis/storage/codec/network/CisNetworkEncoder.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Server/network payload encode branch. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/CisRegionCompactor.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Offline maintenance/reporting path, not normal durability runtime. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/CisRegionInspector.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Existing storage inspection support to reuse later instead of duplicating browser logic. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Save/load start-end, flush lifecycle, and storage-boundary decode-failure classification are now traced. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/CompressionContext.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Compression internals; useful only for future perf/fingerprint debugging. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/RegionFile.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Region read/write transaction boundaries are now traced. |
| `core/src/main/java/io/liparakis/chunkis/storage/io/RegionKey.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Small value object only. |
| `core/src/main/java/io/liparakis/chunkis/storage/mapping/BlockIdRegistry.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Persistent block-id mapping and unresolved-id failure surface. |
| `core/src/main/java/io/liparakis/chunkis/storage/mapping/CisAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Interface only. |
| `core/src/main/java/io/liparakis/chunkis/storage/mapping/CisMapping.java` | `CORE_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Mapping unresolved-id failures are now surfaced indirectly through `CisStorage` load classification; direct mapping hooks remain deferred. |
| `core/src/main/java/io/liparakis/chunkis/storage/mapping/PropertyPacker.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Property packing/unpacking influences palette/state decode correctness. |
| `core/src/main/java/io/liparakis/chunkis/storage/model/CisChunk.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Intermediate encoded chunk model; phase-2 hooks belong at encoder/decoder boundaries instead. |
| `core/src/main/java/io/liparakis/chunkis/storage/model/CisConstants.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Constants only. |
| `core/src/main/java/io/liparakis/chunkis/storage/model/CisSection.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Intermediate section representation; useful only if encode-layout decisions need deeper proof. |
| `fabric/src/main/java/io/liparakis/chunkis/adapter/FabricBlockRegistryAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Adapter cache wrapper; trace palette/mapping failures at callers instead. |
| `fabric/src/main/java/io/liparakis/chunkis/adapter/FabricBlockStateAdapter.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Adapter/reflection cache helper; no direct lifecycle decisions. |
| `fabric/src/main/java/io/liparakis/chunkis/adapter/FabricNbtAdapter.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Actual NBT raw/compressed read/write framing. |
| `fabric/src/main/java/io/liparakis/chunkis/api/ChunkisApi.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | API surface only. |
| `fabric/src/main/java/io/liparakis/chunkis/api/ChunkisDeltaDuck.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Marker/getter-setter interface only. |
| `fabric/src/main/java/io/liparakis/chunkis/api/impl/ChunkisApiImpl.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Thin API implementation; not part of durability lifecycle. |
| `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Registers the minimal debug command surface used to inspect the in-memory trace store. |
| `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaMetrics.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Existing client metrics/log throttling to reuse later. |
| `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaNetworking.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Client receive/apply start-end and top-level failure paths now emit structured sync evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaVisitor.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Client-side delta application details. |
| `fabric/src/main/java/io/liparakis/chunkis/ClientChunkisMod.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Client init wiring only. |
| `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Reproducer command and async teleport driver now emit run start/teleport/stop/failure events. |
| `fabric/src/main/java/io/liparakis/chunkis/command/ChunkDebugCommand.java` | `SUPPORTING_HOOK_REQUIRED` | `PHASE_2_IMPLEMENTED` | Minimal `/chunkis debug` command surface for on/off/latest/clear and readable timeline output. |
| `fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Existing storage/operator inspection surface to extend later, not hot runtime. |
| `fabric/src/main/java/io/liparakis/chunkis/migration/CisWorldMigrator.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Migration path only. |
| `fabric/src/main/java/io/liparakis/chunkis/migration/McaMigrator.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Migration path only. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/accessor/ChunkBlockEntityNbtAccessor.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Accessor only. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/network/ChunkHolderMixin.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Hook ordering for base chunk packet vs delta packet; actual Phase 2 sync tracing landed in `ChunkisNetworking`. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | High-level load source resolution and load transaction span now emit structured events. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixin.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Vanilla save cancellation now emits machine-readable evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Save requests and sparse-save rejection path now emit structured events. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/BlockEntityMixin.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Dirtying and base-capture interaction from block-entity edits. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/ChunkRegionMixin.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Entity-spawn side path; useful if entity churn affects dirty state. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/CommonChunkMixin.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Delta attachment and vanilla dirty-flag override. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/LeavesBlockMixin.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Leaf-decay noise suppression helper. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/NetherPortalBlockMixin.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Portal subsystem, not primary disappearance path. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/PortalForcerMixin.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Portal search diagnostics already use logging. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/SpawnHelperMixin.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Entity population cancellation path. |
| `fabric/src/main/java/io/liparakis/chunkis/mixin/world/WorldChunkMixin.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Restore operation-id threading and off-thread mutation assertion now exist; live mutation origin tracing still remains. |
| `fabric/src/main/java/io/liparakis/chunkis/network/ChunkDeltaPayload.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Compression decisions remain deferred; current sync events already capture payload byte size at callers. |
| `fabric/src/main/java/io/liparakis/chunkis/network/ChunkisNetworking.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Server-side delta send start-end and top-level drop/failure paths now emit structured sync evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/network/FabricNetworkCodecFactory.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Factory/singleton wiring only. |
| `fabric/src/main/java/io/liparakis/chunkis/portal/PortalArrivalFallback.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Separate portal fallback algorithm. |
| `fabric/src/main/java/io/liparakis/chunkis/portal/PortalChunkIndexManager.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Portal metadata/index sidecar; phase-2 priority is low. |
| `fabric/src/main/java/io/liparakis/chunkis/portal/PortalLinkManager.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Separate portal-link persistence subsystem. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/AsyncCisSaveManager.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Async queue acceptance and worker failure path now emit structured evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureScheduler.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Deferred/save-flush rejection path now emits structured evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureUtil.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Base chunk capture implementation and metadata attachment. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/ChunkisStoragePaths.java` | `NO_DEBUG_HOOK_NEEDED` | n/a | Pure path construction helper. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/CisNbtUtil.java` | `CORE_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Metadata envelope/base-NBT/full-baseline semantics. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/CisSnapshotCapture.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Authoritative snapshot capture step. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/DeltaPersistenceGuard.java` | `CORE_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Rejection reasons are central debug evidence. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/FabricCisStorageHelper.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Storage open/close lifecycle and per-dimension wrapper caching. |
| `fabric/src/main/java/io/liparakis/chunkis/storage/StructureMetadataExtractor.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Structure metadata capture and fallback branch. |
| `fabric/src/main/java/io/liparakis/chunkis/world/ChunkBlockEntityCapture.java` | `SUPPORTING_HOOK_REQUIRED` | `REVIEWED_NEEDS_HOOKS` | Block-entity capture/remove semantics. |
| `fabric/src/main/java/io/liparakis/chunkis/world/ChunkRestorer.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Restore start/completion/failure and aggregate applied counts now emit structured events. |
| `fabric/src/main/java/io/liparakis/chunkis/world/GlobalChunkTracker.java` | `CORE_HOOK_REQUIRED` | `PHASE_2_PARTIAL` | Dirty-map transitions, unload-cache put/evict/hit/miss, authoritative-delta keep, and stale async completion are now traced. |
| `fabric/src/main/java/io/liparakis/chunkis/world/LeafTickContext.java` | `INSPECTION_ONLY` | `NEEDS_RECHECK` | Noise-filter context only. |

## Source Audit Table: Validation / Test Support

| File | Classification | Current validation | Future observability assertions |
| --- | --- | --- | --- |
| `core/src/test/java/io/liparakis/chunkis/debug/ChunkTraceStoreTest.java` | `DEBUG_VALIDATION_SUPPORT` | Debug defaults OFF, monotonic bounded store behavior, newest-event retention. | Extend later to assert store export/query behavior if snapshots/export are added. |
| `core/src/test/java/io/liparakis/chunkis/debug/ChunkDeltaTraceTest.java` | `DEBUG_VALIDATION_SUPPORT` | Dirty and clean transitions emit the expected structured events. | Extend later to assert generation-sensitive clean transitions and rejection edge cases. |
| `core/src/test/java/io/liparakis/chunkis/storage/io/CisStorageTraceTest.java` | `DEBUG_VALIDATION_SUPPORT` | Save/load storage hooks emit flush and region read/write transaction events. | Extend later to assert failure paths and missing-entry timelines. |
| `core/src/test/java/io/liparakis/chunkis/storage/codec/CisUniformSectionCodecTest.java` | `DEBUG_VALIDATION_SUPPORT` | Section encoding shape and round-trip behavior. | Assert future encode/decode events include chosen section mode and palette counts. |
| `core/src/test/java/io/liparakis/chunkis/storage/io/CisStorageCompactionTest.java` | `DEBUG_VALIDATION_SUPPORT` | Region compaction preserves live chunks and failure safety. | Assert future region-write/read/compact events and compaction failure assertions. |
| `core/src/test/java/io/liparakis/chunkis/storage/io/CompressionContextTest.java` | `DEBUG_VALIDATION_SUPPORT` | Compression round trip. | Assert future optional compression metrics events only if perf tracing is added. |
| `core/src/test/java/io/liparakis/chunkis/storage/io/RegionFileFreeListTest.java` | `DEBUG_VALIDATION_SUPPORT` | Free-list reuse, footerless rebuild, corruption fallback. | Assert future region allocator events and corruption assertions. |
| `core/src/test/java/io/liparakis/chunkis/storage/mapping/CisMappingTest.java` | `DEBUG_VALIDATION_SUPPORT` | Mapping file creation, reload, unresolved-id rejection. | Assert future mapping/palette failure events. |
| `fabric/src/test/java/io/liparakis/chunkis/command/StorageReportCommandTest.java` | `DEBUG_VALIDATION_SUPPORT` | Storage inspection reports raw payload mix correctly. | Extend later to compare report output with trace/export summaries. |
| `fabric/src/test/java/io/liparakis/chunkis/command/ChunkDebugCommandTest.java` | `DEBUG_VALIDATION_SUPPORT` | Timeline formatter produces readable structured event lines for command output. | Extend later to assert command execution output once command harness coverage is worth the churn. |
| `fabric/src/test/java/io/liparakis/chunkis/command/DurabilityTestCommandTest.java` | `DEBUG_VALIDATION_SUPPORT` | Durability teleport target mapping to chunk coordinates used by trace events. | Extend later to assert start/stop/failure event emission once a low-churn command or scheduler harness exists. |
| `fabric/src/test/java/io/liparakis/chunkis/network/ChunkDeltaPayloadTest.java` | `DEBUG_VALIDATION_SUPPORT` | Payload compression/round-trip behavior. | Assert future client-sync payload size/compression decision events. |
| `fabric/src/test/java/io/liparakis/chunkis/storage/CisSnapshotCaptureTest.java` | `DEBUG_VALIDATION_SUPPORT` | Snapshot capture Y-coordinate correctness. | Extend to assert snapshot-capture event counts. |
| `fabric/src/test/java/io/liparakis/chunkis/util/CisNbtUtilTest.java` | `DEBUG_VALIDATION_SUPPORT` | Metadata envelope and suppression semantics. | Assert future metadata/base-NBT/full-baseline events. |
| `fabric/src/test/java/io/liparakis/chunkis/util/FabricCisStorageHelperTest.java` | `DEBUG_VALIDATION_SUPPORT` | Dimension-specific storage paths. | Minimal future use; only assert storage-open path metadata if added. |
| `fabric/src/gametest/java/io/liparakis/chunkis/gametest/AsyncSaveDataLossGameTest.java` | `DEBUG_VALIDATION_SUPPORT` | Durability round trips and persisted base-chunk presence. | Prime candidate to assert save/load/restore transaction events later. |
| `fabric/src/gametest/java/io/liparakis/chunkis/gametest/CisFixtureMigrationGameTest.java` | `DEBUG_VALIDATION_SUPPORT` | Legacy fixture migration correctness. | Assert migration-related inspection events only if migration tracing is added. |
| `fabric/src/gametest/java/io/liparakis/chunkis/gametest/LegacyEntityStorageHandoffGameTest.java` | `DEBUG_VALIDATION_SUPPORT` | One-time legacy entity replay handoff. | Assert future restore/entity-handoff events. |
| `fabric/src/gametest/java/io/liparakis/chunkis/gametest/StructureMetadataExtractorGameTest.java` | `DEBUG_VALIDATION_SUPPORT` | Direct structure extraction matches vanilla serialization. | Assert structure-capture success/fallback events later. |

## Instrumentation Coverage

### Production audit coverage after Phase 2 pass 1

- total production Java files scanned: `89`
- production files requiring runtime hooks: `49`
- production files marked inspection only: `17`
- production files intentionally skipped for hooks: `23`
- production files still needing review: `0`
- production files fully instrumented: `11`
- production files partially instrumented: `10`

### Validation audit coverage after Phase 2 pass 1

- total validation Java files scanned: `18`
- validation files classified as `DEBUG_VALIDATION_SUPPORT`: `18`
- validation files already asserting observability events: `4`
- validation files needing future observability assertions: `18`

## Phase 2 implementation status

- Minimal trace runtime is now shared under `core/.../debug/*`, not `fabric`-only.
- Debug remains OFF by default through `ChunkisDebugConfig`.
- High-value save path hooks now exist in `ChunkDelta`, `CisStorage`, `RegionFile`, `StoragePreventionMixin`, `ThreadedAnvilChunkStorageMixin`, `AsyncCisSaveManager`, and `BaseChunkCaptureScheduler`.
- High-value load/restore hooks now exist in `ChunkSerializerMixin` and `ChunkRestorer`.
- Minimal operator command surface now exists in `ChunkDebugCommand`:
  `/chunkis debug on`, `/chunkis debug off`, `/chunkis debug latest <count>`, `/chunkis debug clear`.
- Not yet instrumented in this pass:
  `GlobalChunkTracker`, `WorldChunkMixin`, networking/client-sync, codec/mapping failure surfaces, watchpoints/export, and stronger invariant enforcement.

## Detailed Production Sections

## `core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Owns mutable block, block-entity, entity, metadata, dirty, and generation state for one chunk delta.

### Important methods

- `addBlockChange`
- `removeBlockChange`
- `addBlockEntityData` / `removeBlockEntityData`
- `setEntities`
- `setChunkMetadata`
- `markDirtyIfClean`
- `markSaved` / `markSavedIfGeneration`
- `snapshot`

### Debug hooks to add

- `DELTA_MARKED_DIRTY` on clean->dirty transition
- metadata replaced / entity payload replaced events in `VERBOSE`
- future fingerprint fields at snapshot/save boundaries only

### Invariants to check

- dirty transitions visible
- clean transition only after successful persistence
- metadata not silently dropped

### Risk notes

- Shared mutable state is touched by save, load, restore, and async-save logic.
- Generation-based clean semantics are central to stale worker-save avoidance.

### Status

`PHASE_2_PARTIAL`

### Next action

Dirty/clean transitions are implemented. Follow-up work is tracker-level correlation and stronger generation/invariant checks.

## `core/src/main/java/io/liparakis/chunkis/core/BlockInstruction.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Packs and unpacks local block coordinates plus palette index for delta instructions.

### Important methods

- `fromPacked`
- `packPos`
- `unpackX` / `unpackY` / `unpackZ`
- `pack`

### Debug hooks to add

- none initially

### Invariants to check

- if coordinate corruption is ever suspected, verify it at codec-level summaries instead of per instruction

### Risk notes

- Too low-level for the first instrumentation pass.

### Status

`NEEDS_RECHECK`

### Next action

Leave uninstrumented unless later evidence points to packed-coordinate corruption.

## `core/src/main/java/io/liparakis/chunkis/core/Palette.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Simple id<->entry palette used by codecs.

### Important methods

- `getOrAdd`
- `get`
- `copy`

### Debug hooks to add

- no per-call hooks by default
- only emit palette size / lookup failure context from codec callers

### Invariants to check

- palette id failures must name the id and caller

### Risk notes

- Noise risk is high if instrumented directly.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Hook failures from decoder/encoder callers, not from this class itself.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/AbstractCisDecoder.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Decodes stored/network CIS payloads into `ChunkDelta`, including section modes, block entities, entities, and metadata.

### Important methods

- `decodeInternal`
- `decodeSections`
- `decodeSparseSection`
- `decodeUniformSection`
- `decodeDenseSection`

### Debug hooks to add

- `REGION_READ_TX_END` decode result details at caller boundary
- `PALETTE_LOOKUP_FAILED`
- metadata present/missing
- block/block-entity/entity counts after decode

### Invariants to check

- non-empty payload should not disappear silently into empty logical state
- palette/mapping failures must be explicit

### Risk notes

- Current warnings are string logs, not structured evidence.
- Deep per-entry tracing here would be too expensive unless watched/paranoid.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit one summary event per decode plus explicit failure events.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/AbstractCisEncoder.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Encodes a `ChunkDelta` into CIS bytes, choosing section layouts and serializing metadata and entity payloads.

### Important methods

- `encodeInternal`
- `writeSections`
- `writeBlockEntities`
- `writeEntities`
- `writeChunkMetadata`

### Debug hooks to add

- snapshot/encode started-completed summary
- section encoding mix summary
- metadata/entity payload counts

### Invariants to check

- encoded payload count summary must match source delta counts

### Risk notes

- Per-section spam would be too expensive globally.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Add a single encode summary event behind `VERBOSE`.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/CisDecoder.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Storage decoder wrapper with storage-specific palette resolution.

### Important methods

- `decode`
- `decodeGlobalPalette`

### Debug hooks to add

- none beyond wrapper-level source tagging

### Invariants to check

- palette failures need storage context

### Risk notes

- Wrapper should tag source=`storage`, not re-log internals.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Use this wrapper to tag storage decode source in Phase 2.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/CisEncoder.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Storage encoder wrapper with storage-specific global palette writes.

### Important methods

- `encode`
- `writeGlobalPaletteEntry`

### Debug hooks to add

- none beyond wrapper-level source tagging

### Invariants to check

- encode source context should distinguish storage from network payloads

### Risk notes

- Better to tag once here than duplicate at multiple callers.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Use wrapper source tagging only.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/network/CisNetworkDecoder.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Client/network decoder path with block registry resolution.

### Important methods

- `decode`
- `decodeGlobalPalette`
- `readBlocks`

### Debug hooks to add

- payload decode summary at caller boundary
- palette lookup failures with client-sync context

### Invariants to check

- client decode failures should preserve chunk coordinates and payload size

### Risk notes

- Re-decodes in current client metrics path already duplicate work; phase-2 tracing should avoid making that worse.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit failures from `ClientDeltaNetworking`, not deep per-read hooks.

## `core/src/main/java/io/liparakis/chunkis/storage/codec/network/CisNetworkEncoder.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Server/network encoder path for delta packets.

### Important methods

- `encode`
- `writeGlobalPaletteEntry`

### Debug hooks to add

- payload size/compression-decision summary at caller boundary

### Invariants to check

- client sync should record byte size before send/drop

### Risk notes

- Best instrumented from `ChunkisNetworking`.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Keep hooks at networking boundary.

## `core/src/main/java/io/liparakis/chunkis/storage/io/CisRegionCompactor.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Offline region compaction and report generation.

### Important methods

- `compact`
- `compactRegionPath`
- `compactAndCloseRegion`

### Debug hooks to add

- none in Phase 2 hot-path work

### Invariants to check

- reuse existing tests instead of runtime hooks

### Risk notes

- Not part of normal disappearance reproduction path.

### Status

`NEEDS_RECHECK`

### Next action

Revisit only if operator commands later integrate compaction reporting.

## `core/src/main/java/io/liparakis/chunkis/storage/io/CisRegionInspector.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Reads region files for storage usage summaries.

### Important methods

- `inspect`
- `inspectRegionPath`

### Debug hooks to add

- none; reuse as future debug inspect/export support

### Invariants to check

- none in runtime path

### Risk notes

- Valuable as a future command backend, not as a hot-path instrument target.

### Status

`NEEDS_RECHECK`

### Next action

Reuse for future storage summary commands instead of writing a new inspector.

## `core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Top-level storage save/load/prepare/write/read/close wrapper with region-cache and codec/compression thread locals.

### Important methods

- `save`
- `prepareSave`
- `writePrepared`
- `load`
- `loadWithoutClearing`
- `clearChunk`
- `loadUnchecked`
- `getRegionFile`

### Debug hooks to add

- `SAVE_FLUSH_STARTED`
- `SAVE_FLUSH_COMPLETED`
- `SAVE_FLUSH_FAILED`
- `REGION_READ_TX_START`
- `REGION_READ_TX_END`

### Invariants to check

- clean after success only
- read-back verification optional under `PARANOID`
- missing/corrupt payload paths explicit

### Risk notes

- This is the narrowest shared storage boundary; instrument here once instead of at every caller.

### Status

`PHASE_2_PARTIAL`

### Next action

This is now the canonical storage event boundary for Phase 2. Future work is read-back verification and richer world/path correlation.

## `core/src/main/java/io/liparakis/chunkis/storage/io/CompressionContext.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Compression/decompression scratch context.

### Important methods

- compress/decompress internals

### Debug hooks to add

- none unless optional performance tracing is added later

### Invariants to check

- compression round-trip is already unit-tested

### Risk notes

- Hot inner utility; instrumenting it directly would be noise.

### Status

`NEEDS_RECHECK`

### Next action

Keep out of phase-2 hooks.

## `core/src/main/java/io/liparakis/chunkis/storage/io/RegionFile.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Actual region-slot allocator and footer metadata owner for chunk payloads.

### Important methods

- `read`
- `write`
- `flush`
- `compact`
- `loadAllocationMetadata`
- `allocate`
- `writeMetadata`

### Debug hooks to add

- `REGION_READ_TX_START/END`
- `REGION_WRITE_TX_START/END`
- allocation strategy details in `VERBOSE`
- corruption / invalid slot assertion events

### Invariants to check

- region index must not point outside payload area
- write success should optionally be read-back verifiable

### Risk notes

- This is the best place to answer “did the region file actually contain the chunk after write?”

### Status

`PHASE_2_PARTIAL`

### Next action

Read/write transaction boundaries are implemented. Future work is allocator/corruption surfacing and optional read-back verification.

## `core/src/main/java/io/liparakis/chunkis/storage/mapping/BlockIdRegistry.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Persistent block id registry and unresolved-id bookkeeping.

### Important methods

- id registration/loading helpers

### Debug hooks to add

- explicit unresolved-id failure events

### Invariants to check

- missing block id must be surfaced with id value and decode source

### Risk notes

- Silent fallback here would poison decode diagnosis.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit only failure/registration summary events.

## `core/src/main/java/io/liparakis/chunkis/storage/mapping/CisMapping.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Loads, flushes, and resolves persistent block mappings.

### Important methods

- constructor/load path
- `getBlockId`
- `flush`

### Debug hooks to add

- mapping load/flush summaries
- unresolved block-id failure events

### Invariants to check

- palette/mapping failures explicit

### Risk notes

- Mapping corruption can masquerade as general decode corruption.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Add failure events and low-noise load/flush summaries.

## `core/src/main/java/io/liparakis/chunkis/storage/mapping/PropertyPacker.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Property packing/unpacking for block states.

### Important methods

- `writeProperties`
- `readProperties`

### Debug hooks to add

- none unless property decode failures need explicit surfacing

### Invariants to check

- property decode failure should surface from caller

### Risk notes

- Inner utility; avoid direct instrumentation until a real failure points here.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Keep hooks at decoder boundary first.

## `core/src/main/java/io/liparakis/chunkis/storage/model/CisChunk.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Intermediate encoded chunk section container.

### Important methods

- `addUniqueBlock`
- `getOrCreateSection`

### Debug hooks to add

- none initially

### Invariants to check

- handled indirectly by encode/decode tests

### Risk notes

- Better to instrument the serializer decisions above it.

### Status

`NEEDS_RECHECK`

### Next action

Only revisit if section-layout issues remain ambiguous after phase-2 events.

## `core/src/main/java/io/liparakis/chunkis/storage/model/CisSection.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Sparse/dense/uniform section representation.

### Important methods

- `appendDeltaBlock`
- `convertToDense`

### Debug hooks to add

- none initially

### Invariants to check

- encode layout validated through codec tests

### Risk notes

- Direct hooks would be too fine-grained.

### Status

`NEEDS_RECHECK`

### Next action

Use encode summary events instead of direct section tracing.

## `fabric/src/main/java/io/liparakis/chunkis/adapter/FabricNbtAdapter.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Reads/writes raw and compressed NBT payloads for storage/network codecs.

### Important methods

- `writeCompressed`
- `readCompressed`
- `writeRaw`
- `readRaw`

### Debug hooks to add

- only failure summaries

### Invariants to check

- NBT serialization failures should keep caller/source context

### Risk notes

- Best used as a failure-source tag, not a verbose trace emitter.

### Status

`PHASE_2_PARTIAL`

### Next action

Minimal debug command registration is implemented. Future work is shutdown/save-safety event correlation.

## `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Registers commands, events, lifecycle hooks, and shutdown flush behavior.

### Important methods

- `onInitialize`
- `registerEvents`
- `flushBeforeServerStop`
- `flushPendingDeltas`

### Debug hooks to add

- phase-2 bootstrap/config events
- shutdown flush summaries

### Invariants to check

- pending dirty deltas at shutdown must not be silent

### Risk notes

- Useful for world-stop evidence, not for noisy per-tick events.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Add shutdown summary events once trace core exists.

## `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaMetrics.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Client-side metrics and throttled logging.

### Important methods

- packet/decode/block-count metrics helpers

### Debug hooks to add

- none; reuse if later helpful

### Invariants to check

- none in Phase 2

### Risk notes

- Already duplicates decode work in one path; phase-2 tracing should not compound that.

### Status

`NEEDS_RECHECK`

### Next action

Leave untouched until client trace events exist.

## `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaNetworking.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Client receive/decode/apply boundary and main-thread handoff for delta packets.

### Important methods

- `handleIncomingPayload`
- `processChunkDelta`
- `applyPayloadToWorld`
- `applyDelta`

### Debug hooks to add

- `CLIENT_SYNC_TX_START/END`
- invalid payload event
- decode/apply failure event
- thread-handoff event if needed

### Invariants to check

- client delta ordering visible
- apply failures retain chunk coordinates/payload size

### Risk notes

- Current path decodes twice for metrics/logging; trace implementation should avoid triple work.

### Status

`PHASE_2_PARTIAL`

### Next action

Top-level receive/apply start-end and failure events are implemented. Future work is compression/order correlation without extra decode churn.

## `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaVisitor.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Applies decoded deltas to client chunks.

### Important methods

- visitor reset/apply methods

### Debug hooks to add

- applied block/entity counts surfaced back to caller

### Invariants to check

- client apply should not silently no-op on non-empty delta

### Risk notes

- Prefer counting at visitor end, not event-per-block.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Return/apply summary counts to `ClientDeltaNetworking`.

## `fabric/src/main/java/io/liparakis/chunkis/command/DurabilityTestCommand.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Runs the teleport churn reproducer on its own scheduled executor.

### Important methods

- `runTest`
- scheduled teleport loop
- `stopInternal`

### Debug hooks to add

- `DURABILITY_TEST_STARTED`
- `DURABILITY_TELEPORT_EXECUTED`
- `DURABILITY_TEST_STOPPED`
- `DURABILITY_TEST_FAILED`

### Invariants to check

- durability loop visibility
- thread boundary visible

### Risk notes

- The command uses its own executor and server-thread handoff, so it is valuable evidence for sequencing.

### Status

`PHASE_2_PARTIAL`

### Next action

Run start/teleport/stop/failure events are implemented. Future work is only deeper validation of emitted events if a low-churn harness is worth adding.

## `fabric/src/main/java/io/liparakis/chunkis/command/StorageReportCommand.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Existing operator-facing storage inspection command.

### Important methods

- report registration and raw storage inspection helpers

### Debug hooks to add

- none now; future export/inspect commands should reuse this inspection logic where appropriate

### Invariants to check

- none for hot path

### Risk notes

- Already useful to avoid inventing a parallel storage browser.

### Status

`NEEDS_RECHECK`

### Next action

Reuse later for inspect/export support rather than rewrite.

## `fabric/src/main/java/io/liparakis/chunkis/migration/CisWorldMigrator.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Runs CIS-world migration work across existing storage.

### Important methods

- `migrateWorld`

### Debug hooks to add

- none for durability-focused Phase 2

### Invariants to check

- migration correctness is better covered by fixtures than runtime hooks

### Risk notes

- Not part of normal save/load/unload churn.

### Status

`NEEDS_RECHECK`

### Next action

Keep out of the first real instrumentation pass.

## `fabric/src/main/java/io/liparakis/chunkis/migration/McaMigrator.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Converts vanilla MCA content into CIS during migration.

### Important methods

- `migrateWorld`
- region iteration/conversion helpers

### Debug hooks to add

- none for the current bug-hunt pass

### Invariants to check

- existing migration tests remain the right validation surface

### Risk notes

- Only relevant if migration-specific corruption becomes suspected.

### Status

`NEEDS_RECHECK`

### Next action

Skip for Phase 2 unless evidence points here.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/network/ChunkHolderMixin.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Piggybacks Chunkis delta sends alongside vanilla chunk packets.

### Important methods

- `chunkis$onSendPacketToPlayers`

### Debug hooks to add

- `CLIENT_SYNC_TX_START`
- base-packet/delta-send ordering event

### Invariants to check

- client delta order visible

### Risk notes

- This is the exact hook needed to answer “did delta go before/after base chunk packet?”

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit one ordering-aware event per player/chunk send path.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ChunkSerializerMixin.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Loads a delta and attaches it to proto chunks during serialized conversion.

### Important methods

- `chunkis$onConvert`
- `chunkis$restoreChunkDelta`
- `chunkis$loadDelta`
- `chunkis$loadDeltaFromDisk`

### Debug hooks to add

- `LOAD_TX_START`
- `LOAD_SOURCE_RESOLVED`
- `LOAD_TX_END`

### Invariants to check

- load source must be explicit
- synthetic empty branch must be explicit

### Risk notes

- This is the cleanest load-source decision point in the repo.

### Status

`PHASE_2_PARTIAL`

### Next action

Memory-vs-disk-vs-empty resolution is implemented. Future work is richer correlation and truthful `BOTH` detection only if the code path grows.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/StoragePreventionMixin.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Cancels vanilla MCA read/write/scan/sync globally.

### Important methods

- `chunkis$blockWrite`
- `chunkis$blockGetTagAt`
- `chunkis$blockScanChunk`
- `chunkis$blockSync`

### Debug hooks to add

- `VANILLA_SAVE_CANCELLED` equivalent event
- vanilla read cancelled event at `VERBOSE`

### Invariants to check

- cancelled vanilla save must pair with Chunkis persistence path

### Risk notes

- This is high-value evidence and low event volume.

### Status

`PHASE_2_PARTIAL`

### Next action

Vanilla write cancellation is implemented. Future work is correlating cancellation to later Chunkis queue/flush outcomes.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/storage/ThreadedAnvilChunkStorageMixin.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Owns the main save/load/close orchestration for Chunkis persistence.

### Important methods

- `chunkis$onClose`
- `chunkis$onGetUpdatedChunkNbt`
- `chunkis$onSave`
- `chunkis$saveDirtyDelta`
- `chunkis$queueDirtyDelta`
- `chunkis$rejectSparse`

### Debug hooks to add

- `SAVE_TX_START`
- snapshot/structure/entity capture summaries
- `SAVE_REJECTED`
- `SAVE_QUEUED`
- synchronous save path events on load/shutdown
- `LOAD_TX_START` on updated-NBT path

### Invariants to check

- vanilla save cancelled without Chunkis persistence must not be silent
- rejected save reason required

### Risk notes

- This is the most important single instrumentation file in the repo.

### Status

`PHASE_2_PARTIAL`

### Next action

Save start and sparse-save rejection are implemented. Future work is deeper load/shutdown correlation and optional transaction ids.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/BlockEntityMixin.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Captures block-entity-driven dirtying and base-capture interactions.

### Important methods

- `chunkis$onMarkDirty`
- block-entity capture helpers

### Debug hooks to add

- block-entity mutation captured
- base capture scheduled due to block-entity path if applicable

### Invariants to check

- block-entity metadata not silently dropped

### Risk notes

- Important supporting evidence for dirty state origin.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Add low-volume mutation-origin events only.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/ChunkRegionMixin.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Entity-spawn side hook.

### Important methods

- `chunkis$onSpawnEntity`

### Debug hooks to add

- none initially

### Invariants to check

- revisit only if entity-population ordering becomes a proven suspect

### Risk notes

- Peripheral to current disappearance theory.

### Status

`NEEDS_RECHECK`

### Next action

Leave out of first instrumentation pass.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/CommonChunkMixin.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Attaches delta capability to chunks and overrides vanilla dirty reporting.

### Important methods

- `chunkis$getDelta`
- `chunkis$setDelta`
- `chunkis$onNeedsSaving`
- `chunkis$onMarkNeedsSaving`

### Debug hooks to add

- delta attached/replaced event in `VERBOSE`
- dirty flag forced event

### Invariants to check

- dirty transitions visible

### Risk notes

- Useful bridge between vanilla dirtying and tracker state.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Hook only clean->dirty and override paths, not every accessor.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/LeavesBlockMixin.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Sets leaf-decay context to avoid recording natural leaf churn as player edits.

### Important methods

- before/after leaf tick hooks

### Debug hooks to add

- none initially

### Invariants to check

- only revisit if leaf filtering proves to hide real edits

### Risk notes

- Direct tracing here would be noise-heavy.

### Status

`NEEDS_RECHECK`

### Next action

Keep excluded from phase 2.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/NetherPortalBlockMixin.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Portal behavior override path.

### Important methods

- portal replacement helpers

### Debug hooks to add

- none for chunk disappearance phase

### Invariants to check

- none now

### Risk notes

- Separate subsystem.

### Status

`NEEDS_RECHECK`

### Next action

Skip for Phase 2.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/PortalForcerMixin.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Portal search/loading diagnostics and chunk preloading.

### Important methods

- portal candidate/search hooks

### Debug hooks to add

- none now

### Invariants to check

- none now

### Risk notes

- Existing debug logs already cover this subsystem.

### Status

`NEEDS_RECHECK`

### Next action

Skip for durability-focused Phase 2.

## `fabric/src/main/java/io/liparakis/chunkis/portal/PortalChunkIndexManager.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Maintains a portal-chunk side index persisted outside the main chunk storage path.

### Important methods

- `updateChunk`
- `getPortalChunksInRange`
- `close`

### Debug hooks to add

- none in the first pass

### Invariants to check

- revisit only if portal metadata is shown to interfere with chunk state

### Risk notes

- Separate sidecar persistence, not a prime suspect in chunk disappearance.

### Status

`NEEDS_RECHECK`

### Next action

Leave out of Phase 2 unless portal metadata becomes part of the evidence trail.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/SpawnHelperMixin.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Cancels entity population for chunks carrying Chunkis state.

### Important methods

- `chunkis$onPopulateEntities`

### Debug hooks to add

- none initially

### Invariants to check

- revisit only if repopulation suppression becomes a confirmed suspect

### Risk notes

- Could matter indirectly, but not the first evidence gap to close.

### Status

`NEEDS_RECHECK`

### Next action

Keep as follow-up, not first-pass hook.

## `fabric/src/main/java/io/liparakis/chunkis/mixin/world/WorldChunkMixin.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Captures live block/block-entity mutations and restores proto-attached snapshots into live chunks.

### Important methods

- `chunkis$onSetBlockState`
- `chunkis$afterSetBlockState`
- `chunkis$onSetBlockEntity`
- `chunkis$onRemoveBlockEntity`
- `chunkis$onConstructFromProto`
- `chunkis$restoreChunkFromDelta`
- `chunkis$shouldNotTrackChunkMutation`

### Debug hooks to add

- `DELTA_MARKED_DIRTY` origin events
- off-thread mutation rejection event
- `RESTORE_TX_START`
- `RESTORE_COMPLETED`

### Invariants to check

- restore of non-empty delta must not be silent no-op
- thread boundary visible

### Risk notes

- This file bridges live gameplay mutations and restore semantics.

### Status

`PHASE_2_PARTIAL`

### Next action

Restore operation-id threading and off-thread assertion are implemented. Future work is mutation-origin tracing without event spam.

## `fabric/src/main/java/io/liparakis/chunkis/network/ChunkDeltaPayload.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Wire payload framing and optional compression for delta packets.

### Important methods

- `create`
- `read`
- `write`
- compression helpers

### Debug hooks to add

- compression decision summary surfaced to caller

### Invariants to check

- payload size/compression state visible during client sync

### Risk notes

- Keep direct hooks minimal; callers already know chunk coords.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Expose payload stats to networking events rather than instrument raw codec internals.

## `fabric/src/main/java/io/liparakis/chunkis/network/ChunkisNetworking.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Server-side delta encode/send/drop path.

### Important methods

- `sendDelta`
- `extractDelta`
- `encodAndSend`

### Debug hooks to add

- `CLIENT_SYNC_TX_START`
- send skipped because empty/missing delta
- payload-too-large drop event
- send failure event

### Invariants to check

- client sync ordering visible
- dropped payloads not silent

### Risk notes

- Misspelled `encodAndSend` is harmless but a good reminder to keep event source names explicit.

### Status

`PHASE_2_PARTIAL`

### Next action

Send start-end, empty-delta/player-unavailable skips, payload-too-large drops, and send failures are implemented. Future work is shared cross-wire correlation.

## `fabric/src/main/java/io/liparakis/chunkis/storage/AsyncCisSaveManager.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Per-dimension async save queue with coalescing and generation-based stale-write suppression.

### Important methods

- `submit`
- `flushAndClose`
- `SaveWorker.submit`
- `SaveWorker.poll`
- `SaveWorker.process`

### Debug hooks to add

- `SAVE_QUEUED`
- worker picked save
- stale-generation skip event
- `SAVE_FLUSH_STARTED/COMPLETED/FAILED`

### Invariants to check

- delta marked clean only after successful flush
- async boundary visible

### Risk notes

- One of the highest-value files for durability evidence.

### Status

`PHASE_2_PARTIAL`

### Next action

Queue acceptance and worker failure evidence are implemented. Future work is stale-generation skip visibility and transaction correlation.

## `fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureScheduler.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Queues deferred base chunk capture and flushes it either async per tick or synchronously on shutdown.

### Important methods

- `schedule`
- `tick`
- `flushAndClose`
- `submitAsync`
- `saveSynchronously`
- `SchedulerState.process`

### Debug hooks to add

- base-capture queue/backlog events
- `BASE_CHUNK_CAPTURE_STARTED/COMPLETED`
- save mode async vs synchronous

### Invariants to check

- queued dirty chunk should not disappear silently at shutdown

### Risk notes

- This class explains a major branch in save timing.

### Status

`PHASE_2_PARTIAL`

### Next action

Save rejection evidence is implemented. Future work is base-capture queue/capture summaries without per-tick noise.

## `fabric/src/main/java/io/liparakis/chunkis/storage/BaseChunkCaptureUtil.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Captures vanilla-compatible base chunk data and stores it in metadata.

### Important methods

- `captureAndPersistBaseChunkIfMissing`
- `captureBaseChunk`
- `hasPortalBlocks`

### Debug hooks to add

- base capture success/failure summary
- portal/base metadata flag summary in `VERBOSE`

### Invariants to check

- base chunk NBT must not be silently absent when required

### Risk notes

- Metadata-heavy path; useful to trace without touching persistence semantics.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit capture summary only.

## `fabric/src/main/java/io/liparakis/chunkis/storage/CisNbtUtil.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Defines metadata envelope semantics, synthetic base NBT creation, base-chunk extraction, and structure/entity merging helpers.

### Important methods

- `createBaseNbt`
- `putDelta`
- `putChunkMetadata`
- `createChunkMetadataTakingOwnership`
- `extractPersistedStructureMetadata`
- `extractPersistedBaseChunkNbt`
- `hasPersistedBaseChunkNbt`
- `shouldSuppressInitialRepopulation`

### Debug hooks to add

- metadata branch summaries
- base-NBT present/used/absent events
- full-baseline flag visibility

### Invariants to check

- base NBT not silently ignored
- full baseline clear strategy explicit
- metadata not silently dropped

### Risk notes

- This file defines the meaning of several future assertion fields.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Instrument branch summaries at callers, with helper methods exposing needed booleans cheaply.

## `fabric/src/main/java/io/liparakis/chunkis/storage/CisSnapshotCapture.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Builds an authoritative non-air snapshot from a live chunk.

### Important methods

- `capture`

### Debug hooks to add

- snapshot-capture started/completed summary

### Invariants to check

- snapshot result count visible before save

### Risk notes

- One summary event is enough; per-block tracing would be absurd.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit block/block-entity count summary only.

## `fabric/src/main/java/io/liparakis/chunkis/storage/DeltaPersistenceGuard.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Rejects sparse deltas without persisted base state and logs the reason today.

### Important methods

- `shouldRejectSparseDeltaWithoutBase`
- `logRejectedSparseDeltaWithoutBase`

### Debug hooks to add

- `SAVE_REJECTED`
- assertion companion when rejection combines with cancelled vanilla save

### Invariants to check

- save reject reason required

### Risk notes

- This is a prime suspect path for vanish cases because vanilla save is cancelled elsewhere.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Make this the canonical rejection reason source.

## `fabric/src/main/java/io/liparakis/chunkis/storage/FabricCisStorageHelper.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Opens/closes per-dimension storage wrappers and mapping files.

### Important methods

- `getStorage`
- `closeStorage`
- wrapper open/build helpers

### Debug hooks to add

- storage opened/closed summary events

### Invariants to check

- dimension/path context should be available for storage lifecycle issues

### Risk notes

- Good place to attach world/directory metadata to later storage events.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit open/close summaries only.

## `fabric/src/main/java/io/liparakis/chunkis/storage/StructureMetadataExtractor.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Extracts structure metadata directly from chunks.

### Important methods

- `extract`

### Debug hooks to add

- direct extraction success/failure summary
- fallback-needed event from caller

### Invariants to check

- structure metadata capture path explicit

### Risk notes

- The fallback branch in save path is evidence worth recording once per save.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Instrument through caller save path.

## `fabric/src/main/java/io/liparakis/chunkis/world/ChunkBlockEntityCapture.java`

### Classification

`SUPPORTING_HOOK_REQUIRED`

### Current responsibility

Serializes and stores block-entity payloads on a delta.

### Important methods

- `captureBlockEntity`
- `captureBlockEntities`
- `storeInDelta`
- `removeFromDelta`

### Debug hooks to add

- block-entity capture completed/removed summary in `VERBOSE`

### Invariants to check

- block-entity payloads not silently dropped

### Risk notes

- Better instrumented as counts and failures, not per-block-entity spam.

### Status

`REVIEWED_NEEDS_HOOKS`

### Next action

Emit capture count and failure summaries from callers.

## `fabric/src/main/java/io/liparakis/chunkis/world/ChunkRestorer.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Authoritatively clears chunks to air and replays persisted block/block-entity/entity payloads.

### Important methods

- `restore`
- `clearChunkToAir`
- `applyBlockChange`
- `RestorationVisitor.visitBlock`
- `visitBlockEntity`
- `visitEntity`
- `finishRestoration`

### Debug hooks to add

- `RESTORE_TX_START`
- restore summary counts
- restore skipped/failed block event only on aggregate
- legacy entity handoff summary

### Invariants to check

- non-empty delta must not restore zero meaningful blocks silently
- full baseline clear strategy visible

### Risk notes

- This file determines whether saved bytes become live world state or disappear logically.

### Status

`PHASE_2_PARTIAL`

### Next action

Restore start/completion/failure and aggregate counts are implemented. Future work is dedicated numeric fields and stronger zero-restore assertions.

## `fabric/src/main/java/io/liparakis/chunkis/world/GlobalChunkTracker.java`

### Classification

`CORE_HOOK_REQUIRED`

### Current responsibility

Owns dirty delta map and unload-gap LRU cache.

### Important methods

- `markDirty`
- `addDelta`
- `markSaved`
- `markSavedIfUnchanged`
- `getDelta`
- `getActiveDelta`
- `getPendingDeltas`

### Debug hooks to add

- dirty tracked
- dirty removed after save
- unload-cache put/evict/hit/miss
- authoritative-delta kept over weaker replacement
- stale async completion ignored

### Invariants to check

- dirty transitions visible
- dirty chunk must not leave lifecycle silently

### Risk notes

- This is the only central place to answer whether the chunk was still considered pending by Chunkis.

### Status

`PHASE_2_PARTIAL`

### Next action

State transitions, unload-cache lifecycle, and lookup summaries are implemented. Future work is true unload-hook correlation.

## `fabric/src/main/java/io/liparakis/chunkis/world/LeafTickContext.java`

### Classification

`INSPECTION_ONLY`

### Current responsibility

Thread-local context used to suppress leaf-decay noise.

### Important methods

- `enter`
- `exit`
- `isActive`

### Debug hooks to add

- none initially

### Invariants to check

- only revisit if leaf filtering is suspected to mask real edits

### Risk notes

- Useful context, but not first-pass instrumentation.

### Status

`NEEDS_RECHECK`

### Next action

Leave out of Phase 2 unless evidence points here.

## Validation / Test Detail Notes

## `core/src/test/java/io/liparakis/chunkis/storage/codec/CisUniformSectionCodecTest.java`

- Current behavior:
  validates codec section mode choices and round trips.
- Later observability assertions:
  encoder/decoder summary events should report chosen section mode and preserve logical counts.
- Missing tests to add:
  one unit test for event summaries on sparse/uniform/dense decisions.

## `core/src/test/java/io/liparakis/chunkis/storage/io/CisStorageCompactionTest.java`

- Current behavior:
  validates compaction preserves payloads and failure keeps original region file.
- Later observability assertions:
  compaction/region-write/read-back failure events should be assertable.
- Missing tests to add:
  one unit test for `REGION_WRITE_TX_*` or future compaction inspection events.

## `core/src/test/java/io/liparakis/chunkis/storage/io/CompressionContextTest.java`

- Current behavior:
  validates compression round trip.
- Later observability assertions:
  none unless optional performance tracing is added.
- Missing tests to add:
  none in Phase 2.

## `core/src/test/java/io/liparakis/chunkis/storage/io/RegionFileFreeListTest.java`

- Current behavior:
  validates best-fit reuse, legacy rebuild, corruption fallback.
- Later observability assertions:
  allocator strategy and corruption assertion events.
- Missing tests to add:
  one unit test for corruption -> `ASSERTION_FAILED` / region failure event.

## `core/src/test/java/io/liparakis/chunkis/storage/mapping/CisMappingTest.java`

- Current behavior:
  validates mapping creation, restoration, and unresolved-id rejection.
- Later observability assertions:
  mapping failure events include id and source.
- Missing tests to add:
  one unit test for unresolved-id structured event.

## `fabric/src/test/java/io/liparakis/chunkis/command/StorageReportCommandTest.java`

- Current behavior:
  validates report parsing of raw chunk payloads.
- Later observability assertions:
  future export/storage summary output should stay consistent with storage inspection.
- Missing tests to add:
  one command test once export/inspect commands exist.

## `fabric/src/test/java/io/liparakis/chunkis/command/DurabilityTestCommandTest.java`

- Current behavior:
  validates the chunk-coordinate mapping used by durability teleport trace events.
- Later observability assertions:
  durability run start/stop/failure events should keep stable chunk targeting and operation-id readability.
- Missing tests to add:
  one focused test for event emission if the reproducer gains a small injectable scheduler seam without widening the command surface.

## `fabric/src/test/java/io/liparakis/chunkis/network/ChunkDeltaPayloadTest.java`

- Current behavior:
  validates payload compression and codec round trips.
- Later observability assertions:
  payload size/compression decision events.
- Missing tests to add:
  one unit test for sync event summary fields.

## `fabric/src/test/java/io/liparakis/chunkis/storage/CisSnapshotCaptureTest.java`

- Current behavior:
  validates section index to world Y conversion.
- Later observability assertions:
  snapshot event count summary.
- Missing tests to add:
  one unit test for snapshot summary event fields.

## `fabric/src/test/java/io/liparakis/chunkis/util/CisNbtUtilTest.java`

- Current behavior:
  validates metadata envelope and suppression semantics.
- Later observability assertions:
  base-NBT/full-baseline/structure-metadata branch events.
- Missing tests to add:
  one unit test for metadata branch reporting.

## `fabric/src/test/java/io/liparakis/chunkis/util/FabricCisStorageHelperTest.java`

- Current behavior:
  validates dimension-specific path placement.
- Later observability assertions:
  storage-open events include dimension/path metadata.
- Missing tests to add:
  one small test if open/close summary events are exposed.

## `fabric/src/gametest/java/io/liparakis/chunkis/gametest/AsyncSaveDataLossGameTest.java`

- Current behavior:
  validates that repeated far-chunk churn preserves edits and persisted base chunk data.
- Later observability assertions:
  full save/load/restore timeline and no dirty-unload assertion failures.
- Missing tests to add:
  one gametest that exports/asserts trace events around a durability run.

## `fabric/src/gametest/java/io/liparakis/chunkis/gametest/CisFixtureMigrationGameTest.java`

- Current behavior:
  validates legacy fixture migration correctness.
- Later observability assertions:
  optional migration event summaries.
- Missing tests to add:
  none for Phase 2.

## `fabric/src/gametest/java/io/liparakis/chunkis/gametest/LegacyEntityStorageHandoffGameTest.java`

- Current behavior:
  validates one-time replay and handoff of legacy entity payloads.
- Later observability assertions:
  restore/entity handoff events.
- Missing tests to add:
  one gametest asserting legacy entity replay summary fields.

## `fabric/src/gametest/java/io/liparakis/chunkis/gametest/StructureMetadataExtractorGameTest.java`

- Current behavior:
  validates direct structure extraction against vanilla serialized output.
- Later observability assertions:
  direct extraction success/fallback reporting.
- Missing tests to add:
  one gametest for fallback branch if a fixture can trigger it safely.
