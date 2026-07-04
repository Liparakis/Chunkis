package io.liparakis.chunkis.storage.mapping;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.bits.BitReader;
import io.liparakis.chunkis.storage.bits.BitWriter;
import io.liparakis.chunkis.storage.mapping.PropertyPacker.PropertyMeta;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Optimized mapping system for block translation with lossless property-based
 * serialization.
 * Uses identity-based comparison for maximum performance and ensures
 * deterministic bitstream generation.
 * Thread-safe with read-write locking for concurrent access.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 */
public final class CisMapping<B, S, P> implements CisAdapter<S> {

    private static final Gson GSON = new Gson();

    /**
     * Registry view used to resolve stable block identifiers and enumerate known blocks.
     */
    private final BlockRegistryAdapter<B> registry;
    /**
     * State adapter used to move between concrete block-state values and their owning blocks.
     */
    private final BlockStateAdapter<B, S, P> stateAdapter;
    /**
     * Property serializer for the variable-width property payload attached to each block id.
     */
    private final PropertyPacker<B, S, P> packer;
    /**
     * In-memory append-only block-id table, including unresolved tombstones from disk snapshots.
     */
    private final BlockIdRegistry<B> blockIds = new BlockIdRegistry<>();

    /**
     * Path where the JSON mapping file is stored.
     */
    private final Path mappingFilePath;

    /**
     * Read-write lock to ensure thread-safe operations during concurrent chunk
     * saving.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Lock used specifically for flushing data to disk.
     */
    private final Object flushLock = new Object();

    /**
     * Set when the append-only block mapping has entries that have not been
     * written to disk yet.
     */
    private volatile boolean mappingsDirty;

    /**
     * Creates a new CisMapping and loads existing mappings from file if present.
     *
     * @param mappingFile  the path to the mapping file
     * @param registry     the block registry adapter
     * @param stateAdapter the block state adapter
     * @param packer       the property packer instance
     * @throws IOException if loading fails
     */
    public CisMapping(Path mappingFile, BlockRegistryAdapter<B> registry, BlockStateAdapter<B, S, P> stateAdapter,
            PropertyPacker<B, S, P> packer) throws IOException {
        this.mappingFilePath = mappingFile;
        this.registry = registry;
        this.stateAdapter = stateAdapter;
        this.packer = packer;
        this.mappingsDirty = false;

        if (mappingFile.toFile()
                .exists()) {
            loadMappings();
        }

        populateRegisteredBlocks();
        warmPropertyIndexMaps();
        flush();
    }

    /**
     * Loads existing block mappings from the mapping file.
     */
    private void loadMappings() throws IOException {
        try (FileReader reader = new FileReader(mappingFilePath.toFile())) {
            Map<String, Integer> map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
            }.getType());

            if (map == null) {
                return;
            }

            B air = registry.getAir();
            String airId = registry.getId(air); // "minecraft:air"

            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                String idStr = entry.getKey();
                int blockId = entry.getValue();
                B block = registry.getBlock(idStr);

                // Some adapters return air as the fallback for invalid or removed IDs.
                // Keep those identifiers as tombstones so their numeric IDs remain occupied.
                if (block == air && !idStr.equals(airId)) {
                    blockIds.reserveUnresolved(idStr, blockId);
                    continue;
                }

                // Other adapters may signal a missing block with null instead of air.
                if (block == null) {
                    blockIds.reserveUnresolved(idStr, blockId);
                    continue;
                }

