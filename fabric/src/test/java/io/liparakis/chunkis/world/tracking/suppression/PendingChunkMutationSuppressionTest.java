package io.liparakis.chunkis.world.tracking.suppression;

import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChunkMutationSuppressionTest {

    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.clearSuspects();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void tracesSuppressionContextBeginAndEndWithCallerSource() {
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.ofVanilla("overworld")
        );
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        PendingChunkMutationSuppression.begin(
                overworld,
                12,
                -3,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD,
                "PendingChunkMutationSuppressionTest#begin"
        );
        PendingChunkMutationSuppression.end(
                overworld,
                12,
                -3,
                "PendingChunkMutationSuppressionTest#end"
        );

        final List<ChunkTraceEvent> events = ChunkTraceStore.snapshotMatching(event ->
                event.eventType() == ChunkTraceEventType.SUPPRESSION_CONTEXT_STARTED
                        || event.eventType() == ChunkTraceEventType.SUPPRESSION_CONTEXT_ENDED);

        assertEquals(2, events.size());
        assertEquals(ChunkTraceEventType.SUPPRESSION_CONTEXT_STARTED, events.get(0).eventType());
        assertEquals(ChunkTraceReason.PASSIVE_VANILLA_LOAD, events.get(0).reason());
        assertEquals("PendingChunkMutationSuppressionTest#begin", events.get(0).source());
        assertTrue(events.get(0).message().contains("passive_load"));
        assertEquals(ChunkTraceEventType.SUPPRESSION_CONTEXT_ENDED, events.get(1).eventType());
        assertEquals(ChunkTraceReason.PASSIVE_VANILLA_LOAD, events.get(1).reason());
        assertEquals("PendingChunkMutationSuppressionTest#end", events.get(1).source());
    }
}
