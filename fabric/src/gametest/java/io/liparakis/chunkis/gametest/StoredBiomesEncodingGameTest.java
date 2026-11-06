package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.restoration.nbt.StoredSections;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * Covers {@code StoredSections.encodeBiomes}: it must write exactly what vanilla's biome container codec writes,
 * since stored sections are handed to vanilla's chunk reader as they are.
 */
@SuppressWarnings("unused")
public final class StoredBiomesEncodingGameTest {

    /**
     * Encodes a one-biome container, one holding several biomes and one reduced back to a single biome, whose
     * palette still has room for more, both ways and expects equal NBT.
     *
     * @param context the game test context
     */
    @GameTest
    public void directBiomeEncodingMatchesVanillaCodec(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final PalettedContainerFactory palettes = world.palettedContainerFactory();
        final var registry = world.registryAccess().lookupOrThrow(Registries.BIOME);
        final PalettedContainer<Holder<Biome>> single = palettes.createForBiomes();
        final PalettedContainer<Holder<Biome>> mixed = palettes.createForBiomes();
        final PalettedContainer<Holder<Biome>> reduced = palettes.createForBiomes();
        final List<ResourceKey<Biome>> keys = List.of(Biomes.DESERT, Biomes.FOREST, Biomes.OCEAN,
                Biomes.SWAMP, Biomes.TAIGA);
        for (int i = 0; i < 64; i++) {
            mixed.getAndSet(i & 3, (i >> 2) & 3, i >> 4, registry.getOrThrow(keys.get(i % keys.size())));
            reduced.getAndSet(i & 3, (i >> 2) & 3, i >> 4, registry.getOrThrow(keys.get(i % keys.size())));
        }
        for (int i = 0; i < 64; i++) {
            reduced.getAndSet(i & 3, (i >> 2) & 3, i >> 4, registry.getOrThrow(Biomes.SWAMP));
        }
        for (final PalettedContainer<Holder<Biome>> container : List.of(single, mixed, reduced)) {
            final Tag expected = palettes.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, container)
                    .getOrThrow();
            final Tag actual = StoredSections.encodeBiomes(container, palettes.biomeStrategy());
            context.assertTrue(expected.equals(actual), Component.literal("Direct biome encoding " + actual
                    + " differs from vanilla's " + expected + "."));
        }
        context.succeed();
    }
}
