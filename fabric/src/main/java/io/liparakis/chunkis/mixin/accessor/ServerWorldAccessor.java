package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the world's entity manager, whose sections still hold a chunk's entities after the chunk left the
 * tracked range and before vanilla saved them.
 */
@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {

    /**
     * Returns the world's entity manager.
     *
     * @return the entity manager
     */
    @Accessor("entityManager")
    ServerEntityManager<Entity> chunkis$getEntityManager();
}
