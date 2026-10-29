package io.liparakis.chunkis.storage.mapping;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.liparakis.chunkis.core.bits.BitReader;
import io.liparakis.chunkis.core.bits.BitWriter;
import io.liparakis.chunkis.core.mapping.CisAdapter;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.core.mapping.PropertyPacker.PropertyMeta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

    /**
     * Stores gson.
     */
    private static final Gson GSON = new Gson();

    /**
     * Suffix of the temp file written before the atomic replace.
     */
    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * Suffix of the previous complete mapping kept for recovery.
     */
    private static final String BACKUP_SUFFIX = ".bak";

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

        final Map<String, Integer> persisted = readPersistedMappings();
        blockIds.beginBatch();
        if (persisted != null) {
            loadMappings(persisted);
        }

        populateRegisteredBlocks();
        blockIds.endBatch();
        warmPropertyIndexMaps();
        flush();
    }

    /**
     * Reads the persisted id table, falling back to the {@code .bak} copy kept by
     * {@link #flush()}.
     *
     * <p>Returns {@code null} only for a brand-new mapping (neither file exists).
     * An existing but empty or unparseable table is never treated as "no mapping",
     * since re-numbering would silently change every stored block.</p>
     *
     * @throws IOException if a mapping file exists but no readable copy remains
     */
    private Map<String, Integer> readPersistedMappings() throws IOException {
        final Path backup = siblingPath(BACKUP_SUFFIX);
        if (!Files.exists(mappingFilePath) && !Files.exists(backup)) {
            return null;
        }
        IOException primaryFailure = null;
        if (Files.exists(mappingFilePath)) {
            try {
                return parseMappingFile(mappingFilePath);
            } catch (final IOException e) {
                primaryFailure = e;
            }
        }
        try {
            final Map<String, Integer> recovered = parseMappingFile(backup);
            if (primaryFailure != null) {
                System.err.println("Chunkis: Recovered block id mapping from " + backup + " after: "
                        + primaryFailure.getMessage());
            }
            mappingsDirty = true;
            return recovered;
        } catch (final IOException backupFailure) {
            final IOException failure = new IOException("Chunkis: Block id mapping " + mappingFilePath
                    + " is unreadable and no usable backup exists; refusing to re-number blocks", backupFailure);
            if (primaryFailure != null) {
                failure.addSuppressed(primaryFailure);
            }
            throw failure;
        }
    }

    /**
     * Parses one mapping file, rejecting missing, empty or malformed content.
     */
    private static Map<String, Integer> parseMappingFile(final Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Mapping file " + file + " does not exist");
        }
        final Map<String, Integer> map;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
            }.getType());
        } catch (final JsonParseException e) {
            throw new IOException("Mapping file " + file + " is malformed", e);
        }
        if (map == null) {
            throw new IOException("Mapping file " + file + " is empty");
        }
        return map;
    }

    /**
     * Loads existing block mappings from a parsed mapping table.
     */
    private void loadMappings(final Map<String, Integer> map) {
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
     *
     * <p>The file is replaced atomically: the snapshot is written and forced to a
     * temp file, the previous file is kept as {@code .bak}, and the temp file is
     * moved over the target. A crash at any point leaves either the old or the new
     * complete table on disk, never a truncated one.</p>
     *
     * <p>The dirty flag is cleared before the snapshot so a block registered
     * concurrently re-marks it, and it is restored when the write fails.</p>
     *
     * @throws IOException if the mapping cannot be persisted; callers must not
     *                     write chunk payloads that reference unpersisted ids
     */
    public void flush() throws IOException {
        if (!mappingsDirty) {
            return;
        }

        synchronized (flushLock) {
            if (!mappingsDirty) {
                return;
            }
            mappingsDirty = false;
            try {
                writeAtomically(GSON.toJson(createMappingSnapshot()));
            } catch (IOException | RuntimeException e) {
                mappingsDirty = true;
                throw new IOException("Chunkis: Failed to persist block id mapping " + mappingFilePath, e);
            }
        }
    }

    /**
     * Replaces the mapping file with {@code json} via temp file, fsync and atomic move.
     */
    private void writeAtomically(final String json) throws IOException {
        final Path parent = mappingFilePath.toAbsolutePath()
                .getParent();
        Files.createDirectories(parent);
        final Path temp = siblingPath(TEMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            final ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        if (Files.exists(mappingFilePath)) {
            Files.copy(mappingFilePath, siblingPath(BACKUP_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, mappingFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temp, mappingFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the mapping file path with {@code suffix} appended to its name.
     */
    private Path siblingPath(final String suffix) {
        return mappingFilePath.resolveSibling(mappingFilePath.getFileName() + suffix);
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
