package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link NaturalSpawner} to prevent duplicate entity spawning
 * during chunk restoration.
 * <p>
 * When Chunkis restores a chunk from delta storage, it resets the chunk status
 * to trigger generation. This would normally cause {@code populateEntities} to
 * spawn fresh passive mobs on top of entities already restored from the delta,
 * resulting in duplicate entities.
 * </p>
 * <p>
 * This mixin cancels entity population for chunks that have valid delta data,
 * ensuring only the restored entities are present.
 * </p>
 * <p>
 * <b>Scope:</b> Only affects initial passive mob spawning (cows, sheep, etc.).
 * Natural hostile mob spawning is unaffected.
 * </p>
 */
@Mixin(NaturalSpawner.class)
public class SpawnHelperMixin {

    /**
     * Intercepts entity population to prevent duplicate spawning on restored chunks.
     * <p>
     * Chunks with valid Chunkis deltas are skipped, as they already contain
     * restored entities. Fresh chunks without deltas proceed with normal spawning.
     * </p>
     * <p>
     * <b>Important:</b> The {@code world} parameter is {@link ServerLevelAccessor},
     * not {@code ServerWorld}. During generation, this is often a {@code ChunkRegion},
     * so casting to {@code ServerWorld} would fail.
     * </p>
     *
     * @param world      the world access context (often ChunkRegion during generation)
     * @param sourcePos  the position used to resolve spawning attributes
     * @param chunkPos   the chunk position being populated
     * @param random     the random generator for spawning
     * @param ci         callback info to cancel population
     */
    @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
    private static void chunkis$onPopulateEntities(
            final ServerLevelAccessor world,
            final BlockPos sourcePos,
            final ChunkPos chunkPos,
            final RandomSource random,
            final CallbackInfo ci) {

        final ChunkAccess chunk = getChunkAt(world, chunkPos);
        if (shouldCancelSpawning(chunk, ci)) {
            logSpawnCancellation(chunkPos);
        }
    }

    /**
     * Retrieves the chunk at the specified position.
     *
     * @param world the world access
     * @param pos   the chunk position
     * @return the chunk, or {@code null} if not loaded
     */
    @Unique
    private static ChunkAccess getChunkAt(final ServerLevelAccessor world, final ChunkPos pos) {
        return world.getChunk(pos.x(), pos.z());
    }

    /**
     * Determines if entity spawning should be cancelled for this chunk.
     * <p>
     * Spawning is cancelled if the chunk has a non-empty Chunkis delta,
     * indicating it's a restored chunk with existing entities.
     * </p>
     *
     * @param chunk the chunk being populated
     * @param ci    callback info to cancel if needed
     * @return {@code true} if spawning was cancelled
     */
    @Unique
    private static boolean shouldCancelSpawning(final ChunkAccess chunk, final CallbackInfo ci) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return false;
        }
        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (delta == null || !delta.shouldSuppressInitialRepopulation()) {
            return false;
        }
        ci.cancel();
        return true;
    }

    /**
     * Logs entity population cancellation at debug level.
     *
     * @param pos the chunk position
     */
    @Unique
    private static void logSpawnCancellation(final ChunkPos pos) {
        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Cancelled vanilla entity population for restored chunk {}",
                    pos);
        }
    }
}
