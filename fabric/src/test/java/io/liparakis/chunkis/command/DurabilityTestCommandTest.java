package io.liparakis.chunkis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link DurabilityTestCommand} class including reflection-based testing
 * of run state setup, executor shutdown, and custom tracing events.
 */
class DurabilityTestCommandTest {

    /**
     * Sets the active runner executor and run ID in the {@link DurabilityTestCommand} class.
     *
     * @param executor the scheduled executor service to set
     * @param runId    the run ID to set
     * @throws Exception if reflection access fails
     */
    @SuppressWarnings("SameParameterValue")
    private static void setRunState(
            final ScheduledExecutorService executor,
            final String runId
    ) throws Exception {
        executorRef().set(executor);
        runIdRef().set(runId);
    }

    /**
     * Clears the current durability test run state, shutting down the active executor if present.
     *
     * @throws Exception if reflection access fails
     */
    private static void clearRunState() throws Exception {
        final ScheduledExecutorService executor = currentExecutor();
        if (executor != null) {
            executor.shutdownNow();
        }
        executorRef().set(null);
        runIdRef().set(null);
    }

    /**
     * Retrieves the currently active scheduled executor service using reflection.
     *
     * @return the current ScheduledExecutorService instance, or null if none
     * @throws Exception if reflection access fails
     */
    private static ScheduledExecutorService currentExecutor() throws Exception {
        return executorRef().get();
    }

    /**
     * Retrieves the currently active run ID using reflection.
     *
     * @return the current run ID, or null if none
     * @throws Exception if reflection access fails
     */
    private static String currentRunId() throws Exception {
        return runIdRef().get();
    }

    /**
     * Helper method to access the private atomic reference executorRef in {@link DurabilityTestCommand}.
     *
     * @return the AtomicReference wrapping the ScheduledExecutorService
     * @throws Exception if reflection access fails
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<ScheduledExecutorService> executorRef() throws Exception {
        final Field field = DurabilityTestCommand.class.getDeclaredField("executorRef");
        field.setAccessible(true);
        return (AtomicReference<ScheduledExecutorService>) field.get(null);
    }

    /**
     * Helper method to access the private atomic reference runIdRef in {@link DurabilityTestCommand}.
     *
     * @return the AtomicReference wrapping the run ID string
     * @throws Exception if reflection access fails
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<String> runIdRef() throws Exception {
        final Field field = DurabilityTestCommand.class.getDeclaredField("runIdRef");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(null);
    }

    /**
     * Cleans up test run state, trace store events, and debug configuration after each test.
     *
     * @throws Exception if cleanup reflection fails
     */
    @AfterEach
    void tearDown() throws Exception {
        clearRunState();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Tests mapping of teleport targets to Chunk coordinates.
     */
    @Test
    void mapsTeleportTargetToChunkCoordinates() {
        assertEquals(-1,
                DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9))
                        .x());
        assertEquals(1,
                DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9))
                        .z());
    }

    /**
     * Tests that stopping durability runs internally shuts down executors and emits a stop trace event.
     *
     * @throws Exception if reflection or method invocation fails
     */
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
        assertTrue(events.stream()
                .anyMatch(event ->
                        event.eventType() == ChunkTraceEventType.DURABILITY_TEST_STOPPED
                                && "durability-7".equals(event.operationId())
                                && "stopped manually".equals(event.message())));
    }

    /**
     * Tests that started and failed durability events are tracked and stored in the trace store.
     */
    @Test
    void emitsStartedAndFailedDurabilityTraceEvents() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        DurabilityTestCommand.traceStarted(12, 25, "minecraft:overworld", "durability-9");
        DurabilityTestCommand.traceFailed("boom", "minecraft:overworld", "durability-9");

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(5);
        assertTrue(events.stream()
                .anyMatch(event ->
                        event.eventType() == ChunkTraceEventType.DURABILITY_TEST_STARTED
                                && "durability-9".equals(event.operationId())
                                && event.message()
                                .contains("count=12")
                                && event.message()
                                .contains("delayMs=25")));
        assertTrue(events.stream()
                .anyMatch(event ->
                        event.eventType() == ChunkTraceEventType.DURABILITY_TEST_FAILED
                                && event.reason() == ChunkTraceReason.IO_EXCEPTION
                                && "durability-9".equals(event.operationId())
                                && "durability test failed: boom".equals(event.message())));
    }
}
