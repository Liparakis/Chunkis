package io.liparakis.chunkis.client.migration;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.integration.migration.offline.PreLaunchMigrationCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelStorage;

/**
 * Keeps the client event loop rendering while pre-launch conversion runs on a
 * background coordinator thread.
 */
public final class PreLaunchMigrationGate {

    /** State of the one-shot startup gate. */
    private static State state = State.IDLE;

    private PreLaunchMigrationGate() {
        throw new AssertionError("Utility class");
    }

    /**
     * Starts migration once and returns whether the caller must cancel startup.
     *
     * @param client active client used to render and resume startup
     * @param session locked world save session
     * @param dataPackManager resource-pack manager for startup
     * @param saveLoader loaded world save configuration
     * @param newWorld whether startup is creating a new world
     * @return {@code true} while migration owns startup; otherwise {@code false}
     */
    public static synchronized boolean shouldBlockStartup(final MinecraftClient client,
            final LevelStorage.Session session,
            final ResourcePackManager dataPackManager,
            final SaveLoader saveLoader,
            final boolean newWorld) {
        if (state == State.RESUMING) {
            state = State.IDLE;
            return false;
        }
        if (state == State.RUNNING) {
            return true;
        }

        state = State.RUNNING;
        final PendingStart pendingStart = new PendingStart(session, dataPackManager, saveLoader, newWorld);
        client.setScreenAndRender(new MigrationScreen());
        final Thread coordinator = new Thread(() -> runMigration(client, pendingStart), "Chunkis-Migration-Gate");
        coordinator.setDaemon(true);
        coordinator.start();
        return true;
    }

    /** Runs conversion off-thread and posts either resume or failure to the client thread. */
    private static void runMigration(final MinecraftClient client, final PendingStart pendingStart) {
        try {
            PreLaunchMigrationCoordinator.runBeforeIntegratedServerStart(pendingStart.session(),
                    pendingStart.saveLoader(),
                    pendingStart.newWorld());
            client.execute(() -> resumeStartup(client, pendingStart));
        } catch (final Exception e) {
            Chunkis.LOGGER.error("Chunkis pre-launch migration failed", e);
            client.execute(() -> showFailure(client, e));
        }
    }

    /** Resumes integrated-server startup after successful migration. */
    private static synchronized void resumeStartup(final MinecraftClient client, final PendingStart pendingStart) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.RESUMING;
        client.startIntegratedServer(pendingStart.session(),
                pendingStart.dataPackManager(),
                pendingStart.saveLoader(),
                pendingStart.newWorld());
    }

    /** Displays a user-visible migration failure and resets the gate. */
    private static synchronized void showFailure(final MinecraftClient client, final Exception error) {
        state = State.IDLE;
        client.setScreenAndRender(new MessageScreen(Text.literal(
                "Chunkis migration failed: " + (error.getMessage() == null ? error.getClass()
                                                                             .getSimpleName() : error.getMessage()))));
    }

    /** Internal lifecycle states preventing duplicate startup migrations. */
    private enum State {
        IDLE, RUNNING, RESUMING
    }

    /** Startup arguments retained while the background migration is running. */
    private record PendingStart(LevelStorage.Session session, ResourcePackManager dataPackManager,
                                SaveLoader saveLoader, boolean newWorld) {

    }
}
