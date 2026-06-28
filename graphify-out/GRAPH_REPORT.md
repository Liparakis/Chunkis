# Graph Report - Chunkis  (2026-06-28)

## Corpus Check
- 176 files · ~132,026 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2321 nodes · 9885 edges · 117 communities (83 shown, 34 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 1287 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `de613272`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 68|Community 68]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]
- [[_COMMUNITY_Community 80|Community 80]]
- [[_COMMUNITY_Community 81|Community 81]]
- [[_COMMUNITY_Community 82|Community 82]]
- [[_COMMUNITY_Community 83|Community 83]]
- [[_COMMUNITY_Community 84|Community 84]]
- [[_COMMUNITY_Community 85|Community 85]]
- [[_COMMUNITY_Community 86|Community 86]]
- [[_COMMUNITY_Community 87|Community 87]]
- [[_COMMUNITY_Community 88|Community 88]]
- [[_COMMUNITY_Community 89|Community 89]]
- [[_COMMUNITY_Community 90|Community 90]]
- [[_COMMUNITY_Community 91|Community 91]]
- [[_COMMUNITY_Community 92|Community 92]]
- [[_COMMUNITY_Community 93|Community 93]]
- [[_COMMUNITY_Community 94|Community 94]]
- [[_COMMUNITY_Community 95|Community 95]]
- [[_COMMUNITY_Community 96|Community 96]]
- [[_COMMUNITY_Community 97|Community 97]]
- [[_COMMUNITY_Community 98|Community 98]]
- [[_COMMUNITY_Community 99|Community 99]]
- [[_COMMUNITY_Community 100|Community 100]]
- [[_COMMUNITY_Community 101|Community 101]]
- [[_COMMUNITY_Community 102|Community 102]]

## God Nodes (most connected - your core abstractions)
1. `ChunkDelta` - 359 edges
2. `DebugChunkKey` - 125 edges
3. `CisStorage` - 85 edges
4. `CisChunkPos` - 81 edges
5. `PayloadWatchTracer` - 80 edges
6. `RegionFile` - 60 edges
7. `PayloadWatchTarget` - 53 edges
8. `StorageReportCommand` - 50 edges
9. `ChunkTraceEvent` - 44 edges
10. `BlockStateAdapter` - 42 edges

## Surprising Connections (you probably didn't know these)
- `CisStorageMigrator` --references--> `CisStorage`  [EXTRACTED]
  cismigrator/src/main/java/io/liparakis/chunkis/migrator/CisStorageMigrator.java → core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java
- `TestStorageHarness` --references--> `CisStorage`  [EXTRACTED]
  cismigrator/src/test/java/io/liparakis/chunkis/migrator/CisStorageMigratorTest.java → core/src/main/java/io/liparakis/chunkis/storage/io/CisStorage.java
- `ClientDeltaVisitor` --references--> `ChunkDelta`  [EXTRACTED]
  fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaVisitor.java → core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java
- `RestorationVisitor` --references--> `ChunkDelta`  [EXTRACTED]
  fabric/src/main/java/io/liparakis/chunkis/world/ChunkRestorer.java → core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java
- `CommonChunkMixin` --references--> `ChunkDelta`  [EXTRACTED]
  fabric/src/main/java/io/liparakis/chunkis/mixin/world/CommonChunkMixin.java → core/src/main/java/io/liparakis/chunkis/core/ChunkDelta.java

## Import Cycles
- None detected.

## Communities (117 total, 34 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.07
Nodes (14): Axis, ChunkTraceJsonlTest, Direction, MinecraftServer, Optional, ColumnVisitor, EgressResult, PortalArrivalFallback (+6 more)

### Community 1 - "Community 1"
Cohesion: 0.10
Nodes (10): AbstractCisEncoder, DefaultSparseCandidate, EncoderContext, CisEncoder, DataOutputStream, StructureMetadataExtractorGameTest, CisSection, Object (+2 more)

