package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CompressedRegionStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Keeps the chunks of an authoritative world that are not stored as Chunkis entries (still generating, or
 * generated but not yet post-processed) as vanilla serializes them, under Chunkis's own directory
 * ({@link ChunkisStoragePaths#computeUnfinishedChunksDirectory}).
 *
 * <p>Without it such a chunk was dropped on unload and generated from scratch on its next load, while vanilla
 * reads it back and continues; the ring of partly generated chunks around every loaded area paid that again on
 * each reload. Entries are written and read back verbatim, never through Chunkis's restore, and an entry is
 * discarded once Chunkis stores the chunk itself (a Chunkis entry always loads first).</p>
 *
 * <p>Entries are the chunk's uncompressed NBT in CIS region files ({@link CompressedRegionStore}). These chunks
 * are small, about 1 KiB compressed, and vanilla's region format gave each a 4 KiB sector. Worlds written before
 * kept them in vanilla {@code .mca} files in the same directory: those are still read, and an entry read from them
 * moves to the CIS files. Writes run in order on one thread, an entry superseded before its turn skipped, as in
 * vanilla's {@link IOWorker}; reads do not wait behind them.</p>
 */
public final class UnfinishedChunkStore {

    /**
     * Key marking a chunk read back from this store mid-generation; see {@link #markIfGenerating}.
     */
    public static final String GENERATING_MARKER_KEY = "chunkis_unfinished_generation";

    /**
     * Entries in CIS region files.
     */
    private final CompressedRegionStore store;

    /**
     * Vanilla region I/O over {@code .mca} files an earlier version wrote, or {@code null} when there are none.
     */
    private final IOWorker legacy;

    /**
     * Region coordinates ({@link ChunkPos#pack()} of the region) that have a legacy {@code .mca} file.
     */
    private final Set<Long> legacyRegions;

    /**
     * Identifies this store in save failure reports.
     */
    private final RegionStorageInfo storageKey;

    /**
     * World whose chunks this store keeps.
     */
    private final ServerLevel world;

    /**
     * Runs every write in submission order.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Chunkis-UnfinishedChunkIo");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Latest submitted, not yet written entry by position: its NBT, or {@code null} for a removal.
     */
    private final Map<Long, CompletableFuture<CompoundTag>> pending = new ConcurrentHashMap<>();

    /**
     * Positions known to hold an entry: written or read back in this session.
     */
    private final Set<Long> entries = ConcurrentHashMap.newKeySet();

    /**
     * Opens the store of a world.
     *
     * @param world world whose unfinished chunks to keep
     */
    public UnfinishedChunkStore(final ServerLevel world) {
        this.world = world;
        this.storageKey = new RegionStorageInfo(world.getServer().getWorldData().getLevelName(),
                world.dimension(), "chunkis_unfinished");
        final Path directory = ChunkisStoragePaths.computeUnfinishedChunksDirectory(
                ServerWorldPaths.root(world.getServer()), world.dimension());
        try {
            this.store = new CompressedRegionStore(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to open unfinished-chunk storage at " + directory, e);
        }
        this.legacyRegions = legacyRegions(directory);
        // Reads through vanilla's storage create missing region files, so it opens only over existing ones.
        this.legacy = legacyRegions.isEmpty() ? null : new IOWorker(storageKey, directory, false) {
        };
    }

    /**
     * Saves a chunk the way vanilla's own save would: an empty chunk without structures is not worth keeping.
     * Serialization runs on a worker like vanilla's.
     *
     * @param chunk chunk to keep, still generating or not yet post-processed
     * @return {@code true} when an entry is written
     */
    public boolean save(final ChunkAccess chunk) {
        if (chunk.getPersistedStatus() == ChunkStatus.EMPTY
                && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
            return false;
        }
        final ChunkPos pos = chunk.getPos();
        final SerializableChunkData serialized = SerializableChunkData.copyOf(world, chunk);
        entries.add(pos.pack());
        submit(pos, CompletableFuture.supplyAsync(serialized::write, Util.backgroundExecutor()));
        return true;
    }

    /**
     * Reads an unfinished chunk back as vanilla saved it.
     *
     * @param pos chunk position
     * @return the chunk's NBT, if this store holds it
     */
    public CompletableFuture<Optional<CompoundTag>> read(final ChunkPos pos) {
        final CompletableFuture<CompoundTag> unwritten = pending.get(pos.pack());
        if (unwritten != null) {
            return unwritten.thenApply(nbt -> Optional.ofNullable(nbt).map(CompoundTag::copy));
        }
        // Not queued behind writes: an unwritten entry is in pending, and a write leaves pending only once done.
        return CompletableFuture.supplyAsync(() -> readStored(pos), Util.ioPool())
                .thenCompose(stored -> stored.isPresent() || !hasLegacyRegion(pos)
                        ? CompletableFuture.completedFuture(stored)
                        : legacy.loadAsync(pos).thenApply(found -> {
                            found.ifPresent(nbt -> moveFromLegacy(pos, nbt.copy()));
                            return found;
                        }))
                .thenApply(nbt -> {
                    if (nbt.isPresent()) {
                        entries.add(pos.pack());
                    }
                    return nbt;
                });
    }

    /**
     * Marks a chunk read back from this store that is still generating. Its pending ticks then all come from
     * generation (a chunk is not ticked before it is finished), so the chunk counts as freshly generated and its
     * settling does not claim it, exactly as if it had never been unloaded.
     *
     * @param nbt chunk NBT read from this store, upgraded
     * @return {@code nbt}
     */
    public static CompoundTag markIfGenerating(final CompoundTag nbt) {
        if (!"minecraft:full".equals(nbt.getStringOr("Status", ""))) {
            nbt.putBoolean(GENERATING_MARKER_KEY, true);
        }
        return nbt;
    }

    /**
     * Drops a chunk's entry once Chunkis stores the chunk itself.
     *
     * @param pos chunk position
     */
    public void discard(final ChunkPos pos) {
        if (entries.remove(pos.pack())) {
            submit(pos, CompletableFuture.completedFuture(null));
        }
    }

    /**
     * Writes everything pending and closes the region files.
     */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.MINUTES)) {
                Chunkis.LOGGER.error("Chunkis: Unfinished-chunk writes of {} did not finish in 5 minutes; "
                        + "chunks still queued are regenerated on their next load", world.dimension().identifier());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.close();
        if (legacy != null) {
            try {
                legacy.synchronize(true).join();
                legacy.close();
            } catch (final IOException e) {
                Chunkis.LOGGER.error("Chunkis: Failed to close legacy unfinished-chunk storage of {}",
                        world.dimension().identifier(), e);
            }
        }
    }

    /**
     * Queues an entry's write, or its removal for {@code null} NBT, behind every earlier one.
     *
     * @param pos chunk position
     * @param nbt the entry's NBT once serialized
     */
    // IOWorker uses null payloads as deletion tombstones despite its non-null annotation.
    @SuppressWarnings({"DataFlowIssue"})
    private void submit(final ChunkPos pos, final CompletableFuture<CompoundTag> nbt) {
        pending.put(pos.pack(), nbt);
        io.execute(() -> {
            if (pending.get(pos.pack()) != nbt) {
                // A newer entry for this position is queued; only its write matters.
                return;
            }
            try {
                final CompoundTag entry = nbt.join();
                if (entry != null) {
                    store.write(FabricCisStorageHelper.toStoragePos(pos), toBytes(entry));
                } else {
                    store.delete(FabricCisStorageHelper.toStoragePos(pos));
                    if (hasLegacyRegion(pos)) {
                        legacy.store(pos, (CompoundTag) null);
                    }
                }
            } catch (final RuntimeException | IOException failure) {
                world.getServer().reportChunkSaveFailure(failure, storageKey, pos);
            } finally {
                pending.remove(pos.pack(), nbt);
            }
        });
    }

    /**
     * Moves an entry read from a legacy {@code .mca} file into the CIS files. Queued before the read completes,
     * so anything the loaded chunk later submits for this position comes after it.
     *
     * @param pos chunk position
     * @param nbt the entry
     */
    // Deleting the migrated legacy entry requires the vanilla null tombstone.
    @SuppressWarnings({"DataFlowIssue"})
    private void moveFromLegacy(final ChunkPos pos, final CompoundTag nbt) {
        io.execute(() -> {
            if (pending.containsKey(pos.pack())) {
                return;
            }
            try {
                final CisChunkPos storagePos = FabricCisStorageHelper.toStoragePos(pos);
                store.write(storagePos, toBytes(nbt));
                legacy.store(pos, (CompoundTag) null);
            } catch (final IOException failure) {
                // The legacy entry stays and is read again next time.
                Chunkis.LOGGER.warn("Chunkis: Failed to move unfinished chunk {} of {} out of its .mca file", pos,
                        world.dimension().identifier(), failure);
            }
        });
    }

    /**
     * Reads an entry from the CIS files.
     *
     * @param pos chunk position
     * @return the entry, if stored
     */
    private Optional<CompoundTag> readStored(final ChunkPos pos) {
        try {
            final byte[] bytes = store.read(FabricCisStorageHelper.toStoragePos(pos));
            if (bytes == null) {
                return Optional.empty();
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                return Optional.of(NbtIo.read(input, NbtAccounter.unlimitedHeap()));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read unfinished chunk " + pos + " of "
                    + world.dimension().identifier(), e);
        }
    }

    /**
     * Returns whether a legacy {@code .mca} file covers a position.
     *
     * @param pos chunk position
     * @return {@code true} when one does
     */
    private boolean hasLegacyRegion(final ChunkPos pos) {
        return legacyRegions.contains(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ()));
    }

    /**
     * Serializes an entry.
     *
     * @param nbt chunk NBT
     * @return its uncompressed bytes
     * @throws IOException when it cannot be written
     */
    private static byte[] toBytes(final CompoundTag nbt) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(nbt, output);
        }
        return bytes.toByteArray();
    }

    /**
     * Lists the non-empty legacy {@code .mca} region files of a directory.
     *
     * @param directory the store's directory
     * @return their region coordinates, packed like {@link ChunkPos#pack()}
     */
    private static Set<Long> legacyRegions(final Path directory) {
        final Set<Long> regions = ConcurrentHashMap.newKeySet();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.*.mca")) {
            for (final Path file : files) {
                final String[] parts = file.getFileName().toString().split("\\.");
                if (parts.length == 4 && Files.size(file) > 0) {
                    regions.add(ChunkPos.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                }
            }
        } catch (final IOException | NumberFormatException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to list legacy unfinished-chunk files in {}", directory, e);
        }
        return regions;
    }
}
