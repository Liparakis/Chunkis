package io.liparakis.chunkis.mixin.world.tick;

import io.liparakis.chunkis.world.tracking.settle.GenerationSettling;
import io.liparakis.chunkis.world.tracking.settle.GenerationTickDuck;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps which of a chunk's pending ticks come from generation; see {@link GenerationSettling}.
 */
@Mixin(LevelChunkTicks.class)
public class ChunkTickSchedulerMixin implements GenerationTickDuck {

    /**
     * Packed positions of pending generation ticks, created on first use.
     */
    @Unique
    private LongSet chunkis$generationTicks;

    @Override
    public LongSet chunkis$generationTicks() {
        return chunkis$generationTicks;
    }

    @Override
    public void chunkis$setGenerationTicks(final LongSet generationTicks) {
        this.chunkis$generationTicks = generationTicks;
    }

    /**
     * Records the origin of each newly scheduled tick.
     *
     * @param orderedTick tick being scheduled
     * @param ci          callback info
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    private void chunkis$recordTickOrigin(final ScheduledTick<?> orderedTick, final CallbackInfo ci) {
        GenerationSettling.onScheduled(this, orderedTick.pos().asLong());
    }
}
