package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.compression.CompressionContext;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opaque per-chunk payloads in CIS region files ({@code r.X.Z.cis}), Zstd-compressed like CIS entries.
 *
 * <p>Region files place payloads byte-exact rather than in 4 KiB sectors, which matters for small payloads: a
 * chunk of 1 KiB takes 1 KiB, not a whole sector. Compression runs outside any lock; writes take the store's
 * lock and reads only their region's, as in {@link CisStorage}, so reads of other chunks never queue behind a
 * write or each other's decompression.</p>
 */
public final class CompressedRegionStore implements AutoCloseable {

    /**
     * Open region files of the directory.
     */
    private final RegionFileCache regionFiles;

    /**
     * Shared compression entry point.
     */
    private final CompressionContext compression = new CompressionContext();

    /**
     * Opens a store over a directory; region files are created on first write.
     *
     * @param directory directory holding the region files
     * @throws IOException when the directory cannot be created
     */
    public CompressedRegionStore(final Path directory) throws IOException {
        Files.createDirectories(directory);
        this.regionFiles = new RegionFileCache(directory);
    }

    /**
     * Reads a chunk's payload.
     *
     * @param pos chunk position
     * @return the payload, or {@code null} when none is stored
     * @throws IOException when the region cannot be read or the payload does not decompress
     */
    public byte[] read(final CisChunkPos pos) throws IOException {
        RegionFile region;
        // Opening a region may evict another; only under the lock, so it never closes a region mid-write.
        synchronized (this) {
            region = regionFiles.get(pos, false);
        }
        byte[] stored;
        try {
            stored = region == null ? null : region.read(pos);
        } catch (final ClosedChannelException evicted) {
            // The cache closed the region meanwhile; read it again where no write can evict it.
            synchronized (this) {
                region = regionFiles.get(pos, false);
                stored = region == null ? null : region.read(pos);
            }
        }
        if (stored == null) {
            return null;
        }
        try {
            return compression.decompress(stored);
        } catch (final IOException e) {
            throw new IOException("Failed to decompress payload of chunk " + pos + " in " + region.path(), e);
        }
    }

    /**
     * Stores a chunk's payload, replacing any earlier one.
     *
     * @param pos     chunk position
     * @param payload the payload
     * @throws IOException when the region cannot be written
     */
    public void write(final CisChunkPos pos, final byte[] payload) throws IOException {
        final byte[] compressed = compression.compress(payload);
        synchronized (this) {
            regionFiles.get(pos, true).write(pos, compressed);
        }
    }

    /**
     * Removes a chunk's payload, if stored; a missing region file is not created.
     *
     * @param pos chunk position
     * @throws IOException when the region cannot be written
     */
    public synchronized void delete(final CisChunkPos pos) throws IOException {
        final RegionFile region = regionFiles.get(pos, false);
        if (region != null && region.hasChunk(pos)) {
            region.write(pos, null);
        }
    }

    /**
     * Flushes and closes every open region file, compacting those mostly empty.
     */
    @Override
    public synchronized void close() {
        regionFiles.closeAll();
    }
}