### Community 2 - "Community 2"
Cohesion: 0.09
Nodes (12): ChunkPos, AsyncSaveDataLossGameTest, ChunkTargets, LegacyEntityStorageHandoffGameTest, SerializedEntity, PersistedBaseChunkReloadGameTest, LongOpenHashSet, PortalChunkIndex (+4 more)

### Community 3 - "Community 3"
Cohesion: 0.09
Nodes (14): B, Collection, Gson, CisStorageTraceTest, TestBlockRegistryAdapter, TestNbtAdapter, CisMapping, PropertyMeta (+6 more)

### Community 4 - "Community 4"
Cohesion: 0.08
Nodes (12): CisUniformSectionCodecTest, CodecHarness, SectionEncodingInfo, ChunkDeltaTraceTest, ChunkTraceInvariantsTest, CompressionContextTest, ChunkisNetworkingTest, DeltaPersistenceGuardTest (+4 more)

### Community 5 - "Community 5"
Cohesion: 0.08
Nodes (9): Consumer, ChunkTraceJsonl, ChunkTraceWatchpoints, PayloadWatchTarget, EntityWatchKey, EntityWatchState, WatchTraceState, PayloadWatchType (+1 more)

### Community 6 - "Community 6"
Cohesion: 0.15
Nodes (3): PayloadWatchTracer, WatchTraceKey, EntityLoader

### Community 7 - "Community 7"
Cohesion: 0.11
Nodes (7): ByteBuffer, FileChannel, Allocation, FreeBlock, RegionCompactReport, RegionFile, RegionSpaceStats

### Community 8 - "Community 8"
Cohesion: 0.11
Nodes (7): Boolean, HeightLimitView, NbtCompound, PersistedBaseChunkUsage, CisNbtUtil, LengthPrefixedByteArrayOutputStream, LoadChunkNbtResult

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (8): BitReader, AbstractCisDecoder, Palette, DataInputStream, IOException, CisNetworkDecoder, PaletteReadResult, T

### Community 10 - "Community 10"
Cohesion: 0.10
Nodes (5): ChunkTraceInvariants, Long2ObjectMap, BaseChunkCaptureUtil, ChunkDeltaOwnership, DeltaPersistenceGuard

### Community 11 - "Community 11"
Cohesion: 0.12
Nodes (15): CallbackInfo, Inject, Mixin, NbtScanner, ChunkHolderMixin, Packet, Random, RemovalReason (+7 more)

### Community 12 - "Community 12"
Cohesion: 0.10
Nodes (3): ChunkDelta, ChunkDeltaOwnershipTest, ChunkOwnershipTraceHelper

### Community 13 - "Community 13"
Cohesion: 0.15
Nodes (5): ChunkHolder, CompletableFuture, OptionalChunk, ThreadedAnvilChunkStorageMixin, Unique

### Community 14 - "Community 14"
Cohesion: 0.08
Nodes (5): TestBlockStateAdapter, TestBlockRegistryAdapter, TestBlockStateAdapter, TestBlockStateAdapter, Override

### Community 15 - "Community 15"
Cohesion: 0.14
Nodes (10): CisRegionCompactor, CompactionReport, RegionCompaction, RegionCoordinates, CisRegionInspector, RegionCoordinates, RegionSpaceUsage, Path (+2 more)

### Community 16 - "Community 16"
Cohesion: 0.15
Nodes (3): DimensionChunkKey, GlobalChunkTracker, GlobalChunkTrackerTest

### Community 17 - "Community 17"
Cohesion: 0.12
Nodes (5): BlockPos, ChunkRestorer, ScheduledEntityReplay, ScheduledEntityReplayQueue, WorldChunk

### Community 18 - "Community 18"
Cohesion: 0.16
Nodes (9): Identifier, RegistryKey, ChunkisStoragePaths, FabricCisStorageHelperTest, World, Entry, Key, PendingChunkMutationSuppression (+1 more)

### Community 19 - "Community 19"
Cohesion: 0.22
Nodes (3): ChunkDebugCommand, PendingChunkSnapshot, ServerCommandSource

### Community 20 - "Community 20"
Cohesion: 0.15
Nodes (5): AtomicLong, ChunkTraceEvent, ChunkTraceStore, SuspectKey, Suspicion

