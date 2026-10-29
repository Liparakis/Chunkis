package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.capture.CisSnapshotCapture;
import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * Keeps the blocks of recently unloaded chunks, as the section containers their unload save wrote, so reloading
 * one skips decoding its blocks: when the stored entry's block part is exactly the one those containers were
 * written as (same hash), the load fills the delta's blocks from the containers instead
 * ({@link CisStorage.BlockPartSource}). Players revisit areas, and decoding the blocks is most of a load.
 *
 * <p>Bounded by an estimate of the containers' memory ({@code -Dchunkis.recentChunkCacheMb}, default 256),
 * least recently used first out. An entry stays until the chunk's next unload replaces it, the budget evicts it, or
 * the stored entry no longer matches it; the containers are the save's own copies, never touched by the world
 * again, so any number of reads may share them.</p>
 */
public final class RecentChunkBlocks implements CisStorage.BlockPartSource<BlockState, NbtCompound> {

    /**
     * Memory budget in bytes.
     */
    private static final long BUDGET = Long.getLong("chunkis.recentChunkCacheMb", 256L) * 1024 * 1024;

    /**
     * Cached chunks by packed position, least recently stored first.
     */
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

    /**
     * Estimated bytes held.
     */
    private long bytes;

    /**
     * One chunk's blocks as written.
     *
     * @param blockPartHash hash of the block part they were written as
     * @param sections      section containers by section index, {@code null} for sections of only air
     * @param bottomY       world bottom Y
     * @param bytes         estimated memory
     */
    private record Entry(long[] blockPartHash, long[] expectedContent, PalettedContainer<BlockState>[] sections,
            int bottomY, long bytes, long[] content) {
    }

    /**
     * Blocks a load filled a delta with, as the containers and content fingerprint they came from; attached to the
     * delta ({@link ChunkDelta#attachLoadedBlocks}) so the load hands vanilla copies of the containers instead of
     * rebuilding them.
     *
     * @param sections containers by section index, {@code null} for sections of only air; never handed out as is
     * @param content  their content fingerprint
     */
    public record LoadedSections(PalettedContainer<BlockState>[] sections, long[] content) {
    }

    /**
     * Remembers the blocks an unload save wrote.
     *
     * @param pos           chunk position
     * @param blockPartHash hash of the block part written
     * @param content       content fingerprint of those blocks
     * @param sections      the save's section copies
     * @param bottomY       world bottom Y
     */
    public synchronized void put(final CisChunkPos pos, final long[] blockPartHash, final long[] content,
            final PalettedContainer<BlockState>[] sections, final int bottomY) {
        store(pos, new Entry(blockPartHash, null, sections, bottomY, estimate(sections), content));
    }

    /**
     * Remembers the blocks of a chunk unloading without a save, copied from the live chunk; they are used only
     * once found to hold exactly the content the stored entry was written from.
     *
     * @param pos             chunk position
     * @param blockPartHash   hash of the block part the stored entry was written with
     * @param expectedContent content fingerprint of the blocks that entry was written from
     * @param sections        copies of the chunk's sections
     * @param bottomY         world bottom Y
     */
    public synchronized void putToVerify(final CisChunkPos pos, final long[] blockPartHash,
            final long[] expectedContent, final PalettedContainer<BlockState>[] sections, final int bottomY) {
        store(pos, new Entry(blockPartHash, expectedContent, sections, bottomY, estimate(sections), expectedContent));
    }

    /**
     * Stores an entry within the budget.
     *
     * @param pos   chunk position
     * @param entry the entry
     */
    private void store(final CisChunkPos pos, final Entry entry) {
        if (BUDGET <= 0) {
            return;
        }
        final Entry previous = entries.put(key(pos), entry);
        bytes += entry.bytes() - (previous == null ? 0 : previous.bytes());
        final var oldest = entries.entrySet().iterator();
        while (bytes > BUDGET && oldest.hasNext()) {
            bytes -= oldest.next().getValue().bytes();
            oldest.remove();
        }
    }

    /**
     * Drops everything, when the world's storage closes.
     */
    public synchronized void clear() {
        entries.clear();
        bytes = 0;
    }

    @Override
    public Consumer<ChunkDelta<BlockState, NbtCompound>> blocksFor(final CisChunkPos pos,
            final long[] blockPartHash) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(key(pos));
        }
        if (entry == null) {
            return null;
        }
        // Checked outside the lock so loads of other chunks do not wait; a copy is verified once.
        boolean valid = Arrays.equals(entry.blockPartHash(), blockPartHash);
        if (valid && entry.expectedContent() != null) {
            valid = Arrays.equals(entry.expectedContent(), DeferredBlocksSnapshotView.contentPrintOf(entry.sections()));
        }
        synchronized (this) {
            if (entries.get(key(pos)) == entry) {
                if (!valid) {
                    // The stored entry changed since, or the copies are not what it was written from.
                    entries.remove(key(pos));
                    bytes -= entry.bytes();
                } else if (entry.expectedContent() != null) {
                    entries.put(key(pos), new Entry(entry.blockPartHash(), null, entry.sections(), entry.bottomY(),
                            entry.bytes(), entry.content()));
                }
            }
        }
        if (!valid) {
            return null;
        }
        final Entry verified = entry;
        return delta -> {
            final IdentityHashMap<BlockState, Integer> paletteIds = new IdentityHashMap<>();
            final PalettedContainer<BlockState>[] sections = verified.sections();
            for (int section = 0; section < sections.length; section++) {
                if (sections[section] != null) {
                    CisSnapshotCapture.appendSectionBlocks(sections[section], delta, verified.bottomY(), section,
                            paletteIds);
                }
            }
            delta.attachLoadedBlocks(new LoadedSections(sections, verified.content()));
        };
    }

    /**
     * Packs a position as a map key.
     *
     * @param pos chunk position
     * @return key
     */
    private static long key(final CisChunkPos pos) {
        return ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
    }

    /**
     * Estimates the memory a chunk's containers hold.
     *
     * @param sections section containers
     * @return bytes
     */
    private static long estimate(final PalettedContainer<BlockState>[] sections) {
        long total = 64;
        for (final PalettedContainer<BlockState> container : sections) {
            if (container != null) {
                final Object data = PalettedContainerFields.data(container);
                total += 96 + PalettedContainerFields.storage(data).getData().length * 8L
                        + PalettedContainerFields.palette(data).getSize() * 16L;
            }
        }
        return total;
    }

    /**
     * Cache per world, by dimension.
     */
    private static final Map<Object, RecentChunkBlocks> BY_WORLD = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns a world's cache.
     *
     * @param worldKey world registry key
     * @return the cache
     */
    public static RecentChunkBlocks of(final Object worldKey) {
        return BY_WORLD.computeIfAbsent(worldKey, ignored -> new RecentChunkBlocks());
    }
}
