package io.liparakis.chunkis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
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
}
