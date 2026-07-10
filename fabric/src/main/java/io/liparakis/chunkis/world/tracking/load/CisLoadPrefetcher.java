package io.liparakis.chunkis.world.tracking.load;

import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/** Schedules a small forward strip of CIS loads from observed cold requests. */
public final class CisLoadPrefetcher {

    private static final int LOOKAHEAD_CHUNKS = 3;
    private static final Map<UUID, PlayerPosition> LAST_CHUNKS = new ConcurrentHashMap<>();

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
                final PlayerPosition previous = LAST_CHUNKS.get(playerId);
                final boolean sameDimension = previous != null
                        && previous.dimension().equals(world.getRegistryKey());
                final int directionX = sameDimension
                        ? Integer.compare(current.x, previous.chunk().x)
                        : 0;
                final int directionZ = sameDimension
                        ? Integer.compare(current.z, previous.chunk().z)
                        : 0;
                final int retainedDirectionX = directionX != 0
                        ? directionX
                        : sameDimension ? previous.directionX() : 0;
                final int retainedDirectionZ = directionZ != 0
                        ? directionZ
                        : sameDimension ? previous.directionZ() : 0;
                LAST_CHUNKS.put(playerId,
                        new PlayerPosition(world.getRegistryKey(), current,
                                retainedDirectionX, retainedDirectionZ));
            }
        }

        LAST_CHUNKS.keySet()
                .retainAll(onlinePlayers);
    }

    /**
     * Anchors prediction to a real cold request, avoiding movement-only coordinate guesses.
     * Uses only the thread-safe movement snapshot captured by {@link #tick}.
     *
     * @param world owning world
     * @param requested requested cold-load chunk
     */
    public static void observeColdLoad(final ServerWorld world, final ChunkPos requested) {
        PlayerPosition nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (final PlayerPosition movement : LAST_CHUNKS.values()) {
            if (!movement.dimension().equals(world.getRegistryKey())
                    || (movement.directionX() == 0 && movement.directionZ() == 0)) {
                continue;
            }
            final long dx = (long) movement.chunk().x - requested.x;
            final long dz = (long) movement.chunk().z - requested.z;
            final long distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = movement;
            }
        }
        if (nearest == null) {
            return;
        }

        for (int distance = 1; distance <= LOOKAHEAD_CHUNKS; distance++) {
            FabricCisStorageHelper.prefetch(
                    world,
                    new ChunkPos(requested.x + nearest.directionX() * distance,
                            requested.z + nearest.directionZ() * distance),
                    ChunkTraceStore.nextOperationId("prefetch")
            );
        }
    }

    /**
     * Clears player movement state between server lifecycles.
     */
    public static void clear() {
        LAST_CHUNKS.clear();
    }

    private record PlayerPosition(RegistryKey<World> dimension, ChunkPos chunk, int directionX, int directionZ) {

    }
}
