package io.liparakis.chunkis.world.tracking.save;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalescing FIFO queue for pending async saves belonging to one world.
 */
final class PendingSaveQueue {

    private final Object monitor = new Object();
    private final LinkedHashMap<Long, AsyncCisSaveWorker.PendingSave> pending = new LinkedHashMap<>();
    private boolean closed;

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

    AsyncCisSaveWorker.PendingSave poll() {
        synchronized (monitor) {
            while (pending.isEmpty()) {
                if (closed) {
                    return null;
                }
                try {
                    monitor.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            final Map.Entry<Long, AsyncCisSaveWorker.PendingSave> head =
                    pending.entrySet().iterator().next();
            pending.remove(head.getKey());
            return head.getValue();
        }
    }

    void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

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
                        save.liveDelta().isDirty()
                ));
            }
            return snapshots;
        }
    }
}
