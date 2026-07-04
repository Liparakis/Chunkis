package io.liparakis.chunkis.storage.io.region;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Compaction copy and validation helpers for {@link RegionFile}.
 *
 * <p>This owns the temporary-file rewrite path used during region compaction.
 * Header state and swap/recovery remain in {@link RegionFile}.</p>
 */
final class RegionCompactionIO {

    private RegionCompactionIO() {
        throw new AssertionError("Utility class");
    }

    /**
     * Rewrites the live region payloads into a compacted temp file beside the
     * active region file.
     *
     * <p>The replacement file gets a fresh contiguous header and an empty free
     * list because all holes have been eliminated by compaction.</p>
     */
    static Path writeCompactedTempFile(final Path regionPath, final FileChannel source, final int[] offsets,
            final int[] lengths, final long liveBytes, final long reuseHits,
            final long reuseMisses) throws IOException {
        final Path tempPath = regionPath.resolveSibling(regionPath.getFileName() + ".tmp");
        final int maxChunkLen = maxLiveChunkLength(offsets, lengths);

        Files.deleteIfExists(tempPath);
        try (FileChannel dest = FileChannel.open(tempPath, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            RegionFile.writeFully(dest, ByteBuffer.allocate(RegionFile.HEADER_SIZE), 0);
            final ByteBuffer newHeader = writeLiveChunks(dest, source, offsets, lengths, maxChunkLen);
            RegionFile.writeFully(dest, newHeader.flip(), 0);
            RegionFile.writeMetadata(dest, RegionFile.HEADER_SIZE + liveBytes, List.of(), reuseHits, reuseMisses);
            dest.force(true);
        }

        return tempPath;
    }

    /**
     * Verifies that a compacted temp file preserves every live payload exactly
     * and keeps all header offsets within the compacted payload span.
     */
    static void validateCompactedFile(final Path tempPath, final FileChannel source, final int[] offsets,
            final int[] lengths, final long liveBytes) throws IOException {
        final int compactedDataEnd = RegionFile.HEADER_SIZE + (int) liveBytes;
        final int maxChunkLen = maxLiveChunkLength(offsets, lengths);

        try (FileChannel compacted = FileChannel.open(tempPath, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(RegionFile.HEADER_SIZE);
            RegionFile.readFully(compacted, header, 0L);
            header.flip();

            final byte[] originalChunk = new byte[maxChunkLen];
            final byte[] compactedChunk = new byte[maxChunkLen];

            for (int i = 0; i < RegionFile.CHUNKS_PER_REGION; i++) {
                final int newOffset = header.getInt();
                final int newLength = header.getInt();

                if (offsets[i] == 0 || lengths[i] == 0) {
                    if (newOffset != 0 || newLength != 0) {
                        throw new IOException("Compacted header unexpectedly populated empty slot " + i);
                    }
                    continue;
                }

                if (newLength != lengths[i] || newOffset < RegionFile.HEADER_SIZE
                        || newOffset + newLength > compactedDataEnd) {
                    throw new IOException("Compacted header mismatch for slot " + i);
                }

                RegionFile.readChunkBytes(source, offsets[i], lengths[i], originalChunk);
                RegionFile.readChunkBytes(compacted, newOffset, newLength, compactedChunk);

                if (!Arrays.equals(originalChunk, 0, newLength, compactedChunk, 0, newLength)) {
                    throw new IOException("Compacted payload mismatch for slot " + i);
                }
            }
        }
    }

    /**
     * Copies all live chunk payloads into the destination file and builds the
     * replacement header in memory.
     */
    private static ByteBuffer writeLiveChunks(final FileChannel dest, final FileChannel source, final int[] offsets,
            final int[] lengths, final int maxChunkLen) throws IOException {
        int currentOffset = RegionFile.HEADER_SIZE;
        final ByteBuffer newHeader = ByteBuffer.allocate(RegionFile.HEADER_SIZE);
        final ByteBuffer chunkData = ByteBuffer.allocate(maxChunkLen);

        for (int i = 0; i < RegionFile.CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > 0) {
                currentOffset = copyLiveChunk(dest, source, newHeader, chunkData, offsets, lengths, i, currentOffset);
            } else {
                writeEmptyHeaderEntry(newHeader);
            }
        }

        return newHeader;
    }

    /**
     * Copies one live chunk payload and appends its updated header entry.
     */
    private static int copyLiveChunk(final FileChannel dest, final FileChannel source, final ByteBuffer newHeader,
            final ByteBuffer chunkData, final int[] offsets, final int[] lengths,
            final int index, final int currentOffset) throws IOException {
        chunkData.clear();
        chunkData.limit(lengths[index]);
        RegionFile.readFully(source, chunkData, offsets[index]);
        chunkData.flip();
        RegionFile.writeFully(dest, chunkData, currentOffset);
        newHeader.putInt(currentOffset);
        newHeader.putInt(lengths[index]);
        return currentOffset + lengths[index];
    }

    /**
     * Appends a zeroed header entry for an empty slot.
     */
    private static void writeEmptyHeaderEntry(final ByteBuffer header) {
        header.putInt(0);
        header.putInt(0);
    }

    /**
     * Returns the largest live payload length currently referenced by the header.
     */
    private static int maxLiveChunkLength(final int[] offsets, final int[] lengths) {
        int maxLength = 0;
        for (int i = 0; i < RegionFile.CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > maxLength) {
                maxLength = lengths[i];
            }
        }
        return maxLength;
    }
}
