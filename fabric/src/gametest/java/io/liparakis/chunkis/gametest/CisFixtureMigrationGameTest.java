package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.migrator.CisMigrationReport;
import io.liparakis.chunkis.migrator.CisStorageMigrator;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.helpers.NOPLogger;

/**
 * End-to-end GameTest coverage for migrating real V8 CIS fixtures through the
 * live Fabric storage stack.
 *
 * <p>The test copies fixture files into the active GameTest world save,
 * snapshots every populated chunk's logical contents before migration, runs
 * {@link CisStorageMigrator}, then verifies that migrated chunks decode
 * identically while reporting the current runtime CIS version.</p>
 *
 * <p><b>Threading:</b> all GameTest lifecycle methods run on the server main
 * thread. Storage is explicitly closed before fixture setup and after teardown
 * to avoid file-handle conflicts.</p>
 */
@SuppressWarnings("unused")
public final class CisFixtureMigrationGameTest {

    /**
     * Classpath directory containing the V8 fixture files.
     */
    private static final String FIXTURE_ROOT = "V8";

    /**
     * Region file paths relative to {@link #FIXTURE_ROOT}.
     * Each path covers 32×32 = 1024 chunk slots.
     */
    private static final String[] FIXTURE_REGION_FILES = {
            "regions/r.-1.0.cis",
            "regions/r.-1.1.cis",
            "regions/r.0.0.cis",
            "regions/r.0.1.cis"
    };

    /**
     * Total chunk slots covered by {@link #FIXTURE_REGION_FILES}
     * (file count × 1024 slots per region).
     */
    private static final int EXPECTED_SCANNED_CHUNKS = FIXTURE_REGION_FILES.length * 1024;

    /**
     * Bytes per chunk header entry in a CIS region file (offset int + length int).
     */
    private static final int HEADER_ENTRY_BYTES = 8;

    /**
     * Number of chunk slots per region file (32 × 32).
     */
    private static final int REGION_SLOTS = 1024;

    /**
     * Copies the read-only V8 fixture set into the active GameTest world save.
     *
     * @param storageRoot target Chunkis storage root
     * @param regionsDir  target region directory
     * @throws IOException if any fixture cannot be copied
     */
    private static void copyFixtures(final Path storageRoot, final Path regionsDir) throws IOException {
        Files.createDirectories(regionsDir);
        copyFixture(FIXTURE_ROOT + "/global_ids.json", storageRoot.resolve("global_ids.json"));
        for (final String regionFile : FIXTURE_REGION_FILES) {
            copyFixture(FIXTURE_ROOT + "/" + regionFile, storageRoot.resolve(regionFile));
        }
    }

