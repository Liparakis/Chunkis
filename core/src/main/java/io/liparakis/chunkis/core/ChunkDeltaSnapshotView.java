package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Read-only snapshot used by async persistence paths.
 *
 * @param <S> block state type
 * @param <N> payload type
 */
public final class ChunkDeltaSnapshotView<S, N> implements ChunkDeltaView<S, N> {

    /**
     * Dense instruction array copied up to {@link #instructionCount}.
     */
    private final long[] packedInstructions;
    /**
     * Number of valid entries in {@link #packedInstructions}.
     */
    private final int instructionCount;
    /**
     * Copied palette used to decode palette ids in {@link #packedInstructions}.
     */
    private final Palette<S> blockPalette;
    /**
     * Copied block entity payloads keyed by packed local position.
     */
    private final Long2ObjectOpenHashMap<N> blockEntities;
    /**
     * Copied active entity payloads keyed by runtime entity id.
     */
    private final Int2ObjectOpenHashMap<N> activeEntities;
    /**
     * Copied pending entity payloads that have not been spawned yet.
     */
    private final List<N> pendingEntities;
    /**
     * Copied chunk-level metadata payload.
     */
    private final N chunkMetadata;
    /**
     * CIS version captured from the source delta.
     */
    private final int sourceVersion;
    /**
     * Mutation generation captured from the source delta.
     */
    private final long mutationGeneration;
    /**
     * Replay-time repopulation suppression flag captured from the source delta.
     */
    private final boolean suppressInitialRepopulation;
    /**
     * Dirty state captured from the source delta at snapshot time.
     */
    private final boolean dirty;
    /**
     * Unique chunk sections touched by block changes or block entities.
     */
    private final int touchedSectionCount;
    /**
     * Cached encoded metadata payload owned by this snapshot view.
     */
    private byte[] encodedChunkMetadata;

    /**
     * Creates a detached read-only snapshot for async persistence.
     *
     * <p>This copies only the payload needed by encode/save paths and intentionally
     * skips mutable indexing structures such as {@code positionMap}.</p>
     *
     * @param packedInstructions          sparse block instruction buffer from the source delta
     * @param instructionCount            number of valid entries in {@code packedInstructions}
     * @param blockPalette                source block palette
     * @param blockEntities               source block entity payloads
     * @param activeEntities              source active entity payloads
     * @param pendingEntities             source pending entity payloads
     * @param chunkMetadata               source chunk metadata payload
     * @param encodedChunkMetadata        cached encoded metadata payload
     * @param sourceVersion               source CIS version
     * @param mutationGeneration          source mutation generation
     * @param suppressInitialRepopulation source repopulation suppression flag
     * @param dirty                       source dirty state
     * @param touchedSectionCount         source touched section count
     * @param payloadCopier               payload copy strategy for mutable payload values
     */
    ChunkDeltaSnapshotView(
            final long[] packedInstructions,
            final int instructionCount,
            final Palette<S> blockPalette,
            final Long2ObjectOpenHashMap<N> blockEntities,
            final Int2ObjectOpenHashMap<N> activeEntities,
            final List<N> pendingEntities,
            final N chunkMetadata,
            final byte[] encodedChunkMetadata,
            final int sourceVersion,
            final long mutationGeneration,
            final boolean suppressInitialRepopulation,
            final boolean dirty,
            final int touchedSectionCount,
            final UnaryOperator<N> payloadCopier
    ) {
        Objects.requireNonNull(payloadCopier, "payloadCopier");

        this.packedInstructions = Arrays.copyOf(packedInstructions, instructionCount);
        this.instructionCount = instructionCount;
        this.blockPalette = blockPalette.copy();

        if (blockEntities != null && !blockEntities.isEmpty()) {
            this.blockEntities = new Long2ObjectOpenHashMap<>(blockEntities.size());
            for (final Long2ObjectMap.Entry<N> entry : blockEntities.long2ObjectEntrySet()) {
                this.blockEntities.put(entry.getLongKey(), payloadCopier.apply(entry.getValue()));
            }
        } else {
            this.blockEntities = new Long2ObjectOpenHashMap<>(0);
        }

        if (activeEntities != null && !activeEntities.isEmpty()) {
            this.activeEntities = new Int2ObjectOpenHashMap<>(activeEntities.size());
            for (final int entityId : activeEntities.keySet()) {
                this.activeEntities.put(entityId, payloadCopier.apply(activeEntities.get(entityId)));
            }
        } else {
            this.activeEntities = null;
        }

        if (pendingEntities == null || pendingEntities.isEmpty()) {
            this.pendingEntities = Collections.emptyList();
        } else {
            final List<N> copied = new ArrayList<>(pendingEntities.size());
            for (final N payload : pendingEntities) {
                copied.add(payloadCopier.apply(payload));
            }
            this.pendingEntities = copied;
        }

        this.chunkMetadata = chunkMetadata != null ? payloadCopier.apply(chunkMetadata) : null;
        this.encodedChunkMetadata = encodedChunkMetadata != null
                ? Arrays.copyOf(encodedChunkMetadata, encodedChunkMetadata.length)
                : null;
        this.sourceVersion = sourceVersion;
        this.mutationGeneration = mutationGeneration;
        this.suppressInitialRepopulation = suppressInitialRepopulation;
        this.dirty = dirty;
        this.touchedSectionCount = touchedSectionCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachBlock(final ChunkDelta.BlockVisitor<S> visitor) {
        ChunkDeltaViews.forEachPackedBlock(packedInstructions, instructionCount, blockPalette, visitor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long2ObjectMap<N> getBlockEntities() {
        return blockEntities;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachEntity(final Consumer<? super N> consumer) {
        ChunkDeltaViews.forEachEntity(activeEntities, pendingEntities, consumer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int countNonNullEntities() {
        return ChunkDeltaViews.countNonNullEntities(activeEntities, pendingEntities);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBlockChangesCount() {
        return instructionCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getTouchedSectionCount() {
        return touchedSectionCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public N getChunkMetadata() {
        return chunkMetadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getEncodedChunkMetadata() {
        return encodedChunkMetadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cacheEncodedChunkMetadata(final byte[] encodedPayload) {
        encodedChunkMetadata = encodedPayload == null
                ? null
                : Arrays.copyOf(encodedPayload, encodedPayload.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSourceVersion() {
        return sourceVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMutationGeneration() {
        return mutationGeneration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldSuppressInitialRepopulation() {
        return suppressInitialRepopulation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDirty() {
        return dirty;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return ChunkDeltaViews.isEmpty(
                instructionCount,
                blockEntities,
                activeEntities,
                pendingEntities,
                chunkMetadata
        );
    }
}
