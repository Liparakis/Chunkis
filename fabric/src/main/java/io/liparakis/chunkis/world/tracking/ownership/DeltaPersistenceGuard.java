package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
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
        final Object meta = delta.getChunkMetadata();
        return hasReplayPayload(delta)
                && !CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                && !CisNbtUtil.hasFullBlockBaseline(meta);
    }

    public static boolean shouldRejectSparseDeltaWithoutBase(final ChunkDelta<?, ?> delta, final boolean trustV11SnapshotPayload) {
        if (delta == null) {
            return false;
        }
        if (trustV11SnapshotPayload && hasAuthoritativeV11SnapshotPayload(delta)) {
            return false;
        }
        return shouldRejectSparseDeltaWithoutBase(delta);
    }

    public static boolean hasInvalidBlockEntityOnlyPayloadWithoutBase(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        final Object meta = delta.getChunkMetadata();
        return delta.getBlockInstructions().isEmpty()
                && !delta.getBlockEntities().isEmpty()
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
                "Chunkis [INVALID_SAVE]: Rejected base-less sparse delta for {} in {} path={} caller={} shape={} suppressInitialRepopulation={}",
                pos != null ? pos : "<unknown>",
                world != null ? world.getRegistryKey().getValue() : "<unknown>",
                path,
                caller,
                describeDeltaShape(delta),
                delta.shouldSuppressInitialRepopulation()
        );
    }

    public static String describeDeltaShape(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return "null";
        }

        final Object meta = delta.getChunkMetadata();
        return "blocks=" + delta.getBlockInstructions().size()
                + ", blockEntities=" + delta.getBlockEntities().size()
                + ", entities=" + delta.countNonNullEntities()
                + ", sections=" + countSections(delta)
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                + ", fullBaseline=" + CisNbtUtil.hasFullBlockBaseline(meta)
                + ", authoritativeV11Snapshot=" + hasAuthoritativeV11SnapshotPayload(delta)
                + ", metadataKeys=" + describeMetadataKeys(meta)
                + ", sourceVersion=" + delta.getSourceVersion()
                + ", mutationGeneration=" + delta.getMutationGeneration()
                + ", dirty=" + delta.isDirty()
                + ", sparse=" + hasReplayPayload(delta);
    }

    public static String describeLifecycleState(final ChunkDelta<?, ?> delta) {
        if (delta == null) {
            return "null";
        }

        final Object meta = delta.getChunkMetadata();
        return "mutationGeneration=" + delta.getMutationGeneration()
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                + ", fullBaseline=" + CisNbtUtil.hasFullBlockBaseline(meta)
                + ", authoritativeV11Snapshot=" + hasAuthoritativeV11SnapshotPayload(delta)
                + ", metadataKeys=" + describeMetadataKeys(meta)
                + ", suppressInitialRepopulation=" + delta.shouldSuppressInitialRepopulation()
                + ", blockChanges=" + delta.getBlockInstructions().size()
                + ", blockEntities=" + delta.getBlockEntities().size();
    }

    public static boolean hasAuthoritativeV11SnapshotPayload(final ChunkDelta<?, ?> delta) {
        return delta != null
                && delta.getSourceVersion() >= io.liparakis.chunkis.storage.model.CisConstants.VERSION
                && !delta.getBlockInstructions().isEmpty();
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

    private static int countSections(final ChunkDelta<?, ?> delta) {
        final java.util.Set<Integer> sections = new java.util.HashSet<>();
        delta.forEachBlock((x, y, z, state) -> sections.add(y >> 4));
        delta.getBlockEntities().forEach((packedPos, nbt) ->
                sections.add(io.liparakis.chunkis.core.BlockInstruction.unpackY(packedPos) >> 4));
        return sections.size();
    }

    private static String describeMetadataKeys(final Object metadata) {
        if (!(metadata instanceof net.minecraft.nbt.NbtCompound compound) || compound.isEmpty()) {
            return "[]";
        }
        return compound.getKeys().toString();
    }
}


