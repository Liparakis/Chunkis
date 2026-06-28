package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.storage.mapping.PropertyPacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises end-to-end region compaction behavior through the public storage
 * facade.
 */
class CisStorageCompactionTest {

    /** Size of one raw region header entry used by fixture inspection helpers. */
    private static final int HEADER_ENTRY_BYTES = 8;
    /** Corrupt offset used to force compaction down the failure path. */
    private static final int INVALID_CHUNK_OFFSET = Integer.MAX_VALUE - HEADER_ENTRY_BYTES - 1;

    /** Temporary filesystem sandbox for each compaction test. */
    @TempDir
    Path tempDir;

    @Test
    void compactRegionsRemovesSlackWithoutChangingLiveChunks() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisChunkPos pos = new CisChunkPos(0, 0);

        harness.saveChunk(pos, "stone", 512);
        final Path regionFile = harness.regionFile(pos);
        final long sizeAfterLargeSave = Files.size(regionFile);

        harness.saveChunk(pos, "dirt", 1);
        final long sizeBeforeCompaction = Files.size(regionFile);
        final long liveBytesBefore = harness.liveBytes(pos);

        final CisRegionCompactor.CompactionReport report = CisRegionCompactor.compact(harness.storage());

        final long sizeAfterCompaction = Files.size(regionFile);
        assertEquals(1, report.compactedRegions());
        assertEquals(sizeBeforeCompaction, report.physicalBytesBefore());
        assertEquals(sizeAfterCompaction, report.physicalBytesAfter());
        assertEquals(liveBytesBefore, report.liveBytes());
        assertTrue(sizeBeforeCompaction > sizeAfterCompaction, "Expected slack to be removed.");
        assertEquals("dirt", harness.loadSingleState(pos));
        assertEquals(1, harness.storedChunkCount(regionFile));
        assertTrue(sizeAfterLargeSave <= sizeBeforeCompaction, "Test setup failed to create append slack.");

        harness.close();
    }

    @Test
    void compactionFailurePreservesOriginalRegionFile() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisChunkPos pos = new CisChunkPos(0, 0);

        harness.saveChunk(pos, "stone", 4);
        final Path regionFile = harness.regionFile(pos);
        harness.corruptChunkOffset(pos);
        final int corruptedOffset = harness.headerOffset(pos);

        final CisRegionCompactor.CompactionReport report = CisRegionCompactor.compact(harness.storage());

        assertEquals(0, report.compactedRegions());
        assertEquals(1, report.failedRegions());
        assertEquals(Files.size(regionFile), report.physicalBytesBefore());
        assertEquals(corruptedOffset, harness.headerOffset(pos));
        assertTrue(Files.notExists(regionFile.resolveSibling(regionFile.getFileName().toString() + ".tmp")));

        harness.close();
    }

    /**
     * Builds a minimal end-to-end storage harness rooted in the temporary test
     * directory.
     */
    private TestStorageHarness createHarness() throws Exception {
        final Path storageRoot = tempDir.resolve("generated-storage");
        final Path regionsDir = storageRoot.resolve("regions");
        Files.createDirectories(regionsDir);
        return new TestStorageHarness(storageRoot, regionsDir, openStorage(storageRoot, regionsDir));
    }

    /**
     * Opens a storage instance backed by simple string-based test adapters.
     */
    private CisStorage<String, String, String, String> openStorage(
            final Path storageRoot,
            final Path regionsDir
    ) throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                storageRoot.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        return new CisStorage<>(regionsDir, mapping, stateAdapter, new TestNbtAdapter(), "air");
    }

    /**
     * Small harness that exercises real storage I/O while keeping fixture code
     * out of the assertions.
     */
    private final class TestStorageHarness {
        /** Root path containing mapping files plus region storage. */
        private final Path storageRoot;
        /** Directory holding generated test region files. */
        private final Path regionsDir;
        /** Active storage instance under test. */
        private CisStorage<String, String, String, String> storage;

        private TestStorageHarness(
                final Path storageRoot,
                final Path regionsDir,
                final CisStorage<String, String, String, String> storage
        ) {
            this.storageRoot = storageRoot;
            this.regionsDir = regionsDir;
            this.storage = storage;
        }

        /**
         * Returns the active storage under test.
         */
        private CisStorage<String, String, String, String> storage() {
            return storage;
        }

        /**
         * Saves a simple chunk containing the same block state repeated across a
         * chosen number of positions.
         */
        private void saveChunk(final CisChunkPos pos, final String state, final int blockCount) {
            final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
            for (int i = 0; i < blockCount; i++) {
                delta.addBlockChange(i & 15, 64 + ((i >> 8) & 15), (i >> 4) & 15, state);
            }
            storage.save(pos, delta);
        }

        /**
         * Returns the region-file path that owns the supplied chunk.
         */
        private Path regionFile(final CisChunkPos pos) {
            return regionsDir.resolve("r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".cis");
        }

        /**
         * Reads the live payload length for the first stored chunk in the region.
         */
        private long liveBytes(final CisChunkPos pos) throws Exception {
            final byte[] bytes = Files.readAllBytes(regionFile(pos));
            return readInt(bytes, 4);
        }

        /**
         * Counts populated chunk slots in a raw region header.
         */
        private int storedChunkCount(final Path regionFile) throws Exception {
            final byte[] bytes = Files.readAllBytes(regionFile);
            int count = 0;
            for (int i = 0; i < 1024; i++) {
                final int headerOffset = i * HEADER_ENTRY_BYTES;
                if (readInt(bytes, headerOffset) > 0 && readInt(bytes, headerOffset + 4) > 0) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Reads the raw header offset for one chunk slot.
         */
        private int headerOffset(final CisChunkPos pos) throws Exception {
            final byte[] bytes = Files.readAllBytes(regionFile(pos));
            final int index = (pos.x() & 31) + (pos.z() & 31) * 32;
            return readInt(bytes, index * HEADER_ENTRY_BYTES);
        }

        /**
         * Loads the single non-air state written by the test fixture.
         */
        private String loadSingleState(final CisChunkPos pos) {
            final String[] state = new String[1];
            storage.load(pos).forEachBlock((x, y, z, value) -> state[0] = value);
            return state[0];
        }

        /**
         * Corrupts one chunk header offset and reopens storage so compaction sees
         * the damaged region on disk.
         */
        private void corruptChunkOffset(final CisChunkPos pos) throws Exception {
            storage.close();
            final Path regionFile = regionFile(pos);
            final byte[] bytes = Files.readAllBytes(regionFile);
            final int index = (pos.x() & 31) + (pos.z() & 31) * 32;
            writeInt(bytes, index * HEADER_ENTRY_BYTES, INVALID_CHUNK_OFFSET);
            Files.write(regionFile, bytes);
            storage = openStorage(storageRoot, regionsDir);
        }

        /**
         * Closes the active storage instance.
         */
        private void close() {
            storage.close();
        }
    }

    /**
     * Reads a big-endian integer from a raw byte array.
     */
    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Writes a big-endian integer into a raw byte array.
     */
    private static void writeInt(final byte[] data, final int offset, final int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    /**
     * Minimal registry adapter for string-backed block IDs.
     */
    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {
        @Override
        public String getId(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String id) {
            return id;
        }

        @Override
        public String getAir() {
            return "air";
        }

        @Override
        public Collection<String> getRegisteredBlocks() {
            return List.of("air", "stone", "dirt");
        }
    }

    /**
     * Minimal block-state adapter for string-backed states.
     */
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

    /**
     * Minimal NBT adapter that round-trips UTF strings for tests.
     */
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
