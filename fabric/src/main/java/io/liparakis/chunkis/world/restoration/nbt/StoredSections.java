package io.liparakis.chunkis.world.restoration.nbt;

import com.mojang.serialization.Codec;
import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.lighting.LevelLightEngine;

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
    public static ListTag encode(final LevelChunk chunk) {
        final Codec<PalettedContainerRO<Holder<Biome>>> codec = chunk.getLevel().palettedContainerFactory()
                .biomeContainerCodec();
        final Strategy<Holder<Biome>> biomeProvider = chunk.getLevel().palettedContainerFactory()
                .biomeStrategy();
        final LevelLightEngine lighting = chunk.getLevel().getChunkSource().getLightEngine();
        final boolean storeLight = chunk.isLightCorrect();
        final ChunkPos pos = chunk.getPos();
        final LevelChunkSection[] sections = chunk.getSections();
        final ListTag encoded = new ListTag();
        for (int sectionY = lighting.getMinLightSection(); sectionY < lighting.getMaxLightSection(); sectionY++) {
            final CompoundTag entry = new CompoundTag();
            final int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index >= 0 && index < sections.length) {
                Tag biomes = encodeBiomes(sections[index].getBiomes(), biomeProvider);
                if (biomes == null) {
                    biomes = codec.encodeStart(NbtOps.INSTANCE, sections[index].getBiomes())
                            .getOrThrow(message -> new IllegalStateException(
                                    "Chunkis: Failed encoding biomes of chunk " + pos + " section " + index + ": "
                                            + message));
                }
                entry.put(BIOMES_KEY, biomes);
            }
            if (storeLight) {
                final SectionPos sectionPos = SectionPos.of(pos, sectionY);
                putLight(entry, BLOCK_LIGHT_KEY, lighting.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos));
                putLight(entry, SKY_LIGHT_KEY, lighting.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos));
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
     * <p>A section of one biome skips {@link PalettedContainerRO#pack}, which would lock the container and
     * repack its 64 entries only to find that one biome again.</p>
     *
     * @param biomes   biome container of one section
     * @param provider biome palette provider
     * @return the encoded container, or {@code null} when a biome has no registry key and the codec must encode it
     */
    public static CompoundTag encodeBiomes(final PalettedContainerRO<Holder<Biome>> biomes,
            final Strategy<Holder<Biome>> provider) {
        final Holder<Biome> onlyBiome = onlyBiome(biomes);
        if (onlyBiome != null) {
            return encodeBiomes(List.of(onlyBiome), Optional.empty());
        }
        final PalettedContainerRO.PackedData<Holder<Biome>> serialized = biomes.pack(provider);
        return encodeBiomes(serialized.paletteEntries(), serialized.storage());
    }

    /**
     * Returns the biome of a container whose palette can only hold one.
     *
     * @param biomes biome container of one section
     * @return its one biome, or {@code null} when the palette has to be looked through
     */
    private static Holder<Biome> onlyBiome(final PalettedContainerRO<Holder<Biome>> biomes) {
        if (!PalettedContainerFields.AVAILABLE || !(biomes instanceof PalettedContainer<Holder<Biome>> container)) {
            return null;
        }
        final Palette<Holder<Biome>> palette = PalettedContainerFields.palette(
                PalettedContainerFields.data(container));
        return palette instanceof SingleValuePalette<Holder<Biome>> ? palette.valueFor(0) : null;
    }

    /**
     * Writes a biome palette and its packed data.
     *
     * @param biomes  palette entries
     * @param storage packed data, empty for one entry
     * @return the encoded container, or {@code null} when a biome has no registry key
     */
    private static CompoundTag encodeBiomes(final List<Holder<Biome>> biomes,
            final Optional<LongStream> storage) {
        final ListTag palette = new ListTag();
        for (final Holder<Biome> biome : biomes) {
            final var key = biome.unwrapKey();
            if (key.isEmpty()) {
                return null;
            }
            palette.add(StringTag.valueOf(key.get().identifier().toString()));
        }
        final CompoundTag encoded = new CompoundTag();
        encoded.put("palette", palette);
        storage.ifPresent(data -> encoded.putLongArray("data", data.toArray()));
        return encoded;
    }

    /**
     * Copies one light section into an entry, skipping it the way vanilla does when it holds no data.
     *
     * @param entry section entry
     * @param key   light key
     * @param light light section, or {@code null}
     */
    private static void putLight(final CompoundTag entry, final String key, final DataLayer light) {
        if (light != null && !light.isEmpty()) {
            entry.putByteArray(key, light.getData().clone());
        }
    }
}
