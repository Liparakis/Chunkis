package io.liparakis.chunkis.integration.migration.offline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link PreLaunchMigrationCoordinator}.
 */
class PreLaunchMigrationCoordinatorTest {

    /**
     * Utility method to create a registry key representing the vanilla overworld.
     *
     * @return the {@link RegistryKey} for the overworld
     */
    private static RegistryKey<World> overworldKey() {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.ofVanilla("overworld"));
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
                PreLaunchMigrationCoordinator.decide(false, true, true));
    }

    /**
     * Vanilla files appearing after a completed migration are set aside, not imported.
     */
    @Test
    void completedMigrationSetsAsideReappearedSources() {
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.SET_ASIDE_STALE_SOURCES,
                PreLaunchMigrationCoordinator.decide(true, true, true));
    }

    /**
     * With no vanilla files left the dimension is authoritative either way.
     */
    @Test
    void noVanillaFilesIsAuthoritative() {
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.AUTHORITATIVE,
                PreLaunchMigrationCoordinator.decide(false, true, false));
        assertEquals(PreLaunchMigrationCoordinator.DimensionAction.AUTHORITATIVE,
                PreLaunchMigrationCoordinator.decide(false, false, false));
    }
}
