package io.liparakis.chunkis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ChunkDeltaTest {

    /**
     * Performs block changes count tracks stored instructions without double counting updates.
     */
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

    /**
     * A bulk copy carries every instruction across and leaves the target usable for later upserts.
     */
    @Test
    void appendDecodedBlocksFromCopiesEveryInstructionAndKeepsLaterUpsertsWorking() {
        final ChunkDelta<String, String> source = new ChunkDelta<>("air"::equals);
        source.addBlockChange(1, 64, 1, "stone");
        source.addBlockChange(2, 65, 3, "dirt");
        final ChunkDelta<String, String> target = new ChunkDelta<>("air"::equals);

        target.copyBlockPaletteFrom(source);
        target.appendDecodedBlocksFrom(source);
        final Map<Long, String> copied = new LinkedHashMap<>();
        target.forEachBlock((x, y, z, state) -> copied.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(copied).containsExactly(
                Map.entry(BlockInstruction.packPos(1, 64, 1), "stone"),
                Map.entry(BlockInstruction.packPos(2, 65, 3), "dirt"));
        target.addBlockChange(1, 64, 1, "gravel");
        assertThat(target.getBlockChangesCount()).isEqualTo(2);
        assertThat(source.getBlockChangesCount()).isEqualTo(2);
    }

    /**
     * Releasing drops the block instructions of a clean delta and keeps recording later edits, until a capture
     * clears the payload and ends the released state.
     */
    @Test
    void releasedBlockPayloadRecordsLaterEditsUntilCleared() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 64, 1, "stone");
        delta.markSaved();

        delta.releaseBlockPayload();
        assertThat(delta.isBlockPayloadReleased()).isTrue();
        assertThat(delta.getBlockChangesCount()).isZero();
        assertThat(delta.isDirty()).isFalse();

        delta.addBlockChange(2, 64, 2, "dirt");
        assertThat(delta.getBlockChangesCount()).isEqualTo(1);
        assertThat(delta.isBlockPayloadReleased()).isTrue();

        delta.clearBlockPayloads(false);
        assertThat(delta.isBlockPayloadReleased()).isFalse();
    }

    /**
     * Only a clean delta may release its instructions: a dirty one may hold changes found nowhere else.
     */
    @Test
    void releasingADirtyBlockPayloadIsRefused() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 64, 1, "stone");

        org.assertj.core.api.Assertions.assertThatThrownBy(delta::releaseBlockPayload)
                .isInstanceOf(IllegalStateException.class);
        assertThat(delta.getBlockChangesCount()).isEqualTo(1);
    }

    /**
     * Blocks appended in bulk read back exactly like blocks appended one at a time.
     */
    @Test
    void appendDecodedBlocksMatchesSingleAppends() {
        final ChunkDelta<String, String> single = new ChunkDelta<>("air"::equals);
        final ChunkDelta<String, String> bulk = new ChunkDelta<>("air"::equals);
        single.addBlockChange(0, 0, 0, "stone");
        final int[] stoneId = {-1};
        single.forEachBlockInstruction((x, y, z, paletteId, state) -> stoneId[0] = paletteId);
        bulk.copyBlockPaletteFrom(single);
        single.clearBlockPayloads(false);
        single.appendDecodedBlockFast(1, -64, 2, stoneId[0]);
        single.appendDecodedBlockFast(15, 319, 0, stoneId[0]);

        bulk.appendDecodedBlocks(new long[] {ChunkDelta.packDecodedBlock(1, -64, 2, stoneId[0]),
                ChunkDelta.packDecodedBlock(15, 319, 0, stoneId[0]), 0L}, 2);
        final Map<Long, String> expected = new LinkedHashMap<>();
        single.forEachBlock((x, y, z, state) -> expected.put(BlockInstruction.packPos(x, y, z), state));
        final Map<Long, String> actual = new LinkedHashMap<>();
        bulk.forEachBlock((x, y, z, state) -> actual.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(actual).containsExactlyEntriesOf(expected).hasSize(2);
    }

    /**
     * Trimming spare capacity keeps every block and leaves the delta usable for later edits.
     */
    @Test
    void trimBlockCapacityKeepsBlocksAndLaterEdits() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.ensureBlockCapacity(1000);
        delta.addBlockChange(1, 64, 1, "stone");
        delta.addBlockChange(2, 65, 3, "dirt");

        delta.trimBlockCapacity();
        delta.addBlockChange(1, 64, 1, "gravel");
        delta.addBlockChange(4, 70, 4, "sand");
        final Map<Long, String> blocks = new LinkedHashMap<>();
        delta.forEachBlock((x, y, z, state) -> blocks.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(blocks).containsExactly(
                Map.entry(BlockInstruction.packPos(1, 64, 1), "gravel"),
                Map.entry(BlockInstruction.packPos(2, 65, 3), "dirt"),
                Map.entry(BlockInstruction.packPos(4, 70, 4), "sand"));
    }

    /**
     * Performs snapshot append stores blocks without needing the general mutation path.
     */
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

    /**
     * Performs snapshot append rebuilds lookup state when later mutation needs upsert.
     */
    @Test
    void snapshotAppendRebuildsLookupStateWhenLaterMutationNeedsUpsert() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        final Map<Long, String> blocks = new LinkedHashMap<>();

        delta.appendSnapshotBlockChange(1, 64, 1, "stone");
        delta.appendSnapshotBlockChange(2, 65, 3, "dirt");

        delta.addBlockChange(1, 64, 1, "grass");

        delta.forEachBlock((x, y, z, state) -> blocks.put(BlockInstruction.packPos(x, y, z), state));

        assertThat(delta.getBlockChangesCount()).isEqualTo(2);
        assertThat(blocks).containsExactly(
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(1, 64, 1), "grass"),
                org.assertj.core.data.MapEntry.entry(BlockInstruction.packPos(2, 65, 3), "dirt")
        );
    }

    /**
     * Performs snapshot view preserves readable payload without mutable block index state.
     */
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

    /**
     * Performs touched section count tracks blocks and block entities without scanning.
     */
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
