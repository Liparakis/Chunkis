package io.liparakis.chunkis.core;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * Tracks the ownership and dirty state metadata for a ChunkDelta.
 */
final class DeltaOwnershipState {

    /**
     * Atomic increment for {@link #mutationGeneration}, which stays a plain volatile field for reads.
     */
    private static final AtomicLongFieldUpdater<DeltaOwnershipState> MUTATION_GENERATION =
            AtomicLongFieldUpdater.newUpdater(DeltaOwnershipState.class, "mutationGeneration");

    /**
     * Stores ownership reason.
     */
    String ownershipReason;
    /**
     * Stores ownership source.
     */
    String ownershipSource;
    /**
     * Stores first mutation source.
     */
    String firstMutationSource;
    /**
     * Stores pending mutation source.
     */
    String pendingMutationSource;
    /**
     * Generation bumped by every dirtying mutation (server thread). Volatile because the
     * async save worker reads it for its staleness pre-check; all writes stay on the server thread.
     */
    volatile long mutationGeneration;
    /**
     * Generation last persisted; written only on the server thread.
     */
    volatile long savedGeneration;

    /**
     * Performs delta ownership state.
     */
    DeltaOwnershipState() {
        this.ownershipReason = null;
        this.ownershipSource = null;
        this.firstMutationSource = null;
        this.pendingMutationSource = null;
        this.mutationGeneration = 0L;
        this.savedGeneration = 0L;
    }

    /**
     * Bumps {@link #mutationGeneration} atomically, so no increment is lost even if a second writer
     * thread ever appears.
     */
    void incrementMutationGeneration() {
        MUTATION_GENERATION.incrementAndGet(this);
    }
}
