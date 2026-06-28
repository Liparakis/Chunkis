package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries save-path ownership snapshots into lower vanilla storage hooks on the same thread.
 */
public final class PendingVanillaSaveDecision {

    private static final ThreadLocal<Map<Long, Snapshot>> PENDING =
            ThreadLocal.withInitial(HashMap::new);

    private PendingVanillaSaveDecision() {
        throw new AssertionError("Utility class");
    }

    public static void put(
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final ChunkTraceReason reason,
            final String source
    ) {
        PENDING.get().put(pos.toLong(), new Snapshot(delta, reason, source));
    }

    public static Snapshot take(final ChunkPos pos) {
        return PENDING.get().remove(pos.toLong());
    }

    public record Snapshot(
            ChunkDelta<?, ?> delta,
            ChunkTraceReason reason,
            String source
    ) {
    }
}
