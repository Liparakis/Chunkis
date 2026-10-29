package io.liparakis.chunkis.world.restoration.nbt;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PaletteProvider;
import net.minecraft.world.chunk.ReadableContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Stores what a full-baseline chunk needs, besides its blocks, to load as a finished chunk: its biomes,
 * which otherwise only terrain generation provides, and its light, which vanilla would otherwise
 * recompute from scratch on every load.
 *
 * <p>Entries use vanilla's own section format, {@code {Y, biomes, BlockLight, SkyLight}}, so the list can
 * be placed straight into a synthetic chunk's {@code sections}. Like vanilla, light also covers the one
 * section below and above the world, which hold no blocks. Light is stored only when the chunk's light
 * was complete when captured; {@link CisNbtUtil#STORED_LIGHT_KEY} records that.</p>
 */
public final class StoredSections {

    /**
     * Section Y key, as in vanilla chunk NBT.
     */
    static final String Y_KEY = "Y";

    /**
     * Biome container key, as in vanilla chunk NBT.
     */
    static final String BIOMES_KEY = "biomes";

    /**
     * Block light key, as in vanilla chunk NBT.
     */
    static final String BLOCK_LIGHT_KEY = "BlockLight";

    /**
     * Sky light key, as in vanilla chunk NBT.
     */
    static final String SKY_LIGHT_KEY = "SkyLight";

    /**
     * Static holder; not instantiable.
     */
    private StoredSections() {
    }

    /**
     * Encodes every section's biomes, plus its light when the chunk is lit.
     *
     * @param chunk loaded chunk
     * @return one entry per section holding data
     */
    public static NbtList encode(final WorldChunk chunk) {
        final Codec<ReadableContainer<RegistryEntry<Biome>>> codec = chunk.getWorld().getPalettesFactory()
                .biomeContainerCodec();
        final PaletteProvider<RegistryEntry<Biome>> biomeProvider = chunk.getWorld().getPalettesFactory()
                .biomeStrategy();
        final LightingProvider lighting = chunk.getWorld().getChunkManager().getLightingProvider();
        final boolean storeLight = chunk.isLightOn();
        final ChunkPos pos = chunk.getPos();
        final ChunkSection[] sections = chunk.getSectionArray();
        final NbtList encoded = new NbtList();
        for (int sectionY = lighting.getBottomY(); sectionY < lighting.getTopY(); sectionY++) {
            final NbtCompound entry = new NbtCompound();
            final int index = chunk.sectionCoordToIndex(sectionY);
            if (index >= 0 && index < sections.length) {
                NbtElement biomes = encodeBiomes(sections[index].getBiomeContainer(), biomeProvider);
                if (biomes == null) {
                    biomes = codec.encodeStart(NbtOps.INSTANCE, sections[index].getBiomeContainer())
                            .getOrThrow(message -> new IllegalStateException(
                                    "Chunkis: Failed encoding biomes of chunk " + pos + " section " + index + ": "
                                            + message));
                }
                entry.put(BIOMES_KEY, biomes);
            }
            if (storeLight) {
                final ChunkSectionPos sectionPos = ChunkSectionPos.from(pos, sectionY);
                putLight(entry, BLOCK_LIGHT_KEY, lighting.get(LightType.BLOCK).getLightSection(sectionPos));
                putLight(entry, SKY_LIGHT_KEY, lighting.get(LightType.SKY).getLightSection(sectionPos));
            }
            if (!entry.isEmpty()) {
                entry.putByte(Y_KEY, (byte) sectionY);
                encoded.add(entry);
            }
        }
        return encoded;
    }

    /**
     * Encodes a biome container exactly as vanilla's biome container codec does (a {@code palette} list of biome
     * ids and, past one entry, the packed {@code data}), without going through the codec, which cost more than
     * the rest of a save's section capture. Runs on the server thread with every save of a stored chunk.
     *
     * @param biomes   biome container of one section
     * @param provider biome palette provider
     * @return the encoded container, or {@code null} when a biome has no registry key and the codec must encode it
     */
    public static NbtCompound encodeBiomes(final ReadableContainer<RegistryEntry<Biome>> biomes,
            final PaletteProvider<RegistryEntry<Biome>> provider) {
        final ReadableContainer.Serialized<RegistryEntry<Biome>> serialized = biomes.serialize(provider);
        final NbtList palette = new NbtList();
        for (final RegistryEntry<Biome> biome : serialized.paletteEntries()) {
            final var key = biome.getKey();
            if (key.isEmpty()) {
                return null;
            }
            palette.add(NbtString.of(key.get().getValue().toString()));
        }
        final NbtCompound encoded = new NbtCompound();
        encoded.put("palette", palette);
        serialized.storage().ifPresent(data -> encoded.putLongArray("data", data.toArray()));
        return encoded;
    }

    /**
     * Copies one light section into an entry, skipping it the way vanilla does when it holds no data.
     *
     * @param entry section entry
     * @param key   light key
     * @param light light section, or {@code null}
     */
    private static void putLight(final NbtCompound entry, final String key, final ChunkNibbleArray light) {
        if (light != null && !light.isUninitialized()) {
            entry.putByteArray(key, light.asByteArray().clone());
        }
    }
}
