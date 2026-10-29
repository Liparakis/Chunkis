package io.liparakis.chunkis.world.tracking.save;

import java.util.Set;
import java.util.HashSet;
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
     * Positions a worker thread is saving right now. A newer save of the same chunk waits for it, so two saves of
     * one chunk never run at once and the older can never overwrite the newer.
     */
    private final Set<Long> inFlight = new HashSet<>();

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
            monitor.notifyAll();
        }
    }

    /**
     * Polls the next pending save task from the queue, blocking if empty.
     *
     * @return next save task or null if closed
     */
    AsyncCisSaveWorker.PendingSave poll() {
        synchronized (monitor) {
            while (true) {
                for (final Map.Entry<Long, AsyncCisSaveWorker.PendingSave> entry : pending.entrySet()) {
                    if (inFlight.add(entry.getKey())) {
                        pending.remove(entry.getKey());
                        return entry.getValue();
                    }
                }
                if (pending.isEmpty() && closed) {
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
        }
    }

    /**
     * Marks a polled save finished, letting a newer save of the same chunk run.
     *
     * @param posKey chunk position key of the finished save
     */
    void done(final long posKey) {
        synchronized (monitor) {
            inFlight.remove(posKey);
            monitor.notifyAll();
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
