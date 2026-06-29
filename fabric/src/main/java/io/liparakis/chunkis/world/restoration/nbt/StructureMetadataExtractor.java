package io.liparakis.chunkis.world.restoration.nbt;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.Structure;

/**
 * Direct serializer for the vanilla {@code structures} chunk payload.
 *
 * <p>This mirrors the relevant behavior of
 * {@code SerializedChunk.writeStructures(...)} without paying to build a full
 * {@code SerializedChunk} first.</p>
 *
 * @author Liparakis
 * @version 1.1
 *
 */
public final class StructureMetadataExtractor {

    private StructureMetadataExtractor() {
        throw new AssertionError("Utility class");
    }

    /**
     * Extracts the vanilla structure metadata payload for a live chunk.
     *
     * @param world owning world
     * @param chunk live chunk to inspect
     * @return serialized {@code structures} payload, or {@code null} if the chunk
     *         has no starts or references worth persisting
     */
    public static NbtCompound extract(final ServerWorld world, final Chunk chunk) {
        final Map<Structure, StructureStart> starts = chunk.getStructureStarts();
        final Map<Structure, LongSet> references = chunk.getStructureReferences();

        if (starts.isEmpty() && !chunk.hasStructureReferences()) {
            return null;
        }

        final StructureContext context = StructureContext.from(world);
        final Registry<Structure> structureRegistry = context.registryManager().getOrThrow(RegistryKeys.STRUCTURE);
        final ChunkPos chunkPos = chunk.getPos();

        final NbtCompound structures = new NbtCompound();
        final NbtCompound startsNbt = new NbtCompound();

        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            final Structure structure = entry.getKey();
            final StructureStart start = entry.getValue();
            if (structure == null || start == null) {
                continue;
            }

            final Identifier id = structureRegistry.getId(structure);
            if (id != null) {
                startsNbt.put(id.toString(), start.toNbt(context, chunkPos));
            }
        }

        structures.put(CisNbtUtil.STRUCTURE_STARTS_KEY, startsNbt);

        final NbtCompound referencesNbt = new NbtCompound();
        for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
            final LongSet refs = entry.getValue();
            if (refs == null || refs.isEmpty()) {
                continue;
            }

            final Identifier id = structureRegistry.getId(entry.getKey());
            if (id != null) {
                referencesNbt.putLongArray(id.toString(), refs.toLongArray());
            }
        }

        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, referencesNbt);
        return CisNbtUtil.hasStructureData(structures) ? structures : null;
    }
}

