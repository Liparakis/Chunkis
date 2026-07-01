package io.liparakis.chunkis.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

class McaMigratorTest {

    @Test
    void migratedChunkMetadataMarksFullBaseline() {
        final NbtCompound structures = new NbtCompound();
        structures.putString("marker", "value");

        final NbtCompound metadata = McaMigrator.createMigratedChunkMetadata(structures);

        assertTrue(CisNbtUtil.hasFullBlockBaseline(metadata));
    }
}
