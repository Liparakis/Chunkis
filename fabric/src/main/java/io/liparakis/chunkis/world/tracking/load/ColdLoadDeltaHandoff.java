package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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
    private static final Map<Key, ChunkDelta<BlockState, CompoundTag>> PENDING = new ConcurrentHashMap<>();

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
    static void offer(final ServerLevel world, final ChunkPos pos, final ChunkDelta<BlockState, CompoundTag> delta) {
        final Key key = new Key(world.dimension(), pos.pack());
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
    public static ChunkDelta<BlockState, CompoundTag> take(final ServerLevel world, final ChunkPos pos) {
        return PENDING.remove(new Key(world.dimension(), pos.pack()));
    }

    /**
     * Drops every pending delta. Called when the server stops.
     */
    public static void clear() {
        PENDING.clear();
    }

    private record Key(ResourceKey<Level> world, long pos) {
    }
}
