package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared read-side helpers for {@link ChunkDelta} and {@link ChunkDeltaSnapshotView}.
 *
 * <p>These methods centralize the hot-path traversal logic used by both the
 * mutable delta and the detached snapshot view.</p>
 */
final class ChunkDeltaViews {

    /**
     * Mask for the packed local block position stored in the low 32 bits of an
     * instruction.
     */
    private static final long POSITION_MASK = 0xFFFF_FFFFL;

    /**
     * Utility class.
     */
    private ChunkDeltaViews() {
    }

    /**
     * Decodes and visits packed sparse block instructions.
     *
     * @param packedInstructions dense instruction buffer
     * @param instructionCount   number of valid entries in {@code packedInstructions}
     * @param blockPalette       palette used to resolve state ids
     * @param visitor            block visitor
     * @param <S>                block state type
     */
    static <S> void forEachPackedBlock(
            final long[] packedInstructions,
            final int instructionCount,
            final Palette<S> blockPalette,
            final ChunkDelta.BlockVisitor<S> visitor
    ) {
        Objects.requireNonNull(visitor, "visitor");

        for (int i = 0; i < instructionCount; i++) {
            final long instruction = packedInstructions[i];
            final int paletteIndex = (int) (instruction >>> Integer.SIZE);
            final S state = blockPalette.get(paletteIndex);

            if (state == null) {
                continue;
            }

            final long posKey = instruction & POSITION_MASK;
            visitor.visitBlock(
                    BlockInstruction.unpackX(posKey),
                    BlockInstruction.unpackY(posKey),
                    BlockInstruction.unpackZ(posKey),
                    state
            );
        }
    }

    /**
     * Visits active entities first, then pending entities.
     *
     * @param activeEntities  active entity payloads keyed by runtime id
     * @param pendingEntities pending entity payloads
     * @param consumer        entity payload consumer
     * @param <N>             payload type
     */
    static <N> void forEachEntity(
            final Int2ObjectOpenHashMap<N> activeEntities,
            final List<N> pendingEntities,
            final Consumer<? super N> consumer
    ) {
        Objects.requireNonNull(consumer, "consumer");

        if (activeEntities != null) {
            for (final N nbt : activeEntities.values()) {
                consumer.accept(nbt);
            }
        }

        for (final N nbt : pendingEntities) {
            consumer.accept(nbt);
        }
    }

    /**
     * Counts non-null entity payloads across active and pending collections.
     *
     * @param activeEntities  active entity payloads keyed by runtime id
     * @param pendingEntities pending entity payloads
     * @param <N>             payload type
     * @return number of non-null entity payloads
     */
    static <N> int countNonNullEntities(final Int2ObjectOpenHashMap<N> activeEntities, final List<N> pendingEntities) {
        int count = 0;

        if (activeEntities != null) {
            for (final N nbt : activeEntities.values()) {
                if (nbt != null) {
                    count++;
                }
            }
        }

        for (final N nbt : pendingEntities) {
            if (nbt != null) {
                count++;
            }
        }

        return count;
    }

    /**
     * Returns whether a delta/view carries no block, entity, or metadata payload.
     *
     * @param blockChangesCount number of sparse block changes
     * @param blockEntities     block entity payloads keyed by packed position
     * @param activeEntities    active entity payloads keyed by runtime id
     * @param pendingEntities   pending entity payloads
     * @param chunkMetadata     chunk-level metadata payload
     * @param <N>               payload type
     * @return {@code true} if no payload is present
     */
    static <N> boolean isEmpty(
            final int blockChangesCount,
            final Long2ObjectMap<N> blockEntities,
            final Int2ObjectOpenHashMap<N> activeEntities,
            final List<N> pendingEntities,
            final N chunkMetadata
    ) {
        return blockChangesCount == 0
                && (blockEntities == null || blockEntities.isEmpty())
                && (activeEntities == null || activeEntities.isEmpty())
                && pendingEntities.isEmpty()
                && chunkMetadata == null;
    }
}
