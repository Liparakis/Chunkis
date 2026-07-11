package io.liparakis.chunkis.storage.io.region;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Region file handler for 32-32 chunks.
 *
 * <p>Layout (byte offsets):
 * <pre>
 *   [0, 8192)        fixed header 1024 - (offset:int, length:int)
 *   [8192, dataEnd)  chunk payloads, potentially with gaps reused by the allocator
 *   [dataEnd, EOF)   allocation metadata footer (optional; absent in legacy files)
 * </pre>
 *
 * <p>The metadata footer is written after every mutating operation. If a write
 * is interrupted after the footer is truncated but before the new one is written,
 * the next open falls back to legacy free-list reconstruction from the header.
 */
public final class RegionFile implements AutoCloseable {

    /**
     * Fixed number of chunk slots tracked by one region header.
     */
    static final int CHUNKS_PER_REGION = 1024;
    /**
     * Total header size: 1024 chunk entries - 8 bytes per entry.
     */
    static final int HEADER_SIZE = 8192;
    /**
     * Footer magic marking the trailing allocation metadata block.
     */
    static final int FOOTER_MAGIC = 0x43495346;
    /**
     * Trace source label used for read-side region events.
     */
    private static final String READ_SOURCE = "RegionFile#read";
    /**
     * Trace source label used for write-side region events.
     */
    private static final String WRITE_SOURCE = "RegionFile#write";
    /**
     * Bitmask used to fold world chunk coordinates into their 0-31 local slot.
     */
    private static final int REGION_MASK = 31;
    /**
     * Size of one header entry: 4-byte offset + 4-byte length.
     */
    private static final int HEADER_ENTRY_SIZE = 8;
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
     * Chunk payload offsets indexed by local region slot. Package-private for tests/compaction.
     */
    final int[] offsets = new int[CHUNKS_PER_REGION];
    /**
     * Chunk payload lengths indexed by local region slot. Package-private for tests/compaction.
     */
    final int[] lengths = new int[CHUNKS_PER_REGION];
    /**
     * Absolute path to the backing region file on disk.
     */
    private final Path path;
    /**
     * In-memory free blocks available for best-fit reuse. Always sorted and non-overlapping.
     */
    private final RegionAllocationMetadata allocationMetadata = new RegionAllocationMetadata();
    /**
     * Reusable direct buffer for single-entry header writes.
     */
    private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(HEADER_ENTRY_SIZE);
    /**
     * Open channel for all reads, writes, compaction swaps, and header updates.
     */
    private FileChannel channel;
    /**
     * Whether in-memory state has writes not yet forced to disk.
     */
    private boolean dirty = false;

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
    public RegionFile(Path dir, int regionX, int regionZ) throws IOException {
        this.path = dir.resolve("r." + regionX + '.' + regionZ + ".cis");
        this.channel = openChannel();

        if (channel.size() < HEADER_SIZE) {
            initializeNewRegion();
        } else {
            loadHeader();
        }
    }

    /**
     * Writes the allocation metadata payload plus trailing footer marker to
     * {@code target} at {@code metadataStart}.
     */
    static void writeMetadata(final FileChannel target, final long metadataStart,
            final List<RegionFreeBlock> freeBlocks, final long reuseHits, final long reuseMisses)
            throws IOException {
        final int payloadLength = (Integer.BYTES * 3) + (Long.BYTES * 2) + (freeBlocks.size() * HEADER_ENTRY_SIZE);
        final ByteBuffer buffer = ByteBuffer.allocate(payloadLength + (Integer.BYTES * 2));
        buffer.putInt(METADATA_MAGIC);
        buffer.putInt(METADATA_VERSION);
        buffer.putInt(freeBlocks.size());
        buffer.putLong(reuseHits);
        buffer.putLong(reuseMisses);
        for (final RegionFreeBlock block : freeBlocks) {
            buffer.putInt(block.offset());
            buffer.putInt(block.length());
        }
        buffer.putInt(payloadLength);
        buffer.putInt(FOOTER_MAGIC);
        buffer.flip();
        writeFully(target, buffer, metadataStart);
    }

