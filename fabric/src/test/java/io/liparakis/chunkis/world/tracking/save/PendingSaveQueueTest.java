package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PendingSaveQueue} under several worker threads: a chunk is never handed out twice at once, so an
 * older save of it can never overwrite a newer one.
 */
class PendingSaveQueueTest {

    /**
     * A newer save of a chunk still being written waits until that write is done, while other chunks are handed
     * out meanwhile.
     *
     * @throws Exception on timeout
     */
    @Test
    void chunkInFlightIsNotHandedOutAgainUntilDone() throws Exception {
        final PendingSaveQueue queue = new PendingSaveQueue();
        final AsyncCisSaveWorker.PendingSave first = save(1, 0, 1);
        queue.submit(first);
        assertSame(first, queue.poll());

        final AsyncCisSaveWorker.PendingSave newer = save(1, 0, 2);
        final AsyncCisSaveWorker.PendingSave other = save(2, 0, 1);
        queue.submit(newer);
        queue.submit(other);
        assertSame(other, queue.poll());

        final CompletableFuture<AsyncCisSaveWorker.PendingSave> waiting = CompletableFuture.supplyAsync(queue::poll);
        Thread.sleep(100);
        assertEquals(false, waiting.isDone());
        queue.done(first.posKey());
        assertSame(newer, waiting.get(5, TimeUnit.SECONDS));

        queue.done(newer.posKey());
        queue.done(other.posKey());
        queue.close();
        assertNull(queue.poll());
    }

    /**
     * Builds a save carrying only what the queue reads.
     *
     * @param x          chunk x
     * @param z          chunk z
     * @param generation save generation, to tell saves apart
     * @return the save
     */
    private static AsyncCisSaveWorker.PendingSave save(final int x, final int z, final long generation) {
        // The queue reads only the position key; no Minecraft class is needed (or bootstrapped) here.
        return new AsyncCisSaveWorker.PendingSave(null, null, null, null, null, generation, 0L, "test",
                ((long) z << 32) | (x & 0xFFFFFFFFL));
    }
}
