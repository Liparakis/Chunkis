package io.liparakis.chunkis.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.core.model.CisConstants;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link CisStorageMigrator} using an in-process string-backed
 * storage harness.
 *
 * <p>Each test constructs an isolated temporary storage tree so that fixture
 * state cannot leak between cases.
 */
class CisStorageMigratorTest {

    /**
     * Number of chunk slots per region file (32 × 32).
     */
    private static final int REGION_SLOTS = 1024;
    /** Stores current version. */
    private static final int CURRENT_VERSION = CisConstants.VERSION;
    /** Stores legacy version. */
    private static final int LEGACY_VERSION = 8;
    /** Stores version marker file. */
    private static final String VERSION_MARKER_FILE = ".chunkis-cis-version";
    /**
     * Bytes per chunk header entry (offset int + length int).
     */
    private static final int HEADER_ENTRY_BYTES = 8;

    /**
     * Temporary directory used to create isolated fixture environments.
     */
    @TempDir
    Path tempDir;

    /**
     * Asserts that the migration report contains the expected counts.
     */
    private static void assertReport(
            final CisMigrationReport report,
            final int scanned,
            final int migrated,
            final int skipped) {
        assertEquals(scanned, report.scannedChunks());
        assertEquals(migrated, report.migratedChunks());
        assertEquals(skipped, report.skippedChunks());
        assertEquals(0, report.failedChunks());
    }

    /**
     * Verifies that the migrator returns an empty report and does not crash
     * when the target storage directory is missing.
     */
    @Test
    void returnsEmptyReportWhenStorageDirectoryDoesNotExist() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisStorageMigrator<String, String> migrator = migrator(harness);

        final CisMigrationReport report = migrator.migrateStorage(tempDir.resolve("missing"));

