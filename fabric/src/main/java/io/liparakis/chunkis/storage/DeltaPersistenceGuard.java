package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Guards against persisting sparse Chunkis payloads without a persisted base chunk.
 *
 * <p>A delta with sparse blocks, block entities, or entities but no base chunk
 * metadata cannot safely reconstruct terrain on reload. Persisting that state is
 * rejected with explicit attribution logging.</p>
 *
 * @author Liparakis
 * @version 1.0
 *
 */
public final class DeltaPersistenceGuard {

    /**
     * Utility class – not instantiable.
     */
    private DeltaPersistenceGuard() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns {@code true} if {@code delta} should be rejected because it carries
     * neither a persisted base chunk nor a full block baseline, meaning it cannot
     * safely reconstruct terrain on reload.
     *
     * <p>Either anchor is sufficient: a persisted base chunk <em>or</em> a full
     * block baseline allows safe reconstruction. Only when both are absent is the
     * delta considered unsafe to persist.</p>
     *
     * @param delta the candidate delta; {@code null} is treated as safe (not rejected)
     * @return {@code true} if the delta should be rejected
     */
    public static boolean shouldRejectSparseDeltaWithoutBase(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        if (delta.getSourceVersion() >= io.liparakis.chunkis.storage.model.CisConstants.VERSION) {
            return false;
        }
        final Object meta = delta.getChunkMetadata();
        return hasReplayPayload(delta)
                && !CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                && !CisNbtUtil.hasFullBlockBaseline(meta);
    }

    /**
     * Logs attribution for a rejected sparse no-base save.
     *
     * @param world   world that owns the save, may be {@code null}
     * @param pos     chunk position being saved, may be {@code null}
     * @param delta   rejected delta
     * @param path    save path label
     * @param caller  caller label
     */
    public static void logRejectedSparseDeltaWithoutBase(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDelta<?, ?> delta,
            final String path,
            final String caller) {
        if (delta == null) {
            return;
        }
        Chunkis.LOGGER.error(
                "Chunkis [INVALID_SAVE]: Rejected base-less sparse delta for {} in {} path={} caller={} blocks={} blockEntities={} entities={} metadata={} suppressInitialRepopulation={}",
                pos != null ? pos : "<unknown>",
                world != null ? world.getRegistryKey().getValue() : "<unknown>",
                path,
                caller,
                delta.getBlockInstructions().size(),
                delta.getBlockEntities().size(),
                delta.countNonNullEntities(),
                delta.getChunkMetadata() != null,
                delta.shouldSuppressInitialRepopulation()
        );
    }

    /**
     * Returns whether the delta still depends on replay payloads rather than a
     * fully authoritative snapshot baseline.
     */
    private static boolean hasReplayPayload(final ChunkDelta<?, ?> delta) {
        return !delta.getBlockInstructions().isEmpty()
                || !delta.getBlockEntities().isEmpty()
                || delta.countNonNullEntities() > 0;
    }
}