### Community 21 - "Community 21"
Cohesion: 0.19
Nodes (9): ChunkisDebugDomain, ChunkTraceEventType, ChunkTraceReason, ChunkTraceSeverity, ChunkDebugCommandTest, DebugChunkKey, DebugRegionKey, Object2ObjectLinkedOpenHashMap (+1 more)

### Community 22 - "Community 22"
Cohesion: 0.11
Nodes (9): EncodedChunkInput, EntityEncodingException, Int2ObjectMap, Int2ObjectOpenHashMap, IntArrayList, BlockIdRegistry, CisChunk, Reference2IntMap (+1 more)

### Community 23 - "Community 23"
Cohesion: 0.12
Nodes (8): CisDecoder, DataOutput, Long2IntOpenHashMap, Long2ObjectOpenHashMap, CisAdapter, N, NbtAdapter, UnaryOperator

### Community 25 - "Community 25"
Cohesion: 0.10
Nodes (9): Accessor, ChunkBlockEntityNbtAccessor, Chunk, ChunkSection, ChunkSectionDebugUtil, ChunkisApiImpl, CisSnapshotCapture, CisSnapshotCaptureTest (+1 more)

### Community 26 - "Community 26"
Cohesion: 0.18
Nodes (4): CisStorageMigratorTest, TestNbtAdapter, TestStorageHarness, TestStorageHarness

### Community 27 - "Community 27"
Cohesion: 0.12
Nodes (3): SectionPayloadDiagnostics, SectionUniformDiagnostics, StorageReportCommand

### Community 28 - "Community 28"
Cohesion: 0.14
Nodes (10): LinkedHashMap, Long, Map, SaveMode, BaseChunkCaptureScheduler, QueuedCaptureSnapshot, SchedulerState, PendingVanillaSaveDecision (+2 more)

### Community 29 - "Community 29"
Cohesion: 0.16
Nodes (4): FabricBlockStateAdapter, Class, Method, Property

### Community 30 - "Community 30"
Cohesion: 0.14
Nodes (15): BoundedTopList, ChunkEncodingKind(), ChunkReport, DefaultSparseReport, DenseSectionReport, EncoderInputDiagnostics, RegionCoordinates, RegionInspection (+7 more)

### Community 32 - "Community 32"
Cohesion: 0.20
Nodes (10): CallbackInfoReturnable, PointOfInterestStorage, PointOfInterestType, Predicate, RegistryEntry, Set, NetherPortalBlockMixin, PortalForcerMixin (+2 more)

### Community 33 - "Community 33"
Cohesion: 0.15
Nodes (7): ConcurrentHashMap, Runnable, AsyncCisSaveManager, PendingSave, PendingSaveSnapshot, SaveWorker, Thread

### Community 34 - "Community 34"
Cohesion: 0.18
Nodes (6): BoundedInputStream, BufferHolder, FabricNbtAdapter, DataInput, FilterInputStream, InputStream

### Community 35 - "Community 35"
Cohesion: 0.16
Nodes (6): Chunkis, Logger, CisWorldMigrator, CisMigrationReport, CisMigrationReportTest, CisStorageMigrator

### Community 36 - "Community 36"
Cohesion: 0.16
Nodes (4): BlockInstruction, BlockVisitor, DeltaVisitor, FunctionalInterface

### Community 37 - "Community 37"
Cohesion: 0.13
Nodes (6): ByteArrayOutputStream, Inflater, CompressionContext, List, CisVersionEdge, CisVersionMap

### Community 38 - "Community 38"
Cohesion: 0.22
Nodes (3): FabricBlockRegistryAdapter, Block, FabricNetworkCodecFactory

### Community 40 - "Community 40"
Cohesion: 0.19
Nodes (6): ChunkisApi, BlockState, Box, GameTest, CisStorage, PigEntity

### Community 41 - "Community 41"
Cohesion: 0.27
Nodes (3): CisChunkPos, CisStorageCompactionTest, TestStorageHarness

