package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Region file handler for 32×32 chunks.
 *
 * <p>Layout (byte offsets):
 * <pre>
 *   [0, 8192)        fixed header — 1024 × (offset:int, length:int)
 *   [8192, dataEnd)  chunk payloads, potentially with gaps reused by the allocator
 *   [dataEnd, EOF)   allocation metadata footer (optional; absent in legacy files)
 * </pre>
 *
 * <p>The metadata footer is written after every mutating operation. If a write
 * is interrupted after the footer is truncated but before the new one is written,
 * the next open falls back to {@link #rebuildLegacyFreeBlocks()}.
 *
 * @author Liparakis
 * @version 2.0
 */
final class RegionFile implements AutoCloseable {

    private static final String READ_SOURCE = "RegionFile#read";
    private static final String WRITE_SOURCE = "RegionFile#write";

    private static final int REGION_MASK = 31;
    private static final int CHUNKS_PER_REGION = 1024;

    /**
     * Total header size: 1024 chunk entries × 8 bytes per entry.
     */
    static final int HEADER_SIZE = 8192;

    /**
     * Size of one header entry: 4-byte offset + 4-byte length.
     */
    private static final int HEADER_ENTRY_SIZE = 8;

    /**
     * Footer magic marking the trailing allocation metadata block.
     */
    static final int FOOTER_MAGIC = 0x43495346;

    /**
     * Metadata payload magic written before the footer trailer.
     */
    private static final int METADATA_MAGIC = 0x4349534D;

    /**
     * Current on-disk version of the allocation metadata payload.
     */
    private static final int METADATA_VERSION = 1;

    /**
     * Sentinel indicating that no footer has been located or written yet.
     */
    private static final int NO_FOOTER = -1;

    /**
     * Absolute path to the backing region file on disk.
     */
    private final Path path;

    /**
     * Open channel for all reads, writes, compaction swaps, and header updates.
     */
    private FileChannel channel;

    /**
     * Chunk payload offsets indexed by local region slot. Package-private for tests/compaction.
     */
    final int[] offsets = new int[CHUNKS_PER_REGION];

    /**
     * Chunk payload lengths indexed by local region slot. Package-private for tests/compaction.
     */
    final int[] lengths = new int[CHUNKS_PER_REGION];

    /**
     * In-memory free blocks available for best-fit reuse. Always sorted and non-overlapping.
     */
    private final List<FreeBlock> freeBlocks = new ArrayList<>();

    /**
     * Reusable direct buffer for single-entry header writes.
     */
    private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(HEADER_ENTRY_SIZE);

    /**
     * Whether in-memory state has writes not yet forced to disk.
     */
    private boolean dirty = false;

    /**
     * Number of successful allocations satisfied from the free list.
     */
    private long reuseHits = 0L;

    /**
     * Number of writes that had to append because no reusable hole fit.
     */
    private long reuseMisses = 0L;

    /**
     * Offset where trailing metadata starts, or {@link #NO_FOOTER} when unknown.
     * Always equals the logical end of chunk payload data.
     */
    private int metadataOffset = NO_FOOTER;

    /**
     * Opens or creates a region file.
     *
     * @param dir     the parent directory
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @throws IOException if the file cannot be opened
     */
    RegionFile(Path dir, int regionX, int regionZ) throws IOException {
        this.path = dir.resolve("r." + regionX + '.' + regionZ + ".cis");
        this.channel = openChannel();

        if (channel.size() < HEADER_SIZE) {
            initializeNewRegion();
        } else {
            loadHeader();
        }
    }

    /**
     * Initializes a new region file with a zeroed header.
     */
    private void initializeNewRegion() throws IOException {
        writeFully(channel, ByteBuffer.allocate(HEADER_SIZE), 0);
        metadataOffset = HEADER_SIZE;
    }

    /**
     * Loads the header and allocation metadata from an existing region file.
     */
    private void loadHeader() throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        readFully(channel, header, 0);
        header.flip();

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            offsets[i] = header.getInt();
            lengths[i] = header.getInt();
        }

        loadAllocationMetadata();
    }

    /**
     * Reads chunk data from the region file.
     *
     * @param pos the chunk position
     * @return the raw bytes, or {@code null} if the chunk is not present
     * @throws IOException if a read error occurs
     */
    synchronized byte[] read(CisChunkPos pos) throws IOException {
        return read(pos, null);
    }

    synchronized byte[] read(CisChunkPos pos, String operationId) throws IOException {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_READ_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_READ,
                READ_SOURCE,
                "region file read started",
                null,
                new DebugChunkKey(pos.x(), pos.z()),
                regionKey(),
                operationId,
                null,
                null
        );

        final int index = getChunkIndex(pos);

        if (offsets[index] == 0) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE,
                    ChunkTraceEventType.REGION_READ_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.MISSING_ENTRY,
                    READ_SOURCE,
                    "region file entry missing",
                    null,
                    new DebugChunkKey(pos.x(), pos.z()),
                    regionKey(),
                    operationId,
                    null,
                    null
            );
            return null;
        }

        final int dataEnd = dataEndWithoutMetadata();
        if (offsets[index] < HEADER_SIZE || offsets[index] + lengths[index] > dataEnd) {
            throw new IOException("Chunk " + pos + " points outside live region payload area");
        }

        final ByteBuffer buffer = ByteBuffer.allocate(lengths[index]);
        readFully(channel, buffer, offsets[index]);
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_READ_TX_END,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_READ,
                READ_SOURCE,
                "region file read completed",
                null,
                new DebugChunkKey(pos.x(), pos.z()),
                regionKey(),
                operationId,
                null,
                lengths[index]
        );
        return buffer.array();
    }

    synchronized boolean hasChunk(CisChunkPos pos) {
        final int index = getChunkIndex(pos);
        return offsets[index] != 0 && lengths[index] > 0;
    }

    /**
     * Writes chunk data to the region file, or clears the entry when {@code data} is null.
     *
     * <p>Three allocation strategies in priority order:
     * <ol>
     *   <li><b>In-place</b> — new data fits within the old slot; old tail bytes become a free block.</li>
     *   <li><b>Best-fit reuse</b> — a free hole large enough is found; old slot becomes a free block.</li>
     *   <li><b>Append</b> — no hole fits; data is appended at end of file.</li>
     * </ol>
     *
     * <p>The footer is always truncated before any payload write and rewritten
     * afterward. If the metadata write is interrupted, the next open rebuilds
     * the free list from the header via {@link #rebuildLegacyFreeBlocks()}.
     *
     * @param pos  the chunk position
     * @param data the data to write, or {@code null} to clear the chunk
     * @throws IOException if a write error occurs
     */
    synchronized void write(CisChunkPos pos, byte[] data) throws IOException {
        write(pos, data, null);
    }

    synchronized void write(CisChunkPos pos, byte[] data, String operationId) throws IOException {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                WRITE_SOURCE,
                "region write started",
                null,
                new DebugChunkKey(pos.x(), pos.z()),
                regionKey(),
                operationId,
                null,
                data == null ? 0 : data.length
        );

        final int index = getChunkIndex(pos);
        final int oldOffset = offsets[index];
        final int oldLength = lengths[index];
        final int dataLength = (data == null) ? 0 : data.length;

        // Remove footer so the payload area has a clean logical end.
        truncateFooter();

        if (dataLength == 0) {
            if (oldLength > 0) {
                updateHeader(index, 0, 0);
                addFreeBlock(oldOffset, oldLength);
            }
            writeMetadata();
            dirty = true;
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE,
                    ChunkTraceEventType.REGION_WRITE_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.STORAGE_WRITE,
                    WRITE_SOURCE,
                    "region clear completed",
                    null,
                    new DebugChunkKey(pos.x(), pos.z()),
                    regionKey(),
                    operationId,
                    null,
                    0
            );
            return;
        }

        if (oldLength > 0 && dataLength <= oldLength) {
            // In-place: new data fits inside the existing slot.
            writeFully(channel, ByteBuffer.wrap(data), oldOffset);
            updateHeader(index, oldOffset, dataLength);
            if (oldLength > dataLength) {
                addFreeBlock(oldOffset + dataLength, oldLength - dataLength);
            }
            writeMetadata();
            dirty = true;
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE,
                    ChunkTraceEventType.REGION_WRITE_TX_END,
                    ChunkTraceSeverity.INFO,
                    ChunkTraceReason.STORAGE_WRITE,
                    WRITE_SOURCE,
                    "region write completed",
                    null,
                    new DebugChunkKey(pos.x(), pos.z()),
                    regionKey(),
                    operationId,
                    null,
                    dataLength
            );
            return;
        }

        // Best-fit or append.
        final Allocation allocation = allocate(dataLength);
        writeFully(channel, ByteBuffer.wrap(data), allocation.offset());
        updateHeader(index, allocation.offset(), dataLength);
        if (oldLength > 0) {
            addFreeBlock(oldOffset, oldLength);
        }
        writeMetadata();
        dirty = true;
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE,
                ChunkTraceEventType.REGION_WRITE_TX_END,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                WRITE_SOURCE,
                "region write completed",
                null,
                new DebugChunkKey(pos.x(), pos.z()),
                regionKey(),
                operationId,
                null,
                dataLength
        );
    }

    /**
     * Forces pending writes to disk. Silently no-ops if already clean.
     */
    void flush() {
        if (!dirty) {
            return;
        }
        try {
            if (channel.isOpen()) {
                channel.force(false);
            }
            dirty = false;
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Failed to flush region file {}", path, e);
        }
    }

    /**
     * Flushes then closes the backing channel.
     */
    @Override
    public void close() {
        flush();
        try {
            channel.close();
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Failed to close region file {}", path, e);
        }
    }

    /**
     * Compacts the region file by rewriting live chunks contiguously.
     *
     * <p>{@code compact} and {@code compactWithReport} are both {@code synchronized}.
     * Java's intrinsic locks are reentrant, so the inner call from here is safe.
     */
    synchronized void compact() {
        try {
            flush();
            compactWithReport();
        } catch (final IOException e) {
            Chunkis.LOGGER.error("Failed to compact region {}", path, e);
            recoverChannel();
        }
    }

    /**
     * Compacts the region and returns before/after byte statistics.
     *
     * @throws IOException if compaction or validation fails
     */
    synchronized RegionCompactReport compactWithReport() throws IOException {
        flush();

        final long physicalBytesBefore = channel.size();
        final int maxChunkLen = maxLiveChunkLength();
        final long liveBytes = sumLiveBytes();
        final Path tempPath = writeCompactedTempFile(maxChunkLen, liveBytes);

        try {
            validateCompactedFile(tempPath, maxChunkLen, liveBytes);
            final long physicalBytesAfter = Files.size(tempPath);
            swapCompactedFile(tempPath);
            dirty = false;
            return new RegionCompactReport(path, physicalBytesBefore, physicalBytesAfter, liveBytes);
        } catch (final IOException e) {
            Files.deleteIfExists(tempPath);
            recoverChannel();
            throw e;
        }
    }

    /**
     * Writes a compacted replacement file beside the current region and returns its path.
     *
     * <p>{@code maxChunkLen} and {@code liveBytes} are passed in to avoid
     * recomputing them inside the compaction loop.</p>
     */
    private Path writeCompactedTempFile(final int maxChunkLen, final long liveBytes) throws IOException {
        final Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.deleteIfExists(tempPath);

        try (FileChannel dest = FileChannel.open(
                tempPath, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
        )) {

            writeFully(dest, ByteBuffer.allocate(HEADER_SIZE), 0);
            final ByteBuffer newHeader = writeLiveChunks(dest, maxChunkLen);
            writeFully(dest, newHeader.flip(), 0);
            writeMetadata(dest, HEADER_SIZE + liveBytes, List.of(), reuseHits, reuseMisses);
            dest.force(true);
        }

        return tempPath;
    }

    /**
     * Copies all live chunks into the compacted file and builds the replacement
     * header in memory.
     *
     * @param maxChunkLen scratch buffer size — must be &ge; the largest live payload
     */
    private ByteBuffer writeLiveChunks(final FileChannel dest, final int maxChunkLen) throws IOException {
        int currentOffset = HEADER_SIZE;
        final ByteBuffer newHeader = ByteBuffer.allocate(HEADER_SIZE);
        // One scratch buffer reused for every chunk copy to avoid per-chunk allocation.
        final ByteBuffer chunkData = ByteBuffer.allocate(maxChunkLen);

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > 0) {
                currentOffset = copyLiveChunk(dest, newHeader, chunkData, i, currentOffset);
            } else {
                writeEmptyHeaderEntry(newHeader);
            }
        }

        return newHeader;
    }

    /**
     * Copies one live chunk payload into the compacted file and appends its
     * updated header entry.
     */
    private int copyLiveChunk(
            final FileChannel dest,
            final ByteBuffer newHeader,
            final ByteBuffer chunkData,
            final int index,
            final int currentOffset) throws IOException {
        chunkData.clear();
        chunkData.limit(lengths[index]);
        readFully(channel, chunkData, offsets[index]);
        chunkData.flip();
        writeFully(dest, chunkData, currentOffset);

        newHeader.putInt(currentOffset);
        newHeader.putInt(lengths[index]);
        return currentOffset + lengths[index];
    }

    /**
     * Appends an empty (zeroed) chunk entry to a header buffer being built in memory.
     */
    private static void writeEmptyHeaderEntry(final ByteBuffer header) {
        header.putInt(0);
        header.putInt(0);
    }

    /**
     * Replaces the live region file with the compacted temp file atomically (or
     * via a non-atomic fallback on file-systems that do not support atomic rename)
     * then reloads the in-memory header state.
     */
    private void swapCompactedFile(final Path tempPath) throws IOException {
        channel.close();
        try {
            Files.move(
                    tempPath, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            );
        } catch (final IOException atomicFailure) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
        channel = openChannel();
        loadHeader();
    }

    /**
     * Attempts to reopen the backing channel after a failed compaction.
     *
     * <p>Only acts when the channel is closed — if the compaction failure
     * happened before {@link #swapCompactedFile} called {@code channel.close()},
     * the original channel is still valid and no recovery is needed.</p>
     */
    private void recoverChannel() {
        if (channel.isOpen()) {
            return;
        }
        try {
            channel = openChannel();
            if (channel.size() >= HEADER_SIZE) {
                loadHeader();
            }
        } catch (final IOException ex) {
            Chunkis.LOGGER.error("CRITICAL: Failed to reopen region after failed compaction: {}", path, ex);
        }
    }

    /**
     * Loads the persisted allocation metadata footer, or reconstructs free blocks
     * from the header for legacy files that predate the footer format.
     */
    private void loadAllocationMetadata() throws IOException {
        freeBlocks.clear();
        reuseHits = 0L;
        reuseMisses = 0L;

        final int footerStart = discoverFooterStart();
        if (footerStart == NO_FOOTER) {
            metadataOffset = (int) channel.size();
            rebuildLegacyFreeBlocks();
            return;
        }

        metadataOffset = footerStart;
        if (!readFooterMetadata(footerStart)) {
            // Footer was present but invalid; start with an empty free list.
            freeBlocks.clear();
            reuseHits = 0L;
            reuseMisses = 0L;
        }
    }

    /**
     * Locates the trailing metadata footer and returns its payload start offset.
     *
     * @return footer payload offset, or {@link #NO_FOOTER} when absent or malformed
     */
    private int discoverFooterStart() throws IOException {
        final long size = channel.size();
        if (size < HEADER_SIZE + (Integer.BYTES * 2L)) {
            return NO_FOOTER;
        }

        final ByteBuffer footer = ByteBuffer.allocate(Integer.BYTES * 2);
        readFully(channel, footer, size - (Integer.BYTES * 2L));
        footer.flip();

        final int payloadLength = footer.getInt();
        final int footerMagic = footer.getInt();
        if (footerMagic != FOOTER_MAGIC) {
            return NO_FOOTER;
        }

        final long footerStart = size - (Integer.BYTES * 2L) - payloadLength;
        if (payloadLength < 0 || footerStart < HEADER_SIZE) {
            return NO_FOOTER;
        }

        return (int) footerStart;
    }

    /**
     * Parses and validates the persisted free-list footer payload.
     *
     * @param footerStart start offset of the metadata payload
     * @return {@code true} when the footer is well-formed and accepted
     */
    private boolean readFooterMetadata(final int footerStart) throws IOException {
        final long size = channel.size();
        final int payloadLength = (int) (size - footerStart - (Integer.BYTES * 2L));
        final ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        readFully(channel, payload, footerStart);
        payload.flip();

        if (payload.remaining() < Integer.BYTES * 3 + Long.BYTES * 2) {
            return false;
        }
        if (payload.getInt() != METADATA_MAGIC || payload.getInt() != METADATA_VERSION) {
            return false;
        }

        final int freeCount = payload.getInt();
        if (freeCount < 0 || payload.remaining() != Long.BYTES * 2 + (freeCount * HEADER_ENTRY_SIZE)) {
            return false;
        }

        final long parsedHits = payload.getLong();
        final long parsedMisses = payload.getLong();
        final List<FreeBlock> parsed = new ArrayList<>(freeCount);
        for (int i = 0; i < freeCount; i++) {
            parsed.add(new FreeBlock(payload.getInt(), payload.getInt()));
        }

        if (!validateFreeBlocks(parsed)) {
            return false;
        }

        freeBlocks.clear();
        freeBlocks.addAll(mergeAdjacent(parsed));
        reuseHits = parsedHits;
        reuseMisses = parsedMisses;
        return true;
    }

    /**
     * Reconstructs reusable gaps for legacy region files that predate the footer
     * format, by scanning the header for live spans and recording the spaces
     * between them.
     */
    private void rebuildLegacyFreeBlocks() {
        final List<FreeBlock> liveBlocks = new ArrayList<>();
        final int dataEnd = dataEndWithoutMetadata();

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] > 0 && lengths[i] > 0
                    && offsets[i] >= HEADER_SIZE
                    && offsets[i] + lengths[i] <= dataEnd) {
                liveBlocks.add(new FreeBlock(offsets[i], lengths[i]));
            }
        }

        liveBlocks.sort(Comparator.comparingInt(FreeBlock::offset));

        final List<FreeBlock> gaps = new ArrayList<>();
        int cursor = HEADER_SIZE;
        for (final FreeBlock live : liveBlocks) {
            if (live.offset() > cursor) {
                gaps.add(new FreeBlock(cursor, live.offset() - cursor));
            }
            cursor = Math.max(cursor, live.offset() + live.length());
        }
        if (cursor < dataEnd) {
            gaps.add(new FreeBlock(cursor, dataEnd - cursor));
        }

        freeBlocks.clear();
        freeBlocks.addAll(mergeAdjacent(gaps));
    }

    /**
     * Validates that parsed free blocks are ordered, non-overlapping, within the
     * payload area, and disjoint from all live chunk spans.
     */
    private boolean validateFreeBlocks(final List<FreeBlock> blocks) {
        final List<FreeBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(FreeBlock::offset));
        int previousEnd = HEADER_SIZE;

        for (final FreeBlock block : sorted) {
            if (block.length() <= 0 || block.offset() < HEADER_SIZE) {
                return false;
            }
            final int end = block.offset() + block.length();
            if (end > metadataOffset || block.offset() < previousEnd) {
                return false;
            }
            if (overlapsLiveBlock(block.offset(), end)) {
                return false;
            }
            previousEnd = end;
        }

        return true;
    }

    /**
     * Returns {@code true} if the candidate range {@code [freeOffset, freeEnd)}
     * overlaps any currently indexed live payload.
     */
    private boolean overlapsLiveBlock(final int freeOffset, final int freeEnd) {
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
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
     * Removes the trailing metadata block so the next write operates against the
     * true payload end.
     */
    private void truncateFooter() throws IOException {
        final int dataEnd = dataEndWithoutMetadata();
        if (channel.size() > dataEnd) {
            channel.truncate(dataEnd);
        }
        metadataOffset = dataEnd;
    }

    /**
     * Chooses an allocation target for a new payload using best-fit reuse before
     * falling back to append.
     *
     * <p>The free list is always kept sorted by offset (via {@link #addFreeBlock}
     * → {@link #mergeAdjacent}), so a linear scan for best-fit is sufficient for
     * typical free-list sizes (&lt;1024 entries).</p>
     */
    private Allocation allocate(final int dataLength) throws IOException {
        int bestIndex = -1;
        int bestLength = Integer.MAX_VALUE;

        for (int i = 0; i < freeBlocks.size(); i++) {
            final FreeBlock block = freeBlocks.get(i);
            if (block.length() >= dataLength && block.length() < bestLength) {
                bestIndex = i;
                bestLength = block.length();
            }
        }

        if (bestIndex == -1) {
            reuseMisses++;
            return new Allocation((int) channel.size(), false);
        }

        final FreeBlock block = freeBlocks.remove(bestIndex);
        if (block.length() > dataLength) {
            // Re-insert the remainder; mergeAdjacent in addFreeBlock keeps the list normalized.
            addFreeBlock(block.offset() + dataLength, block.length() - dataLength);
        }
        reuseHits++;
        return new Allocation(block.offset(), true);
    }

    /**
     * Adds a reusable hole and normalizes the in-memory free list.
     *
     * <p>{@link #writeMetadata} trusts that this method already merged adjacent
     * blocks, so it does not call {@link #mergeAdjacent} again.</p>
     */
    private void addFreeBlock(final int offset, final int length) {
        if (length <= 0 || offset < HEADER_SIZE) {
            return;
        }

        freeBlocks.add(new FreeBlock(offset, length));
        final List<FreeBlock> merged = mergeAdjacent(freeBlocks);
        freeBlocks.clear();
        freeBlocks.addAll(merged);
    }

    /**
     * Rewrites the normalized allocation metadata footer at the current end of
     * the file. The free list is already merged by {@link #addFreeBlock}; no
     * second merge pass is needed here.
     */
    private void writeMetadata() throws IOException {
        metadataOffset = (int) channel.size();
        writeMetadata(channel, metadataOffset, freeBlocks, reuseHits, reuseMisses);
    }

    /**
     * Writes the allocation metadata payload plus trailing footer marker to
     * {@code target} at {@code metadataStart}.
     */
    private static void writeMetadata(
            final FileChannel target,
            final long metadataStart,
            final List<FreeBlock> freeBlocks,
            final long reuseHits,
            final long reuseMisses
    ) throws IOException {
        final int payloadLength = (Integer.BYTES * 3) + (Long.BYTES * 2) + (freeBlocks.size() * HEADER_ENTRY_SIZE);
        final ByteBuffer buffer = ByteBuffer.allocate(payloadLength + (Integer.BYTES * 2));
        buffer.putInt(METADATA_MAGIC);
        buffer.putInt(METADATA_VERSION);
        buffer.putInt(freeBlocks.size());
        buffer.putLong(reuseHits);
        buffer.putLong(reuseMisses);
        for (final FreeBlock block : freeBlocks) {
            buffer.putInt(block.offset());
            buffer.putInt(block.length());
        }
        buffer.putInt(payloadLength);
        buffer.putInt(FOOTER_MAGIC);
        buffer.flip();
        writeFully(target, buffer, metadataStart);
    }

    /**
     * Verifies that the compacted temp file preserves every live entry exactly
     * and that all offsets fall within the expected compacted payload span.
     *
     * @param maxChunkLen scratch buffer size — must be &ge; the largest live payload
     * @param liveBytes   expected total payload bytes after compaction
     */
    private void validateCompactedFile(
            final Path tempPath,
            final int maxChunkLen,
            final long liveBytes
    ) throws IOException {
        final int compactedDataEnd = HEADER_SIZE + (int) liveBytes;

        try (FileChannel compacted = FileChannel.open(tempPath, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            readFully(compacted, header, 0L);
            header.flip();

            // Two scratch arrays reused across all slots to avoid per-chunk allocation.
            final byte[] originalChunk = new byte[maxChunkLen];
            final byte[] compactedChunk = new byte[maxChunkLen];

            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                final int newOffset = header.getInt();
                final int newLength = header.getInt();

                if (offsets[i] == 0 || lengths[i] == 0) {
                    if (newOffset != 0 || newLength != 0) {
                        throw new IOException("Compacted header unexpectedly populated empty slot " + i);
                    }
                    continue;
                }

                if (newLength != lengths[i] || newOffset < HEADER_SIZE || newOffset + newLength > compactedDataEnd) {
                    throw new IOException("Compacted header mismatch for slot " + i);
                }

                readChunkBytes(channel, offsets[i], lengths[i], originalChunk);
                readChunkBytes(compacted, newOffset, newLength, compactedChunk);

                if (!Arrays.equals(originalChunk, 0, newLength, compactedChunk, 0, newLength)) {
                    throw new IOException("Compacted payload mismatch for slot " + i);
                }
            }
        }
    }

    /**
     * Captures space accounting for diagnostics and operator reports.
     */
    synchronized RegionSpaceStats spaceStats() {
        long reusableBytes = 0L;
        int largestFreeBlock = 0;
        for (final FreeBlock block : freeBlocks) {
            reusableBytes += block.length();
            largestFreeBlock = Math.max(largestFreeBlock, block.length());
        }

        final long physicalBytes = safeChannelSize();
        final int metadataBytes = Math.max(0, (int) physicalBytes - dataEndWithoutMetadata());
        return new RegionSpaceStats(
                path,
                physicalBytes,
                sumLiveBytes(),
                reusableBytes,
                metadataBytes,
                freeBlocks.size(),
                largestFreeBlock,
                reuseHits,
                reuseMisses
        );
    }

    /**
     * Updates the header entry for a chunk in memory and on disk.
     *
     * <p>When {@code length == 0} the offset is also zeroed so that an absent
     * chunk is reliably detected by {@code offsets[i] == 0}.</p>
     */
    private void updateHeader(final int index, final int offset, final int length) throws IOException {
        offsets[index] = (length == 0) ? 0 : offset;
        lengths[index] = length;

        headerBuffer.clear();
        headerBuffer.putInt(offsets[index]);
        headerBuffer.putInt(lengths[index]);
        headerBuffer.flip();

        writeFully(channel, headerBuffer, (long) index * HEADER_ENTRY_SIZE);
    }

    /**
     * Returns the local slot index for a chunk position within this region
     * (0–1023, row-major in Z).
     */
    private static int getChunkIndex(final CisChunkPos pos) {
        return (pos.x() & REGION_MASK) + (pos.z() & REGION_MASK) * 32;
    }

    /**
     * Returns the logical end of chunk payload bytes, excluding trailing metadata.
     */
    private int dataEndWithoutMetadata() {
        return metadataOffset == NO_FOOTER ? (int) safeChannelSize() : metadataOffset;
    }

    /**
     * Returns the current file size, falling back to {@link #HEADER_SIZE} when
     * the channel cannot be queried (e.g. during error recovery).
     */
    private long safeChannelSize() {
        try {
            return channel.size();
        } catch (final IOException e) {
            return HEADER_SIZE;
        }
    }

    /**
     * Returns the largest currently referenced chunk payload size in the region.
     */
    private int maxLiveChunkLength() {
        int maxLength = 0;
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > maxLength) {
                maxLength = lengths[i];
            }
        }
        return maxLength;
    }

    /**
     * Sums the payload bytes referenced by the current header.
     */
    private long sumLiveBytes() {
        long liveBytes = 0L;
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > 0) {
                liveBytes += lengths[i];
            }
        }
        return liveBytes;
    }

    /**
     * Opens the backing region file channel with read/write/create semantics.
     */
    private FileChannel openChannel() throws IOException {
        return FileChannel.open(
                path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
        );
    }

    private DebugRegionKey regionKey() {
        final String fileName = path.getFileName().toString();
        final String[] parts = fileName.substring(2, fileName.length() - 4).split("\\.");
        return new DebugRegionKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /**
     * Reads exactly {@code target.remaining()} bytes or throws on unexpected EOF.
     */
    private static void readFully(final FileChannel source, final ByteBuffer target, final long position)
            throws IOException {
        long currentPosition = position;
        while (target.hasRemaining()) {
            final int bytesRead = source.read(target, currentPosition);
            if (bytesRead < 0) {
                throw new IOException("Unexpected EOF while reading region file");
            }
            currentPosition += bytesRead;
        }
    }

    /**
     * Writes all bytes in {@code source}, retrying until none remain.
     */
    private static void writeFully(final FileChannel target, final ByteBuffer source, final long position)
            throws IOException {
        long currentPosition = position;
        while (source.hasRemaining()) {
            final int bytesWritten = target.write(source, currentPosition);
            if (bytesWritten <= 0) {
                throw new IOException("Failed to make progress while writing region file");
            }
            currentPosition += bytesWritten;
        }
    }

    /**
     * Reads one chunk payload into a caller-provided scratch buffer.
     */
    private static void readChunkBytes(
            final FileChannel source,
            final int offset,
            final int length,
            final byte[] target
    ) throws IOException {
        readFully(source, ByteBuffer.wrap(target, 0, length), offset);
    }

    /**
     * Coalesces overlapping or touching free blocks into a minimal sorted list.
     * Zero-length entries are discarded.
     */
    private static List<FreeBlock> mergeAdjacent(final List<FreeBlock> blocks) {
        final List<FreeBlock> sorted = new ArrayList<>();
        for (final FreeBlock block : blocks) {
            if (block.length() > 0) {
                sorted.add(block);
            }
        }
        sorted.sort(Comparator.comparingInt(FreeBlock::offset));
        if (sorted.isEmpty()) {
            return List.of();
        }

        final List<FreeBlock> merged = new ArrayList<>();
        FreeBlock current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            final FreeBlock next = sorted.get(i);
            final int currentEnd = current.offset() + current.length();
            if (next.offset() <= currentEnd) {
                current = new FreeBlock(
                        current.offset(),
                        Math.max(currentEnd, next.offset() + next.length()) - current.offset()
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * Exposes the backing path for tests and storage-level compaction plumbing.
     */
    Path path() {
        return path;
    }

    /**
     * Immutable compaction result for one region file.
     */
    record RegionCompactReport(
            Path path,
            long physicalBytesBefore,
            long physicalBytesAfter,
            long liveBytes
    ) {}

    /**
     * Immutable space-usage snapshot for one region file.
     */
    record RegionSpaceStats(
            Path path,
            long physicalBytes,
            long liveBytes,
            long reusableBytes,
            int metadataBytes,
            int freeBlockCount,
            int largestFreeBlock,
            long reuseHits,
            long reuseMisses
    ) {}

    /**
     * One reusable byte range inside a region payload area.
     */
    private record FreeBlock(int offset, int length) {}

    /**
     * Allocation decision returned by best-fit lookup.
     */
    private record Allocation(int offset, boolean reused) {}
}
