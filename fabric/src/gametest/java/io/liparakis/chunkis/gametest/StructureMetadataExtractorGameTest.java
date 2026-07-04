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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiecesList;
import net.minecraft.structure.StructureStart;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;

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
        Structure structure = registry.get(Identifier.ofVanilla("village"));
        if (structure == null) {
            structure = registry.get(Identifier.ofVanilla("village_plains"));
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
     * vanilla's {@link SerializedChunk#structureData} for the same live chunk.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 100)
    public void directStructureExtractionMatchesVanillaSerializedChunk(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final BlockPos anchor = context.getAbsolutePos(new BlockPos(1, 2, 1));
        final WorldChunk chunk = world.getWorldChunk(anchor);
        final ChunkPos chunkPos = chunk.getPos();

        final Registry<Structure> structureRegistry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.STRUCTURE);
        final Structure structure = getTestStructure(structureRegistry);
        context.assertTrue(structure != null, Text.literal("Expected a structure to exist in the registry."));

        final StructureStart start = new StructureStart(
                structure,
                chunkPos,
                1,
                new StructurePiecesList(List.of()));

        final Map<Structure, StructureStart> starts = new HashMap<>();
        starts.put(structure, start);
        chunk.setStructureStarts(starts);

        final LongSet references = new LongOpenHashSet(new long[]{
                chunkPos.toLong(),
                new ChunkPos(chunkPos.x + 1, chunkPos.z).toLong()
        });
        final Map<Structure, LongSet> referenceMap = new HashMap<>();
        referenceMap.put(structure, references);
        chunk.setStructureReferences(referenceMap);

        final NbtCompound direct = StructureMetadataExtractor.extract(world, chunk);
        final NbtCompound vanilla = SerializedChunk.fromChunk(world, chunk)
                .structureData();

        context.assertTrue(
                CisNbtUtil.hasStructureData(direct),
                Text.literal("Expected direct extractor to emit structure data."));
        context.assertTrue(
                CisNbtUtil.hasStructureData(vanilla),
                Text.literal("Expected vanilla SerializedChunk to emit structure data."));
        context.assertTrue(
                Objects.equals(direct, vanilla),
                Text.literal("Direct structure extractor output did not match SerializedChunk structureData()."));

        context.complete();
    }
}


