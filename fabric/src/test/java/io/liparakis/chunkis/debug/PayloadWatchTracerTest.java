package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadWatchTracerTest {
    @AfterEach
    void tearDown() {
        ChunkTraceWatchpoints.clear();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

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
