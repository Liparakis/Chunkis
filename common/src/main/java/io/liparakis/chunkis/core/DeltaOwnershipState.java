package io.liparakis.chunkis.core;

/**
 * Tracks the ownership and dirty state metadata for a ChunkDelta.
 */
final class DeltaOwnershipState {

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
     * Stores mutation generation.
     */
    long mutationGeneration;
    /**
     * Stores saved generation.
     */
    long savedGeneration;

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
}