    /**
     * Returns the local slot index for a chunk position within this region
     * (0â€“1023, row-major in Z).
     */
    private static int getChunkIndex(final CisChunkPos pos) {
        return (pos.x() & REGION_MASK) + (pos.z() & REGION_MASK) * 32;
    }

    /**
     * Performs chunk key.
     */
    private static DebugChunkKey chunkKey(final CisChunkPos pos) {
        return new DebugChunkKey(pos.x(), pos.z());
    }

    /**
     * Reads exactly {@code target.remaining()} bytes or throws on unexpected EOF.
     */
    static void readFully(final FileChannel source, final ByteBuffer target, final long position) throws IOException {
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
    static void writeFully(final FileChannel target, final ByteBuffer source, final long position) throws IOException {
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
    static void readChunkBytes(final FileChannel source, final int offset, final int length, final byte[] target)
            throws IOException {
        readFully(source, ByteBuffer.wrap(target, 0, length), offset);
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
    public synchronized byte[] read(CisChunkPos pos) throws IOException {
        return read(pos, null);
    }

    /**
     * Performs read.
     */
    public synchronized byte[] read(CisChunkPos pos, String operationId) throws IOException {
        final DebugChunkKey chunkKey = chunkKey(pos);
        ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_READ_TX_START,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_READ, READ_SOURCE, "region file read started", null
                , chunkKey, regionKey(), operationId, null, null);

        final int index = getChunkIndex(pos);

        if (offsets[index] == 0) {
            ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_READ_TX_END,
                    ChunkTraceSeverity.INFO, ChunkTraceReason.MISSING_ENTRY, READ_SOURCE, "region file entry missing"
                    , null, chunkKey, regionKey(), operationId, null, null);
            return null;
        }

        final int dataEnd = dataEndWithoutMetadata();
        if (offsets[index] < HEADER_SIZE || offsets[index] + lengths[index] > dataEnd) {
            throw new IOException("Chunk " + pos + " points outside live region payload area");
        }

        final ByteBuffer buffer = ByteBuffer.allocate(lengths[index]);
        readFully(channel, buffer, offsets[index]);
        ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_READ_TX_END,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_READ, READ_SOURCE, "region file read completed",
                null, chunkKey, regionKey(), operationId, null, lengths[index]);
        return buffer.array();
    }

    /**
     * Performs has chunk.
     */
    public synchronized boolean hasChunk(CisChunkPos pos) {
        final int index = getChunkIndex(pos);
        return offsets[index] != 0 && lengths[index] > 0;
    }

    /**
     * Writes chunk data to the region file, or clears the entry when {@code data} is null.
     *
     * <p>Three allocation strategies in priority order:
     * <ol>
     *   <li><b>In-place</b> new data fits within the old slot; old tail bytes become a free block.</li>
     *   <li><b>Best-fit reuse</b> a free hole large enough is found; old slot becomes a free block.</li>
     *   <li><b>Append</b> no hole fits; data is appended at end of file.</li>
     * </ol>
     *
     * <p>The footer is always truncated before any payload write and rewritten
     * afterward. If the metadata write is interrupted, the next open rebuilds
     * the free list from the header using the legacy reconstruction path.
     *
     * @param pos  the chunk position
     * @param data the data to write, or {@code null} to clear the chunk
     * @throws IOException if a write error occurs
     */
    public synchronized void write(CisChunkPos pos, byte[] data) throws IOException {
        write(pos, data, null);
    }

    /**
     * Performs write.
     */
    public synchronized void write(CisChunkPos pos, byte[] data, String operationId) throws IOException {
        final DebugChunkKey chunkKey = chunkKey(pos);
        ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "region write started", null,
                chunkKey, regionKey(), operationId, null, data == null ? 0 : data.length);

        final int index = getChunkIndex(pos);
        final int oldOffset = offsets[index];
        final int oldLength = lengths[index];
        final int dataLength = (data == null) ? 0 : data.length;

        // Remove footer so the payload area has a clean logical end.
        truncateFooter();

