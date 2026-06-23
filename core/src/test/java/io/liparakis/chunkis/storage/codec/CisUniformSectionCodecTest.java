package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.bits.BitReader;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.storage.mapping.PropertyPacker;
import io.liparakis.chunkis.storage.model.CisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CisUniformSectionCodecTest {

    @TempDir
    Path tempDir;

    @Test
    void encodesFullSingleStateSectionUsingUniformSentinelAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    delta.addBlockChange(x, y, z, "stone");
                }
            }
        }

        final byte[] encoded = harness.encoder.encode(delta);

        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            assertThat(in.readInt()).isEqualTo(CisConstants.MAGIC);
            assertThat(in.readInt()).isEqualTo(CisConstants.VERSION);

            final int globalPaletteSize = in.readInt();
            assertThat(globalPaletteSize).isEqualTo(2);

            in.skipBytes(globalPaletteSize * 2);
            final int propertyBytes = in.readInt();
            in.skipBytes(propertyBytes);

            assertThat(in.readUnsignedShort()).isEqualTo(1);
            final int sectionDataLength = in.readInt();
            final byte[] sectionData = in.readNBytes(sectionDataLength);

            final BitReader reader = new BitReader(sectionData);
            assertThat(reader.readZigZag(CisConstants.SECTION_Y_BITS)).isEqualTo(4);
            assertThat(reader.read(1)).isEqualTo(CisConstants.SECTION_ENCODING_SPARSE);
            assertThat(reader.read(CisConstants.BLOCK_COUNT_BITS)).isEqualTo(4096);
            assertThat(reader.read(1)).isEqualTo(1);
        }

        final ChunkDelta<String, String> decoded = harness.decoder.decode(encoded);
        final int[] blockCount = {0};
        decoded.forEachBlock((x, y, z, state) -> {
            blockCount[0]++;
            assertThat(state).isEqualTo("stone");
        });
        assertThat(blockCount[0]).isEqualTo(4096);
    }

    private CodecHarness newHarness() throws IOException {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );

        return new CodecHarness(
                new CisEncoder<>(mapping, stateAdapter, new TestNbtAdapter(), "air"),
                new CisDecoder<>(mapping, stateAdapter, new TestNbtAdapter(), "air")
        );
    }

    private record CodecHarness(CisEncoder<String, String> encoder, CisDecoder<String, String> decoder) {
    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {
        private static final String AIR = "air";
        private static final Map<String, String> BLOCKS = canonicalBlocks();

        @Override
        public String getId(final String block) {
            return block;
        }

        @Override
        public String getBlock(final String id) {
            return BLOCKS.getOrDefault(id, AIR);
        }

        @Override
        public String getAir() {
            return AIR;
        }

        @Override
        public Collection<String> getRegisteredBlocks() {
            return BLOCKS.values();
        }

        private static Map<String, String> canonicalBlocks() {
            final Map<String, String> blocks = new LinkedHashMap<>();
            blocks.put(AIR, AIR);
            blocks.put("stone", "stone");
            return blocks;
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
        public String withProperty(final String state, final String property, final int valueIndex) {
            return state;
        }
    }

    private static final class TestNbtAdapter implements NbtAdapter<String> {
        @Override
        public void writeCompressed(final String tag, final DataOutput output) throws IOException {
            output.writeUTF(tag);
        }

        @Override
        public String readCompressed(final DataInput input) throws IOException {
            return input.readUTF();
        }

        @Override
        public void writeRaw(final String tag, final DataOutput output) throws IOException {
            output.writeUTF(tag);
        }

        @Override
        public String readRaw(final DataInput input) throws IOException {
            return input.readUTF();
        }

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
