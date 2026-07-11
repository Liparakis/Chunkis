package io.liparakis.chunkis.debug.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.ChunkDelta;
import org.junit.jupiter.api.Test;

class ChunkTraceInvariantsTest {

    /**
     * Performs non empty restore assertion requires replay payload and zero applied count.
     */
    @Test
    void nonEmptyRestoreAssertionRequiresReplayPayloadAndZeroAppliedCount() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.addBlockChange(1, 64, 1, "stone");

        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 0)).isTrue();
        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 1)).isFalse();
        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(new ChunkDelta<>(), 0)).isFalse();
    }

    /**
     * Performs metadata only delta does not trigger non empty restore assertion.
     */
    @Test
    void metadataOnlyDeltaDoesNotTriggerNonEmptyRestoreAssertion() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.setChunkMetadata("metadata", false);

        assertThat(ChunkTraceInvariants.shouldAssertNonEmptyRestore(delta, 0)).isFalse();
    }

    /**
     * Performs block entity only payload without base is invalid.
     */
    @Test
    void blockEntityOnlyPayloadWithoutBaseIsInvalid() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.addBlockEntityData(1, 64, 1, "chest");

        assertThat(ChunkTraceInvariants.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta, false)).isTrue();
        assertThat(ChunkTraceInvariants.hasInvalidBlockEntityOnlyPayloadWithoutBase(delta, true)).isFalse();
    }

    /**
     * Performs snapshot backed restore is not empty when only base was applied.
     */
    @Test
    void snapshotBackedRestoreIsNotEmptyWhenOnlyBaseWasApplied() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>();
        delta.setChunkMetadata("metadata", false);

        assertThat(ChunkTraceInvariants.shouldReportRestoreEmptyResult(delta, 0, true)).isFalse();
        assertThat(ChunkTraceInvariants.shouldReportRestoreEmptyResult(delta, 0, false)).isTrue();
    }
}