        if (dataLength == 0) {
            if (oldLength > 0) {
                updateHeader(index, 0, 0);
                allocationMetadata.addFreeBlock(oldOffset, oldLength);
            }
            writeMetadata();
            dirty = true;
            ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_WRITE_TX_END,
                    ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "region clear completed",
                    null, chunkKey, regionKey(), operationId, null, 0);
            return;
        }

        if (oldLength > 0 && dataLength <= oldLength) {
            // In-place: new data fits inside the existing slot.
            writeFully(channel, ByteBuffer.wrap(data), oldOffset);
            updateHeader(index, oldOffset, dataLength);
            if (oldLength > dataLength) {
                allocationMetadata.addFreeBlock(oldOffset + dataLength, oldLength - dataLength);
            }
            writeMetadata();
            dirty = true;
            ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_WRITE_TX_END,
                    ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "region write completed",
                    null, chunkKey, regionKey(), operationId, null, dataLength);
            return;
        }

        // Best-fit or append.
        final Allocation allocation = allocate(dataLength);
        writeFully(channel, ByteBuffer.wrap(data), allocation.offset());
        updateHeader(index, allocation.offset(), dataLength);
        if (oldLength > 0) {
            allocationMetadata.addFreeBlock(oldOffset, oldLength);
        }
        writeMetadata();
        dirty = true;
        ChunkTraceStore.trace(ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.REGION_WRITE_TX_END,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "region write completed", null
                , chunkKey, regionKey(), operationId, null, dataLength);
    }

    /**
     * Forces pending writes to disk. Silently no-ops if already clean.
     */
    public void flush() {
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
    public synchronized void compact() {
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
    public synchronized RegionCompactReport compactWithReport() throws IOException {
        flush();

        final long physicalBytesBefore = channel.size();
        final long liveBytes = sumLiveBytes();
        final Path tempPath = writeCompactedTempFile(liveBytes);

        try {
            validateCompactedFile(tempPath, liveBytes);
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
    private Path writeCompactedTempFile(final long liveBytes) throws IOException {
        return RegionCompactionIO.writeCompactedTempFile(path, channel, offsets, lengths, liveBytes,
                allocationMetadata.reuseHits(), allocationMetadata.reuseMisses());
    }

    /**
     * Replaces the live region file with the compacted temp file atomically (or
     * via a non-atomic fallback on file-systems that do not support atomic rename)
     * then reloads the in-memory header state.
     */
    private void swapCompactedFile(final Path tempPath) throws IOException {
        channel = RegionCompactionRecovery.swapCompactedFile(channel, tempPath, path);
        loadHeader();
    }

    /**
     * Attempts to reopen the backing channel after a failed compaction.
     *
     * <p>Only acts when the channel is closed if the compaction failure
     * happened before {@link #swapCompactedFile} called {@code channel.close()},
     * the original channel is still valid and no recovery is needed.</p>
     */
    private void recoverChannel() {
        channel = RegionCompactionRecovery.recoverChannel(channel, path);
        try {
            if (channel.isOpen() && channel.size() >= HEADER_SIZE) {
                loadHeader();
            }
        } catch (final IOException ex) {
            Chunkis.LOGGER.error("CRITICAL: Failed to reload region header after failed compaction: {}", path, ex);
        }
    }

    /**
     * Loads the persisted allocation metadata footer, or reconstructs free blocks
     * from the header for legacy files that predate the footer format.
     */
    private void loadAllocationMetadata() throws IOException {
        allocationMetadata.reset();

        final int footerStart = discoverFooterStart();
        if (footerStart == NO_FOOTER) {
            metadataOffset = (int) channel.size();
            allocationMetadata.rebuildLegacy(offsets, lengths, dataEndWithoutMetadata());
            return;
        }

        metadataOffset = footerStart;
        if (!readFooterMetadata(footerStart)) {
            // Footer was present but invalid; start with an empty free list.
            allocationMetadata.reset();
        }
    }

    /**
     * Locates the trailing metadata footer and returns its payload start offset.
     *
     * @return footer payload offset, or {@link #NO_FOOTER} when absent or malformed
     */
    private int discoverFooterStart() throws IOException {
        return RegionFooterIO.discoverFooterStart(channel, HEADER_SIZE, FOOTER_MAGIC, NO_FOOTER);
    }

    /**
     * Parses and validates the persisted free-list footer payload.
     *
     * @param footerStart start offset of the metadata payload
     * @return {@code true} when the footer is well-formed and accepted
     */
    private boolean readFooterMetadata(final int footerStart) throws IOException {
        final ByteBuffer payload = RegionFooterIO.readFooterPayload(channel, footerStart);
        return allocationMetadata.loadFromFooter(payload, metadataOffset, offsets, lengths, HEADER_ENTRY_SIZE,
                METADATA_MAGIC, METADATA_VERSION);
    }

    /**
     * Removes the trailing metadata block so the next write operates against the
     * true payload end.
     */
    private void truncateFooter() throws IOException {
        final int dataEnd = dataEndWithoutMetadata();
        RegionFooterIO.truncateFooter(channel, dataEnd);
        metadataOffset = dataEnd;
    }

    /**
     * Chooses an allocation target for a new payload using best-fit reuse before
     * falling back to append.
     *
     * <p>The free list is always kept sorted by offset by
     * {@link RegionAllocationMetadata#addFreeBlock(int, int)}, so a linear
     * scan for best-fit is sufficient for typical free-list sizes
     * (&lt;1024 entries).</p>
     */
    private Allocation allocate(final int dataLength) throws IOException {
        return allocationMetadata.allocate(dataLength, (int) channel.size());
    }

    /**
     * Rewrites the normalized allocation metadata footer at the current end of
     * the file. The free list is already normalized by
     * {@link RegionAllocationMetadata#addFreeBlock(int, int)}, so no second
     * merge pass is needed here.
     */
    private void writeMetadata() throws IOException {
        metadataOffset = (int) channel.size();
        writeMetadata(channel, metadataOffset, allocationMetadata.freeBlocks(), allocationMetadata.reuseHits(),
                allocationMetadata.reuseMisses());
    }

    /**
     * Verifies that the compacted temp file preserves every live entry exactly
     * and that all offsets fall within the expected compacted payload span.
     *
     * @param liveBytes expected total payload bytes after compaction
     */
    private void validateCompactedFile(final Path tempPath, final long liveBytes) throws IOException {
        RegionCompactionIO.validateCompactedFile(tempPath, channel, offsets, lengths, liveBytes);
    }

    /**
     * Captures space accounting for diagnostics and operator reports.
     */
    public synchronized RegionSpaceStats spaceStats() {
        final long physicalBytes = safeChannelSize();
        final int metadataBytes = Math.max(0, (int) physicalBytes - dataEndWithoutMetadata());
        return new RegionSpaceStats(path, physicalBytes, sumLiveBytes(), allocationMetadata.reusableBytes(),
                metadataBytes, allocationMetadata.freeBlockCount(), allocationMetadata.largestFreeBlock(),
                allocationMetadata.reuseHits(), allocationMetadata.reuseMisses());
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
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    }

    /**
     * Performs region key.
     */
    private DebugRegionKey regionKey() {
        final String fileName = path.getFileName()
                .toString();
        final String[] parts = fileName.substring(2, fileName.length() - 4)
                .split("\\.");
        return new DebugRegionKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /**
     * Exposes the backing path for tests and storage-level compaction plumbing.
     */
    public Path path() {
        return path;
    }

    /**
     * Immutable compaction result for one region file.
     */
    public record RegionCompactReport(Path path, long physicalBytesBefore, long physicalBytesAfter, long liveBytes) {

    }

    /**
     * Immutable space-usage snapshot for one region file.
     */
    public record RegionSpaceStats(Path path, long physicalBytes, long liveBytes, long reusableBytes, int metadataBytes,
                                   int freeBlockCount, int largestFreeBlock, long reuseHits, long reuseMisses) {

    }

    /**
     * Allocation decision returned by best-fit lookup.
     */
    record Allocation(int offset, boolean reused) {

    }
}
