package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Schedules a small forward strip of CIS loads when players cross chunks.
 */
public final class CisLoadPrefetcher {

    private static final int LOOKAHEAD_CHUNKS = 3;
    private static final Map<UUID, PlayerPosition> LAST_CHUNKS = new HashMap<>();

    private CisLoadPrefetcher() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records player movement and starts bounded forward prefetches.
     * Must run on the server thread.
     *
     * @param server active server
     */
    public static void tick(final MinecraftServer server) {
        final Set<UUID> onlinePlayers = new HashSet<>();

        for (final ServerWorld world : server.getWorlds()) {
            for (final ServerPlayerEntity player : world.getPlayers()) {
                final UUID playerId = player.getUuid();
                onlinePlayers.add(playerId);
                final ChunkPos current = player.getChunkPos();
                final PlayerPosition previous = LAST_CHUNKS.put(
                        playerId,
                        new PlayerPosition(world.getRegistryKey(), current)
                );
                if (previous == null || !previous.dimension()
                        .equals(world.getRegistryKey())) {
                    continue;
                }

                final int dx = Integer.compare(current.x, previous.chunk().x);
                final int dz = Integer.compare(current.z, previous.chunk().z);
                if (dx == 0 && dz == 0) {
                    continue;
                }

                for (int distance = 1; distance <= LOOKAHEAD_CHUNKS; distance++) {
                    FabricCisStorageHelper.prefetch(
                            world,
                            new ChunkPos(current.x + dx * distance, current.z + dz * distance),
                            ChunkTraceStore.nextOperationId("prefetch")
                    );
                }
            }
        }

        LAST_CHUNKS.keySet()
                .retainAll(onlinePlayers);
    }

    /**
     * Clears player movement state between server lifecycles.
     */
    public static void clear() {
        LAST_CHUNKS.clear();
    }

    private record PlayerPosition(RegistryKey<World> dimension, ChunkPos chunk) {

    }
}
