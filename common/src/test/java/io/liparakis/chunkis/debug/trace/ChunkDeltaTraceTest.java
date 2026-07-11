package io.liparakis.chunkis.debug.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChunkDeltaTraceTest {

    /**
     * Performs tear down.
     */
    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Performs emits dirty and clean events when debug lifecycle is enabled.
     */
    @Test
    void emitsDirtyAndCleanEventsWhenDebugLifecycleIsEnabled() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        delta.addBlockChange(1, 64, 2, "stone");
        delta.markSaved();

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(10);
        assertThat(latest).extracting(ChunkTraceEvent::eventType)
                .containsExactly(
                        ChunkTraceEventType.DELTA_MARKED_CLEAN,
                        ChunkTraceEventType.DELTA_MARKED_DIRTY
                );
    }
}
