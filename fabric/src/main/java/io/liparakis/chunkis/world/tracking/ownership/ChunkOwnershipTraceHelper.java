package io.liparakis.chunkis.world.tracking.ownership;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.world.tracking.suppression.ChunkMutationTrackingScope;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

public final class ChunkOwnershipTraceHelper {

    private ChunkOwnershipTraceHelper() {
        throw new AssertionError("Utility class");
    }

    public static void traceDecision(
            final RegistryKey<World> worldKey,
            final ChunkPos pos,
            final String decision,
            final ChunkTraceReason reason,
            final String source,
            final ChunkDelta<?, ?> delta,
            final ChunkMutationTrackingScope.Cause passiveCause
    ) {
        traceDecision(
                worldKey,
                pos != null ? new DebugChunkKey(pos.x, pos.z) : null,
                decision,
                reason,
                source,
                delta,
                passiveCause
        );
    }

    public static void traceDecision(
            final RegistryKey<World> worldKey,
            final DebugChunkKey chunkKey,
            final String decision,
            final ChunkTraceReason reason,
            final String source,
            final ChunkDelta<?, ?> delta,
            final ChunkMutationTrackingScope.Cause passiveCause
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.CHUNKIS_OWNERSHIP_DECISION,
                ChunkTraceSeverity.INFO,
                reason,
                source,
                describeDecision(decision, delta, passiveCause),
                worldKey != null ? worldKey.getValue().toString() : null,
                chunkKey,
                null,
                null,
                delta != null && delta.isDirty(),
                null
        );
    }

    private static String describeDecision(
            final String decision,
            final ChunkDelta<?, ?> delta,
            final ChunkMutationTrackingScope.Cause passiveCause
    ) {
        final Object metadata = delta != null ? delta.getChunkMetadata() : null;
        return "decision=" + decision
                + ", hasDelta=" + (delta != null)
                + ", dirty=" + (delta != null && delta.isDirty())
                + ", hasBase=" + ChunkDeltaOwnership.hasChunkisPersistenceAnchorBaseOnly(metadata)
                + ", fullBaseline=" + ChunkDeltaOwnership.hasChunkisFullBaselineOnly(metadata)
                + ", blockChanges=" + (delta != null ? delta.getBlockInstructions().size() : 0)
                + ", blockEntities=" + (delta != null ? delta.getBlockEntities().size() : 0)
                + ", mutationGeneration=" + (delta != null ? delta.getMutationGeneration() : 0)
                + ", firstMutationSource=" + (delta != null ? delta.getFirstMutationSource() : null)
                + ", ownershipReason=" + (delta != null ? delta.getOwnershipReason() : null)
                + ", ownershipSource=" + (delta != null ? delta.getOwnershipSource() : null)
                + ", passiveContext=" + (passiveCause != null ? passiveCause.name() : ChunkMutationTrackingScope.Cause.NONE.name());
    }
}

