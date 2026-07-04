package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.tracking.ownership.PendingVanillaSaveDecision;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@code StoragePreventionMixin}.
 */
class StoragePreventionMixinTest {

    /**
     * Tests that an untouched vanilla save (either an untouched autosave or a null snapshot)
     * is allowed to write and not blocked.
     *
     * @throws ReflectiveOperationException if reflection fails
     */
    @Test
    void untouchedVanillaSaveIsAllowed() throws ReflectiveOperationException {
        assertFalse(shouldBlockWrite(new PendingVanillaSaveDecision.Snapshot(
                null,
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED
        )));
        assertFalse(shouldBlockWrite(null));
    }

    /**
     * Tests that a vanilla save attempt for a chunk owned by Chunkis is blocked.
     *
     * @throws ReflectiveOperationException if reflection fails
     */
    @Test
    void chunkisOwnedVanillaSaveIsBlocked() throws ReflectiveOperationException {
        assertTrue(shouldBlockWrite(new PendingVanillaSaveDecision.Snapshot(
                new ChunkDelta<>(),
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE
        )));
    }

    /**
     * Helper method to invoke the private static method {@code chunkis$shouldBlockVanillaWrite} in {@code StoragePreventionMixin} via reflection.
     *
     * @param snapshot the pending vanilla save decision snapshot
     * @return true if the vanilla write should be blocked, false otherwise
     * @throws ReflectiveOperationException if reflection fails
     */
    private static boolean shouldBlockWrite(final PendingVanillaSaveDecision.Snapshot snapshot)
            throws ReflectiveOperationException {
        final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.storage.StoragePreventionMixin");
        final Method method = mixinClass.getDeclaredMethod(
                "chunkis$shouldBlockVanillaWrite",
                PendingVanillaSaveDecision.Snapshot.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, snapshot);
    }
}
