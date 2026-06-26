package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.storage.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMutationTrackingScopeTest {

    @Test
    void prefersBaseApplyDuringAnchoredLoad() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.setChunkMetadata(CisNbtUtil.createChunkMetadataTakingOwnership(
                new NbtCompound(),
                false,
                true,
                null,
                false
        ), false);

        assertEquals(
                ChunkMutationTrackingScope.Cause.BASE_APPLY,
                ChunkMutationTrackingScope.initialCauseForLoad(delta)
        );
    }

    @Test
    void fallsBackToPassiveLoadWithoutChunkisAnchor() {
        assertEquals(
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD,
                ChunkMutationTrackingScope.initialCauseForLoad(new ChunkDelta<>())
        );
    }

    @Test
    void restoreTakesPriorityAndSuppressionTraceIsScoped() {
        final ChunkMutationTrackingScope scope = new ChunkMutationTrackingScope();

        scope.push(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD);
        assertEquals(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD, scope.currentCause());
        assertTrue(scope.shouldTraceSuppression(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD));
        assertFalse(scope.shouldTraceSuppression(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD));

        scope.push(ChunkMutationTrackingScope.Cause.RESTORE);
        assertEquals(ChunkMutationTrackingScope.Cause.RESTORE, scope.currentCause());
        assertTrue(scope.shouldTraceSuppression(ChunkMutationTrackingScope.Cause.RESTORE));
        assertFalse(scope.shouldTraceSuppression(ChunkMutationTrackingScope.Cause.RESTORE));

        scope.pop(ChunkMutationTrackingScope.Cause.RESTORE);
        assertEquals(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD, scope.currentCause());
        scope.pop(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD);
        assertFalse(scope.isSuppressed());

        scope.push(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD);
        assertTrue(scope.shouldTraceSuppression(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD));
    }

    @Test
    void mapsSuppressionEventsByCause() {
        assertEquals(
                ChunkTraceEventType.MUTATION_SUPPRESSED_RESTORE,
                ChunkMutationTrackingScope.suppressionEventType(ChunkMutationTrackingScope.Cause.RESTORE)
        );
        assertEquals(
                ChunkTraceEventType.MUTATION_SUPPRESSED_BASE_APPLY,
                ChunkMutationTrackingScope.suppressionEventType(ChunkMutationTrackingScope.Cause.BASE_APPLY)
        );
        assertEquals(
                ChunkTraceEventType.MUTATION_SUPPRESSED_PASSIVE_LOAD,
                ChunkMutationTrackingScope.suppressionEventType(ChunkMutationTrackingScope.Cause.PASSIVE_LOAD)
        );
    }
}
