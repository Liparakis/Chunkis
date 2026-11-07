package io.liparakis.chunkis.world.restoration.nbt;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Direct serializer for the vanilla {@code structures} chunk payload.
 *
 * <p>This mirrors the relevant behavior of
 * {@code SerializedChunk.writeStructures(...)} without paying to build a full
 * {@code SerializedChunk} first.</p>
 */
public final class StructureMetadataExtractor {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
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
    public static CompoundTag extract(final ServerLevel world, final ChunkAccess chunk) {
        final Map<Structure, StructureStart> starts = chunk.getAllStarts();
        final Map<Structure, LongSet> references = chunk.getAllReferences();

        if (starts.isEmpty() && references.isEmpty()) {
            return null;
        }

        final StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(world);
        final Registry<Structure> structureRegistry = context.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE);
        final ChunkPos chunkPos = chunk.getPos();

        final CompoundTag structures = new CompoundTag();
        final CompoundTag startsNbt = new CompoundTag();

        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            final Structure structure = entry.getKey();
            final StructureStart start = entry.getValue();
            if (structure == null || start == null) {
                continue;
            }

            final Identifier id = structureRegistry.getKey(structure);
            if (id != null) {
                startsNbt.put(id.toString(), start.createTag(context, chunkPos));
            }
        }

        structures.put(CisNbtUtil.STRUCTURE_STARTS_KEY, startsNbt);

        final CompoundTag referencesNbt = new CompoundTag();
        for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
            final LongSet refs = entry.getValue();
            if (refs == null || refs.isEmpty()) {
                continue;
            }

            final Identifier id = structureRegistry.getKey(entry.getKey());
            if (id != null) {
                referencesNbt.putLongArray(id.toString(), refs.toLongArray());
            }
        }

        structures.put(CisNbtUtil.STRUCTURE_REFERENCES_KEY, referencesNbt);
        return CisNbtUtil.hasStructureData(structures) ? structures : null;
    }
}
