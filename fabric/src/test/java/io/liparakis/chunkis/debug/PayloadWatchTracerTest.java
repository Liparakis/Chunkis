package io.liparakis.chunkis.debug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link PayloadWatchTracer} class.
 */
class PayloadWatchTracerTest {

    /**
     * Cleans up watchpoints, trace store, and debug configurations after each test.
     */
    @AfterEach
    void tearDown() {
        ChunkTraceWatchpoints.clear();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Tests that tracing a delta stage successfully captures and logs a watch failure
     * when a watched block is missing from the payload.
     */
    @Test
    void traceDeltaStageReportsMissingWatchedBlock() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", 8, -60, 8));

        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();
        PayloadWatchTracer.traceDeltaStage(
                "minecraft:overworld",
                new DebugChunkKey(0, 0),
                0,
                0,
                delta,
                "op-1",
                ChunkTraceEventType.WATCH_CAPTURED,
                "dirty-tracker",
                "test",
                "delta entered dirty tracker",
                null
                                          );

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(2);
        assertTrue(events.stream().anyMatch(event ->
                                                    event.eventType() == ChunkTraceEventType.WATCH_FAILED
                                                            && "dirty-tracker".equals(event.payloadWatchStage())
                                                            && "missing during dirty-tracker".equals(event.message())));
    }
}