        assertReport(report, 0, 0, 0);
        harness.close();
    }

    /**
     * Verifies that legacy chunks (e.g., version 8) are correctly identified
     * and upgraded to the target version (e.g., version 9).
     */
    @Test
    void migratesLegacyChunkCreatedInIsolatedTempStorage() throws Exception {
        final TestStorageHarness harness = createHarness();

        final CisChunkPos legacyPos = new CisChunkPos(0, 0);
        final CisChunkPos currentPos = new CisChunkPos(1, 0);

        harness.saveChunk(legacyPos, "stone");
        harness.saveChunk(currentPos, "dirt");
        harness.rewriteChunkVersionToLegacy(legacyPos);

        final CisMigrationReport report = migrator(harness).migrateStorage(harness.regionsDir());

        assertReport(report, REGION_SLOTS, 1, REGION_SLOTS - 1);
        assertEquals(CURRENT_VERSION,
                harness.storage()
                        .load(legacyPos)
                        .getSourceVersion());
        assertEquals(CURRENT_VERSION,
                harness.storage()
                        .load(currentPos)
                        .getSourceVersion());

        harness.close();
    }

    /**
     * Verifies that a successful migration run marks the storage at the target
     * version so later startups can skip the full region rescan.
     */
    @Test
    void successfulMigrationMarksStorageAndSkipsLaterRescan() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisChunkPos legacyPos = new CisChunkPos(0, 0);

        harness.saveChunk(legacyPos, "stone");
        harness.rewriteChunkVersionToLegacy(legacyPos);

        final CisStorageMigrator<String, String> migrator = migrator(harness);
        final CisMigrationReport firstReport = migrator.migrateStorage(harness.regionsDir());
        final CisMigrationReport secondReport = migrator.migrateStorage(harness.regionsDir());

        assertReport(firstReport, REGION_SLOTS, 1, REGION_SLOTS - 1);
        assertReport(secondReport, 0, 0, 0);
        assertTrue(Files.exists(harness.regionsDir()
                .resolve(VERSION_MARKER_FILE)));

        harness.close();
    }

    /**
     * Verifies that only a marker for the current target version suppresses the
     * expensive migration scan.
     */
    @Test
    void staleVersionMarkerDoesNotSuppressRequiredMigration() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisChunkPos legacyPos = new CisChunkPos(0, 0);

        harness.saveChunk(legacyPos, "stone");
        harness.rewriteChunkVersionToLegacy(legacyPos);
        Files.writeString(
                harness.regionsDir()
                        .resolve(VERSION_MARKER_FILE),
                Integer.toString(LEGACY_VERSION)
        );

        final CisMigrationReport report = migrator(harness).migrateStorage(harness.regionsDir());

        assertReport(report, REGION_SLOTS, 1, REGION_SLOTS - 1);
        assertEquals(CURRENT_VERSION,
                harness.storage()
                        .load(legacyPos)
                        .getSourceVersion());

        harness.close();
    }

    /**
     * Verifies that the migrator ignores files that do not follow the region
     * naming convention and correctly counts only valid region slots.
     */
    @Test
    void ignoresNonRegionFilesAndCountsOnlyRegionSlots() throws Exception {
        final TestStorageHarness harness = createHarness();
        Files.createFile(harness.regionsDir()
                .resolve("notes.txt"));

        final CisMigrationReport report = migrator(harness).migrateStorage(harness.regionsDir());

        assertReport(report, 0, 0, 0);
        harness.close();
    }

    /**
     * Verifies that migration failures do not delete the source chunk entry.
     *
     * <p>This is the critical data-safety property for upgrades: a chunk that
     * cannot be decoded by the current migrator must remain on disk for future
     * salvage instead of being cleared as "corrupt" during the migration scan.</p>
     */
    @Test
    void migrationFailureDoesNotDeleteSourceChunkEntry() throws Exception {
        final TestStorageHarness harness = createHarness();
        final CisChunkPos legacyPos = new CisChunkPos(0, 0);

        harness.saveChunk(legacyPos, "stone");
        harness.rewriteChunkVersionToLegacy(legacyPos);
        assertTrue(harness.chunkEntryExists(legacyPos), "Expected legacy chunk entry before corruption.");

        harness.corruptChunkPayload(legacyPos);

        final CisMigrationReport report = migrator(harness).migrateStorage(harness.regionsDir());

        assertEquals(REGION_SLOTS, report.scannedChunks());
        assertEquals(0, report.migratedChunks());
        assertEquals(REGION_SLOTS - 1, report.skippedChunks());
        assertEquals(1, report.failedChunks());
        assertTrue(
                harness.chunkEntryExists(legacyPos),
                "Migration must preserve the original chunk bytes when decode fails."
        );
        assertFalse(Files.exists(harness.regionsDir()
                .resolve(VERSION_MARKER_FILE)));

        harness.close();
    }

    /**
     * Creates a {@link CisStorageMigrator} instance for the provided harness.
     *
     * @param harness storage harness to wrap
     * @return new migrator instance
     */
    private CisStorageMigrator<String, String> migrator(final TestStorageHarness harness) {
        return new CisStorageMigrator<>(
                harness.storage(),
                LoggerFactory.getLogger(CisStorageMigratorTest.class),
                CURRENT_VERSION
        );
    }

    /**
     * Creates a new isolated storage harness for a test case.
     */
    private TestStorageHarness createHarness() throws Exception {
        final Path storageRoot = tempDir.resolve("generated-storage");
        final Path regionsDir = storageRoot.resolve("regions");
        Files.createDirectories(regionsDir);
        return new TestStorageHarness(storageRoot, regionsDir, openStorage(storageRoot, regionsDir));
    }

    /**
     * Opens a {@link CisStorage} instance using test-only SPI implementation.
     */
    private CisStorage<String, String, String, String> openStorage(
            final Path storageRoot,
            final Path regionsDir) throws Exception {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                storageRoot.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );

        return new CisStorage<>(regionsDir, mapping, new TestNbtAdapter(), "air");
    }

    /**
     * Stub implementation of {@link BlockRegistryAdapter} for testing.
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
     * Stub implementation of {@link BlockStateAdapter} for testing.
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
     * Stub implementation of {@link NbtAdapter} for testing.
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

    /**
     * Wraps a temporary {@link CisStorage} instance and exposes helpers for
     * writing chunks and surgically rewriting chunk version bytes in region files.
     *
     * <p>Call {@link #close()} at the end of each test to release file handles.
     */
    private final class TestStorageHarness {

        /** Stores storage root. */
        private final Path storageRoot;
        /** Stores regions dir. */
        private final Path regionsDir;
        /** Stores string. */
        private CisStorage<String, String, String, String> storage;

        /** Performs test storage harness. */
        private TestStorageHarness(
                final Path storageRoot,
                final Path regionsDir,
                final CisStorage<String, String, String, String> storage) {
            this.storageRoot = storageRoot;
            this.regionsDir = regionsDir;
            this.storage = storage;
        }

        /** Performs grow. */
        private static byte[] grow(final byte[] source, final int newLength) {
            final byte[] expanded = new byte[newLength];
            System.arraycopy(source, 0, expanded, 0, source.length);
            return expanded;
        }

        /** Performs inflate. */
        private static byte[] inflate(final byte[] compressed) throws Exception {
            final long decompressedSize = com.github.luben.zstd.Zstd.decompressedSize(compressed);
            if (com.github.luben.zstd.Zstd.isError(decompressedSize)) {
                throw new IOException(
                        "Failed to read Zstd size: " + com.github.luben.zstd.Zstd.getErrorName(decompressedSize));
            }
            return com.github.luben.zstd.Zstd.decompress(compressed, (int) decompressedSize);
        }

        /** Performs deflate. */
        private static byte[] deflate(final byte[] raw) {
            return com.github.luben.zstd.Zstd.compress(raw, 3);
        }

        /** Performs read int. */
        private static int readInt(final byte[] data, final int offset) {
            return ((data[offset] & 0xFF) << 24)
                    | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
        }

        /** Performs write int. */
        private static void writeInt(final byte[] data, final int offset, final int value) {
            data[offset] = (byte) (value >>> 24);
            data[offset + 1] = (byte) (value >>> 16);
            data[offset + 2] = (byte) (value >>> 8);
            data[offset + 3] = (byte) value;
        }

        /**
         * @return the path to the directory containing {@code .cis} region files.
         */
        Path regionsDir() {
            return regionsDir;
        }

        /**
         * @return the underlying {@link CisStorage} instance.
         */
        CisStorage<String, String, String, String> storage() {
            return storage;
        }

        /**
         * Saves a minimal single-block chunk delta at {@code pos}.
         *
         * @param pos   target chunk position
         * @param state block state string to record
         */
        void saveChunk(final CisChunkPos pos, final String state) {
            final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
            delta.addBlockChange(0, 64, 0, state);
            storage.save(pos, delta);
        }

        /**
         * Closes the current storage, patches the encoded version field of the
         * specified chunk in its region file, and reopens storage.
         *
         * <p>If the rewritten payload is larger than the original slot, the region
         * byte array is grown and the header is updated to point to the new offset.
         *
         * @param pos chunk whose version field should be overwritten
         */
        void rewriteChunkVersionToLegacy(final CisChunkPos pos) throws Exception {
            rewriteChunkVersion(pos);
        }

        /** Performs rewrite chunk version. */
        void rewriteChunkVersion(final CisChunkPos pos) throws Exception {
            storage.close();

            final Path regionFile = regionsDir.resolve(
                    "r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".cis");
            final byte[] regionBytes = Files.readAllBytes(regionFile);

            final int index = (pos.x() & 31) + (pos.z() & 31) * 32;
            final int headerOffset = index * HEADER_ENTRY_BYTES;
            final int chunkOffset = readInt(regionBytes, headerOffset);
            final int chunkLength = readInt(regionBytes, headerOffset + 4);

            final byte[] compressed = new byte[chunkLength];
            System.arraycopy(regionBytes, chunkOffset, compressed, 0, chunkLength);

            final byte[] raw = inflate(compressed);
            writeInt(raw, 4, CisStorageMigratorTest.LEGACY_VERSION);

            final byte[] rewritten = deflate(raw);
            final boolean fits = rewritten.length <= chunkLength;
            final byte[] target = fits ? regionBytes : grow(regionBytes, regionBytes.length + rewritten.length);
            final int writeOffset = fits ? chunkOffset : regionBytes.length;

            System.arraycopy(rewritten, 0, target, writeOffset, rewritten.length);
            writeInt(target, headerOffset, writeOffset);
            writeInt(target, headerOffset + 4, rewritten.length);

            Files.write(regionFile, target);
            storage = openStorage(storageRoot, regionsDir);
        }

        /**
         * Corrupts the stored payload for {@code pos} without removing the region
         * header entry, simulating a decode failure during migration.
         *
         * @param pos target chunk position
         */
        void corruptChunkPayload(final CisChunkPos pos) throws Exception {
            storage.close();

            final Path regionFile = regionsDir.resolve(
                    "r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".cis");
            final byte[] regionBytes = Files.readAllBytes(regionFile);

            final int index = (pos.x() & 31) + (pos.z() & 31) * 32;
            final int headerOffset = index * HEADER_ENTRY_BYTES;
            final int chunkOffset = readInt(regionBytes, headerOffset);

            regionBytes[chunkOffset] ^= (byte) 0x7F;

            Files.write(regionFile, regionBytes);
            storage = openStorage(storageRoot, regionsDir);
        }

        /**
         * Returns whether the region header still points at a stored chunk entry.
         *
         * @param pos target chunk position
         * @return {@code true} if the region slot is still populated
         */
        boolean chunkEntryExists(final CisChunkPos pos) throws Exception {
            storage.close();

            final Path regionFile = regionsDir.resolve(
                    "r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".cis");
            final byte[] regionBytes = Files.readAllBytes(regionFile);

            final int index = (pos.x() & 31) + (pos.z() & 31) * 32;
            final int headerOffset = index * HEADER_ENTRY_BYTES;
            final boolean exists =
                    readInt(regionBytes, headerOffset) > 0
                            && readInt(regionBytes, headerOffset + 4) > 0;

            storage = openStorage(storageRoot, regionsDir);
            return exists;
        }

        /**
         * Closes the underlying storage and releases file handles.
         */
        void close() {
            storage.close();
        }
    }

}
