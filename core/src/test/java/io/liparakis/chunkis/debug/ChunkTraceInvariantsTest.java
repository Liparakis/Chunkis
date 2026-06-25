package io.liparakis.chunkis.debug;

import io.liparakis.chunkis.core.ChunkDelta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTraceInvariantsTest {

    @Test
    void nonEmptyRestoreAssertionRequiresReplayPayloadAndZeroAppliedCount() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");

        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 0)).isTrue();
        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 1)).isFalse();
        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(new ChunkDelta<>(), 0)).isFalse();
    }

    @Test
    void metadataOnlyDeltaDoesNotTriggerNonEmptyRestoreAssertion() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.setChunkMetadata("metadata", false);

        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 0)).isFalse();
    }
}
