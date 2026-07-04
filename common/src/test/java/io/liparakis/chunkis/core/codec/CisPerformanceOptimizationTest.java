package io.liparakis.chunkis.core.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CisPerformanceOptimizationTest {

    @TempDir
    Path tempDir;

    @Test
    void testAllAirSection() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        // All air (no modifications - empty delta)
        verifyEquivalence(harness, delta);
    }

    @Test
    void testAllStoneSection() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        fillSectionUniform(delta, 4, "stone");
        verifyEquivalence(harness, delta);
    }

    @Test
    void testMostlyStoneWithAirExceptions() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);
        fillSectionUniform(delta, 4, "stone");

        // Exceptions to air
        delta.addBlockChange(0, 64, 0, "air");
        delta.addBlockChange(15, 64 + 15, 15, "air");
        delta.addBlockChange(5, 64 + 5, 5, "air");

        verifyEquivalence(harness, delta);
    }

    @Test
    void testMostlyAirWithStoneExceptions() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        // Default is air, some stone exceptions
        delta.addBlockChange(0, 64, 0, "stone");
        delta.addBlockChange(15, 64 + 15, 15, "stone");
        delta.addBlockChange(8, 64 + 8, 8, "stone");

        verifyEquivalence(harness, delta);
    }

    @Test
    void testCheckerboardSection() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        for (int y = 64; y < 80; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if ((x + y + z) % 2 == 0) {
                        delta.addBlockChange(x, y, z, "stone");
                    }
                }
            }
        }

        verifyEquivalence(harness, delta);
    }

    @Test
    void testLayeredTerrainSection() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        // 8 layers of stone, 8 layers of dirt
        for (int y = 64; y < 80; y++) {
            final String state = (y < 72) ? "stone" : "dirt";
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    delta.addBlockChange(x, y, z, state);
                }
            }
        }

        verifyEquivalence(harness, delta);
    }

    @Test
    void testNegativeSectionY() throws Exception {
        final CodecHarness harness = newHarness();
        final ChunkDelta<String, String> delta = new ChunkDelta<>("air"::equals);

        // sectionY = -4 -> block Y = -64 to -49
        final int sectionY = -4;
        final int baseY = sectionY << 4;
        fillSectionUniform(delta, sectionY, "stone");

        // Add exceptions
        delta.addBlockChange(0, baseY, 0, "air");
        delta.addBlockChange(15, baseY + 15, 15, "air");
        delta.addBlockChange(7, baseY + 7, 7, "dirt");

        verifyEquivalence(harness, delta);
    }

    @Test
    void coarseBlockCapacityReserveScalesBySectionCount() {
        assertThat(AbstractCisDecoder.coarseBlockCapacityReserve(0)).isZero();
        assertThat(AbstractCisDecoder.coarseBlockCapacityReserve(1)).isEqualTo(4096);
        assertThat(AbstractCisDecoder.coarseBlockCapacityReserve(4)).isEqualTo(16384);
    }

    private void fillSectionUniform(final ChunkDelta<String, String> delta, final int sectionY, final String state) {
        final int baseY = sectionY << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    delta.addBlockChange(x, baseY + y, z, state);
                }
            }
        }
    }

    private void verifyEquivalence(
            final CodecHarness harness,
            final ChunkDelta<String, String> original
    ) throws Exception {
        final byte[] encoded = harness.encoder.encode(original);
        final ChunkDelta<String, String> decoded = harness.decoder.decode(encoded);

        // 1. Verify blocks match exactly
        final Map<Long, String> originalBlocks = getBlocksMap(original);
        final Map<Long, String> decodedBlocks = getBlocksMap(decoded);
        assertThat(decodedBlocks).isEqualTo(originalBlocks);

        // 2. Verify instruction counts match
        assertThat(decoded.getBlockChangesCount()).isEqualTo(original.getBlockChangesCount());

        // 3. Verify no duplicate positions
        final Set<Long> seenPositions = new HashSet<>();
        decoded.forEachBlock((x, y, z, state) -> {
            final long pos = (x & 0xF) | ((z & 0xF) << 4) | (((long) y) << 8);
            final boolean added = seenPositions.add(pos);
            assertThat(added).withFailMessage("Duplicate position detected: " + x + ", " + y + ", " + z)
                    .isTrue();
        });
    }

    private Map<Long, String> getBlocksMap(final ChunkDelta<String, String> delta) {
        final Map<Long, String> map = new LinkedHashMap<>();
        delta.forEachBlock((x, y, z, state) -> {
            final long pos = (x & 0xF) | ((z & 0xF) << 4) | (((long) y) << 8);
            map.put(pos, state);
        });
        return map;
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
                new CisEncoder<>(mapping, new TestNbtAdapter(), "air"),
                new CisDecoder<>(mapping, new TestNbtAdapter(), "air")
        );
    }

    private record CodecHarness(CisEncoder<String, String> encoder, CisDecoder<String, String> decoder) {

    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {

        private static final String AIR = "air";
        private static final Map<String, String> BLOCKS = canonicalBlocks();

        private static Map<String, String> canonicalBlocks() {
            final Map<String, String> blocks = new LinkedHashMap<>();
            blocks.put(AIR, AIR);
            blocks.put("stone", "stone");
            blocks.put("dirt", "dirt");
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
}
