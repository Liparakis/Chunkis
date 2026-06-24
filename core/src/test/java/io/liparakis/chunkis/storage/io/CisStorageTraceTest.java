package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceStore;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.storage.mapping.PropertyPacker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CisStorageTraceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void emitsSaveAndLoadStorageEventsWhenLifecycleDebugIsEnabled() throws Exception {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );
        Files.createDirectories(tempDir.resolve("regions"));

        final CisStorage<String, String, String, String> storage =
                new CisStorage<>(tempDir.resolve("regions"), mapping, stateAdapter, new TestNbtAdapter(), "air");

        final CisChunkPos pos = new CisChunkPos(3, -2);
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        delta.addBlockChange(1, 70, 1, "stone");
        final String saveOperationId = "save-op-1";
        final String loadOperationId = "load-op-1";

        assertThat(storage.save(pos, delta, saveOperationId)).isTrue();
        storage.load(pos, loadOperationId);
        storage.close();

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(20);
        final List<ChunkTraceEventType> eventTypes = events.stream().map(ChunkTraceEvent::eventType).toList();

        assertThat(eventTypes).contains(
                ChunkTraceEventType.SAVE_FLUSH_STARTED,
                ChunkTraceEventType.REGION_WRITE_TX_START,
                ChunkTraceEventType.REGION_WRITE_TX_END,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceEventType.REGION_READ_TX_START,
                ChunkTraceEventType.REGION_READ_TX_END
        );
        assertThat(events)
                .filteredOn(event -> saveOperationId.equals(event.operationId()))
                .extracting(ChunkTraceEvent::eventType)
                .contains(
                        ChunkTraceEventType.SAVE_TX_START,
                        ChunkTraceEventType.SAVE_FLUSH_STARTED,
                        ChunkTraceEventType.REGION_WRITE_TX_START,
                        ChunkTraceEventType.REGION_WRITE_TX_END,
                        ChunkTraceEventType.SAVE_FLUSH_COMPLETED
                );
        assertThat(events)
                .filteredOn(event -> loadOperationId.equals(event.operationId()))
                .extracting(ChunkTraceEvent::eventType)
                .contains(
                        ChunkTraceEventType.LOAD_TX_START,
                        ChunkTraceEventType.REGION_READ_TX_START,
                        ChunkTraceEventType.REGION_READ_TX_END,
                        ChunkTraceEventType.LOAD_TX_END
                );
    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {
        @Override
        public String getId(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String id) {
            return id;
        }

        @Override
        public String getAir() {
            return "air";
        }

        @Override
        public Collection<String> getRegisteredBlocks() {
            return List.of("air", "stone");
        }
    }

    private static final class TestBlockStateAdapter implements BlockStateAdapter<String, String, String> {
        @Override
        public String getDefaultState(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String state) {
            return state;
        }

        @Override
        public List<String> getProperties(final String block) {
            return List.of();
        }

        @Override
        public String getPropertyName(final String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(final String property) {
            return List.of();
        }

        @Override
        public int getValueIndex(final String state, final String property) {
            return 0;
        }

        @Override
        public String withProperty(final String state, final String property, final int i) {
            return state;
        }
    }

    private static final class TestNbtAdapter implements NbtAdapter<String> {
        @Override
        public void write(final String tag, final DataOutput output) throws IOException {
            output.writeUTF(tag);
        }

        @Override
        public String read(final DataInput input) throws IOException {
            return input.readUTF();
        }
    }
}
