package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

class OfflineWorldMigrationReportTest {

    @Test
    void jsonLineIncludesHandledChunkSummary() {
        final OfflineWorldMigrationReport report = new OfflineWorldMigrationReport(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("overworld")),
                3,
                512,
                2,
                1
        );

        assertTrue(report.toJsonLine().contains("\"worldId\":\"minecraft:overworld\""));
        assertTrue(report.toJsonLine().contains("\"handledChunks\":512"));
        assertTrue(report.toJsonLine().contains("\"retiredRegions\":1"));
    }
}
