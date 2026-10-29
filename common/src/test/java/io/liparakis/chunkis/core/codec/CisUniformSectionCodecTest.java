package io.liparakis.chunkis.core.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.bits.BitReader;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import io.liparakis.chunkis.core.model.CisConstants;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisMapping;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CisUniformSectionCodecTest {

    /**
     * Stores section y 64.
     */
    private static final int SECTION_Y_64 = 4;
    /**
     * Stores section y 80.
     */
    private static final int SECTION_Y_80 = 5;
    /**
     * Stores default sparse sentinel.
     */
    private static final int DEFAULT_SPARSE_SENTINEL = CisConstants.DEFAULT_SPARSE_SECTION_SENTINEL;

    /**
     * Stores temp dir.
     */
    @TempDir
    Path tempDir;

    /**
     * Performs create mixed section delta.
     */
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

    /**
     * Performs encodes full single state section using uniform sentinel and round trips.
     */
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

    /**
     * Performs chooses sparse when dense source section would be larger.
     */
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

    /**
     * Performs chooses dense when sparse source section would be larger with large chunk palette.
     */
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

    /**
     * Performs chooses default sparse for mostly stone with air caves and round trips.
     */
    @Test
    void choosesDefaultSparseForMostlyStoneWithAirCavesAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // Caves are explicit air; default-sparse requires every slot to be set.
                    delta.addBlockChange(x, y, z, (x + y + z) % 17 != 0 ? "stone" : "air");
                }
            }
        }

        final byte[] encoded = harness.encoder.encode(delta);
        final List<SectionEncodingInfo> sections = parseSections(encoded);
        assertThat(sections)
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "default_sparse", 241, 0));

        final ChunkDelta<String, String> decoded = harness.decoder.decode(encoded);
        final int[] stoneCount = {0};
        final int[] airCount = {0};
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

    /**
     * Performs keeps normal sparse for mostly air section with few blocks.
     */
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

    /**
     * Performs preserves explicit air override in sparse base backed delta.
     */
    @Test
    void preservesExplicitAirOverrideInSparseBaseBackedDelta() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        delta.addBlockChange(4, 85, 7, "air");

        final ChunkDelta<String, String> decoded = harness.decoder.decode(harness.encoder.encode(delta));

        assertThat(snapshot(decoded)).containsEntry((((long) 85) << 8) | (7L << 4) | 4L, "air");
    }

    /**
     * Performs preserves explicit air when default sparse would otherwise collapse it.
     */
    @Test
    void preservesExplicitAirWhenDefaultSparseWouldOtherwiseCollapseIt() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int i = 0; i < 17; i++) {
            delta.addBlockChange(i & 15, 85, (i >> 4) & 15, "stone");
        }
        delta.addBlockChange(12, 85, 12, "air");

        final ChunkDelta<String, String> decoded = harness.decoder.decode(harness.encoder.encode(delta));

        assertThat(snapshot(decoded)).containsEntry((((long) 85) << 8) | (12L << 4) | 12L, "air");
    }

    /**
     * Performs still prefers uniform for full single state section.
     */
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

    /**
     * Performs chooses dense for noisy section when default sparse would be larger.
     */
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

    /**
     * Performs preserves logical section across repeated save reload.
     */
    @Test
    void preservesLogicalSectionAcrossRepeatedSaveReload() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = createMixedSectionDelta();

        final ChunkDelta<String, String> decoded1 = harness.decoder.decode(harness.encoder.encode(delta));
        final ChunkDelta<String, String> decoded2 = harness.decoder.decode(harness.encoder.encode(decoded1));

        assertThat(snapshot(decoded2)).isEqualTo(snapshot(decoded1));
    }

    /**
     * Encodes varied chunks once block by block and once through {@link io.liparakis.chunkis.core.SectionStatesSource}
     * and expects identical bytes: the section path is a faster route to the same payload, not a new format.
     */
    @Test
    void sectionStatesSourceEncodesSameBytesAsBlockWalk() throws Exception {
        final String[] palette = {"air", "stone", "dirt", "grass", "water", "sand", "gravel", "oak_log", "ore"};
        final java.util.Random random = new java.util.Random(7);
        for (int trial = 0; trial < 120; trial++) {
            final ChunkDelta<String, String> delta = new ChunkDelta<>(state -> state.equals("air"));
            final int kind = trial % 5;
            for (int sectionY = -1; sectionY < 3; sectionY++) {
                final int fill = random.nextInt(palette.length);
                for (int i = 0; i < 4096; i++) {
                    final int x = i & 15;
                    final int z = (i >> 4) & 15;
                    final int y = sectionY * 16 + (i >> 8);
                    final String state = switch (kind) {
                        case 0 -> palette[fill];
                        case 1 -> random.nextInt(40) == 0 ? palette[random.nextInt(palette.length)] : palette[fill];
                        case 2 -> random.nextInt(25) == 0 ? palette[random.nextInt(palette.length)] : null;
                        case 3 -> palette[random.nextInt(palette.length)];
                        default -> palette[Math.floorMod(y / 3 + (x > 7 ? 1 : 0), 4)];
                    };
                    if (state != null) {
                        delta.addBlockChange(x, y, z, state);
                    }
                }
            }
            final byte[] walked = newHarness().encoder.encode(delta);
            final byte[] bySection = newHarness().encoder.encode(sectionSource(delta));
            assertThat(bySection).as("trial %d", trial).isEqualTo(walked);
        }
    }

    /**
     * Copying a payload's block part and encoding only the rest gives the exact payload a full encode gives, and a
     * payload that is not current-version has no block part.
     */
    @Test
    void blockPartSpliceReproducesFullEncode() throws Exception {
        final String[] palette = {"air", "stone", "dirt", "grass", "water", "sand", "gravel", "oak_log", "ore"};
        final java.util.Random random = new java.util.Random(5);
        for (int trial = 0; trial < 40; trial++) {
            final CodecHarness harness = newHarness();
            final ChunkDelta<String, String> delta = new ChunkDelta<>(state -> state.equals("air"));
            for (int i = 0; i < 4096 * 3; i++) {
                if (random.nextInt(3) > 0) {
                    delta.addBlockChange(i & 15, (i >> 8) - 16, (i >> 4) & 15,
                            palette[trial % 2 == 0 ? random.nextInt(palette.length) : (i >> 9) % 3]);
                }
            }
            final byte[] full = harness.encoder.encode(delta);
            final int length = CisBlockPart.length(full);
            assertThat(length).isPositive().isLessThanOrEqualTo(full.length);
            final byte[] spliced = harness.encoder.encodeWithBlockPart(java.util.Arrays.copyOf(full, length), delta);
            assertThat(spliced).as("trial %d", trial).isEqualTo(full);
        }
        final byte[] notCis = new byte[64];
        assertThat(CisBlockPart.length(notCis)).isEqualTo(-1);
    }

    /**
     * A payload with one coded section, rewritten in the earlier single-shared-stream layout (which for one section
     * holds the same coder bytes, unframed), decodes to the same blocks: entries written before per-section streams
     * still load.
     */
    @Test
    void decodesEarlierSharedCodedStreamLayout() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>(state -> state.equals("air"));
        final String[] palette = {"stone", "dirt", "grass", "gravel"};
        for (int i = 0; i < 4096; i++) {
            final int y = 64 + (i >> 8);
            delta.addBlockChange(i & 15, y, (i >> 4) & 15, palette[((i >> 8) / 4 + ((i & 15) > 9 ? 1 : 0)) % 4]);
        }
        final byte[] current = harness.encoder.encode(delta);
        final java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(current);
        int offset = 8;
        final int paletteSize = in.getInt(offset);
        offset += 4 + 2 * paletteSize;
        offset += 4 + in.getInt(offset);
        offset += 2;
        offset += 4 + in.getInt(offset);
        offset += 4 + in.getInt(offset);
        final int marker = in.getInt(offset);
        assertThat(marker).as("per-section layout marker").isNegative();
        final int sectionStreamLength = in.getInt(offset + 4);
        assertThat(-(marker + 1)).isEqualTo(4 + sectionStreamLength);
        final java.io.ByteArrayOutputStream legacy = new java.io.ByteArrayOutputStream();
        legacy.write(current, 0, offset);
        legacy.write(java.nio.ByteBuffer.allocate(4).putInt(sectionStreamLength).array());
        legacy.write(current, offset + 8, sectionStreamLength);
        final int tail = offset + 8 + sectionStreamLength;
        legacy.write(current, tail, current.length - tail);

        assertThat(snapshot(harness.decoder.decode(legacy.toByteArray())))
                .isEqualTo(snapshot(harness.decoder.decode(current)))
                .isEqualTo(snapshot(delta));
    }

    /**
     * Wraps a delta so it also offers its blocks a whole section at a time.
     *
     * @param delta source delta
     * @return the same view, plus {@link io.liparakis.chunkis.core.SectionStatesSource}
     */
    @SuppressWarnings("unchecked")
    private static io.liparakis.chunkis.core.ChunkDeltaView<String, String> sectionSource(
            final ChunkDelta<String, String> delta) {
        return (io.liparakis.chunkis.core.ChunkDeltaView<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                CisUniformSectionCodecTest.class.getClassLoader(),
                new Class<?>[]{io.liparakis.chunkis.core.ChunkDeltaView.class,
                        io.liparakis.chunkis.core.SectionStatesSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("forEachSectionStates")) {
                        final java.util.TreeMap<Integer, Object[]> sections = new java.util.TreeMap<>();
                        delta.forEachBlock((x, y, z, state) -> sections.computeIfAbsent(y >> 4,
                                key -> new Object[4096])[((y & 15) << 8) | (z << 4) | x] = state);
                        final var visitor = (io.liparakis.chunkis.core.SectionStatesSource.SectionStatesVisitor) args[0];
                        sections.forEach(visitor::visit);
                        return null;
                    }
                    return method.invoke(delta, args);
                });
    }

    /**
     * Performs new harness.
     */
    private CodecHarness newHarness() throws IOException {
        final TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        final CisMapping<String, String, String> mapping = new CisMapping<>(
                tempDir.resolve("global_ids.json"),
                new TestBlockRegistryAdapter(),
                stateAdapter,
                new PropertyPacker<>(stateAdapter)
        );

        return new CodecHarness(
                new CisEncoder<>(mapping, new TestNbtAdapter(), "air"),
                new CisDecoder<>(mapping, new TestNbtAdapter(), "air")
        );
    }

    /**
     * Performs snapshot.
     */
    private Map<Long, String> snapshot(final ChunkDelta<String, String> delta) {
        final Map<Long, String> out = new LinkedHashMap<>();
        delta.forEachBlock((x, y, z, state) -> out.put((((long) y) << 8) | ((long) (z & 15) << 4) | (x & 15), state));
        return out;
    }

    /**
     * Encodes a noisy dense section of 40 states, whose blocks rarely match their prediction, and expects it raw
     * (one byte per block) and every block back after decoding.
     */
    @Test
    void storesNoisySmallPaletteDenseSectionRawAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        for (int i = 0; i < 4096; i++) {
            delta.addBlockChange(i & 15, 64 + (i >> 8), (i >> 4) & 15, "block:" + (i * 7 % 40));
        }

        final byte[] encoded = harness.encoder.encode(delta);
        assertThat(parseSections(encoded))
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "dense", 0, 41));
        assertThat(denseStreamLengths(encoded)).containsExactly(4096, 0);
        assertSameBlocks(delta, harness.decoder.decode(encoded));
    }

    /**
     * Encodes a layered dense section, whose blocks nearly all match their prediction, and expects it
     * context-coded far below a byte per block and every block back after decoding.
     */
    @Test
    void codesPredictableDenseSectionAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        for (int i = 0; i < 4096; i++) {
            final int y = i >> 8;
            final String layer = (i & 255) == 37 ? "ore:" + y : "layer:" + (y / 3);
            delta.addBlockChange(i & 15, 64 + y, (i >> 4) & 15, layer);
        }

        final byte[] encoded = harness.encoder.encode(delta);
        final int[] lengths = denseStreamLengths(encoded);
        assertThat(lengths[0]).isZero();
        assertThat(lengths[1]).isPositive().isLessThan(256);
        assertSameBlocks(delta, harness.decoder.decode(encoded));
    }

    /**
     * Encodes a dense section of 300 states, whose indices need 9 bits, and expects them to stay bit-packed and
     * every block back after decoding.
     */
    @Test
    void keepsWidePaletteDenseSectionBitPackedAndRoundTrips() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        for (int i = 0; i < 4096; i++) {
            delta.addBlockChange(i & 15, 64 + (i >> 8), (i >> 4) & 15, "block:" + (i % 300));
        }

        final byte[] encoded = harness.encoder.encode(delta);
        assertThat(parseSections(encoded))
                .containsExactly(new SectionEncodingInfo(SECTION_Y_64, "dense", 0, 301));
        assertThat(denseStreamLengths(encoded)).containsExactly(0, 0);
        assertSameBlocks(delta, harness.decoder.decode(encoded));
    }

    /**
     * Asserts two deltas hold the same block at every position.
     */
    private static void assertSameBlocks(final ChunkDelta<String, String> expected,
            final ChunkDelta<String, String> actual) {
        final java.util.Map<Long, String> want = new java.util.HashMap<>();
        expected.forEachBlock((x, y, z, state) -> want.put(BlockInstruction.packPos(x, y, z), state));
        final java.util.Map<Long, String> got = new java.util.HashMap<>();
        actual.forEachBlock((x, y, z, state) -> got.put(BlockInstruction.packPos(x, y, z), state));
        assertThat(got).isEqualTo(want);
    }

    /**
     * Reads the lengths of the raw and the coded dense block streams that follow the section bits.
     */
    private static int[] denseStreamLengths(final byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            in.readInt();
            in.readInt();
            final int globalPaletteSize = in.readInt();
            in.skipBytes(globalPaletteSize * 2);
            in.skipBytes(in.readInt());
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
            final int raw = in.readInt();
            in.skipBytes(raw);
            // The coded stream's length prefix is negative for the per-section layout; its magnitude is the length.
            final int coded = in.readInt();
            return new int[]{raw, coded < 0 ? -(coded + 1) : coded};
        }
    }

    /**
     * Performs parse sections.
     */
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
            final int globalBits = CisDecoder.calculateBitsNeeded(globalPaletteSize);
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
                    final int bitsPerBlock = CisDecoder.calculateBitsNeeded(localSize + 1);
                    // Indices of up to 8 bits are in the streams after the section bits; a bit says raw or coded.
                    if (bitsPerBlock <= CisConstants.MAX_CODED_DENSE_BITS) {
                        reader.read(1);
                    }
                    for (int block = 0; bitsPerBlock > CisConstants.MAX_CODED_DENSE_BITS && block < 4096; block++) {
                        reader.read(bitsPerBlock);
                    }
                    sections.add(new SectionEncodingInfo(sectionY, "dense", 0, localSize));
                }
            }

            return sections;
        }
    }

    private record CodecHarness(CisEncoder<String, String> encoder, CisDecoder<String, String> decoder) {

    }

    private record SectionEncodingInfo(int sectionY, String encoding, int blockCount, int localSize) {

    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {

        /**
         * Stores air.
         */
        private static final String AIR = "air";
        /**
         * Stores string.
         */
        private static final Map<String, String> BLOCKS = canonicalBlocks();

        /**
         * Performs canonical blocks.
         */
        private static Map<String, String> canonicalBlocks() {
            final Map<String, String> blocks = new LinkedHashMap<>();
            blocks.put(AIR, AIR);
            blocks.put("stone", "stone");
            for (int i = 0; i < 300; i++) {
                blocks.put("block:" + i, "block:" + i);
            }
            return blocks;
        }

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

    /**
     * Unset slots in a mostly-filled section must stay unset; they may not become
     * explicit air through default-sparse encoding.
     */
    @Test
    void mostlyFilledSectionWithUnsetSlotsDoesNotInventAir() throws Exception {
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

        final ChunkDelta<String, String> decoded = harness.decoder.decode(harness.encoder.encode(delta));
        final int[] counts = {0, 0};
        decoded.forEachBlock((x, y, z, state) -> counts["air".equals(state) ? 1 : 0]++);
        assertThat(counts[0]).isEqualTo(4096 - 241);
        assertThat(counts[1]).isZero();
    }

    /**
     * Round-trip property: for random sections mixing "no change" (absent), explicit
     * air and real blocks, decode(encode(delta)) must reproduce exactly the same
     * instructions. Covers every section encoding the cost model can pick.
     */
    @Test
    void randomSectionsRoundTripExactlyIncludingExplicitAirAndUnchangedSlots() throws Exception {
        final CodecHarness harness = newHarness();
        final String[] blocks = {"air", "stone", "dirt"};
        // fill = chance a slot is set at all; dominant = chance a set slot uses blocks[dominantIndex]
        final double[][] shapes = {
                {0.05, 0.0}, {0.5, 0.0}, {0.9, 0.0}, {1.0, 0.0},
                {0.85, 0.97}, {1.0, 0.97}, {0.9, 0.6}, {1.0, 0.6}
        };
        final java.util.Random random = new java.util.Random(0x5EEDL);
        for (int round = 0; round < 40; round++) {
            final double[] shape = shapes[round % shapes.length];
            final int dominantIndex = round % blocks.length;
            final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
            final Map<Long, String> expected = new LinkedHashMap<>();
            for (int y = 64; y < 80; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (random.nextDouble() >= shape[0]) {
                            continue;
                        }
                        final String state = random.nextDouble() < shape[1]
                                ? blocks[dominantIndex]
                                : blocks[random.nextInt(blocks.length)];
                        delta.addBlockChange(x, y, z, state);
                        expected.put(posKey(x, y, z), state);
                    }
                }
            }

            final ChunkDelta<String, String> decoded = harness.decoder.decode(harness.encoder.encode(delta));
            final Map<Long, String> actual = new LinkedHashMap<>();
            decoded.forEachBlock((x, y, z, state) -> actual.put(posKey(x, y, z), state));

            assertThat(actual).as("round %d shape fill=%s dominant=%s", round, shape[0], shape[1])
                    .isEqualTo(expected);
        }
    }

    /**
     * Packs a block position for map comparison.
     */
    private static long posKey(final int x, final int y, final int z) {
        return ((long) y << 8) | ((long) z << 4) | x;
    }
}
