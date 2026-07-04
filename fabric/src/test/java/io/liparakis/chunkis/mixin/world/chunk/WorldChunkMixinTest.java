package io.liparakis.chunkis.mixin.world.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying the behavior of methods inside {@code WorldChunkMixin}.
 */
class WorldChunkMixinTest {

    /**
     * Helper method to invoke the private static method {@code chunkis$takeRestoreOperationId} via reflection.
     *
     * @param duck the {@link ChunkisDeltaDuck} instance
     * @return the restore operation ID
     */
    private static String invokeTakeRestoreOperationId(final ChunkisDeltaDuck duck) {
        try {
            final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.world.chunk.WorldChunkMixin");
            final Method method = mixinClass
                    .getDeclaredMethod("chunkis$takeRestoreOperationId", ChunkisDeltaDuck.class);
            method.setAccessible(true);
            return (String) method.invoke(null, duck);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Helper method to invoke the private static method {@code chunkis$tracePostRestoreFailure} via reflection.
     *
     * @param worldId     the world ID
     * @param chunkKey    the chunk key
     * @param operationId the operation ID
     * @param failedStage the failed stage
     */
    @SuppressWarnings("SameParameterValue")
    private static void invokeTracePostRestoreFailure(
            final String worldId,
            final DebugChunkKey chunkKey,
            final String operationId,
            final String failedStage
    ) {
        try {
            final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.world.chunk.WorldChunkMixin");
            final Method method = mixinClass.getDeclaredMethod(
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

    /**
     * Helper method to invoke the private static method {@code chunkis$shouldMarkRestoredDeltaSaved} via reflection.
     *
     * @param duck  the {@link ChunkisDeltaDuck} instance
     * @param delta the chunk delta
     * @return true if the restored delta should be marked saved, false otherwise
     */
    private static boolean invokeShouldMarkRestoredDeltaSaved(
            final ChunkisDeltaDuck duck,
            final ChunkDelta<?, ?> delta
    ) {
        try {
            final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.world.chunk.WorldChunkMixin");
            final Method method = mixinClass.getDeclaredMethod(
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

    /**
     * Helper method to invoke the private static method {@code chunkis$shouldSeedPostProcessingBlockEntities} via
     * reflection.
     *
     * @param hasLiveBlockEntities      whether the chunk has live block entities
     * @param hasPendingBlockEntityNbts whether the chunk has pending block entity NBTs
     * @param hasDelta                  whether the chunk has a delta
     * @param hasDeltaBlockEntities     whether the delta has block entities
     * @return true if post processing block entities should be seeded, false otherwise
     */
    private static boolean invokeShouldSeedPostProcessingBlockEntities(
            final boolean hasLiveBlockEntities,
            final boolean hasPendingBlockEntityNbts,
            final boolean hasDelta,
            final boolean hasDeltaBlockEntities
    ) {
        try {
            final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.world.chunk.WorldChunkMixin");
            final Method method = mixinClass.getDeclaredMethod(
                    "chunkis$shouldSeedPostProcessingBlockEntities",
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (boolean) method.invoke(
                    null,
                    hasLiveBlockEntities,
                    hasPendingBlockEntityNbts,
                    hasDelta,
                    hasDeltaBlockEntities
            );
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Resets the trace store and debug configuration after each test.
     */
    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Tests that the restore operation ID is correctly retrieved and cleared from the duck.
     */
    @Test
    void takesRestoreOperationIdFromDuckAndClearsIt() {
        final FakeChunkisDeltaDuck duck = new FakeChunkisDeltaDuck();
        duck.chunkis$setRestoreOperationId("load-42");

        assertEquals("load-42", invokeTakeRestoreOperationId(duck));
        assertNull(duck.chunkis$getRestoreOperationId());
    }

    /**
     * Tests that post-restore follow-up failures are correctly traced.
     */
    @Test
    void tracesPostRestoreFollowUpFailure() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);

        invokeTracePostRestoreFailure(
                "minecraft:overworld",
                new DebugChunkKey(7, -3),
                "load-17",
                "portal-index-update"
        );

        final ChunkTraceEvent event = ChunkTraceStore.latest(1)
                .getFirst();
        assertEquals(ChunkTraceEventType.RESTORE_FAILED, event.eventType());
        assertEquals(ChunkTraceReason.RESTORE_EXCEPTION, event.reason());
        assertEquals("load-17", event.operationId());
        assertEquals("minecraft:overworld", event.worldId());
        assertEquals(7,
                event.chunkKey()
                        .x());
        assertEquals(-3,
                event.chunkKey()
                        .z());
        assertTrue(event.message()
                .contains("portal-index-update"));
    }

    /**
     * Tests that restored deltas are marked as saved only when loaded from storage.
     */
    @Test
    void marksRestoredDeltaSavedOnlyWhenLoadedFromStorage() {
        final ChunkDelta<String, String> dirtyDelta = new ChunkDelta<>();
        dirtyDelta.markDirty();
        final FakeChunkisDeltaDuck memoryDuck = new FakeChunkisDeltaDuck();
        memoryDuck.chunkis$setRestoreLoadedFromStorage(false);
        final FakeChunkisDeltaDuck storageDuck = new FakeChunkisDeltaDuck();
        storageDuck.chunkis$setRestoreLoadedFromStorage(true);

        assertTrue(invokeShouldMarkRestoredDeltaSaved(storageDuck, dirtyDelta));
        assertFalse(invokeShouldMarkRestoredDeltaSaved(memoryDuck, dirtyDelta));
    }

    /**
     * Tests that post processing block entities are seeded only when vanilla has no pending block entities.
     */
    @Test
    void seedsPostProcessingBlockEntitiesOnlyWhenVanillaHasNothingPendingYet() {
        assertTrue(invokeShouldSeedPostProcessingBlockEntities(false, false, true, true));
        assertFalse(invokeShouldSeedPostProcessingBlockEntities(true, false, true, true));
        assertFalse(invokeShouldSeedPostProcessingBlockEntities(false, true, true, true));
        assertFalse(invokeShouldSeedPostProcessingBlockEntities(false, false, false, true));
        assertFalse(invokeShouldSeedPostProcessingBlockEntities(false, false, true, false));
    }

    /**
     * Fake implementation of {@link ChunkisDeltaDuck} for testing purposes.
     */
    private static final class FakeChunkisDeltaDuck implements ChunkisDeltaDuck {

        /**
         * The chunk delta associated with this duck.
         */
        private final ChunkDelta<?, ?> delta = new ChunkDelta<>();

        /**
         * The restore operation ID.
         */
        private String restoreOperationId;

        /**
         * Whether the restore was loaded from storage.
         */
        private boolean restoreLoadedFromStorage;

        /**
         * Retrieves the chunk delta.
         *
         * @return the chunk delta
         */
        @Override
        public ChunkDelta<?, ?> chunkis$getDelta() {
            return delta;
        }

        /**
         * Sets the chunk delta.
         *
         * @param delta the chunk delta to set
         * @throws UnsupportedOperationException always, as setting delta is not supported in this fake
         */
        @Override
        public void chunkis$setDelta(final ChunkDelta<?, ?> delta) {
            throw new UnsupportedOperationException();
        }

        /**
         * Retrieves the restore operation ID.
         *
         * @return the restore operation ID
         */
        @Override
        public String chunkis$getRestoreOperationId() {
            return restoreOperationId;
        }

        /**
         * Sets the restore operation ID.
         *
         * @param operationId the restore operation ID to set
         */
        @Override
        public void chunkis$setRestoreOperationId(final String operationId) {
            this.restoreOperationId = operationId;
        }

        /**
         * Checks if the restore was loaded from storage.
         *
         * @return true if the restore was loaded from storage, false otherwise
         */
        @Override
        public boolean chunkis$wasRestoreLoadedFromStorage() {
            return restoreLoadedFromStorage;
        }

        /**
         * Sets whether the restore was loaded from storage.
         *
         * @param restoreLoadedFromStorage true if loaded from storage, false otherwise
         */
        @Override
        public void chunkis$setRestoreLoadedFromStorage(final boolean restoreLoadedFromStorage) {
            this.restoreLoadedFromStorage = restoreLoadedFromStorage;
        }
    }
}