                blockIds.registerResolved(block, blockId);
            }

        }
    }

    /**
     * Populates mappings for every block currently available in the game registry.
     *
     * <p>Existing IDs keep their original position. Removed blocks remain reserved
     * from the loaded mapping, and newly discovered blocks append after the highest
     * persisted ID.
     */
    private void populateRegisteredBlocks() {
        rwLock.writeLock()
                .lock();
        try {
            for (B block : registry.getRegisteredBlocks()) {
                if (blockIds.getId(block) == -1) {
                    blockIds.registerNext(block);
                    mappingsDirty = true;
                }
            }
        } finally {
            rwLock.writeLock()
                    .unlock();
        }
    }

    /**
     * Pre-builds immutable property value/index caches for all currently
     * registered blocks so save-time palette writes do not pay cache-construction
     * cost on first use.
     */
    private void warmPropertyIndexMaps() {
        for (B block : registry.getRegisteredBlocks()) {
            final S defaultState = stateAdapter.getDefaultState(block);
            for (P property : stateAdapter.getProperties(block)) {
                stateAdapter.getPropertyValues(property);
                stateAdapter.getValueIndex(defaultState, property);
            }
        }
    }

    /**
     * Gets the block ID for a given block state, registering it if necessary.
     * Thread-safe with optimistic read-lock strategy.
     *
     * @param state the block state
     * @return the block ID
     */
    public int getBlockId(S state) {
        final B block = stateAdapter.getBlock(state);
        final int cachedId = blockIds.getId(block);
        if (cachedId != -1) {
            return cachedId;
        }

        rwLock.writeLock()
                .lock();
        try {
            final int id = blockIds.getId(block);
            if (id != -1) {
                return id;
            }

            return registerNewBlock(block);
        } finally {
            rwLock.writeLock()
                    .unlock();
        }
    }

    /**
     * Registers a new block ID while recording that the mapping file needs a
     * flush.
     */
    private int registerNewBlock(final B block) {
        final int id = blockIds.registerNext(block);
        mappingsDirty = true;
        return id;
    }

    /**
     * Flushes new mappings to disk if there are unsaved changes.
     * Uses double-checked locking to minimize I/O.
     */
    public void flush() {
        if (!mappingsDirty) {
            return;
        }

        synchronized (flushLock) {
            if (!mappingsDirty) {
                return;
            }

            try {
                Map<String, Integer> snapshot = createMappingSnapshot();
                Path parent = mappingFilePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileWriter writer = new FileWriter(mappingFilePath.toFile())) {
                    GSON.toJson(snapshot, writer);
                }
                mappingsDirty = false;
            } catch (Exception e) {
                System.err.println("Chunkis: Failed to save mappings: " + e.getMessage());
            }
        }
    }

    /**
     * Creates a snapshot of current mappings in a format suitable for JSON
     * serialization.
     *
     * @return a map of block identifier strings to their allocated IDs
     */
    private Map<String, Integer> createMappingSnapshot() {
        rwLock.readLock()
                .lock();
        try {
            return blockIds.snapshot(registry);
        } finally {
            rwLock.readLock()
                    .unlock();
        }
    }

    /**
     * Writes all property values of a BlockState to the BitWriter.
     * Each property uses the minimum bits required for its value range.
     *
     * @param writer the BitWriter to write to
     * @param state  the BlockState to serialize
     */
    public void writeStateProperties(BitWriter writer, S state) {
        B block = stateAdapter.getBlock(state);
        PropertyMeta<P>[] metas = packer.getPropertyMetas(block);
        packer.writeProperties(writer, state, metas);
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader  the BitReader to read from
     * @param blockId the block ID
     * @return the reconstructed BlockState
     * @throws IOException if the block ID is unknown
     */
    public S readStateProperties(BitReader reader, int blockId) throws IOException {
        B block = getBlockInternal(blockId);

        if (block == null) {
            throw new IOException("Unknown Block ID " + blockId + " - stream desync detected");
        }

        PropertyMeta<P>[] metas = packer.getPropertyMetas(block);
        return packer.readProperties(reader, block, metas);
    }

    /**
     * Gets the block instance for a given ID.
     *
     * @param id the block ID
     * @return the block instance, or {@code null} if not mapped
     */
    private B getBlockInternal(int id) {
        return blockIds.getBlock(id);
    }
}
