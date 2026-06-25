package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldChunkMixinTest {

    @Test
    void takesRestoreOperationIdFromDuckAndClearsIt() {
        final FakeChunkisDeltaDuck duck = new FakeChunkisDeltaDuck();
        duck.chunkis$setRestoreOperationId("load-42");

        assertEquals("load-42", WorldChunkMixin.chunkis$takeRestoreOperationId(duck));
        assertNull(duck.chunkis$getRestoreOperationId());
    }

    private static final class FakeChunkisDeltaDuck implements ChunkisDeltaDuck {

        private final ChunkDelta<?, ?> delta = new ChunkDelta<>();
        private String restoreOperationId;

        @Override
        public ChunkDelta<?, ?> chunkis$getDelta() {
            return delta;
        }

        @Override
        public void chunkis$setDelta(final ChunkDelta<?, ?> delta) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chunkis$getRestoreOperationId() {
            return restoreOperationId;
        }

        @Override
        public void chunkis$setRestoreOperationId(final String operationId) {
            this.restoreOperationId = operationId;
        }
    }
}
