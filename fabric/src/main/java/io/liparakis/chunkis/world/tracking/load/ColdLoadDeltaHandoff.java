package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Hands the delta the load hook built its synthetic NBT from (decoded from storage, or the
 * tracked delta it just flushed) to the chunk-convert hook, so a load decodes the stored
 * entry at most once and the restored chunk uses the same delta its synthetic NBT was
 * built from.
 *
 * <p>Every load passes through the load hook, which replaces any entry for its chunk;
 * the convert hook removes it. An entry left by an aborted load therefore never
 * outlives the next load of that chunk.</p>
 */
public final class ColdLoadDeltaHandoff {

    /**
     * Decoded deltas waiting for their chunk to be converted.
     */
    private static final Map<Key, ChunkDelta<BlockState, NbtCompound>> PENDING = new ConcurrentHashMap<>();

    /**
     * Static holder; not instantiable.
     */
    private ColdLoadDeltaHandoff() {
    }

    /**
     * Records the delta the load hook decoded, or forgets a previous one when
     * {@code delta} is {@code null}.
     *
     * @param world target world
     * @param pos   chunk position
     * @param delta decoded delta, or {@code null}
     */
    static void offer(final ServerWorld world, final ChunkPos pos, final ChunkDelta<BlockState, NbtCompound> delta) {
        final Key key = new Key(world.getRegistryKey(), pos.toLong());
        if (delta == null) {
            PENDING.remove(key);
        } else {
            PENDING.put(key, delta);
        }
    }

    /**
     * Removes and returns the delta decoded for this chunk's load.
     *
     * @param world target world
     * @param pos   chunk position
     * @return decoded delta, or {@code null} when the load hook decoded none
     */
    public static ChunkDelta<BlockState, NbtCompound> take(final ServerWorld world, final ChunkPos pos) {
        return PENDING.remove(new Key(world.getRegistryKey(), pos.toLong()));
    }

    /**
     * Drops every pending delta. Called when the server stops.
     */
    public static void clear() {
        PENDING.clear();
    }

    private record Key(RegistryKey<World> world, long pos) {
    }
}
