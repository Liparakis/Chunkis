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

final class ThreadedAnvilChunkStorageMixinSavePathTest {

    private static ChunkDelta<BlockState, NbtCompound> invokeClaimAfterEntityCapture(
            final ChunkDelta<BlockState, NbtCompound> delta) throws Exception {
        final Method method = ThreadedAnvilChunkStorageMixin.class.getDeclaredMethod(
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

    @Test
    void skippedEntityCaptureLeavesMissingDeltaNull() throws Exception {
        assertDoesNotThrow(() -> assertNull(invokeClaimAfterEntityCapture(null)));
    }

    @Test
    void capturedEntityDeltaIsClaimedWhenPresent() throws Exception {
        final ChunkDelta<BlockState, NbtCompound> delta = new ChunkDelta<>();

        final ChunkDelta<BlockState, NbtCompound> claimed = invokeClaimAfterEntityCapture(delta);

        assertSame(delta, claimed);
        assertTrue(delta.hasOwnershipClaim());
    }
}
