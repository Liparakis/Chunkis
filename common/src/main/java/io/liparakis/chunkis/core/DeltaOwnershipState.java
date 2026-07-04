package io.liparakis.chunkis.core;

/**
 * Tracks the ownership and dirty state metadata for a ChunkDelta.
 */
final class DeltaOwnershipState {

    String ownershipReason;
    String ownershipSource;
    String firstMutationSource;
    String pendingMutationSource;
    long mutationGeneration;
    long savedGeneration;

    DeltaOwnershipState() {
        this.ownershipReason = null;
        this.ownershipSource = null;
        this.firstMutationSource = null;
        this.pendingMutationSource = null;
        this.mutationGeneration = 0L;
        this.savedGeneration = 0L;
    }
}
