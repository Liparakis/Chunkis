package io.liparakis.chunkis.storage.io.region;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory allocation metadata for one region file.
 *
 * <p>This owns the normalized free-list and reuse counters. File I/O and footer
 * framing remain in {@link RegionFile}; this class only manages allocation state.</p>
 */
final class RegionAllocationMetadata {

    /**
     * Normalized reusable holes inside the region payload area.
     */
    private final List<RegionFreeBlock> freeBlocks = new ArrayList<>();

    /**
     * Number of allocations satisfied from an existing free block.
     */
    private long reuseHits;

    /**
     * Number of allocations that had to append at EOF.
     */
    private long reuseMisses;

    /**
     * Clears all in-memory allocation state.
     */
    void reset() {
        freeBlocks.clear();
        reuseHits = 0L;
        reuseMisses = 0L;
    }

    /**
     * Loads allocation state from a parsed footer payload.
     *
     * <p>The payload must already exclude the trailing footer trailer and be
     * positioned at its beginning. Returns {@code false} when the payload shape,
     * counts, or free-block ranges are inconsistent with the live region header.</p>
     */
    boolean loadFromFooter(final ByteBuffer payload, final int metadataOffset, final int[] offsets,
                           final int[] lengths, final int headerEntrySize, final int metadataMagic,
                           final int metadataVersion) {
        if (payload.remaining() < Integer.BYTES * 3 + Long.BYTES * 2) {
            return false;
        }
        if (payload.getInt() != metadataMagic || payload.getInt() != metadataVersion) {
            return false;
        }

        final int freeCount = payload.getInt();
        if (freeCount < 0 || payload.remaining() != Long.BYTES * 2 + (freeCount * headerEntrySize)) {
            return false;
        }

        final long parsedHits = payload.getLong();
        final long parsedMisses = payload.getLong();
        final List<RegionFreeBlock> parsed = new ArrayList<>(freeCount);
        for (int i = 0; i < freeCount; i++) {
            parsed.add(new RegionFreeBlock(payload.getInt(), payload.getInt()));
        }

        if (!validateFreeBlocks(parsed, metadataOffset, offsets, lengths)) {
            return false;
        }

        freeBlocks.clear();
        freeBlocks.addAll(mergeAdjacent(parsed));
        reuseHits = parsedHits;
        reuseMisses = parsedMisses;
        return true;
    }

    /**
     * Rebuilds the free-list for legacy region files that do not yet carry
     * persisted footer metadata.
     */
    void rebuildLegacy(final int[] offsets, final int[] lengths, final int dataEnd) {
        final List<RegionFreeBlock> liveBlocks = new ArrayList<>();

        for (int i = 0; i < RegionFile.CHUNKS_PER_REGION; i++) {
            if (offsets[i] > 0 && lengths[i] > 0 && offsets[i] >= RegionFile.HEADER_SIZE && offsets[i] + lengths[i] <= dataEnd) {
                liveBlocks.add(new RegionFreeBlock(offsets[i], lengths[i]));
            }
        }

        liveBlocks.sort(Comparator.comparingInt(RegionFreeBlock::offset));

        final List<RegionFreeBlock> gaps = new ArrayList<>();
        int cursor = RegionFile.HEADER_SIZE;
        for (final RegionFreeBlock live : liveBlocks) {
            if (live.offset() > cursor) {
                gaps.add(new RegionFreeBlock(cursor, live.offset() - cursor));
            }
            cursor = Math.max(cursor, live.offset() + live.length());
        }
        if (cursor < dataEnd) {
            gaps.add(new RegionFreeBlock(cursor, dataEnd - cursor));
        }

        freeBlocks.clear();
        freeBlocks.addAll(mergeAdjacent(gaps));
    }

    /**
     * Chooses a best-fit reusable block or falls back to append-at-end.
     *
     * @param dataLength   payload length being allocated
     * @param appendOffset current append position when no free block fits
     * @return allocation decision including chosen offset and reuse attribution
     */
    RegionFile.Allocation allocate(final int dataLength, final int appendOffset) {
        int bestIndex = -1;
        int bestLength = Integer.MAX_VALUE;

        for (int i = 0; i < freeBlocks.size(); i++) {
            final RegionFreeBlock block = freeBlocks.get(i);
            if (block.length() >= dataLength && block.length() < bestLength) {
                bestIndex = i;
                bestLength = block.length();
            }
        }

        if (bestIndex == -1) {
            reuseMisses++;
            return new RegionFile.Allocation(appendOffset, false);
        }

        final RegionFreeBlock block = freeBlocks.remove(bestIndex);
        if (block.length() > dataLength) {
            addFreeBlock(block.offset() + dataLength, block.length() - dataLength);
        }
        reuseHits++;
        return new RegionFile.Allocation(block.offset(), true);
    }

