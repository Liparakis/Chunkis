package io.liparakis.chunkis.world.tracking.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeltaPersistenceGuard}, verifying validation rules for delta persistence
 * under various combinations of payloads (block entity, entity, block changes) and base chunk status.
 */
class DeltaPersistenceGuardTest {

    /**
     * Verifies that a delta with only block entity changes, targeting the current version,
     * is rejected if it lacks a base chunk baseline.
     */
    @Test
    void rejectsCurrentVersionBlockEntityOnlyPayloadWithoutBase() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.setSourceVersion(io.liparakis.chunkis.storage.model.CisConstants.VERSION);
        delta.addBlockEntityData(1, 64, 1, new NbtCompound());

        assertTrue(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
        assertTrue(DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta));
    }

    /**
     * Verifies that a delta containing only block entity changes is allowed
     * when a persisted base chunk baseline is present in its metadata.
     */
    @Test
    void allowsBlockEntityOnlyPayloadWhenPersistedBaseExists() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockEntityData(1, 64, 1, new NbtCompound());
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        false,
                        CisNbtUtil.createBaseNbt(1, 1, 1)
                                                             ),
                false
                              );

        assertFalse(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
        assertFalse(DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta));
    }

    /**
     * Verifies that a delta with block changes is allowed if it is configured
     * with full baseline metadata.
     */
    @Test
    void allowsFullSnapshotPayloadWhenFullBaselineMetadataExists() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        true,
                        null
                                                             ),
                false
                              );

        assertFalse(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
    }

    /**
     * Verifies that a delta with block changes but no base metadata is allowed
     * when checked with the authoritative flag set to true, but rejected otherwise.
     */
    @Test
    void allowsV11AuthoritativeSnapshotPayloadWithoutBaseMetadata() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.setSourceVersion(io.liparakis.chunkis.storage.model.CisConstants.VERSION);
        delta.addBlockChange(1, 64, 1, "stone");

        assertFalse(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta, true));
        assertTrue(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
    }

    /**
     * Verifies that a delta containing only entities is rejected if it does not
     * have a base chunk baseline.
     */
    @Test
    void rejectsEntityOnlyPayloadWithoutBase() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addPendingEntity(new NbtCompound());

        assertTrue(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
    }

    /**
     * Verifies that a delta containing only entities is allowed when a persisted
     * base chunk baseline is present in its metadata.
     */
    @Test
    void allowsEntityOnlyPayloadWhenPersistedBaseExists() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addPendingEntity(new NbtCompound());
        delta.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        true,
                        false,
                        CisNbtUtil.createBaseNbt(1, 1, 1)
                                                             ),
                false
                              );

        assertFalse(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
    }
}
