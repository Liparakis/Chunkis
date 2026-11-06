package io.liparakis.chunkis.mixin.accessor;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the entity section cache, which unlike the world's entity lookup also returns sections whose chunk
 * is hidden (below full level) but whose entities are not saved yet.
 */
@Mixin(PersistentEntitySectionManager.class)
public interface ServerEntityManagerAccessor {

    /**
     * Returns the entity section cache.
     *
     * @return the section cache
     */
    @Accessor("sectionStorage")
    EntitySectionStorage<?> chunkis$getCache();
}
