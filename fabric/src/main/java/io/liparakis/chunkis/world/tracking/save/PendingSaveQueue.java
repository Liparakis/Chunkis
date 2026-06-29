package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalescing FIFO queue for pending async saves belonging to one world.
 */
final class PendingSaveQueue {

    /**
     * Monitor synchronization lock object.
     */
    private final Object monitor = new Object();

    /**
     * Backing insertion-ordered map coalescing pending saves by chunk coordinates posKey.
     */
    private final LinkedHashMap<Long, AsyncCisSaveWorker.PendingSave> pending = new LinkedHashMap<>();

    /**
     * True if the queue has been closed and should reject further saves.
     */
    private boolean closed;

    /**
     * Default constructor.
     */
    PendingSaveQueue() {
    }

    /**
     * Submits a pending save to the queue, coalescing any duplicates.
     *
     * @param save target pending save task details
     */
    void submit(final AsyncCisSaveWorker.PendingSave save) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            pending.remove(save.posKey());
            pending.put(save.posKey(), save);
            monitor.notify();
        }
    }

    /**
     * Polls the next pending save task from the queue, blocking if empty.
     *
     * @return next save task or null if closed
     */
    AsyncCisSaveWorker.PendingSave poll() {
        synchronized (monitor) {
            while (pending.isEmpty()) {
                if (closed) {
                    return null;
                }
                try {
                    monitor.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread()
                            .interrupt();
                    return null;
                }
            }

            final Map.Entry<Long, AsyncCisSaveWorker.PendingSave> head =
                    pending.entrySet()
                            .iterator()
                            .next();
            pending.remove(head.getKey());
            return head.getValue();
        }
    }

    /**
     * Closes the queue and wakes up any threads blocked in poll.
     */
    void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    /**
     * Generates a diagnostic snapshot map of all current queued tasks.
     *
     * @return map of pending save snapshots keyed by coordinate keys
     */
    Map<DebugChunkKey, AsyncCisSaveManager.PendingSaveSnapshot> snapshot() {
        synchronized (monitor) {
            final Map<DebugChunkKey, AsyncCisSaveManager.PendingSaveSnapshot> snapshots =
                    new LinkedHashMap<>(pending.size());
            for (final AsyncCisSaveWorker.PendingSave save : pending.values()) {
                final DebugChunkKey chunkKey = new DebugChunkKey(save.pos().x, save.pos().z);
                snapshots.put(chunkKey, new AsyncCisSaveManager.PendingSaveSnapshot(
                        chunkKey,
                        save.operationId(),
                        save.generation(),
                        save.liveDelta()
                                .isDirty()
                ));
            }
            return snapshots;
        }
    }
}
