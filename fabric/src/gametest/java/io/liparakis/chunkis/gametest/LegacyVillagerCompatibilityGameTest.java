package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.Chunkis;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Compares direct CIS-style entity decoding against vanilla data fixing for the 26.1 villager schema change.
 * These are schema fixtures, not a claim of complete real-world upgrade coverage.
 */
public final class LegacyVillagerCompatibilityGameTest {

    /**
     * Minecraft 1.21.11's world_version, read from that release's bundled version.json.
     */
    private static final int SOURCE_VERSION = 4671;

    /**
     * Checks a previously saved villager's explicit type, profession and level.
     *
     * @param context running game world
     */
    @GameTest
    public void oldVillagerDataMatchesVanillaUpgrade(final GameTestHelper context) {
        verifyLegacyEntity(context, "minecraft:villager");
    }

    /**
     * Checks the other entity affected by the new VillagerDataFinalized data fix.
     *
     * @param context running game world
     */
    @GameTest
    public void oldZombieVillagerDataMatchesVanillaUpgrade(final GameTestHelper context) {
        verifyLegacyEntity(context, "minecraft:zombie_villager");
    }

    /**
     * Uses Minecraft's actual fixer and entity loader, the same loader called by CIS entity replay.
     * No entity is spawned or ticked; this checks decoding of the supplied fields only.
     *
     * @param context running game world
     * @param id legacy entity type
     */
    private static void verifyLegacyEntity(final GameTestHelper context, final String id) {
        final CompoundTag villagerData = new CompoundTag();
        villagerData.putString("type", "minecraft:desert");
        villagerData.putString("profession", "minecraft:librarian");
        villagerData.putInt("level", 4);
        final CompoundTag legacy = new CompoundTag();
        legacy.putString("id", id);
        legacy.put("VillagerData", villagerData);
        legacy.putInt("Xp", 90);
        final ListTag entities = new ListTag();
        entities.add(legacy.copy());
        final CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SOURCE_VERSION);
        chunk.putIntArray("Position", new int[]{0, 0});
        chunk.put("Entities", entities);
        final CompoundTag upgradedChunk = DataFixTypes.ENTITY_CHUNK.updateToCurrentVersion(
                DataFixers.getDataFixer(), chunk, SOURCE_VERSION);
        final CompoundTag upgraded = upgradedChunk.getList("Entities").orElseThrow().getCompound(0).orElseThrow();
        context.assertTrue(!legacy.contains("VillagerDataFinalized")
                        && upgraded.getBooleanOr("VillagerDataFinalized", false),
                Component.literal("Expected the real 26.1 fixer to add VillagerDataFinalized for " + id));

        final Entity direct = EntityType.loadEntityRecursive(legacy, context.getLevel(), EntitySpawnReason.LOAD,
                entity -> entity);
        final Entity fixed = EntityType.loadEntityRecursive(upgraded, context.getLevel(), EntitySpawnReason.LOAD,
                entity -> entity);
        context.assertTrue(direct instanceof VillagerDataHolder && fixed instanceof VillagerDataHolder,
                Component.literal("Legacy fixture failed to decode: " + id));
        final var actual = ((VillagerDataHolder) direct).getVillagerData();
        final var expected = ((VillagerDataHolder) fixed).getVillagerData();
        context.assertTrue(actual.equals(expected),
                Component.literal("Direct decoding differs from vanilla upgrade for " + id));
        context.assertTrue(actual.level() == 4
                        && actual.type().unwrapKey().orElseThrow().identifier().toString().equals("minecraft:desert")
                        && actual.profession().unwrapKey().orElseThrow().identifier().toString().equals("minecraft:librarian"),
                Component.literal("Explicit legacy villager fields were lost: " + id));
        Chunkis.LOGGER.info("LEGACY_DATA_VERSION_PROBE entity={} sourceVersion={} directFinalized={} fixedFinalized={} "
                        + "decodedVillagerDataEqual={}", id, SOURCE_VERSION,
                legacy.contains("VillagerDataFinalized"), upgraded.getBooleanOr("VillagerDataFinalized", false),
                actual.equals(expected));
        context.succeed();
    }
}
