package io.liparakis.chunkis.integration.c2me;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Verifies that only the unpublished C2ME chunk is excluded from resend checks.
 */
class C2meChunkPromotionTest {

    /**
     * A different chunk, dimension, or worker must retain its ordinary behavior.
     */
    @Test
    void scopeMatchesOnlyTheConstructingChunkOnThisThread() {
        final Object world = new Object();
        final Object result = new Object();
        assertFalse(C2meChunkPromotion.isConstructing(world, 12, -34));
        assertSame(result, C2meChunkPromotion.construct(world, 12, -34, () -> {
            assertTrue(C2meChunkPromotion.isConstructing(world, 12, -34));
            assertFalse(C2meChunkPromotion.isConstructing(world, 13, -34));
            assertFalse(C2meChunkPromotion.isConstructing(world, 12, -35));
            assertFalse(C2meChunkPromotion.isConstructing(new Object(), 12, -34));
            assertFalse(CompletableFuture.supplyAsync(() ->
                    C2meChunkPromotion.isConstructing(world, 12, -34)).join());
            return result;
        }));
        assertFalse(C2meChunkPromotion.isConstructing(world, 12, -34));
    }

    /**
     * Nested construction and exceptions must restore the outer scope and leave no stale suppression.
     */
    @Test
    void nestedFailuresPreserveTheOuterScopeAndPropagateTheOriginalException() {
        final Object world = new Object();
        final IllegalStateException failure = new IllegalStateException("construction failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                C2meChunkPromotion.construct(world, 1, 2, () -> {
                    assertSame(failure, assertThrows(IllegalStateException.class, () ->
                            C2meChunkPromotion.construct(world, 3, 4, () -> {
                                assertTrue(C2meChunkPromotion.isConstructing(world, 1, 2));
                                assertTrue(C2meChunkPromotion.isConstructing(world, 3, 4));
                                throw failure;
                            })));
                    assertTrue(C2meChunkPromotion.isConstructing(world, 1, 2));
                    assertFalse(C2meChunkPromotion.isConstructing(world, 3, 4));
                    throw failure;
                })));
        assertFalse(C2meChunkPromotion.isConstructing(world, 1, 2));
        assertFalse(C2meChunkPromotion.isConstructing(world, 3, 4));
    }
}
