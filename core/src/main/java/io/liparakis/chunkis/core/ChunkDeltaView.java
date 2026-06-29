package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.Consumer;

/**
 * Read-only view of chunk delta payloads used by encode/persistence paths.
 *
 * @param <S> block state type
 * @param <N> payload type
 */
public interface ChunkDeltaView<S, N> {

    /**
     * Visits sparse block changes without materializing {@link BlockInstruction} objects.
     *
     * @param visitor block visitor
     */
    void forEachBlock(ChunkDelta.BlockVisitor<S> visitor);

    /**
     * Returns block entity payloads keyed by packed local block position.
     *
     * @return block entity payload map
     */
    Long2ObjectMap<N> getBlockEntities();

    /**
     * Visits entity payloads in implementation-defined active/pending order.
     *
     * @param consumer entity payload consumer
     */
    void forEachEntity(Consumer<? super N> consumer);

    /**
     * Counts non-null entity payloads without materializing an intermediate list.
     *
     * @return number of non-null entity payloads
     */
    int countNonNullEntities();

    /**
     * Returns the number of stored block changes.
     *
     * @return block change count
     */
    int getBlockChangesCount();

    /**
     * Returns chunk-level metadata payload.
     *
     * @return metadata payload, or {@code null}
     */
    N getChunkMetadata();

    /**
     * Returns the cached encoded metadata payload, if available.
     *
     * @return encoded metadata payload, or {@code null}
     */
    byte[] getEncodedChunkMetadata();

    /**
     * Caches an encoded metadata payload for later reuse by persistence code.
     *
     * @param encodedPayload encoded metadata payload, or {@code null} to clear
     */
    void cacheEncodedChunkMetadata(byte[] encodedPayload);

    /**
     * Returns the CIS format version associated with this view.
     *
     * @return CIS source version
     */
    int getSourceVersion();

    /**
     * Returns the mutation generation captured by this view.
     *
     * @return mutation generation
     */
    long getMutationGeneration();

    /**
     * Returns whether reload should suppress one-time repopulation work.
     *
     * @return {@code true} if suppression is enabled
     */
    boolean shouldSuppressInitialRepopulation();

    /**
     * Returns whether the underlying delta/view represents unsaved changes.
     *
     * @return {@code true} if dirty
     */
    boolean isDirty();

    /**
     * Returns whether this view carries no block, entity, or metadata payload.
     *
     * @return {@code true} if empty
     */
    boolean isEmpty();
}