    /**
     * Copies one classpath resource to the requested destination.
     *
     * @param resourcePath fixture resource path on the classpath
     * @param destination  target file path
     * @throws IOException if the resource is missing or the copy fails
     */
    private static void copyFixture(final String resourcePath, final Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = CisFixtureMigrationGameTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing fixture resource: " + resourcePath);
            }
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes a directory tree if it exists; no-op if {@code path} is absent.
     *
     * @param path root directory to remove
     * @throws IOException if a delete operation fails
     */
    private static void deleteRecursively(final Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(target -> {
                        try {
                            Files.deleteIfExists(target);
                        } catch (final IOException e) {
                            throw new RuntimeException("Failed to delete " + target, e);
                        }
                    });
        } catch (final RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    /**
     * Reads region file headers and returns the absolute chunk positions for every
     * non-empty slot in the copied fixture set, in deterministic order.
     *
     * <p>Filename format is {@code r.<regionX>.<regionZ>.cis}. Each slot header
     * is {@value #HEADER_ENTRY_BYTES} bytes (big-endian offset int + length int).
     * A slot is populated when both offset and length are positive.</p>
     *
     * @param regionsDir copied fixture region directory
     * @return populated chunk positions sorted by X then Z
     * @throws IOException if a region file cannot be read
     */
    private static List<CisChunkPos> collectPopulatedChunks(final Path regionsDir) throws IOException {
        final TreeSet<CisChunkPos> positions = new TreeSet<>(
                Comparator.comparingInt(CisChunkPos::x)
                        .thenComparingInt(CisChunkPos::z));

        for (final String regionFile : FIXTURE_REGION_FILES) {
            // Strip "regions/r." prefix and ".cis" suffix to extract "regionX.regionZ".
            final String stem = regionFile.substring("regions/r.".length(), regionFile.length() - ".cis".length());
            final String[] parts = stem.split("\\.");
            final int regionX = Integer.parseInt(parts[0]);
            final int regionZ = Integer.parseInt(parts[1]);
            final byte[] bytes = Files.readAllBytes(regionsDir.resolve("r." + regionX + "." + regionZ + ".cis"));

            for (int index = 0; index < REGION_SLOTS; index++) {
                final int headerOffset = index * HEADER_ENTRY_BYTES;
                if (readInt(bytes, headerOffset) <= 0 || readInt(bytes, headerOffset + 4) <= 0) {
                    continue;
                }
                // Chunk X = (regionX << 5) | (index % 32); Z = (regionZ << 5) | (index / 32).
                positions.add(new CisChunkPos(
                        (regionX << 5) + (index & 31),
                        (regionZ << 5) + (index >>> 5)));
            }
        }

        return new ArrayList<>(positions);
    }

    /**
     * Loads and snapshots all requested chunks, asserting the expected source
     * version and suppression flag for each one.
     *
     * @param storage         active storage
     * @param positions       chunk positions to capture
     * @param expectedVersion expected decoded source version
     * @return deterministic snapshot map keyed by chunk position
     */
    private static Map<CisChunkPos, ChunkSnapshot> snapshotChunks(
            final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final List<CisChunkPos> positions,
            final int expectedVersion) {
        final Map<CisChunkPos, ChunkSnapshot> snapshots = new LinkedHashMap<>();
        final NbtCompound deltaMarker = buildRootWithDeltaMarker();

        for (final CisChunkPos pos : positions) {
            final ChunkDelta<BlockState, NbtCompound> delta = storage.load(pos);

            if (delta.isEmpty()) {
                throw new IllegalStateException(
                        "Expected populated fixture chunk at " + pos + " but decoded an empty delta.");
            }
            if (delta.getSourceVersion() != expectedVersion) {
                throw new IllegalStateException(
                        "Expected chunk " + pos + " to decode as v" + expectedVersion
                                + " but got v" + delta.getSourceVersion());
            }
            if (!CisNbtUtil.shouldSuppressInitialRepopulation(deltaMarker, delta)) {
                throw new IllegalStateException(
                        "Expected chunk " + pos + " suppression flag to be true but got false.");
            }

            snapshots.put(pos, ChunkSnapshot.from(delta));
        }

        return snapshots;
    }

    /**
     * Reads one big-endian {@code int} from {@code bytes} at {@code offset}.
     *
     * @param bytes  source byte array
     * @param offset starting byte index
     * @return decoded integer
     */
    private static int readInt(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /**
     * Builds a minimal synthetic chunk root carrying the Chunkis delta marker,
     * suitable for passing to {@link CisNbtUtil#shouldSuppressInitialRepopulation}.
     *
     * @return root compound with the delta marker set to {@code true}
     */
    private static NbtCompound buildRootWithDeltaMarker() {
        final NbtCompound root = new NbtCompound();
        final NbtCompound chunkisData = new NbtCompound();
        chunkisData.putBoolean(CisNbtUtil.HAS_DELTA_KEY, true);
        root.put(CisNbtUtil.CHUNKIS_DATA_KEY, chunkisData);
        return root;
    }

    /**
     * Initializes and configures a {@link CisStorage} instance using Fabric-specific adapters
     * and mapping files located within the test storage directory.
     *
     * @param storageRoot the target Chunkis storage root directory
     * @param regionsDir  the target regions subdirectory containing CIS region files
     * @return the initialized CisStorage instance
     * @throws IOException if the global mapping file cannot be read or initialized
     */
    private static CisStorage<Block, BlockState, Property<?>, NbtCompound> createStorage(final Path storageRoot,
            final Path regionsDir) throws IOException {
        final FabricBlockStateAdapter blockStateAdapter = new FabricBlockStateAdapter();
        final PropertyPacker<Block, BlockState, Property<?>> packer = new PropertyPacker<>(blockStateAdapter);
        final CisMapping<Block, BlockState, Property<?>> mapping = new CisMapping<>(
                storageRoot.resolve("global_ids.json"),
                new FabricBlockRegistryAdapter(),
                blockStateAdapter,
                packer
        );
        return new CisStorage<>(
                regionsDir,
                mapping,
                new FabricNbtAdapter(),
                net.minecraft.block.Blocks.AIR.getDefaultState()
        );
    }

    /**
     * Verifies that the real V8 fixture set migrates losslessly to the current
     * CIS version when processed through the Fabric runtime adapters.
     *
     * @param context GameTest execution context
     * @throws IOException if fixture setup fails
     */
    @GameTest(maxTicks = 400)
    public void migratesV8FixturesWithoutChangingLogicalChunkContents(final TestContext context) throws IOException {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ServerWorld world = context.getWorld();
        final Path storageRoot = Objects.requireNonNull(world.getServer())
                .getSavePath(WorldSavePath.ROOT)
                .resolve("chunkis_migration_test");
        final Path regionsDir = storageRoot.resolve("regions");

        deleteRecursively(storageRoot);
        copyFixtures(storageRoot, regionsDir);

        final List<CisChunkPos> populatedChunks = collectPopulatedChunks(regionsDir);
        context.assertTrue(!populatedChunks.isEmpty(),
                Text.literal("Expected at least one populated V8 fixture chunk."));

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = createStorage(storageRoot, regionsDir);

        try {
            final Map<CisChunkPos, ChunkSnapshot> before = snapshotChunks(storage, populatedChunks, 8);

            final CisMigrationReport report =
                    new CisStorageMigrator<>(storage, NOPLogger.NOP_LOGGER).migrateStorage(regionsDir);

            context.assertTrue(
                    report.failedChunks() == 0,
                    Text.literal("Expected no failed chunk migrations, got " + report.failedChunks()));
            context.assertTrue(
                    report.migratedChunks() == populatedChunks.size(),
                    Text.literal(
                            "Expected " + populatedChunks.size() + " migrated chunks, got " + report.migratedChunks()));
            context.assertTrue(
                    report.scannedChunks() == EXPECTED_SCANNED_CHUNKS,
                    Text.literal("Expected to scan " + EXPECTED_SCANNED_CHUNKS + " chunk slots, got "
                            + report.scannedChunks()));

            final Map<CisChunkPos, ChunkSnapshot> after = snapshotChunks(storage,
                    populatedChunks,
                    CisConstants.VERSION);
            context.assertTrue(
                    before.equals(after),
                    Text.literal("Migrated chunk contents did not match the original fixture snapshot."));
            context.complete();
        } finally {
            ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
            storage.close();
            deleteRecursively(storageRoot);
        }
    }

    /**
     * Stable logical representation of one decoded chunk delta, used for
     * before/after equality checks across migration.
     *
     * <p>All list fields are sorted to ensure deterministic equality regardless
     * of iteration order in the underlying delta maps.</p>
     *
     * @param blocks        sorted block-change entries
     * @param blockEntities sorted block-entity entries
     * @param entities      sorted entity payload entries
     * @param chunkMetadata canonical chunk metadata string
     */
    private record ChunkSnapshot(
            List<String> blocks,
            List<String> blockEntities,
            List<String> entities,
            String chunkMetadata) {

        /**
         * Builds a deterministic, immutable snapshot from a decoded chunk delta.
         *
         * <p>Air blocks are excluded from the block list since they represent
         * the absence of data rather than a real instruction. Block-entity and
         * entity maps are iterated and sorted so equality checks are stable.</p>
         *
         * @param delta decoded chunk delta
         * @return snapshot suitable for equality comparison
         */
        private static ChunkSnapshot from(final ChunkDelta<BlockState, NbtCompound> delta) {
            final List<String> blocks = new ArrayList<>();
            for (final BlockInstruction instruction : delta.getBlockInstructions()) {
                final BlockState state = delta.getBlockPalette()
                        .get(instruction.paletteIndex());
                if (state == null || state.isAir()) {
                    continue;
                }
                blocks.add(instruction.x() + "," + instruction.y() + "," + instruction.z() + "=" + state);
            }
            blocks.sort(String::compareTo);

            final List<String> blockEntities = new ArrayList<>();
            delta.getBlockEntities()
                    .forEach((packedPos, nbt) -> blockEntities.add(
                            BlockInstruction.unpackX(packedPos) + ","
                                    + BlockInstruction.unpackY(packedPos) + ","
                                    + BlockInstruction.unpackZ(packedPos) + "=" + nbt));
            blockEntities.sort(String::compareTo);

            final List<String> entities = delta.getEntitiesList()
                    .stream()
                    .map(String::valueOf)
                    .sorted()
                    .toList();

            return new ChunkSnapshot(
                    List.copyOf(blocks),
                    List.copyOf(blockEntities),
                    entities,
                    String.valueOf(delta.getChunkMetadata()));
        }
    }
}