    /**
     * Adds a reusable range and re-normalizes the free-list.
     */
    void addFreeBlock(final int offset, final int length) {
        if (length <= 0 || offset < RegionFile.HEADER_SIZE) {
            return;
        }

        freeBlocks.add(new RegionFreeBlock(offset, length));
        final List<RegionFreeBlock> merged = mergeAdjacent(freeBlocks);
        freeBlocks.clear();
        freeBlocks.addAll(merged);
    }

    /**
     * Sums the bytes currently tracked as reusable free space.
     */
    long reusableBytes() {
        long reusableBytes = 0L;
        for (final RegionFreeBlock block : freeBlocks) {
            reusableBytes += block.length();
        }
        return reusableBytes;
    }

    /**
     * Returns the largest reusable hole currently known.
     */
    int largestFreeBlock() {
        int largest = 0;
        for (final RegionFreeBlock block : freeBlocks) {
            largest = Math.max(largest, block.length());
        }
        return largest;
    }

    /**
     * Returns the number of normalized reusable blocks currently tracked.
     */
    int freeBlockCount() {
        return freeBlocks.size();
    }

    /**
     * Returns the cumulative free-list reuse hit counter.
     */
    long reuseHits() {
        return reuseHits;
    }

    /**
     * Returns the cumulative append-at-end miss counter.
     */
    long reuseMisses() {
        return reuseMisses;
    }

    /**
     * Exposes the normalized free-list for footer serialization.
     */
    List<RegionFreeBlock> freeBlocks() {
        return freeBlocks;
    }

    /**
     * Validates that parsed free blocks are ordered, non-overlapping, within the
     * live payload span, and disjoint from every live chunk entry.
     */
    private static boolean validateFreeBlocks(final List<RegionFreeBlock> blocks, final int metadataOffset,
                                              final int[] offsets, final int[] lengths) {
        final List<RegionFreeBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(RegionFreeBlock::offset));
        int previousEnd = RegionFile.HEADER_SIZE;

        for (final RegionFreeBlock block : sorted) {
            if (block.length() <= 0 || block.offset() < RegionFile.HEADER_SIZE) {
                return false;
            }
            final int end = block.offset() + block.length();
            if (end > metadataOffset || block.offset() < previousEnd) {
                return false;
            }
            if (overlapsLiveBlock(block.offset(), end, offsets, lengths)) {
                return false;
            }
            previousEnd = end;
        }

        return true;
    }

    /**
     * Returns whether a candidate reusable range intersects a live chunk span.
     */
    private static boolean overlapsLiveBlock(final int freeOffset, final int freeEnd, final int[] offsets,
                                             final int[] lengths) {
        for (int i = 0; i < RegionFile.CHUNKS_PER_REGION; i++) {
            if (offsets[i] == 0 || lengths[i] == 0) {
                continue;
            }
            final int liveEnd = offsets[i] + lengths[i];
            if (freeOffset < liveEnd && offsets[i] < freeEnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * Coalesces touching or overlapping free blocks into one sorted minimal list.
     */
    private static List<RegionFreeBlock> mergeAdjacent(final List<RegionFreeBlock> blocks) {
        final List<RegionFreeBlock> sorted = new ArrayList<>();
        for (final RegionFreeBlock block : blocks) {
            if (block.length() > 0) {
                sorted.add(block);
            }
        }
        sorted.sort(Comparator.comparingInt(RegionFreeBlock::offset));
        if (sorted.isEmpty()) {
            return List.of();
        }

        final List<RegionFreeBlock> merged = new ArrayList<>();
        RegionFreeBlock current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            final RegionFreeBlock next = sorted.get(i);
            final int currentEnd = current.offset() + current.length();
            if (next.offset() <= currentEnd) {
                current = new RegionFreeBlock(current.offset(),
                        Math.max(currentEnd, next.offset() + next.length()) - current.offset());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
