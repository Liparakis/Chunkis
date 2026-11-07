package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.StructureMetadataExtractor;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/**
 * Verifies that direct structure metadata extraction matches vanilla's
 * SerializedChunk structure payload for the same live chunk state.
 */
@SuppressWarnings("unused")
public final class StructureMetadataExtractorGameTest {

    /**
     * Resolves a structure from the registry to use for testing.
     * Tries to find a vanilla village or village_plains, falling back to the first available structure.
     *
     * @param registry the structure registry
     * @return the resolved structure, or null if the registry is empty
     */
    private static Structure getTestStructure(final Registry<Structure> registry) {
        Structure structure = registry.getValue(Identifier.withDefaultNamespace("village"));
        if (structure == null) {
            structure = registry.getValue(Identifier.withDefaultNamespace("village_plains"));
        }
        if (structure == null) {
            structure = registry.stream()
                    .findFirst()
                    .orElse(null);
        }
        return structure;
    }

    /**
     * Game test that verifies the output of {@link StructureMetadataExtractor#extract} matches
     * vanilla's {@code SerializableChunkData.structureData} for the same live chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 100)
    public void directStructureExtractionMatchesVanillaSerializedChunk(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final BlockPos anchor = context.absolutePos(new BlockPos(1, 2, 1));
        final LevelChunk chunk = world.getChunkAt(anchor);
        final ChunkPos chunkPos = chunk.getPos();

        final Registry<Structure> structureRegistry = world.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE);
        final Structure structure = getTestStructure(structureRegistry);
        context.assertTrue(structure != null, Component.literal("Expected a structure to exist in the registry."));
        assert structure != null;

        final StructureStart start = new StructureStart(
                structure,
                chunkPos,
                1,
                new PiecesContainer(List.of()));

        final Map<Structure, StructureStart> starts = new HashMap<>();
        starts.put(structure, start);
        chunk.setAllStarts(starts);

        final LongSet references = new LongOpenHashSet(new long[]{
                chunkPos.pack(),
                new ChunkPos(chunkPos.x() + 1, chunkPos.z()).pack()
        });
        final Map<Structure, LongSet> referenceMap = new HashMap<>();
        referenceMap.put(structure, references);
        chunk.setAllReferences(referenceMap);

        final CompoundTag direct = StructureMetadataExtractor.extract(world, chunk);
        final CompoundTag vanilla = SerializableChunkData.copyOf(world, chunk)
                .structureData();

        context.assertTrue(
                CisNbtUtil.hasStructureData(direct),
                Component.literal("Expected direct extractor to emit structure data."));
        context.assertTrue(
                CisNbtUtil.hasStructureData(vanilla),
                Component.literal("Expected vanilla SerializedChunk to emit structure data."));
        context.assertTrue(
                Objects.equals(direct, vanilla),
                Component.literal("Direct structure extractor output did not match SerializedChunk structureData()."));

        chunk.setAllStarts(Map.of());
        context.assertTrue(
                Objects.equals(StructureMetadataExtractor.extract(world, chunk),
                        SerializableChunkData.copyOf(world, chunk).structureData()),
                Component.literal("Reference-only structure metadata did not match vanilla."));
        chunk.setAllReferences(Map.of());
        context.assertTrue(StructureMetadataExtractor.extract(world, chunk) == null,
                Component.literal("Empty structure metadata should not be persisted."));

        context.succeed();
    }
}


