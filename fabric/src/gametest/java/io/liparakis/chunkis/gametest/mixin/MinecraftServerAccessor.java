package io.liparakis.chunkis.gametest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    /**
     * Whether the current tick still has time budget before the next tick is due.
     *
     * @return true while the server may keep working before the next tick
     */
    @Invoker("shouldKeepTicking")
    boolean chunkis$shouldKeepTicking();

    /**
     * Marks that the server is idling until the next tick.
     *
     * @param waiting whether the server is waiting for the next tick
     */
    @Accessor("waitingForNextTick")
    void chunkis$setWaitingForNextTick(boolean waiting);

    /**
     * Sets when the next tick is due.
     *
     * @param nanos due time, on {@code Util.getMeasuringTimeNano()}'s clock
     */
    @Accessor("tickStartTimeNanos")
    void chunkis$setTickStartTimeNanos(long nanos);
}
