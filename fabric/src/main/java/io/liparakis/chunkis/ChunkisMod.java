package io.liparakis.chunkis;

import io.liparakis.chunkis.command.ChunkDebugCommand;
import io.liparakis.chunkis.command.DurabilityTestCommand;
import io.liparakis.chunkis.command.StorageReportCommand;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.PayloadWatchTracer;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;
import io.liparakis.chunkis.migration.CisWorldMigrator;
import io.liparakis.chunkis.migration.McaMigrator;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.portal.PortalChunkIndexManager;
import io.liparakis.chunkis.portal.PortalLinkManager;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import io.liparakis.chunkis.world.tracking.ownership.DeltaPersistenceGuard;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;

/**
 * Main Fabric entrypoint for Chunkis.
 *
 * <p>This class performs common initialization for both integrated and dedicated
 * servers. Client-only setup belongs in {@link ClientChunkisMod}.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>register network payloads</li>
 *   <li>register server commands</li>
 *   <li>register world/server lifecycle hooks</li>
 *   <li>flush pending Chunkis state during shutdown</li>
 *   <li>clear static runtime state after shutdown</li>
 * </ul>
 *
 * <p>The entrypoint intentionally stays thin. Migration, storage, dirty-delta
 * tracking, async saves, base chunk capture, and portal indexing are handled by
 * their dedicated classes.</p>
 *
 * @author Liparakis
 * @version 1.2
 */
public final class ChunkisMod implements ModInitializer {

    /**
     * Registers Chunkis network payloads.
     */
    private static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(ChunkDeltaPayload.ID, ChunkDeltaPayload.CODEC);
    }

    /**
     * Registers Chunkis server commands.
     */
    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DurabilityTestCommand.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ChunkDebugCommand.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> StorageReportCommand.register(dispatcher));
    }

    /**
     * Registers server and world lifecycle hooks.
     *
     * <p>World load runs migration. Server stopping flushes Chunkis runtime state while worlds and
     * storage are still available. Server stopped clears static managers.</p>
     */
    private static void registerEvents() {
        ServerWorldEvents.LOAD.register((server, world) -> migrateWorld(world));

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> GlobalChunkTracker.noteChunkUnloaded(chunk));

        ServerTickEvents.END_WORLD_TICK.register(ScheduledEntityReplayQueue::tick);

        ServerTickEvents.END_SERVER_TICK.register(server -> PayloadWatchTracer.tickEntityReloadAssertions());

        ServerLifecycleEvents.SERVER_STOPPING.register(ChunkisMod::flushBeforeServerStop);

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearRuntimeState());
    }

    /**
     * Runs world migration passes.
     *
     * <p>MCA migration runs before CIS world migration, matching the existing
     * migration flow.</p>
     *
     * @param world loaded server world
     */
    private static void migrateWorld(final ServerWorld world) {
        McaMigrator.migrateWorld(world);
        CisWorldMigrator.migrateWorld(world);
    }

    /**
     * Flushes Chunkis runtime state before the server fully stops.
     *
     * <p>The per-world order is preserved exactly:</p>
     * <ol>
     *   <li>force-save currently pending tracked deltas</li>
     *   <li>flush and close async CIS saves</li>
     *   <li>close portal chunk index state</li>
     * </ol>
     *
     * @param server stopping Minecraft server
     */
    private static void flushBeforeServerStop(final MinecraftServer server) {
        for (final ServerWorld world : server.getWorlds()) {
            flushWorldBeforeStop(world);
        }

        PortalLinkManager.close(server);
        PayloadWatchTracer.checkUnrestoredAssertions();
    }

    /**
     * Flushes Chunkis-managed state for one world.
     *
     * @param world world being stopped
     */
    private static void flushWorldBeforeStop(final ServerWorld world) {
        flushPendingDeltas(world);
        AsyncCisSaveManager.flushAndClose(world);
        PortalChunkIndexManager.close(world);
    }

    /**
     * Force-saves pending dirty deltas for one world.
     *
     * <p>This is a final synchronous safety sweep. Normal chunk saves should
     * already flush most deltas, but shutdown can leave dirty entries in the
     * global tracker.</p>
     *
     * <p>The pending map is a snapshot, so it is safe to iterate while successful
     * saves remove entries from the tracker.</p>
     *
     * @param world world whose pending deltas should be flushed
     */
    private static void flushPendingDeltas(final ServerWorld world) {
        final Map<ChunkPos, ChunkDelta<BlockState, NbtCompound>> pending = GlobalChunkTracker.getPendingDeltas(world);

        if (pending.isEmpty()) {
            return;
        }

        Chunkis.LOGGER.warn("Chunkis [STOPPING]: Force-saving {} dirty delta(s) for {}", pending.size(),
                            world.getRegistryKey().getValue());

        final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage =
                FabricCisStorageHelper.getStorage(world);

        for (final Map.Entry<ChunkPos, ChunkDelta<BlockState, NbtCompound>> entry : pending.entrySet()) {
            savePendingDelta(world, storage, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Saves one pending dirty delta.
     *
     * <p>The {@link CisChunkPos} wrapper is allocated only after the dirty check,
     * avoiding unnecessary objects for stale clean entries in the pending snapshot.</p>
     *
     * @param world   owning world
     * @param storage world CIS storage
     * @param pos     chunk position
     * @param delta   delta to save, may be {@code null}
     */
    @SuppressWarnings("All")
    private static void savePendingDelta(final ServerWorld world, final CisStorage<Block, BlockState, Property<?>,
            NbtCompound> storage, final ChunkPos pos, final ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta == null || !delta.isDirty()) {
            return;
        }

        if (DeltaPersistenceGuard.shouldRejectSparseDeltaWithoutBase(delta, true)) {
            DeltaPersistenceGuard.logRejectedSparseDeltaWithoutBase(world, pos, delta, "server-stopping", "ChunkisMod"
                    + "#savePendingDelta");
            return;
        }

        FabricCisStorageHelper.saveTrackedDelta(world, storage, pos, delta);
    }

    /**
     * Clears static Chunkis runtime state after the server has stopped.
     *
     * <p>Persistence work should already be complete by this point. This releases
     * in-memory references so singleplayer disconnects and server restarts do not
     * leak stale state into the next lifecycle.</p>
     */
    private static void clearRuntimeState() {
        GlobalChunkTracker.clear();
        AsyncCisSaveManager.clear();
        PortalChunkIndexManager.clear();
        PortalLinkManager.clear();
        ScheduledEntityReplayQueue.clear();
    }

    /**
     * Fabric common initialization hook.
     */
    @Override
    public void onInitialize() {
        registerPayloads();
        registerCommands();
        registerEvents();
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.block("minecraft:overworld", 8, -60, 8));
    }
}


