package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import java.lang.reflect.Method;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Test class for save path and delta claiming behavior in {@code ThreadedAnvilChunkStorageMixin}.
 */
final class ThreadedAnvilChunkStorageMixinSavePathTest {

    /**
     * Helper method to invoke the private static method {@code chunkis$claimEntityCapturedDelta} in {@code ThreadedAnvilChunkStorageMixin} via reflection.
     *
     * @param delta the {@link ChunkDelta} to claim ownership of
     * @return the claimed {@link ChunkDelta}
     * @throws Exception if reflection or execution fails
     */
    private static ChunkDelta<BlockState, NbtCompound> invokeClaimAfterEntityCapture(
            final ChunkDelta<BlockState, NbtCompound> delta) throws Exception {
        final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.storage.ThreadedAnvilChunkStorageMixin");
        final Method method = mixinClass.getDeclaredMethod(
                "chunkis$claimEntityCapturedDelta",
                ChunkDelta.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        final ChunkDelta<BlockState, NbtCompound> claimed =
                (ChunkDelta<BlockState, NbtCompound>) method.invoke(
                        null,
                        delta,
                        "EXPLICIT_CHUNKIS_MUTATION",
                        "test"
                );
        return claimed;
    }

    /**
     * Tests that a null delta is handled gracefully, returning null without throwing.
     *
     */
    @Test
    void skippedEntityCaptureLeavesMissingDeltaNull() {
        assertDoesNotThrow(() -> assertNull(invokeClaimAfterEntityCapture(null)));
    }

    /**
     * Tests that a non-null delta successfully claims ownership when captured.
     *
     * @throws Exception if reflection or execution fails
     */
    @Test
    void capturedEntityDeltaIsClaimedWhenPresent() throws Exception {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();

        final ChunkDelta<BlockState, NbtCompound> claimed = invokeClaimAfterEntityCapture(delta);

        assertSame(delta, claimed);
        assertTrue(delta.hasOwnershipClaim());
    }
}
