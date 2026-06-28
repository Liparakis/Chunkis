package io.liparakis.chunkis.world;

import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
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
        final ChunkPos chunkPos = new ChunkPos(12, -3);
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        PendingChunkMutationSuppression.begin(
                overworld,
                chunkPos,
                ChunkMutationTrackingScope.Cause.PASSIVE_LOAD,
                "PendingChunkMutationSuppressionTest#begin"
        );
        PendingChunkMutationSuppression.end(
                overworld,
                chunkPos,
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
