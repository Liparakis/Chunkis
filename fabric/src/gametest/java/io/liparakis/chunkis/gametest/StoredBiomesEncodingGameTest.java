package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.restoration.nbt.StoredSections;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.PalettesFactory;

/**
 * Covers {@link StoredSections#encodeBiomes}: it must write exactly what vanilla's biome container codec writes,
 * since stored sections are handed to vanilla's chunk reader as they are.
 */
@SuppressWarnings("unused")
public final class StoredBiomesEncodingGameTest {

    /**
     * Encodes a one-biome container and one holding several biomes both ways and expects equal NBT.
     *
     * @param context the game test context
     */
    @GameTest
    public void directBiomeEncodingMatchesVanillaCodec(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final PalettesFactory palettes = world.getPalettesFactory();
        final var registry = world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        final PalettedContainer<RegistryEntry<Biome>> single = palettes.getBiomeContainer();
        final PalettedContainer<RegistryEntry<Biome>> mixed = palettes.getBiomeContainer();
        final List<RegistryKey<Biome>> keys = List.of(BiomeKeys.DESERT, BiomeKeys.FOREST, BiomeKeys.OCEAN,
                BiomeKeys.SWAMP, BiomeKeys.TAIGA);
        for (int i = 0; i < 64; i++) {
            mixed.swap(i & 3, (i >> 2) & 3, i >> 4, registry.getOrThrow(keys.get(i % keys.size())));
        }
        for (final PalettedContainer<RegistryEntry<Biome>> container : List.of(single, mixed)) {
            final NbtElement expected = palettes.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, container)
                    .getOrThrow();
            final NbtElement actual = StoredSections.encodeBiomes(container, palettes.biomeStrategy());
            context.assertTrue(expected.equals(actual), Text.literal("Direct biome encoding " + actual
                    + " differs from vanilla's " + expected + "."));
        }
        context.complete();
    }
}
