package io.liparakis.chunkis.storage.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.bits.BitReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CisMappingTest {

    private static final Gson GSON = new Gson();
    private static final Type MAPPING_TYPE = new TypeToken<Map<String, Integer>>() {
    }.getType();

    @TempDir
    Path tempDir;

    @Test
    void createsFullRegistryMappingAndParentDirectoriesWhenMissing() throws IOException {
        Path mappingFile = tempDir.resolve("nested/chunkis/global_ids.json");

        newMapping(mappingFile);

        assertThat(readMapping(mappingFile))
                .containsEntry("minecraft:air", 0)
                .containsEntry("known:stone", 1)
                .containsEntry("known:dirt", 2);
    }

    @Test
    void restoresKnownIdsAndAppendsNewRegisteredBlocksAfterHighestPersistedId() throws IOException {
        Path mappingFile = writeMapping(Map.of(
                "minecraft:air", 0,
                "known:stone", 2,
                "missing:block", 5));

        CisMapping<String, String, String> mapping = newMapping(mappingFile);

        assertThat(mapping.getBlockId("known:stone")).isEqualTo(2);
        assertThat(mapping.getBlockId("known:dirt")).isEqualTo(6);

        mapping.flush();

        assertThat(readMapping(mappingFile))
                .containsEntry("minecraft:air", 0)
                .containsEntry("known:stone", 2)
                .containsEntry("missing:block", 5)
                .containsEntry("known:dirt", 6);
        assertThat(readMapping(mappingFile).values()).containsExactly(0, 2, 5, 6);
    }

    @Test
    void rejectsDecodeForUnresolvedPersistedBlockId() throws IOException {
        Path mappingFile = writeMapping(Map.of(
                "minecraft:air", 0,
                "missing:block", 5));

        CisMapping<String, String, String> mapping = newMapping(mappingFile);

        assertThatThrownBy(() -> mapping.readStateProperties(new BitReader(new byte[0]), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown Block ID 5");
    }

    private Path writeMapping(Map<String, Integer> mapping) throws IOException {
        Path mappingFile = tempDir.resolve("global_ids.json");
        Files.writeString(mappingFile, GSON.toJson(mapping));
        return mappingFile;
    }

    private Map<String, Integer> readMapping(Path mappingFile) throws IOException {
        return GSON.fromJson(Files.readString(mappingFile), MAPPING_TYPE);
    }

    private CisMapping<String, String, String> newMapping(Path mappingFile) throws IOException {
        BlockStateAdapter<String, String, String> stateAdapter = new TestBlockStateAdapter();
        return new CisMapping<>(mappingFile, new TestBlockRegistryAdapter(), stateAdapter,
                new PropertyPacker<>(stateAdapter));
    }

    private static final class TestBlockRegistryAdapter implements BlockRegistryAdapter<String> {

        private static final String AIR = "minecraft:air";
        private static final Map<String, String> KNOWN_BLOCKS = canonicalBlocks();

        private static Map<String, String> canonicalBlocks() {
            Map<String, String> blocks = new LinkedHashMap<>();
            blocks.put(AIR, AIR);
            blocks.put("known:stone", "known:stone");
            blocks.put("known:dirt", "known:dirt");
            return blocks;
        }

        @Override
        public String getId(String block) {
            return block;
        }

        @Override
        public String getBlock(String id) {
            return KNOWN_BLOCKS.getOrDefault(id, AIR);
        }

        @Override
        public String getAir() {
            return AIR;
        }

        @Override
        public Collection<String> getRegisteredBlocks() {
            return KNOWN_BLOCKS.values();
        }
    }

    private static final class TestBlockStateAdapter implements BlockStateAdapter<String, String, String> {

        @Override
        public String getDefaultState(String block) {
            return block;
        }

        @Override
        public String getBlock(String state) {
            return state;
        }

        @Override
        public List<String> getProperties(String block) {
            return List.of();
        }

        @Override
        public String getPropertyName(String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(String property) {
            return List.of();
        }

        @Override
        public int getValueIndex(String state, String property) {
            return 0;
        }

        @Override
        public String withProperty(String state, String property, int valueIndex) {
            return state;
        }
    }
}