### Community 42 - "Community 42"
Cohesion: 0.26
Nodes (4): BlockEntity, Nullable, ChunkBlockEntityCapture, WrapperLookup

### Community 43 - "Community 43"
Cohesion: 0.18
Nodes (3): ChunkEncodingKind, ChunkMixCounters, ReportRenderer

### Community 44 - "Community 44"
Cohesion: 0.14
Nodes (5): ChunkisDebugLevel, ChatMessage, CommandDispatcher, DateTimeFormatter, ChunkisDebugConfig

### Community 46 - "Community 46"
Cohesion: 0.22
Nodes (6): CustomPayload, Deflater, Id, ChunkDeltaPayload, PacketCodec, RegistryByteBuf

### Community 47 - "Community 47"
Cohesion: 0.19
Nodes (3): AfterEach, ChunkTraceWatchpointsTest, PayloadWatchTracerTest

### Community 48 - "Community 48"
Cohesion: 0.21
Nodes (4): ChunkisMutationGuardDuck, Cause, TracedFlag, ChunkMutationTrackingScope

### Community 49 - "Community 49"
Cohesion: 0.23
Nodes (5): AutoCloseable, Deprecated, ContextHandle, ContextHolder, LeafTickContext

### Community 52 - "Community 52"
Cohesion: 0.21
Nodes (5): ReadWriteLock, FabricCisStorageHelper, StorageInitializationException, StorageWrapper, Throwable

### Community 54 - "Community 54"
Cohesion: 0.12
Nodes (16): 1. Attachment, 2. Live mutation tracking, 3. Save-time snapshot rebuild, 4. Load and restore, Common debugging locations, Common failure modes, Delta And Ownership Model, Important entry points (+8 more)

### Community 57 - "Community 57"
Cohesion: 0.27
Nodes (3): ProtoChunk, SerializedChunk, ChunkSerializerMixin

### Community 58 - "Community 58"
Cohesion: 0.13
Nodes (15): Architecture, Common debugging locations, Common failure modes, Invariants, Load And Restore Pipeline, Owning classes, Portal follow-up, Problem this subsystem solves (+7 more)

### Community 59 - "Community 59"
Cohesion: 0.13
Nodes (15): Commands, Debugging a persistence regression, Event model, Extension points, Lifecycle, Load path, Owning classes, Payload summaries (+7 more)

### Community 60 - "Community 60"
Cohesion: 0.13
Nodes (15): Common debugging locations, Common failure modes, Invariants, Load-side usage, Metadata envelope, Owning classes, Persisted base chunk snapshot, Problem this subsystem solves (+7 more)

### Community 61 - "Community 61"
Cohesion: 0.13
Nodes (15): Common debugging locations, Common failure modes, Data ownership chain, Encoding pipeline, Invariants, Mapping model, On-disk layout, Owning classes (+7 more)

### Community 63 - "Community 63"
Cohesion: 0.23
Nodes (3): ClientDeltaNetworking, ClientWorld, Context

### Community 65 - "Community 65"
Cohesion: 0.14
Nodes (14): CIS version upgrade, Common debugging locations, Common failure modes, Important entry points, Invariants, MCA -> CIS import, Migration And Versioning, Owning classes (+6 more)

### Community 66 - "Community 66"
Cohesion: 0.14
Nodes (14): Assertion model, Commands, Common debugging locations, Common failure modes, Debugging a persistence bug, Event model, Invariants, Observability And Debugging (+6 more)

### Community 67 - "Community 67"
Cohesion: 0.14
Nodes (14): Architecture, Async save path, Base capture during save, Common debugging locations, Common failure modes, Invariants, Normal save path, Owning classes (+6 more)

### Community 68 - "Community 68"
Cohesion: 0.14
Nodes (14): Async durability model, Common debugging locations, Common failure modes, Data flow, Guard model, Invariants, Natural mutation exceptions, Owning classes (+6 more)

### Community 69 - "Community 69"
Cohesion: 0.23
Nodes (4): AtomicInteger, ClientDeltaMetrics, LongAdder, Supplier

### Community 70 - "Community 70"
Cohesion: 0.22
Nodes (3): CisMappingTest, TestBlockRegistryAdapter, Type

