package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.tracking.ownership.PendingVanillaSaveDecision;
import java.lang.reflect.Method;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

class StoragePreventionMixinTest {

    @Test
    void untouchedVanillaSaveIsAllowed() throws ReflectiveOperationException {
        assertFalse(shouldBlockWrite(new PendingVanillaSaveDecision.Snapshot(
                null,
                ChunkTraceReason.VANILLA_AUTOSAVE_UNTOUCHED
        )));
        assertFalse(shouldBlockWrite(null));
    }

    @Test
    void chunkisOwnedVanillaSaveIsBlocked() throws ReflectiveOperationException {
        assertTrue(shouldBlockWrite(new PendingVanillaSaveDecision.Snapshot(
                new ChunkDelta<>(),
                ChunkTraceReason.RESTORE_OF_EXISTING_CHUNKIS_STORAGE
        )));
    }

    private static boolean shouldBlockWrite(final PendingVanillaSaveDecision.Snapshot snapshot)
            throws ReflectiveOperationException {
        final Method method = StoragePreventionMixin.class.getDeclaredMethod(
                "chunkis$shouldBlockVanillaWrite",
                PendingVanillaSaveDecision.Snapshot.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, snapshot);
    }
}
