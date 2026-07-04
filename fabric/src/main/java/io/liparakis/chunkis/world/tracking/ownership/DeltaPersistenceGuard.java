package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Guards against persisting sparse Chunkis payloads without a persisted base chunk.
 *
 * <p>A delta with sparse blocks, block entities, or entities but no base chunk
 * metadata cannot safely reconstruct terrain on reload. Persisting that state is
 * rejected with explicit attribution logging.</p>
 */
public final class DeltaPersistenceGuard {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
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
    public static boolean shouldRejectSparseDeltaWithoutBase(final ChunkDeltaView<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        final Object meta = delta.getChunkMetadata();
        return hasReplayPayload(delta)
                && !CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                && !CisNbtUtil.hasFullBlockBaseline(meta);
    }

    /**
     * Returns {@code true} if {@code delta} should be rejected, with option to trust v11 snapshots.
     *
     * @param delta                   the candidate delta; {@code null} is treated as safe
     * @param trustV11SnapshotPayload true if v11 snapshots are trusted baseline sources
     * @return {@code true} if the delta should be rejected
     */
    public static boolean shouldRejectSparseDeltaWithoutBase(final ChunkDeltaView<?, ?> delta,
            final boolean trustV11SnapshotPayload) {
        if (delta == null) {
            return false;
        }
        if (trustV11SnapshotPayload && hasAuthoritativeV11SnapshotPayload(delta)) {
            return false;
        }
        return shouldRejectSparseDeltaWithoutBase(delta);
    }

    /**
     * Evaluates if delta represents block entities only without baseline data.
     *
     * @param delta candidate delta
     * @return true if payload is invalid block-entity only
     */
    public static boolean hasInvalidBlockEntityOnlyPayloadWithoutBase(final ChunkDeltaView<?, ?> delta) {
        if (delta == null) {
            return false;
        }
        final Object meta = delta.getChunkMetadata();
        return delta.getBlockChangesCount() == 0
                && !delta.getBlockEntities()
                .isEmpty()
                && !CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                && !CisNbtUtil.hasFullBlockBaseline(meta);
    }

    /**
     * Logs attribution for a rejected sparse no-base save.
     *
     * @param world  world that owns the save, may be {@code null}
     * @param pos    chunk position being saved, may be {@code null}
     * @param delta  rejected delta
     * @param path   save path label
     * @param caller caller label
     */
    public static void logRejectedSparseDeltaWithoutBase(
            final ServerWorld world,
            final ChunkPos pos,
            final ChunkDeltaView<?, ?> delta,
            final String path,
            final String caller) {
        if (delta == null) {
            return;
        }
        Chunkis.LOGGER.error(
                "Chunkis [INVALID_SAVE]: Rejected base-less sparse delta for {} in {} path={} caller={} shape={} suppressInitialRepopulation={}",
                pos != null ? pos : "<unknown>",
                world != null ? world.getRegistryKey()
                                .getValue() : "<unknown>",
                path,
                caller,
                describeDeltaShape(delta),
                delta.shouldSuppressInitialRepopulation()
        );
    }

    /**
     * Generates a descriptive string for a chunk delta shape/metrics.
     *
     * @param delta candidate delta
     * @return description summary text
     */
    public static String describeDeltaShape(final ChunkDeltaView<?, ?> delta) {
        if (delta == null) {
            return "null";
        }

        final Object meta = delta.getChunkMetadata();
        return "blocks=" + delta.getBlockChangesCount()
                + ", blockEntities=" + delta.getBlockEntities()
                .size()
                + ", entities=" + delta.countNonNullEntities()
                + ", sections=" + delta.getTouchedSectionCount()
                + ", hasBase=" + CisNbtUtil.hasPersistedBaseChunkNbt(meta)
                + ", fullBaseline=" + CisNbtUtil.hasFullBlockBaseline(meta)
                + ", authoritativeV11Snapshot=" + hasAuthoritativeV11SnapshotPayload(delta)
                + ", metadataKeys=" + describeMetadataKeys(meta)
                + ", sourceVersion=" + delta.getSourceVersion()
                + ", mutationGeneration=" + delta.getMutationGeneration()
                + ", dirty=" + delta.isDirty()
                + ", sparse=" + hasReplayPayload(delta);
    }

    /**
     * Generates a descriptive string for a delta lifecycle state.
     *
     * @param delta candidate delta
     * @return description lifecycle text
     */
    public static String describeLifecycleState(final ChunkDeltaView<?, ?> delta) {
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
                + ", blockChanges=" + delta.getBlockChangesCount()
                + ", blockEntities=" + delta.getBlockEntities()
                .size();
    }

    /**
     * Checks if the delta contains an authoritative v11 snapshot payload.
     *
     * @param delta candidate delta
     * @return true if snapshot payload is authoritative v11
     */
    public static boolean hasAuthoritativeV11SnapshotPayload(final ChunkDeltaView<?, ?> delta) {
        return delta != null
                && delta.getSourceVersion() >= io.liparakis.chunkis.core.model.CisConstants.VERSION
                && delta.getBlockChangesCount() > 0;
    }

    /**
     * Returns whether the delta still depends on replay payloads rather than a
     * fully authoritative snapshot baseline.
     */
    private static boolean hasReplayPayload(final ChunkDeltaView<?, ?> delta) {
        return delta.getBlockChangesCount() > 0
                || !delta.getBlockEntities()
                .isEmpty()
                || delta.countNonNullEntities() > 0;
    }

    /**
     * Describes key listings found inside metadata compounds.
     *
     * @param metadata candidate metadata object
     * @return descriptive keys text list
     */
    private static String describeMetadataKeys(final Object metadata) {
        if (!(metadata instanceof net.minecraft.nbt.NbtCompound compound) || compound.isEmpty()) {
            return "[]";
        }
        return compound.getKeys()
                .toString();
    }
}
