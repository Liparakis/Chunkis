package io.liparakis.chunkis.integration.migration.offline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link PreLaunchMigrationCoordinator}.
 */
class PreLaunchMigrationCoordinatorTest {

    /**
     * Utility method to create a registry key representing the vanilla overworld.
     *
     * @return the {@link ResourceKey} for the overworld
     */
    private static ResourceKey<Level> overworldKey() {
        return ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
    }

    /**
     * Tests that {@link PreLaunchMigrationCoordinator#verifyMigrationSucceeded(OfflineWorldMigrationReport, Path)}
     * does not throw any exception when provided with a clean migration report (i.e., with zero failed chunks).
     */
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

    /**
     * Tests that {@link PreLaunchMigrationCoordinator#verifyMigrationSucceeded(OfflineWorldMigrationReport, Path)}
     * throws an {@link IllegalStateException} when the migration report indicates that one or more chunks failed.
     */
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

    /**
     * CIS files plus leftover vanilla regions without the completion marker mean an
     * interrupted migration: the remaining regions must be migrated, never deleted.
     */
    @Test
    void interruptedMigrationResumesInsteadOfDeletingSources() {
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.MIGRATE,
                PreLaunchMigrationCoordinator.decide(false, true));
    }

    /**
     * Vanilla files appearing after a completed migration are set aside, not imported.
     */
    @Test
    void completedMigrationSetsAsideReappearedSources() {
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.SET_ASIDE_STALE_SOURCES,
                PreLaunchMigrationCoordinator.decide(true, true));
    }

    /**
     * With no vanilla files left the dimension is authoritative either way.
     */
    @Test
    void noVanillaFilesIsAuthoritative() {
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.AUTHORITATIVE,
                PreLaunchMigrationCoordinator.decide(false, false));
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.AUTHORITATIVE,
                PreLaunchMigrationCoordinator.decide(false, false));
    }
}
