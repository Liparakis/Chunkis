package io.liparakis.chunkis.storage.mapping;

import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maintains the append-only block ID table used by {@link CisMapping}.
 *
 * <p>Resolved blocks are available for encode/decode. Unresolved identifiers
 * represent removed mods or renamed blocks loaded from an existing
 * {@code global_ids.json}; they are intentionally preserved in snapshots so
 * their numeric IDs are never reused for new blocks.
 */
final class BlockIdRegistry<B> {

    /**
     * Sentinel returned by fastutil maps when a block has not been assigned an id.
     */
    private static final int MISSING_BLOCK_ID = -1;
    /**
     * Highest representable persisted block id in the current on-disk format.
     */
    private static final int MAX_BLOCK_ID = 0xFFFF;

    /**
     * Identity-based mapping from currently loaded block instances to persisted numeric ids.
     */
    private final Reference2IntMap<B> blockToId = new Reference2IntOpenHashMap<>();
    /**
     * Reverse lookup from persisted numeric ids to currently loaded block instances.
     */
    private final Int2ObjectMap<B> idToBlock = new Int2ObjectOpenHashMap<>();
    /**
     * Tombstones for unresolved block identifiers whose numeric ids must remain occupied.
     */
    private final Map<String, Integer> unresolvedIds = new HashMap<>();
    /**
     * Lock-free decode lookup table published after each resolved-id mutation.
     * Reads dominate writes, so decode paths read from this volatile array instead
     * of taking a lock around {@link #idToBlock}.
     */
    private volatile Object[] blocksById = new Object[0];
    /**
     * Lock-free encode lookup table published after each resolved-id mutation.
     * Uses identity semantics to match block-instance lookups in the hot encode
     * path.
     */
    private volatile Map<B, Integer> idsByBlock = new IdentityHashMap<>();
    /**
     * Next append-only id that will be assigned to a newly discovered block.
     */
    private int nextId = 0;

    /** Performs block id registry. */
    BlockIdRegistry() {
        blockToId.defaultReturnValue(MISSING_BLOCK_ID);
    }

    /**
     * Returns a stable insertion-ordered view sorted by numeric id for deterministic JSON output.
     */
    private static Map<String, Integer> sortByNumericId(Map<String, Integer> snapshot) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        snapshot.entrySet()
                .stream()
                .sorted(Comparator.comparingInt(Map.Entry::getValue))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    /**
     * Validates that an on-disk id fits within the current persisted block-id range.
     */
    private static void validateId(int id) {
        if (id < 0 || id > MAX_BLOCK_ID) {
            throw new IllegalArgumentException("Chunkis block ID out of range: " + id);
        }
    }

    /** Performs get id. */
    int getId(B block) {
        final Integer id = idsByBlock.get(block);
        return id != null ? id : MISSING_BLOCK_ID;
    }

    /** Performs get block. */
    B getBlock(int id) {
        final Object[] snapshot = blocksById;
        if (id < 0 || id >= snapshot.length) {
            return null;
        }

        @SuppressWarnings("unchecked") final B block = (B) snapshot[id];
        return block;
    }

    /**
     * Registers a currently available block at a specific persisted ID.
     */
    void registerResolved(B block, int id) {
        validateId(id);
        blockToId.put(block, id);
        idToBlock.put(id, block);
        publishBlockLookup(id, block);
        advanceNextId(id);
    }

    /**
     * Reserves an ID for a block identifier that no longer resolves.
     */
    void reserveUnresolved(String blockIdentifier, int id) {
        validateId(id);
        unresolvedIds.put(blockIdentifier, id);
        advanceNextId(id);
    }

    /**
     * Appends a new block after every persisted resolved or unresolved ID.
     */
    int registerNext(B block) {
        int id = nextId;
        registerResolved(block, id);
        return id;
    }

    /**
     * Builds the on-disk JSON view, including unresolved tombstones.
     *
     * <p>The returned map is ordered by numeric ID so {@code global_ids.json}
     * reflects the append-only table directly and remains reviewable in diffs.
     */
    Map<String, Integer> snapshot(BlockRegistryAdapter<B> registry) {
        Map<String, Integer> snapshot = new HashMap<>(unresolvedIds);
        for (Int2ObjectMap.Entry<B> entry : idToBlock.int2ObjectEntrySet()) {
            snapshot.put(registry.getId(entry.getValue()), entry.getIntKey());
        }
        return sortByNumericId(snapshot);
    }

    /**
     * Advances {@link #nextId} past an existing assigned id, preserving append-only allocation.
     */
    private void advanceNextId(int assignedId) {
        if (assignedId >= nextId) {
            nextId = assignedId + 1;
        }
        if (nextId > MAX_BLOCK_ID + 1) {
            throw new IllegalStateException("Chunkis block ID space exhausted");
        }
    }

    /**
     * Publishes an updated id-to-block array for lock-free decode lookups.
     */
    private void publishBlockLookup(final int id, final B block) {
        final Object[] current = blocksById;
        final Object[] updated = id < current.length
                ? Arrays.copyOf(current, current.length)
                : Arrays.copyOf(current, id + 1);
        updated[id] = block;
        blocksById = updated;

        final Map<B, Integer> currentIds = idsByBlock;
        final IdentityHashMap<B, Integer> updatedIds = new IdentityHashMap<>(currentIds.size() + 1);
        updatedIds.putAll(currentIds);
        updatedIds.put(block, id);
        idsByBlock = updatedIds;
    }
}
