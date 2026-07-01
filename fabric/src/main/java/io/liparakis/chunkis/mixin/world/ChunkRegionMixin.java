package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents worldgen structure passes from spawning one-shot entities again for
 * chunks that Chunkis is restoring from persisted delta data.
 *
 * <p>Structure generation places mobs and other entities through
 * {@link ChunkRegion#spawnEntity(Entity)}. When Chunkis rewinds a chunk for
 * base terrain regeneration, those one-shot spawns can replay even though the
 * saved entity payload will be restored separately. This mixin blocks only the
 * generation-time entity spawn path for chunks explicitly marked to suppress
 * initial repopulation.</p>
 *
 * <p><b>Threading:</b> Invoked on the server main thread during chunk generation.
 * All field accesses in this mixin must remain main-thread-only.</p>
 */
@Mixin(ChunkRegion.class)
public class ChunkRegionMixin {

    /**
     * Default constructor for ChunkRegionMixin.
     */
    public ChunkRegionMixin() {
    }

    /**
     * Cancels worldgen entity spawns for restored chunks so villages, mineshafts,
     * outposts, bastions, and similar structures do not redeploy entities that are
     * already represented in the persisted CIS delta.
     *
     * <p>The fast path exits immediately when {@code entity} is {@code null},
     * when the owning chunk does not implement {@link ChunkisDeltaDuck}, or when
     * no delta is attached - covering the common non-restored case with minimal
     * overhead.</p>
     *
     * @param entity the entity vanilla is attempting to spawn during generation
     * @param cir    callback used to cancel the spawn and set the return value
     */
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSpawnEntity(
            final Entity entity,
            final CallbackInfoReturnable<Boolean> cir) {

        if (entity == null) {
            return;
        }

        final BlockPos pos = entity.getBlockPos();
        final ChunkPos chunkPos = new ChunkPos(pos);
        final Chunk chunk = ((ChunkRegion) (Object) this).getChunk(chunkPos.x, chunkPos.z);

        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }

        final ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (delta == null || !delta.shouldSuppressInitialRepopulation()) {
            return;
        }

        cir.setReturnValue(false);

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Suppressed worldgen entity spawn for restored chunk {} ({})",
                    chunkPos,
                    entity.getType());
        }
    }
}