package io.liparakis.chunkis.mixin.world.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Test class for verifying natural leaf decay detection transitions in {@code WorldChunkMixin}.
 */
final class WorldChunkMixinLeafTickTest {

    /**
     * Verifies that leaf distance repair inside a leaf tick is not classified as decay.
     *
     * @throws ReflectiveOperationException if reflection fails during method invocation
     */
    @Test
    void leafDistanceRepairInsideLeafTickIsNotClassifiedAsDecay() throws ReflectiveOperationException {
        assertFalse(isNaturalLeafDecayTransition(
                true,
                true,
                true,
                false
        ));
    }

    /**
     * Verifies that leaf removal to air inside a leaf tick is classified as decay.
     *
     * @throws ReflectiveOperationException if reflection fails during method invocation
     */
    @Test
    void leafRemovalToAirInsideLeafTickIsClassifiedAsDecay() throws ReflectiveOperationException {
        assertTrue(isNaturalLeafDecayTransition(
                true,
                true,
                false,
                true
        ));
    }

    /**
     * Helper method to invoke the private static method {@code chunkis$isNaturalLeafDecayTransition} via reflection.
     *
     * @param leafTickActive      whether the leaf tick is active
     * @param previousIsLeaves    whether the previous block state was leaves
     * @param nextIsLeaves        whether the next block state is leaves
     * @param nextIsAir           whether the next block state is air
     * @return true if the transition is classified as natural leaf decay, false otherwise
     * @throws ReflectiveOperationException if reflection fails during method invocation
     */
    @SuppressWarnings("SameParameterValue")
    private static boolean isNaturalLeafDecayTransition(
            final boolean leafTickActive,
            final boolean previousIsLeaves,
            final boolean nextIsLeaves,
            final boolean nextIsAir) throws ReflectiveOperationException {
        final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.world.chunk.WorldChunkMixin");
        final Method method = mixinClass.getDeclaredMethod(
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
