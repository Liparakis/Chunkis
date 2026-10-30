package io.liparakis.chunkis.storage.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CisStorageTraceTest {

    /**
     * Stores temp dir.
     */
    @TempDir
    Path tempDir;

    /**
     * Performs tear down.
     */
    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Performs emits save and load storage events when lifecycle debug is enabled.
     */
    @Test
    void emitsSaveAndLoadStorageEventsWhenLifecycleDebugIsEnabled() throws Exception {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        Files.createDirectories(tempDir.resolve("regions"));

        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(tempDir.resolve("regions"), mapping, new TestNbtAdapter(), "air");

        final CisChunkPos pos = new CisChunkPos(3, -2);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 70, 1, "stone");
        final String saveOperationId = "save-op-1";
        final String loadOperationId = "load-op-1";

        assertThat(storage.save(pos, delta, saveOperationId)).isTrue();
        storage.load(pos, loadOperationId);
        storage.close();

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(20);
        final List<ChunkTraceEventType> eventTypes = events.stream()
                .map(ChunkTraceEvent::eventType)
                .toList();

        assertThat(eventTypes).contains(
                ChunkTraceEventType.SAVE_FLUSH_STARTED,
                ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceEventType.REGION_WRITE_TX_END,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceEventType.REGION_READ_TX_START,
                ChunkTraceEventType.REGION_READ_TX_END
        );
        assertThat(events)
                .filteredOn(event -> saveOperationId.equals(event.operationId()))
                .extracting(ChunkTraceEvent::eventType)
                .contains(
                        ChunkTraceEventType.SAVE_TX_START,
                        ChunkTraceEventType.SAVE_FLUSH_STARTED,
                        ChunkTraceEventType.REGION_WRITE_TX_START,
                        ChunkTraceEventType.REGION_WRITE_TX_END,
                        ChunkTraceEventType.SAVE_FLUSH_COMPLETED
                );
        assertThat(events)
                .filteredOn(event -> loadOperationId.equals(event.operationId()))
                .extracting(ChunkTraceEvent::eventType)
                .contains(
                        ChunkTraceEventType.LOAD_TX_START,
                        ChunkTraceEventType.REGION_READ_TX_START,
                        ChunkTraceEventType.REGION_READ_TX_END,
                        ChunkTraceEventType.LOAD_TX_END
                );
    }

    /**
     * Performs classifies unknown block id load failure as mapping lookup failure.
     */
    @Test
    void classifiesUnknownBlockIdLoadFailureAsMappingLookupFailure() throws Exception {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        Files.createDirectories(tempDir.resolve("regions"));
        final Path mappingFile = tempDir.resolve("global_ids.json");

        final CisStorage<String, String, String, String> writerStorage =
                new CisStorage<>(
                        tempDir.resolve("regions"),
                        new CisMapping<>(
                                mappingFile,
                                new TestBlockRegistryAdapter(),
                                stateAdapter,
                                new PropertyPacker<>(stateAdapter)
                        ),
                        new TestNbtAdapter(),
                        "air"
                );

        final CisChunkPos pos = new CisChunkPos(8, 4);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 70, 1, "stone");
        assertThat(writerStorage.save(pos, delta, "save-op-mapping")).isTrue();
        writerStorage.close();

        Files.writeString(mappingFile, "{\"air\":0,\"missing:block\":1}");

        final CisStorage<String, String, String, String> readerStorage =
                new CisStorage<>(
                        tempDir.resolve("regions"),
                        new CisMapping<>(
                                mappingFile,
                                new TestBlockRegistryAdapter(),
                                stateAdapter,
                                new PropertyPacker<>(stateAdapter)
                        ),
                        new TestNbtAdapter(),
                        "air"
                );

        final CisStorage.LoadResult<String, String> loadResult = readerStorage.loadWithPresence(pos,
                "load-op-mapping");
        final ChunkDelta<String, String> loaded = loadResult.delta();
        final ChunkDelta<String, String> regenerated = new ChunkDelta<>("air"::equals);
        regenerated.addBlockChange(2, 70, 2, "stone");
        final boolean overwriteAccepted = readerStorage.save(pos, regenerated, "save-op-after-failed-load");
        final boolean entryKept = readerStorage.contains(pos);
        readerStorage.close();

        assertThat(overwriteAccepted).as("save over an undecodable entry must be refused").isFalse();

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(loadResult.failed()).isTrue();
        assertThat(entryKept).as("undecodable entry must not be deleted").isTrue();
        try (var quarantined = Files.list(tempDir.resolve("regions")
                .resolve("quarantine"))) {
            assertThat(quarantined.toList()).hasSize(1);
        }
        assertThat(ChunkTraceStore.latest(20))
                .filteredOn(event -> "load-op-mapping".equals(event.operationId()))
                .filteredOn(event -> event.eventType() == ChunkTraceEventType.LOAD_TX_END)
                .extracting(ChunkTraceEvent::reason)
                .contains(ChunkTraceReason.MAPPING_LOOKUP_FAILED);
    }

    /**
     * Reads chunks of more regions than the cache keeps open from several threads while saves keep evicting
     * regions, and expects every read to return its chunk: a read that finds its region closed by eviction is
     * retried, never reported as a failed load.
     */
    @Test
    void concurrentReadsSurviveRegionEviction() throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        Files.createDirectories(tempDir.resolve("regions"));
        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(tempDir.resolve("regions"), mapping, new TestNbtAdapter(), "air");
        final int regions = 80;
        for (int r = 0; r < regions; r++) {
            final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
            for (int i = 0; i <= r % 7; i++) {
                delta.addBlockChange(i, 70, 1, "stone");
            }
            assertThat(storage.save(new CisChunkPos(r * 32, 0), delta, "save-" + r)).isTrue();
        }
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        final List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int offset = t;
            futures.add(pool.submit(() -> {
                for (int round = 0; round < 25; round++) {
                    for (int r = offset; r < regions; r += 3) {
                        final CisStorage.LoadResult<String, String> result =
                                storage.loadWithPresence(new CisChunkPos(r * 32, 0), "read");
                        assertThat(result.failed()).as("region %d", r).isFalse();
                        assertThat(result.delta().getBlockChangesCount()).as("region %d", r).isEqualTo(r % 7 + 1);
                    }
                }
                return null;
            }));
        }
        for (final java.util.concurrent.Future<?> future : futures) {
            future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        storage.close();
    }

    /**
     * Loading an entry without its blocks yields a clean released delta: no block instructions, its block entities
     * intact, and the same block-part hash a full load reports. A missing entry yields {@code null}.
     */
    @Test
    void loadReleasedWithoutClearingSkipsOnlyTheBlocks() throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        final Path regionDir = Files.createDirectories(tempDir.resolve("regions"));
        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(regionDir, mapping, new TestNbtAdapter(), "air");
        final CisChunkPos pos = new CisChunkPos(3, 4);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        for (int i = 0; i < 5; i++) {
            delta.addBlockChange(i, 64, 2, "stone");
        }
        delta.addBlockEntityData(1, 64, 2, "chest-data");
        assertThat(storage.save(pos, delta, "save")).isTrue();

        final ChunkDelta<String, String> released = storage.loadReleasedWithoutClearing(pos);
        assertThat(released.isBlockPayloadReleased()).isTrue();
        assertThat(released.isDirty()).isFalse();
        assertThat(released.getBlockChangesCount()).isZero();
        assertThat(released.getBlockEntities().values()).containsExactly("chest-data");
        assertThat(released.getStoredBlockPartHash())
                .isEqualTo(storage.loadWithPresence(pos, "full").delta().getStoredBlockPartHash());
        assertThat(storage.loadReleasedWithoutClearing(new CisChunkPos(40, 40))).isNull();
        storage.close();
    }

    /**
     * Saves chunks of more regions than the cache keeps open from several threads at once, so regions keep being
     * evicted under writes in progress, and expects every last save to be what a reopened storage reads back: a
     * write that meets its region closed by eviction is retried, never lost or half-applied.
     */
    @Test
    void concurrentWritesSurviveRegionEviction() throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        final Path regionDir = Files.createDirectories(tempDir.resolve("regions"));
        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(regionDir, mapping, new TestNbtAdapter(), "air");
        final int regions = 80;
        final int rounds = 6;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        final List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int offset = t;
            futures.add(pool.submit(() -> {
                for (int round = 1; round <= rounds; round++) {
                    for (int r = offset; r < regions; r += 8) {
                        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
                        for (int i = 0; i < round + r % 5; i++) {
                            delta.addBlockChange(i, 70, 1, "stone");
                        }
                        assertThat(storage.save(new CisChunkPos(r * 32, 0), delta, "save")).as("region %d", r).isTrue();
                    }
                }
                return null;
            }));
        }
        for (final java.util.concurrent.Future<?> future : futures) {
            future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        storage.close();

        final CisStorage<String, String, String, String> reopened =
                new CisStorage<>(regionDir, mapping, new TestNbtAdapter(), "air");
        for (int r = 0; r < regions; r++) {
            final CisStorage.LoadResult<String, String> result =
                    reopened.loadWithPresence(new CisChunkPos(r * 32, 0), "verify");
            assertThat(result.failed()).as("region %d", r).isFalse();
            assertThat(result.delta().getBlockChangesCount()).as("region %d", r).isEqualTo(rounds + r % 5);
        }
        reopened.close();
    }

    /**
     * A load whose stored block part a block source knows takes the blocks from the source and decodes only the
     * rest; a source that does not know that exact block part leaves the load to decode it.
     */
    @Test
    void blockPartSourceSuppliesKnownBlocksOnly() throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        Files.createDirectories(tempDir.resolve("regions"));
        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(tempDir.resolve("regions"), mapping, new TestNbtAdapter(), "air");
        final CisChunkPos pos = new CisChunkPos(4, 4);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 70, 1, "stone");
        delta.addBlockChange(2, 71, 3, "stone");
        assertThat(storage.save(pos, delta, "save")).isTrue();
        final long[] storedHash = storage.load(pos).getStoredBlockPartHash();
        assertThat(storedHash).isNotNull();

        final int[] supplied = {0};
        storage.setBlockPartSource((at, hash) -> java.util.Arrays.equals(hash, storedHash)
                ? loaded -> {
                    supplied[0]++;
                    loaded.addBlockChange(1, 70, 1, "stone", false);
                    loaded.addBlockChange(2, 71, 3, "stone", false);
                }
                : null);
        final ChunkDelta<String, String> fromSource = storage.load(pos);
        assertThat(supplied[0]).isEqualTo(1);
        assertThat(fromSource.getBlockChangesCount()).isEqualTo(2);
        assertThat(fromSource.isDirty()).isFalse();

        storage.setBlockPartSource((at, hash) -> null);
        assertThat(storage.load(pos).getBlockChangesCount()).isEqualTo(2);
        storage.close();
    }

    /**
     * Performs paranoid write verification reads back stored bytes.
     */
    @Test
    void paranoidWriteVerificationReadsBackStoredBytes() throws Exception {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.PARANOID);
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        Files.createDirectories(tempDir.resolve("regions"));

        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(tempDir.resolve("regions"), mapping, new TestNbtAdapter(), "air");

        final CisChunkPos pos = new CisChunkPos(2, 5);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 70, 1, "stone");

        assertThat(storage.save(pos, delta, "save-op-paranoid")).isTrue();
        storage.close();

        assertThat(ChunkTraceStore.latest(20))
                .filteredOn(event -> "save-op-paranoid-verify".equals(event.operationId()))
                .extracting(ChunkTraceEvent::eventType)
                .contains(
                        ChunkTraceEventType.REGION_READ_TX_START,
                        ChunkTraceEventType.REGION_READ_TX_END
                );
        assertThat(ChunkTraceStore.latest(20))
                .filteredOn(event -> "save-op-paranoid".equals(event.operationId()))
                .filteredOn(event -> event.eventType() == ChunkTraceEventType.ASSERTION_FAILED)
                .isEmpty();
    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {

        /**
         * Stores known blocks.
         */
        private static final List<String> KNOWN_BLOCKS = List.of("air", "stone");

        @Override
        public String getId(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String id) {
            return KNOWN_BLOCKS.contains(id) ? id : null;
        }

        @Override
        public String getAir() {
            return "air";
        }

        @Override
        public Collection<String> getRegisteredBlocks() {
            return KNOWN_BLOCKS;
        }
    }

    private static final class TestBlockStateAdapter implements BlockStateAdapter<String, String, String> {

        @Override
        public String getDefaultState(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String state) {
            return state;
        }

        @Override
        public List<String> getProperties(final String block) {
            return List.of();
        }

        @Override
        public String getPropertyName(final String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(final String property) {
            return List.of();
        }

        @Override
        public int getValueIndex(final String state, final String property) {
            return 0;
        }

        @Override
        public String withProperty(final String state, final String property, final int i) {
            return state;
        }
    }

    private static final class TestNbtAdapter implements NbtAdapter<String> {

        @Override
        public void write(final String tag, final DataOutput output) throws IOException {
            output.writeUTF(tag);
        }

        @Override
        public String read(final DataInput input) throws IOException {
            return input.readUTF();
        }
    }
}
