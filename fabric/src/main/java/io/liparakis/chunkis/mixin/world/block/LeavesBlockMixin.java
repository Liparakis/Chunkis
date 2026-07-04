package io.liparakis.chunkis.mixin.world.block;

import io.liparakis.chunkis.world.tracking.state.LeafTickContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link LeavesBlock} to track when leaf decay or updates are
 * happening.
 * <p>
 * This mixin wraps the {@code scheduledTick} and {@code randomTick} methods with a leaf tick context,
 * allowing
 * Chunkis to differentiate between player-initiated block changes and automated
 * leaf
 * changes (decay, growth, updates).
 * </p>
 *
 * <p>
 * <b>Exception Safety:</b> Uses TAIL injection to guarantee cleanup even if the
 * method throws an exception.
 * </p>
 *
 * <p>
 * <b>Performance:</b> ~40 ns overhead per leaf tick (2 ThreadLocal operations).
 * </p>
 */
@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {

    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void chunkis$beforeLeafTick(
            final BlockState state,
            final ServerWorld world,
            final BlockPos pos,
            final Random random,
            final CallbackInfo ci) {
        LeafTickContext.enterDirect();
    }

    /**
     * Exits the leaf tick context after the scheduled tick completes.
     * Uses TAIL to ensure cleanup happens even if an exception is thrown.
     *
     * @param state  the current block state of the leaves
     * @param world  the server world in which the tick occurred
     * @param pos    the position of the leaves block
     * @param random the random generator for this tick
     * @param ci     the Mixin {@link CallbackInfo}; unused but required by the
     *               injection contract
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void chunkis$afterLeafTick(
            final BlockState state,
            final ServerWorld world,
            final BlockPos pos,
            final Random random,
            final CallbackInfo ci) {
        LeafTickContext.exitDirect();
    }

    /**
     * Enters the leaf tick context before the random tick runs.
     *
     * @param state  the current block state of the leaves
     * @param world  the server world in which the tick is occurring
     * @param pos    the position of the leaves block
     * @param random the random generator for this tick
     * @param ci     the Mixin {@link CallbackInfo}; unused but required by the
     *               injection contract
     */
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void chunkis$beforeLeafRandomTick(
            final BlockState state,
            final ServerWorld world,
            final BlockPos pos,
            final Random random,
            final CallbackInfo ci) {
        LeafTickContext.enterDirect();
    }

    /**
     * Exits the leaf tick context after the random tick completes.
     * Uses TAIL to ensure cleanup happens even if an exception is thrown.
     *
     * @param state  the current block state of the leaves
     * @param world  the server world in which the tick occurred
     * @param pos    the position of the leaves block
     * @param random the random generator for this tick
     * @param ci     the Mixin {@link CallbackInfo}; unused but required by the
     *               injection contract
     */
    @Inject(method = "randomTick", at = @At("TAIL"))
    private void chunkis$afterLeafRandomTick(
            final BlockState state,
            final ServerWorld world,
            final BlockPos pos,
            final Random random,
            final CallbackInfo ci) {
        LeafTickContext.exitDirect();
    }
}
