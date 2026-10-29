package io.liparakis.chunkis.world.entity.capture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Remembers which entity payloads each chunk's CIS entry holds on disk, so a vanilla entity save
 * for an unloaded chunk can skip reading and decoding the whole entry when nothing changed.
 *
 * <p>Vanilla stops ticking a chunk's entities, unloads the chunk (the save hook captures those
 * entities), and only later unloads the entity section and writes its entity list. That list is
 * normally identical to what the chunk save just wrote.</p>
 *
 * <p>A fingerprint is recorded only when an async save completes, and is dropped whenever another
 * write for the chunk is queued or made, when the chunk loads, and when the server stops. It is
 * the sorted list of per-entity 64-bit hashes (NBT {@code hashCode} plus the hash of its SNBT
 * text).</p>
 */
public final class StoredEntityFingerprints {

    /**
     * Fingerprints of the entity payloads last written for each chunk.
     */
    private static final Map<Key, long[]> STORED = new ConcurrentHashMap<>();

    /**
     * Static holder; not instantiable.
     */
    private StoredEntityFingerprints() {
    }

    /**
     * Fingerprints entity payloads independently of their order.
     *
     * @param forEach visits every entity payload
     * @return sorted per-entity hashes
     */
    public static long[] of(final Consumer<Consumer<NbtCompound>> forEach) {
        final List<NbtCompound> payloads = new ArrayList<>();
        forEach.accept(payloads::add);
        final long[] hashes = new long[payloads.size()];
        for (int i = 0; i < hashes.length; i++) {
            final NbtCompound nbt = payloads.get(i);
            hashes[i] = nbt == null ? 0L : ((long) nbt.hashCode() << 32) | (nbt.toString().hashCode() & 0xFFFF_FFFFL);
        }
        Arrays.sort(hashes);
        return hashes;
    }

    /**
     * Records what a completed save wrote for the chunk.
     *
     * @param world       target world
     * @param pos         chunk position
     * @param fingerprint fingerprint of the written entity payloads
     */
    public static void record(final ServerWorld world, final ChunkPos pos, final long[] fingerprint) {
        STORED.put(key(world, pos), fingerprint);
    }

    /**
     * Forgets the chunk's fingerprint because its entry is about to change or its state moved
     * back into memory.
     *
     * @param world target world
     * @param pos   chunk position
     */
    public static void forget(final ServerWorld world, final ChunkPos pos) {
        STORED.remove(key(world, pos));
    }

    /**
     * Consumes the chunk's fingerprint and reports whether it equals {@code fingerprint}.
     *
     * @param world       target world
     * @param pos         chunk position
     * @param fingerprint fingerprint of the entities about to be saved
     * @return true when the stored entry already holds exactly these entity payloads
     */
    public static boolean consumeIfMatches(final ServerWorld world, final ChunkPos pos, final long[] fingerprint) {
        final long[] stored = STORED.remove(key(world, pos));
        return stored != null && Arrays.equals(stored, fingerprint);
    }

    /**
     * Drops every fingerprint. Called when the server stops.
     */
    public static void clear() {
        STORED.clear();
    }

    /**
     * Builds the map key for a chunk.
     *
     * @param world target world
     * @param pos   chunk position
     * @return map key
     */
    private static Key key(final ServerWorld world, final ChunkPos pos) {
        return new Key(world.getRegistryKey(), pos.toLong());
    }

    private record Key(RegistryKey<World> world, long pos) {
    }
}
