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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CisUniformSectionCodecTest {

    private static final int SECTION_Y_64 = 4;
    private static final int SECTION_Y_80 = 5;
    private static final int DEFAULT_SPARSE_SENTINEL = CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL;

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
        final int[] blockCount = { 0 };
        decoded.forEachBlock((x, y, z, state) -> {
            blockCount[0]++;
            assertThat(state).isEqualTo("stone");
        });
        assertThat(blockCount[0]).isEqualTo(4096);
    }

    @Test
    void choosesSparseWhenDenseSourceSectionWouldBeLarger() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int i = 0; i < 513; i++) {
            final int x = i & 15;
            final int z = (i >> 4) & 15;
            final int y = 64 + ((i >> 8) & 15);
            delta.addBlockChange(x, y, z, "stone");
        }

        final List<SectionEncodingInfo> sections = parseSections(harness.encoder.encode(delta));
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "sparse", 513, 0));
    }

    @Test
    void choosesDenseWhenSparseSourceSectionWouldBeLargerWithLargeChunkPalette() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int i = 0; i < 511; i++) {
            final int x = i & 15;
            final int z = (i >> 4) & 15;
            final int y = 64 + ((i >> 8) & 15);
            delta.addBlockChange(x, y, z, "stone");
        }

        for (int i = 0; i < 256; i++) {
            final int x = i & 15;
            final int z = (i >> 4) & 15;
            delta.addBlockChange(x, 80, z, "block:" + i);
        }

        final List<SectionEncodingInfo> sections = parseSections(harness.encoder.encode(delta));
        assertThat(sections)
                .contains(new SectionEncodingInfo(SECTION_Y_64, "dense", 0, 2))
                .contains(new SectionEncodingInfo(SECTION_Y_80, "sparse", 256, 0));
    }

    @Test
    void choosesDefaultSparseForMostlyStoneWithAirCavesAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if ((x + y + z) % 17 != 0) {
                        delta.addBlockChange(x, y, z, "stone");
                    }
                }
            }
        }

        final byte[] encoded = harness.encoder.encode(delta);
        final List<SectionEncodingInfo> sections = parseSections(encoded);
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "default_sparse", 241, 0));

        final ChunkDelta<String, String> decoded = harness.decoder.decode(encoded);
        final int[] stoneCount = { 0 };
        final int[] airCount = { 0 };
        decoded.forEachBlock((x, y, z, state) -> {
            if ("stone".equals(state)) {
                stoneCount[0]++;
            } else if ("air".equals(state)) {
                airCount[0]++;
            }
        });
        assertThat(stoneCount[0]).isEqualTo(4096 - 241);
        assertThat(airCount[0]).isEqualTo(241);
    }

    @Test
    void keepsNormalSparseForMostlyAirSectionWithFewBlocks() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int i = 0; i < 12; i++) {
            delta.addBlockChange(i & 3, 64 + (i >> 2), (i >> 1) & 3, "stone");
        }

        final List<SectionEncodingInfo> sections = parseSections(harness.encoder.encode(delta));
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "sparse", 12, 0));
    }

    @Test
    void stillPrefersUniformForFullSingleStateSection() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    delta.addBlockChange(x, y, z, "stone");
                }
            }
        }

        final List<SectionEncodingInfo> sections = parseSections(harness.encoder.encode(delta));
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "uniform", 4096, 0));
    }

    @Test
    void choosesDenseForNoisySectionWhenDefaultSparseWouldBeLarger() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int i = 0; i < 4096; i++) {
            final int x = i & 15;
            final int z = (i >> 4) & 15;
            final int y = 64 + ((i >> 8) & 15);
            final String state = i < 2048 ? "stone" : "block:" + ((i - 2048) & 255);
            delta.addBlockChange(x, y, z, state);
        }

        final List<SectionEncodingInfo> sections = parseSections(harness.encoder.encode(delta));
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "dense", 0, 258));
    }

    @Test
    void preservesLogicalSectionAcrossRepeatedSaveReload() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = createMixedSectionDelta();

        final ChunkDelta<String, String> decoded1 = harness.decoder.decode(harness.encoder.encode(delta));
        final ChunkDelta<String, String> decoded2 = harness.decoder.decode(harness.encoder.encode(decoded1));

        assertThat(snapshot(decoded2)).isEqualTo(snapshot(decoded1));
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

    private static ChunkDelta<String, String> createMixedSectionDelta() {
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if ((x * 31 + y * 7 + z) % 23 == 0) {
                        delta.addBlockChange(x, y, z, "block:" + ((x + z) & 7));
                    } else if ((x + z) % 9 != 0) {
                        delta.addBlockChange(x, y, z, "stone");
                    }
                }
            }
        }
        return delta;
    }

    private Map<Long, String> snapshot(final ChunkDelta<String, String> delta) {
        final Map<Long, String> out = new LinkedHashMap<>();
        delta.forEachBlock((x, y, z, state) -> out.put((((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15), state));
        return out;
    }

    private List<SectionEncodingInfo> parseSections(final byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            in.readInt();
            in.readInt();

            final int globalPaletteSize = in.readInt();
            in.skipBytes(globalPaletteSize * 2);
            final int propertyBytes = in.readInt();
            in.skipBytes(propertyBytes);

            final int sectionCount = in.readUnsignedShort();
            final int sectionDataLength = in.readInt();
            final byte[] sectionData = in.readNBytes(sectionDataLength);
            final BitReader reader = new BitReader(sectionData);
            final int globalBits = AbstractCisDecoder.calculateBitsNeeded(globalPaletteSize);
            final List<SectionEncodingInfo> sections = new ArrayList<>(sectionCount);

            for (int i = 0; i < sectionCount; i++) {
                final int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
                final int mode = (int) reader.read(1);
                if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
                    final int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);
                    if (blockCount == CisConstants.UNIFORM_SECTION_SENTINEL) {
                        reader.read(globalBits);
                        sections.add(new SectionEncodingInfo(sectionY, "uniform", blockCount, 0));
                    } else if (blockCount == DEFAULT_SPARSE_SENTINEL) {
                        reader.read(globalBits);
                        final int exceptionCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);
                        for (int entry = 0; entry < exceptionCount; entry++) {
                            reader.read(12);
                            reader.read(globalBits);
                        }
                        sections.add(new SectionEncodingInfo(sectionY, "default_sparse", exceptionCount, 0));
                    } else {
                        for (int entry = 0; entry < blockCount; entry++) {
                            reader.read(12);
                            reader.read(globalBits);
                        }
                        sections.add(new SectionEncodingInfo(sectionY, "sparse", blockCount, 0));
                    }
                } else {
                    final int localSize = (int) reader.read(CisConstants.PALETTE_SIZE_BITS);
                    for (int entry = 0; entry < localSize; entry++) {
                        reader.read(globalBits);
                    }
                    final int bitsPerBlock = AbstractCisDecoder.calculateBitsNeeded(localSize + 1);
                    for (int block = 0; block < 4096; block++) {
                        reader.read(bitsPerBlock);
                    }
                    sections.add(new SectionEncodingInfo(sectionY, "dense", 0, localSize));
                }
            }

            return sections;
        }
    }

    private record SectionEncodingInfo(int sectionY, String encoding, int blockCount, int localSize) {
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
            for (int i = 0; i < 300; i++) {
                blocks.put("block:" + i, "block:" + i);
            }
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
