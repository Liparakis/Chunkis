package io.liparakis.chunkis.core;

import static org.assertj.core.api.Assertions.assertThat;

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
}
