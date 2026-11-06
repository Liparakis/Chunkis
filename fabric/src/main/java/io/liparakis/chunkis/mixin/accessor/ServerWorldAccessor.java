package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the world's entity manager, whose sections still hold a chunk's entities after the chunk left the
 * tracked range and before vanilla saved them.
 */
@Mixin(ServerLevel.class)
public interface ServerWorldAccessor {

    /**
     * Returns the world's entity manager.
     *
     * @return the entity manager
     */
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> chunkis$getEntityManager();
}
