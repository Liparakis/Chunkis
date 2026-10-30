package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.core.compression.CompressionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opaque per-chunk payloads in CIS region files ({@code r.X.Z.cis}), Zstd-compressed like CIS entries.
 *
 * <p>Region files place payloads byte-exact rather than in 4 KiB sectors, which matters for small payloads: a
 * chunk of 1 KiB takes 1 KiB, not a whole sector. Compression runs outside any lock, and every read and write
 * holds only its region file's own lock, as in {@link CisStorage}: reads never queue behind writes to other
 * regions, nor behind disk syncs.</p>
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
        final StoredPayload stored = regionFiles.inRegion(pos, false, null,
                region -> new StoredPayload(region.path(), region.read(pos)));
        if (stored == null || stored.bytes() == null) {
            return null;
        }
        try {
            return compression.decompress(stored.bytes());
        } catch (final IOException e) {
            throw new IOException("Failed to decompress payload of chunk " + pos + " in " + stored.file(), e);
        }
    }

    /**
     * A stored payload and the file it came from, for errors.
     *
     * @param file  region file
     * @param bytes compressed payload, or {@code null} when the chunk has none
     */
    private record StoredPayload(Path file, byte[] bytes) {
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
        regionFiles.inRegion(pos, true, null, region -> {
            region.write(pos, compressed);
            region.releaseFreesIfMany();
            return null;
        });
    }

    /**
     * Removes a chunk's payload, if stored; a missing region file is not created.
     *
     * @param pos chunk position
     * @throws IOException when the region cannot be written
     */
    public void delete(final CisChunkPos pos) throws IOException {
        regionFiles.inRegion(pos, false, null, region -> {
            if (region.hasChunk(pos)) {
                region.write(pos, null);
                region.releaseFreesIfMany();
            }
            return null;
        });
    }

    /**
     * Flushes and closes every open region file, compacting those mostly empty.
     */
    @Override
    public synchronized void close() {
        regionFiles.closeAll();
    }
}
