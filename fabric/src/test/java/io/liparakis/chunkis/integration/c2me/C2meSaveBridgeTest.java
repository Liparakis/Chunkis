package io.liparakis.chunkis.integration.c2me;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

/**
 * Tests the check that C2ME still has the save method the bridge hooks.
 */
class C2meSaveBridgeTest {

    /**
     * Stands in for C2ME's class with the hooked method.
     */
    static final class WithSaveHook {
        /**
         * The hooked method's shape.
         *
         * @param ignoredContext loading context
         * @param ignoredChunk   chunk to save
         * @return nothing
         */
        @SuppressWarnings("unused") // Queried reflectively by the compatibility probe.
        Object asyncSave(final Object ignoredContext, final ChunkAccess ignoredChunk) {
            return null;
        }
    }

    /**
     * Stands in for a C2ME version that renamed it.
     */
    static final class WithoutSaveHook {
        /**
         * A renamed method of the same shape.
         *
         * @param ignoredContext loading context
         * @param ignoredChunk   chunk to save
         * @return nothing
         */
        @SuppressWarnings("unused") // Queried reflectively by the compatibility probe.
        Object save(final Object ignoredContext, final ChunkAccess ignoredChunk) {
            return null;
        }
    }

    /**
     * The hook is found only where the method exists with a chunk parameter, and a missing class is no hook.
     */
    @Test
    void findsTheSaveHookOnlyWhereItExists() {
        final ClassLoader loader = getClass().getClassLoader();
        assertTrue(C2meSaveBridge.hasSaveHook(loader, WithSaveHook.class.getName()));
        assertFalse(C2meSaveBridge.hasSaveHook(loader, WithoutSaveHook.class.getName()));
        assertFalse(C2meSaveBridge.hasSaveHook(loader, "com.example.Missing"));
    }
}
