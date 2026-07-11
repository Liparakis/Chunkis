package io.liparakis.chunkis.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Tests the client-only block-entity replay guard.
 */
class ClientDeltaVisitorTest {

    /**
     * Performs only trial spawners are owned by vanilla.
     */
    @Test
    void onlyTrialSpawnersAreOwnedByVanilla() {
        final NbtCompound trialSpawner = new NbtCompound();
        trialSpawner.putString("id", "minecraft:trial_spawner");

        final NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");

        assertTrue(ClientDeltaVisitor.isVanillaOwnedBlockEntity(trialSpawner));
        assertFalse(ClientDeltaVisitor.isVanillaOwnedBlockEntity(chest));
        assertFalse(ClientDeltaVisitor.isVanillaOwnedBlockEntity(new NbtCompound()));
    }
}
