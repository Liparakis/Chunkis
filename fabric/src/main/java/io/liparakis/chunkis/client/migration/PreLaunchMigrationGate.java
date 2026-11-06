package io.liparakis.chunkis.client.migration;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.integration.migration.offline.PreLaunchMigrationCoordinator;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Keeps the client event loop rendering while pre-launch conversion runs on a
 * background coordinator thread.
 */
public final class PreLaunchMigrationGate {

    /**
     * State of the one-shot startup gate.
     */
    private static State state = State.IDLE;

    /**
     * Performs pre launch migration gate.
     */
    private PreLaunchMigrationGate() {
        throw new AssertionError("Utility class");
    }

    /**
     * Starts migration once and returns whether the caller must cancel startup.
     *
     * @param client          active client used to render and resume startup
     * @param session         locked world save session
     * @param dataPackManager resource-pack manager for startup
     * @param saveLoader      loaded world save configuration
     * @param gameRules       startup game-rule overrides supplied by Minecraft
     * @param newWorld        whether startup is creating a new world
     * @return {@code true} while migration owns startup; otherwise {@code false}
     */
    public static synchronized boolean shouldBlockStartup(final Minecraft client,
            final LevelStorageSource.LevelStorageAccess session,
            final PackRepository dataPackManager,
            final WorldStem saveLoader,
            final Optional<GameRules> gameRules,
            final boolean newWorld) {
        if (state == State.RESUMING) {
            state = State.IDLE;
            return false;
        }
        if (state == State.RUNNING) {
            return true;
        }

        state = State.RUNNING;
        final PendingStart pendingStart = new PendingStart(session, dataPackManager, saveLoader, gameRules, newWorld);
        client.setScreenAndShow(new MigrationScreen());
        final Thread coordinator = new Thread(() -> runMigration(client, pendingStart), "Chunkis-Migration-Gate");
        coordinator.setDaemon(true);
        coordinator.start();
        return true;
    }

    /**
     * Runs conversion off-thread and posts either resume or failure to the client thread.
     */
    private static void runMigration(final Minecraft client, final PendingStart pendingStart) {
        try {
            PreLaunchMigrationCoordinator.runBeforeIntegratedServerStart(pendingStart.session(),
                    pendingStart.saveLoader(),
                    pendingStart.newWorld());
            client.execute(() -> resumeStartup(client, pendingStart));
        } catch (final Exception | Error e) {
            // Errors (e.g. OutOfMemoryError) must also leave the progress screen, or the client hangs on it.
            Chunkis.LOGGER.error("Chunkis pre-launch migration failed", e);
            client.execute(() -> showFailure(client, e));
        }
    }

    /**
     * Resumes integrated-server startup after successful migration.
     */
    private static synchronized void resumeStartup(final Minecraft client, final PendingStart pendingStart) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.RESUMING;
        client.doWorldLoad(pendingStart.session(),
                pendingStart.dataPackManager(),
                pendingStart.saveLoader(),
                pendingStart.gameRules(),
                pendingStart.newWorld());
    }

    /**
     * Displays a user-visible migration failure and resets the gate.
     */
    private static synchronized void showFailure(final Minecraft client, final Throwable error) {
        state = State.IDLE;
        client.setScreenAndShow(new GenericMessageScreen(Component.literal(
                "Chunkis migration failed: " + (error.getMessage() == null ? error.getClass()
                                                                             .getSimpleName() : error.getMessage()))));
    }

    /**
     * Internal lifecycle states preventing duplicate startup migrations.
     */
    private enum State {
        /**
         * Stores idle.
         */
        IDLE, RUNNING, RESUMING
    }

    /**
     * Startup arguments retained while the background migration is running.
     */
    private record PendingStart(LevelStorageSource.LevelStorageAccess session, PackRepository dataPackManager,
                                WorldStem saveLoader, Optional<GameRules> gameRules, boolean newWorld) {

    }
}
