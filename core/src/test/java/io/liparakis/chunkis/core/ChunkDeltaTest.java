package io.liparakis.chunkis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ChunkDeltaTest {

    @Test
    void blockChangesCountTracksStoredInstructionsWithoutDoubleCountingUpdates() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        assertThat(delta.getBlockChangesCount()).isZero();

        delta.addBlockChange(1, 64, 1, "stone");
        assertThat(delta.getBlockChangesCount()).isEqualTo(1);

        delta.addBlockChange(1, 64, 1, "dirt");
        assertThat(delta.getBlockChangesCount()).isEqualTo(1);

        delta.addBlockChange(2, 64, 1, "stone");
        assertThat(delta.getBlockChangesCount()).isEqualTo(2);

        delta.clearBlockPayloads(false);
        assertThat(delta.getBlockChangesCount()).isZero();
    }

    @Test
    void snapshotAppendStoresBlocksWithoutNeedingTheGeneralMutationPath() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        final Map<Long, String> blocks = new LinkedHashMap<>();

        delta.ensureBlockCapacity(2);
        delta.appendSnapshotBlockChange(1, 64, 1, "stone");
        delta.appendSnapshotBlockChange(2, 65, 3, "dirt");

        delta.forEachBlock((x, y, z, state) -> blocks.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(delta.getBlockChangesCount()).isEqualTo(2);
        assertThat(blocks).containsExactly(
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(1, 64, 1), "stone"),
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(2, 65, 3), "dirt")
        );
    }

    @Test
    void snapshotViewPreservesReadablePayloadWithoutMutableBlockIndexState() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        final Map<Long, String> blocks = new LinkedHashMap<>();

        delta.addBlockChange(1, 64, 1, "stone");
        delta.addBlockChange(2, 65, 3, "dirt");
        delta.addBlockEntityData(1, 64, 1, "chest");
        delta.addPendingEntity("zombie");

        final ChunkDeltaView<String, String> snapshot = delta.snapshotView(UnaryOperator.identity());

        snapshot.forEachBlock((x, y, z, state) -> blocks.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(snapshot.getBlockChangesCount()).isEqualTo(2);
        assertThat(snapshot.getBlockEntities()).hasSize(1);
        assertThat(snapshot.countNonNullEntities()).isEqualTo(1);
        assertThat(snapshot.getTouchedSectionCount()).isEqualTo(1);
        assertThat(blocks).containsExactly(
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(1, 64, 1), "stone"),
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(2, 65, 3), "dirt")
        );
    }

    @Test
    void touchedSectionCountTracksBlocksAndBlockEntitiesWithoutScanning() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        delta.addBlockChange(1, 64, 1, "stone");
        delta.addBlockChange(1, 96, 1, "dirt");
        delta.addBlockEntityData(1, 128, 1, "chest");

        assertThat(delta.getTouchedSectionCount()).isEqualTo(3);

        delta.removeBlockEntityData(1, 128, 1, false);
        assertThat(delta.getTouchedSectionCount()).isEqualTo(2);

        delta.clearBlockPayloads(false);
        assertThat(delta.getTouchedSectionCount()).isZero();
    }
}
