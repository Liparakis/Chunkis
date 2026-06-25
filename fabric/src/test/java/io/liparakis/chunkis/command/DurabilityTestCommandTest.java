package io.liparakis.chunkis.command;

import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurabilityTestCommandTest {

    @AfterEach
    void tearDown() throws Exception {
        clearRunState();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void mapsTeleportTargetToChunkCoordinates() {
        assertEquals(-1, DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9)).x());
        assertEquals(1, DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9)).z());
    }

    @Test
    void stopInternalEmitsStoppedTraceAndClearsRunState() throws Exception {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        setRunState(executor, "durability-7");

        final Method stopInternal = DurabilityTestCommand.class
                .getDeclaredMethod("stopInternal", String.class);
        stopInternal.setAccessible(true);

        final boolean stopped = (boolean) stopInternal.invoke(null, "stopped manually");

        assertTrue(stopped);
        assertTrue(executor.isShutdown());
        assertNull(currentRunId());
        assertNull(currentExecutor());

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(5);
        assertTrue(events.stream().anyMatch(event ->
                event.eventType() == ChunkTraceEventType.DURABILITY_TEST_STOPPED
                        && "durability-7".equals(event.operationId())
                        && "stopped manually".equals(event.message())));
    }

    @Test
    void emitsStartedAndFailedDurabilityTraceEvents() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        DurabilityTestCommand.traceStarted(12, 25, "minecraft:overworld", "durability-9");
        DurabilityTestCommand.traceFailed("boom", "minecraft:overworld", "durability-9");

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(5);
        assertTrue(events.stream().anyMatch(event ->
                event.eventType() == ChunkTraceEventType.DURABILITY_TEST_STARTED
                        && "durability-9".equals(event.operationId())
                        && event.message().contains("count=12")
                        && event.message().contains("delayMs=25")));
        assertTrue(events.stream().anyMatch(event ->
                event.eventType() == ChunkTraceEventType.DURABILITY_TEST_FAILED
                        && event.reason() == ChunkTraceReason.IO_EXCEPTION
                        && "durability-9".equals(event.operationId())
                        && "durability test failed: boom".equals(event.message())));
    }

    private static void setRunState(
            final ScheduledExecutorService executor,
            final String runId
    ) throws Exception {
        executorRef().set(executor);
        runIdRef().set(runId);
    }

    private static void clearRunState() throws Exception {
        final ScheduledExecutorService executor = currentExecutor();
        if (executor != null) {
            executor.shutdownNow();
        }
        executorRef().set(null);
        runIdRef().set(null);
    }

    private static ScheduledExecutorService currentExecutor() throws Exception {
        return executorRef().get();
    }

    private static String currentRunId() throws Exception {
        return runIdRef().get();
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<ScheduledExecutorService> executorRef() throws Exception {
        final Field field = DurabilityTestCommand.class.getDeclaredField("executorRef");
        field.setAccessible(true);
        return (AtomicReference<ScheduledExecutorService>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String> runIdRef() throws Exception {
        final Field field = DurabilityTestCommand.class.getDeclaredField("runIdRef");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(null);
    }
}
