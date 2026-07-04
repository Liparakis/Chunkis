package io.liparakis.chunkis.migration.offline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class PreLaunchMigrationCoordinatorTest {

    @Test
    void verifyMigrationSucceededAllowsCleanReport() {
        assertDoesNotThrow(() -> PreLaunchMigrationCoordinator.verifyMigrationSucceeded(
                new OfflineWorldMigrationReport(
                        overworldKey(),
                        2,
                        128,
                        0,
                        2
                ),
                Path.of("migration")
        ));
    }

    @Test
    void verifyMigrationSucceededFailsClosedOnChunkFailures() {
        assertThrows(IllegalStateException.class, () -> PreLaunchMigrationCoordinator.verifyMigrationSucceeded(
                new OfflineWorldMigrationReport(
                        overworldKey(),
                        2,
                        127,
                        1,
                        1
                ),
                Path.of("migration")
        ));
    }

    @Test
    void backupFilesAloneDoNotMakeCisAuthoritative() {
        assertFalse(PreLaunchMigrationCoordinator.shouldTreatCisAsAuthoritative(false));
        assertFalse(PreLaunchMigrationCoordinator.shouldDeleteVanillaSourcesAsStale(false));
    }

    @Test
    void cisDataMakesChunkisAuthoritative() {
        assertTrue(PreLaunchMigrationCoordinator.shouldTreatCisAsAuthoritative(true));
        assertTrue(PreLaunchMigrationCoordinator.shouldDeleteVanillaSourcesAsStale(true));
    }

    private static RegistryKey<World> overworldKey() {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("overworld"));
    }
}