### Community 73 - "Community 73"
Cohesion: 0.17
Nodes (12): Common debugging locations, Common failure modes, Entry points, Invariants, Key rules, Networking And Client Sync, Owning classes, Pipeline (+4 more)

### Community 74 - "Community 74"
Cohesion: 0.39
Nodes (3): AtomicReference, DurabilityTestCommandTest, ScheduledExecutorService

### Community 75 - "Community 75"
Cohesion: 0.17
Nodes (11): 3.0.0 Notes, Architecture Docs, At a Glance, Building, Contact, Contributing, FAQ, How It Works (+3 more)

### Community 76 - "Community 76"
Cohesion: 0.18
Nodes (11): Common debugging locations, Core invariants, Failure modes to keep in mind, High-level data flow, Main integration points, Module layout, See also, System Overview (+3 more)

### Community 77 - "Community 77"
Cohesion: 0.25
Nodes (3): MetricsSnapshot, RegionKey, NotNull

### Community 78 - "Community 78"
Cohesion: 0.18
Nodes (11): Coding conventions and architectural principles, Developer Guide, Good entry files, How data flows, How to add a storage version, How to add a subsystem, How to add a trace event, How to debug (+3 more)

### Community 83 - "Community 83"
Cohesion: 0.29
Nodes (4): McaMigrator, Mutable, PalettesFactory, StorageKey

### Community 87 - "Community 87"
Cohesion: 0.46
Nodes (3): Biome, ServerWorldAccess, SpawnHelperMixin

### Community 91 - "Community 91"
Cohesion: 0.43
Nodes (3): ClientChunkisMod, ClientModInitializer, Environment

## Knowledge Gaps
- **162 isolated node(s):** `At a Glance`, `✅ Is This For You?`, `How It Works`, `3.0.0 Notes`, `Known Incompatibilities` (+157 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **34 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ChunkDelta` connect `Community 12` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 13`, `Community 14`, `Community 16`, `Community 17`, `Community 18`, `Community 21`, `Community 22`, `Community 23`, `Community 24`, `Community 25`, `Community 26`, `Community 28`, `Community 29`, `Community 30`, `Community 31`, `Community 32`, `Community 33`, `Community 35`, `Community 36`, `Community 37`, `Community 40`, `Community 41`, `Community 42`, `Community 45`, `Community 47`, `Community 48`, `Community 53`, `Community 57`, `Community 62`, `Community 63`, `Community 71`, `Community 79`, `Community 82`, `Community 83`, `Community 86`, `Community 87`, `Community 91`, `Community 98`, `Community 100`?**
  _High betweenness centrality (0.170) - this node is a cross-community bridge._
- **Why does `CisStorage` connect `Community 40` to `Community 1`, `Community 3`, `Community 5`, `Community 7`, `Community 13`, `Community 15`, `Community 21`, `Community 23`, `Community 26`, `Community 28`, `Community 30`, `Community 31`, `Community 33`, `Community 35`, `Community 37`, `Community 41`, `Community 52`, `Community 62`, `Community 77`, `Community 79`, `Community 83`?**
  _High betweenness centrality (0.024) - this node is a cross-community bridge._
- **Why does `DebugChunkKey` connect `Community 21` to `Community 2`, `Community 5`, `Community 6`, `Community 7`, `Community 10`, `Community 11`, `Community 13`, `Community 16`, `Community 17`, `Community 18`, `Community 19`, `Community 20`, `Community 24`, `Community 28`, `Community 31`, `Community 32`, `Community 33`, `Community 40`, `Community 44`, `Community 45`, `Community 47`, `Community 50`, `Community 57`, `Community 62`, `Community 63`, `Community 71`, `Community 72`, `Community 86`, `Community 97`?**
  _High betweenness centrality (0.021) - this node is a cross-community bridge._
- **What connects `At a Glance`, `✅ Is This For You?`, `How It Works` to the rest of the system?**
  _162 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.07432651736449204 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.10176390773405698 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.08729081863410222 - nodes in this community are weakly interconnected._