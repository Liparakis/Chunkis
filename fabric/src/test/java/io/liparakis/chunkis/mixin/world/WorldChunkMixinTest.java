package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.DebugChunkKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChunkMixinTest {

    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void takesRestoreOperationIdFromDuckAndClearsIt() {
        final FakeChunkisDeltaDuck duck = new FakeChunkisDeltaDuck();
        duck.chunkis$setRestoreOperationId("load-42");

        assertEquals("load-42", invokeTakeRestoreOperationId(duck));
        assertNull(duck.chunkis$getRestoreOperationId());
    }

    @Test
    void tracesPostRestoreFollowUpFailure() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        invokeTracePostRestoreFailure(
                "minecraft:overworld",
                new DebugChunkKey(7, -3),
                "load-17",
                "portal-index-update"
        );

        final ChunkTraceEvent event = ChunkTraceStore.latest(1).getFirst();
        assertEquals(ChunkTraceEventType.RESTORE_FAILED, event.eventType());
        assertEquals(ChunkTraceReason.RESTORE_EXCEPTION, event.reason());
        assertEquals("load-17", event.operationId());
        assertEquals("minecraft:overworld", event.worldId());
        assertEquals(7, event.chunkKey().x());
        assertEquals(-3, event.chunkKey().z());
        assertTrue(event.message().contains("portal-index-update"));
    }

    @Test
    void marksRestoredDeltaSavedOnlyWhenLoadedFromStorage() {
        final ChunkDelta<String, String> dirtyDelta = new ChunkDelta<>();
        dirtyDelta.markDirty();
        final FakeChunkisDeltaDuck memoryDuck = new FakeChunkisDeltaDuck();
        memoryDuck.chunkis$setRestoreLoadedFromStorage(false);
        final FakeChunkisDeltaDuck storageDuck = new FakeChunkisDeltaDuck();
        storageDuck.chunkis$setRestoreLoadedFromStorage(true);

        assertTrue(invokeShouldMarkRestoredDeltaSaved(storageDuck, dirtyDelta));
        assertTrue(!invokeShouldMarkRestoredDeltaSaved(memoryDuck, dirtyDelta));
    }

    private static String invokeTakeRestoreOperationId(final ChunkisDeltaDuck duck) {
        try {
            final Method method = WorldChunkMixin.class
                    .getDeclaredMethod("chunkis$takeRestoreOperationId", ChunkisDeltaDuck.class);
            method.setAccessible(true);
            return (String) method.invoke(null, duck);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeTracePostRestoreFailure(
            final String worldId,
            final DebugChunkKey chunkKey,
            final String operationId,
            final String failedStage
    ) {
        try {
            final Method method = WorldChunkMixin.class.getDeclaredMethod(
                    "chunkis$tracePostRestoreFailure",
                    String.class,
                    DebugChunkKey.class,
                    String.class,
                    String.class
            );
            method.setAccessible(true);
            method.invoke(null, worldId, chunkKey, operationId, failedStage);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean invokeShouldMarkRestoredDeltaSaved(
            final ChunkisDeltaDuck duck,
            final ChunkDelta<?, ?> delta
    ) {
        try {
            final Method method = WorldChunkMixin.class.getDeclaredMethod(
                    "chunkis$shouldMarkRestoredDeltaSaved",
                    ChunkisDeltaDuck.class,
                    ChunkDelta.class
            );
            method.setAccessible(true);
            return (boolean) method.invoke(null, duck, delta);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
    private static final class FakeChunkisDeltaDuck implements ChunkisDeltaDuck {

        private final ChunkDelta<?, ?> delta = new ChunkDelta<>();
        private String restoreOperationId;
        private boolean restoreLoadedFromStorage;

        @Override
        public ChunkDelta<?, ?> chunkis$getDelta() {
            return delta;
        }

        @Override
        public void chunkis$setDelta(final ChunkDelta<?, ?> delta) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chunkis$getRestoreOperationId() {
            return restoreOperationId;
        }

        @Override
        public void chunkis$setRestoreOperationId(final String operationId) {
            this.restoreOperationId = operationId;
        }

        @Override
        public boolean chunkis$wasRestoreLoadedFromStorage() {
            return restoreLoadedFromStorage;
        }

        @Override
        public void chunkis$setRestoreLoadedFromStorage(final boolean restoreLoadedFromStorage) {
            this.restoreLoadedFromStorage = restoreLoadedFromStorage;
        }
    }
}
