package io.liparakis.chunkis.mixin.world.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class WorldChunkMixinLeafTickTest {

    @Test
    void leafDistanceRepairInsideLeafTickIsNotClassifiedAsDecay() throws ReflectiveOperationException {
        assertFalse(isNaturalLeafDecayTransition(
                true,
                true,
                true,
                false
        ));
    }

    @Test
    void leafRemovalToAirInsideLeafTickIsClassifiedAsDecay() throws ReflectiveOperationException {
        assertTrue(isNaturalLeafDecayTransition(
                true,
                true,
                false,
                true
        ));
    }

    private static boolean isNaturalLeafDecayTransition(
            final boolean leafTickActive,
            final boolean previousIsLeaves,
            final boolean nextIsLeaves,
            final boolean nextIsAir) throws ReflectiveOperationException {
        final Method method = WorldChunkMixin.class.getDeclaredMethod(
                "chunkis$isNaturalLeafDecayTransition",
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, leafTickActive, previousIsLeaves, nextIsLeaves, nextIsAir);
    }
}
