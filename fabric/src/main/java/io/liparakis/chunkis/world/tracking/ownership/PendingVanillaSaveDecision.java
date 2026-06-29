package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.math.ChunkPos;

/**
 * Carries save-path ownership snapshots into lower vanilla storage hooks on the same thread.
 */
public final class PendingVanillaSaveDecision {

    /**
     * Map storing thread-local snapshots of save decisions.
     */
    private static final ThreadLocal<Map<Long, Snapshot>> PENDING =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PendingVanillaSaveDecision() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records a pending save decision snapshot.
     *
     * @param pos    chunk position
     * @param delta  associated block delta
     * @param reason trace reason identifier
     */
    public static void put(
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final ChunkTraceReason reason
    ) {
        PENDING.get()
                .put(pos.toLong(), new Snapshot(delta, reason));
    }

    /**
     * Consumes and returns a pending save decision snapshot.
     *
     * @param pos chunk position
     * @return consumed decision snapshot or null
     */
    public static Snapshot take(final ChunkPos pos) {
        return PENDING.get()
                .remove(pos.toLong());
    }

    /**
     * Snapshot representing a pending save decision.
     *
     * @param delta  associated block delta
     * @param reason trace reason mapping
     */
    public record Snapshot(
            ChunkDelta<?, ?> delta,
            ChunkTraceReason reason
    ) {

    }
}
