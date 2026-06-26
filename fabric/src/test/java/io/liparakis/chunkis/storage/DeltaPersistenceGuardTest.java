package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaPersistenceGuardTest {

    @Test
    void rejectsCurrentVersionBlockEntityOnlyPayloadWithoutBase() {
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.setSourceVersion(io.liparakis.chunkis.storage.model.CisConstants.VERSION);
        delta.addBlockEntityData(1, 64, 1, new NbtCompound());

        assertTrue(DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta));
        assertTrue(DeltaPersistenceGuard.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta));
    }

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
}
